/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.rmi.server.RMISocketFactory;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;

import org.junit.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Gauge;
import com.datastax.driver.core.*;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.exceptions.UnauthorizedException;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.ServerTestUtils;
import org.apache.cassandra.auth.AuthCacheService;
import org.apache.cassandra.auth.AuthKeyspace;
import org.apache.cassandra.auth.AuthSchemaChangeListener;
import org.apache.cassandra.auth.AuthTestUtils;
import org.apache.cassandra.auth.IRoleManager;
import org.apache.cassandra.concurrent.ScheduledExecutors;
import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.config.EncryptionOptions;
import org.apache.cassandra.config.SmallestDataStorageMebibytes;
import org.apache.cassandra.db.virtual.VirtualKeyspaceRegistry;
import org.apache.cassandra.db.virtual.VirtualSchemaKeyspace;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.index.SecondaryIndexManager;
import org.apache.cassandra.io.util.File;
import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.metrics.CassandraMetricsRegistry;
import org.apache.cassandra.metrics.ClientMetrics;
import org.apache.cassandra.schema.*;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.functions.FunctionName;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.db.marshal.TupleType;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.schema.IndexMetadata;
import org.apache.cassandra.schema.KeyspaceMetadata;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.serializers.TypeSerializer;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.transport.*;
import org.apache.cassandra.transport.ProtocolVersion;
import org.apache.cassandra.transport.messages.ResultMessage;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.JMXServerUtils;
import org.assertj.core.api.Assertions;
import org.apache.cassandra.utils.Pair;
import org.awaitility.Awaitility;

import static com.datastax.driver.core.SocketOptions.DEFAULT_CONNECT_TIMEOUT_MILLIS;
import static com.datastax.driver.core.SocketOptions.DEFAULT_READ_TIMEOUT_MILLIS;
import static org.apache.cassandra.utils.Clock.Global.currentTimeMillis;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Base class for CQL tests.
 */
public abstract class CQLTester
{
    /**
     * The super user
     */
    private static final User SUPER_USER = new User("cassandra", "cassandra");

    protected static final Logger logger = LoggerFactory.getLogger(CQLTester.class);

    public static final String KEYSPACE = "cql_test_keyspace";
    public static final String KEYSPACE_PER_TEST = "cql_test_keyspace_alt";
    protected static final boolean USE_PREPARED_VALUES = Boolean.valueOf(System.getProperty("cassandra.test.use_prepared", "true"));
    protected static final boolean REUSE_PREPARED = Boolean.valueOf(System.getProperty("cassandra.test.reuse_prepared", "true"));
    protected static final long ROW_CACHE_SIZE_IN_MIB = new SmallestDataStorageMebibytes(System.getProperty("cassandra.test.row_cache_size", "0MiB")).toMebibytes();
    private static final AtomicInteger seqNumber = new AtomicInteger();
    protected static final ByteBuffer TOO_BIG = ByteBuffer.allocate(FBUtilities.MAX_UNSIGNED_SHORT + 1024);
    public static final String DATA_CENTER = ServerTestUtils.DATA_CENTER;
    public static final String DATA_CENTER_REMOTE = ServerTestUtils.DATA_CENTER_REMOTE;
    public static final String RACK1 = ServerTestUtils.RACK1;

    private static org.apache.cassandra.transport.Server server;
    private static JMXConnectorServer jmxServer;
    protected static String jmxHost;
    protected static int jmxPort;
    protected static MBeanServerConnection jmxConnection;

    protected static final int nativePort;
    protected static final InetAddress nativeAddr;
    protected static final Set<InetAddressAndPort> remoteAddrs = new HashSet<>();
    private static final Map<Pair<User, ProtocolVersion>, Cluster> clusters = new HashMap<>();
    private static final Map<Pair<User, ProtocolVersion>, Session> sessions = new HashMap<>();

    private static Consumer<Cluster.Builder> clusterBuilderConfigurator;

    public static final List<ProtocolVersion> PROTOCOL_VERSIONS = new ArrayList<>(ProtocolVersion.SUPPORTED.size());

    private static final String CREATE_INDEX_NAME_REGEX = "(\\s*(\\w*|\"\\w*\")\\s*)";
    private static final String CREATE_INDEX_REGEX = String.format("\\A\\s*CREATE(?:\\s+CUSTOM)?\\s+INDEX" +
                                                                   "(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s*" +
                                                                   "%s?\\s*ON\\s+(%<s\\.)?%<s\\s*" +
                                                                   "(\\((?:\\s*\\w+\\s*\\()?%<s\\))?",
                                                                   CREATE_INDEX_NAME_REGEX);
    private static final Pattern CREATE_INDEX_PATTERN = Pattern.compile(CREATE_INDEX_REGEX, Pattern.CASE_INSENSITIVE);

    /** Return the current server version if supported by the driver, else
     * the latest that is supported.
     *
     * @return - the preferred versions that is also supported by the driver
     */
    public static final ProtocolVersion getDefaultVersion()
    {
        return PROTOCOL_VERSIONS.contains(ProtocolVersion.CURRENT)
               ? ProtocolVersion.CURRENT
               : PROTOCOL_VERSIONS.get(PROTOCOL_VERSIONS.size() - 1);
    }

    static
    {
        checkProtocolVersion();

        nativeAddr = InetAddress.getLoopbackAddress();
        nativePort = getAutomaticallyAllocatedPort(nativeAddr);

        ServerTestUtils.daemonInitialization();
    }

    private List<String> keyspaces = new ArrayList<>();
    private List<String> tables = new ArrayList<>();
    private List<String> views = new ArrayList<>();
    private List<String> types = new ArrayList<>();
    private List<String> functions = new ArrayList<>();
    private List<String> aggregates = new ArrayList<>();

    private User user;

    // We don't use USE_PREPARED_VALUES in the code below so some test can foce value preparation (if the result
    // is not expected to be the same without preparation)
    private boolean usePrepared = USE_PREPARED_VALUES;
    private static boolean reusePrepared = REUSE_PREPARED;

    protected boolean usePrepared()
    {
        return usePrepared;
    }

    /**
     * Use the specified user for executing the queries over the network.
     * @param username the user name
     * @param password the user password
     */
    public void useUser(String username, String password)
    {
        this.user = new User(username, password);
    }

    /**
     * Use the super user for executing the queries over the network.
     */
    public void useSuperUser()
    {
        this.user = SUPER_USER;
    }

    /**
     * Returns a port number that is automatically allocated,
     * typically from an ephemeral port range.
     *
     * @return a port number
     */
    private static int getAutomaticallyAllocatedPort(InetAddress address)
    {
        try
        {
            try (ServerSocket sock = new ServerSocket())
            {
                // A port number of {@code 0} means that the port number will be automatically allocated,
                // typically from an ephemeral port range.
                sock.bind(new InetSocketAddress(address, 0));
                return sock.getLocalPort();
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static void checkProtocolVersion()
    {
        // The latest versions might not be supported yet by the java driver
        for (ProtocolVersion version : ProtocolVersion.SUPPORTED)
        {
            try
            {
                com.datastax.driver.core.ProtocolVersion.fromInt(version.asInt());
                PROTOCOL_VERSIONS.add(version);
            }
            catch (IllegalArgumentException e)
            {
                logger.warn("Protocol Version {} not supported by java driver", version);
            }
        }
    }

    public static void prepareServer()
    {
        ServerTestUtils.prepareServer();
    }

    public static void cleanup()
    {
        ServerTestUtils.cleanup();
    }

    /**
     * Starts the JMX server. It's safe to call this method multiple times.
     */
    public static void startJMXServer() throws Exception
    {
        if (jmxServer != null)
            return;

        InetAddress loopback = InetAddress.getLoopbackAddress();
        jmxHost = loopback.getHostAddress();
        jmxPort = getAutomaticallyAllocatedPort(loopback);
        jmxServer = JMXServerUtils.createJMXServer(jmxPort, true);
        jmxServer.start();
    }
    
    public static void createMBeanServerConnection() throws Exception
    {
        assert jmxServer != null : "jmxServer not started";

        Map<String, Object> env = new HashMap<>();
        env.put("com.sun.jndi.rmi.factory.socket", RMISocketFactory.getDefaultSocketFactory());
        JMXConnector jmxc = JMXConnectorFactory.connect(getJMXServiceURL(), env);
        jmxConnection =  jmxc.getMBeanServerConnection();
    }

    public static JMXServiceURL getJMXServiceURL() throws MalformedURLException
    {
        assert jmxServer != null : "jmxServer not started";

        return new JMXServiceURL(String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", jmxHost, jmxPort));
    }

    @BeforeClass
    public static void setUpClass()
    {
        if (ROW_CACHE_SIZE_IN_MIB > 0)
            DatabaseDescriptor.setRowCacheSizeInMiB(ROW_CACHE_SIZE_IN_MIB);
        StorageService.instance.setPartitionerUnsafe(Murmur3Partitioner.instance);

        // Once per-JVM is enough
        prepareServer();
    }

    @AfterClass
    public static void tearDownClass()
    {
        for (Session sess : sessions.values())
                sess.close();
        for (Cluster cl : clusters.values())
                cl.close();

        if (server != null)
            server.stop();

        // We use queryInternal for CQLTester so prepared statement will populate our internal cache (if reusePrepared is used; otherwise prepared
        // statements are not cached but re-prepared every time). So we clear the cache between test files to avoid accumulating too much.
        if (reusePrepared)
            QueryProcessor.clearInternalStatementsCache();

        TokenMetadata metadata = StorageService.instance.getTokenMetadata();
        metadata.clearUnsafe();

        if (jmxServer != null && jmxServer instanceof RMIConnectorServer)
        {
            try
            {
                ((RMIConnectorServer) jmxServer).stop();
            }
            catch (IOException e)
            {
                logger.warn("Error shutting down jmx", e);
            }
        }
    }

    @Before
    public void beforeTest() throws Throwable
    {
        schemaChange(String.format("CREATE KEYSPACE IF NOT EXISTS %s WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '1'}", KEYSPACE));
        schemaChange(String.format("CREATE KEYSPACE IF NOT EXISTS %s WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '1'}", KEYSPACE_PER_TEST));
    }

    @After
    public void afterTest() throws Throwable
    {
        dropPerTestKeyspace();

        // Restore standard behavior in case it was changed
        usePrepared = USE_PREPARED_VALUES;
        reusePrepared = REUSE_PREPARED;

        final List<String> keyspacesToDrop = copy(keyspaces);
        final List<String> tablesToDrop = copy(tables);
        final List<String> viewsToDrop = copy(views);
        final List<String> typesToDrop = copy(types);
        final List<String> functionsToDrop = copy(functions);
        final List<String> aggregatesToDrop = copy(aggregates);
        keyspaces = null;
        tables = null;
        views = null;
        types = null;
        functions = null;
        aggregates = null;
        user = null;

        // We want to clean up after the test, but dropping a table is rather long so just do that asynchronously
        ScheduledExecutors.optionalTasks.execute(new Runnable()
        {
            public void run()
            {
                try
                {
                    for (int i = viewsToDrop.size() - 1; i >= 0; i--)
                        schemaChange(String.format("DROP MATERIALIZED VIEW IF EXISTS %s.%s", KEYSPACE, viewsToDrop.get(i)));

                    for (int i = tablesToDrop.size() - 1; i >= 0; i--)
                        schemaChange(String.format("DROP TABLE IF EXISTS %s.%s", KEYSPACE, tablesToDrop.get(i)));

                    for (int i = aggregatesToDrop.size() - 1; i >= 0; i--)
                        schemaChange(String.format("DROP AGGREGATE IF EXISTS %s", aggregatesToDrop.get(i)));

                    for (int i = functionsToDrop.size() - 1; i >= 0; i--)
                        schemaChange(String.format("DROP FUNCTION IF EXISTS %s", functionsToDrop.get(i)));

                    for (int i = typesToDrop.size() - 1; i >= 0; i--)
                        schemaChange(String.format("DROP TYPE IF EXISTS %s.%s", KEYSPACE, typesToDrop.get(i)));

                    for (int i = keyspacesToDrop.size() - 1; i >= 0; i--)
                        schemaChange(String.format("DROP KEYSPACE IF EXISTS %s", keyspacesToDrop.get(i)));

                    // Dropping doesn't delete the sstables. It's not a huge deal but it's cleaner to cleanup after us
                    // Thas said, we shouldn't delete blindly before the TransactionLogs.SSTableTidier for the table we drop
                    // have run or they will be unhappy. Since those taks are scheduled on StorageService.tasks and that's
                    // mono-threaded, just push a task on the queue to find when it's empty. No perfect but good enough.

                    final CountDownLatch latch = new CountDownLatch(1);
                    ScheduledExecutors.nonPeriodicTasks.execute(new Runnable()
                    {
                        public void run()
                        {
                            latch.countDown();
                        }
                    });
                    latch.await(2, TimeUnit.SECONDS);

                    removeAllSSTables(KEYSPACE, tablesToDrop);
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    public static List<String> buildNodetoolArgs(List<String> args)
    {
        List<String> allArgs = new ArrayList<>();
        allArgs.add("bin/nodetool");
        allArgs.add("-p");
        allArgs.add(Integer.toString(jmxPort));
        allArgs.add("-h");
        allArgs.add(jmxHost == null ? "127.0.0.1" : jmxHost);
        allArgs.addAll(args);
        return allArgs;
    }

    public static List<String> buildCqlshArgs(List<String> args)
    {
        List<String> allArgs = new ArrayList<>();
        allArgs.add("bin/cqlsh");
        allArgs.add(nativeAddr.getHostAddress());
        allArgs.add(Integer.toString(nativePort));
        allArgs.add("-e");
        allArgs.addAll(args);
        return allArgs;
    }

    public static List<String> buildCassandraStressArgs(List<String> args)
    {
        List<String> allArgs = new ArrayList<>();
        allArgs.add("tools/bin/cassandra-stress");
        allArgs.addAll(args);
        if (args.indexOf("-port") == -1)
        {
            allArgs.add("-port");
            allArgs.add("native=" + Integer.toString(nativePort));
        }
        return allArgs;
    }

    protected static void requireAuthentication()
    {
        DatabaseDescriptor.setAuthenticator(new AuthTestUtils.LocalPasswordAuthenticator());
        DatabaseDescriptor.setAuthorizer(new AuthTestUtils.LocalCassandraAuthorizer());
        DatabaseDescriptor.setNetworkAuthorizer(new AuthTestUtils.LocalCassandraNetworkAuthorizer());

        // The CassandraRoleManager constructor set the supported and alterable options based on
        // DatabaseDescriptor authenticator type so it needs to be created only after the authenticator is set.
        IRoleManager roleManager =  new AuthTestUtils.LocalCassandraRoleManager()
        {
            public void setup()
            {
                loadRoleStatement();
                QueryProcessor.executeInternal(createDefaultRoleQuery());
            }
        };

        DatabaseDescriptor.setRoleManager(roleManager);
        MigrationManager.announceNewKeyspace(AuthKeyspace.metadata(), true);
        DatabaseDescriptor.getRoleManager().setup();
        DatabaseDescriptor.getAuthenticator().setup();
        DatabaseDescriptor.getAuthorizer().setup();
        DatabaseDescriptor.getNetworkAuthorizer().setup();
        Schema.instance.registerListener(new AuthSchemaChangeListener());

        AuthCacheService.initializeAndRegisterCaches();
    }

    /**
     *  Initialize Native Transport for test that need it.
     */
    protected static void requireNetwork() throws ConfigurationException
    {
        requireNetwork(server -> {}, cluster -> {});
    }

    /**
     *  Initialize Native Transport for the tests that need it.
     */
    protected static void requireNetwork(Consumer<Server.Builder> serverConfigurator,
                                         Consumer<Cluster.Builder> clusterConfigurator) throws ConfigurationException
    {
        if (server != null)
            return;

        clusterBuilderConfigurator = clusterConfigurator;

        startServices();
        startServer(serverConfigurator);
    }

    private static void startServices()
    {
        SystemKeyspace.finishStartup();
        VirtualKeyspaceRegistry.instance.register(VirtualSchemaKeyspace.instance);
        StorageService.instance.initServer();
        SchemaLoader.startGossiper();
    }

    protected static void reinitializeNetwork()
    {
        reinitializeNetwork(server -> {}, cluster -> {});
    }

    protected static void reinitializeNetwork(Consumer<Server.Builder> serverConfigurator,
                                              Consumer<Cluster.Builder> clusterConfigurator)
    {
        if (server != null && server.isRunning())
        {
            server.stop();
            server = null;
        }
        List<CloseFuture> futures = new ArrayList<>();
        for (Cluster cluster : clusters.values())
            futures.add(cluster.closeAsync());
        for (Session session : sessions.values())
            futures.add(session.closeAsync());
        FBUtilities.waitOnFutures(futures);
        clusters.clear();
        sessions.clear();

        clusterBuilderConfigurator = clusterConfigurator;

        startServer(serverConfigurator);
    }

    private static void startServer(Consumer<Server.Builder> decorator)
    {
        Server.Builder serverBuilder = new Server.Builder().withHost(nativeAddr).withPort(nativePort);
        decorator.accept(serverBuilder);
        server = serverBuilder.build();
        ClientMetrics.instance.init(Collections.singleton(server));
        server.start();
    }

    private static Cluster initClientCluster(User user, ProtocolVersion version)
    {
        SocketOptions socketOptions =
                new SocketOptions().setConnectTimeoutMillis(Integer.getInteger("cassandra.test.driver.connection_timeout_ms",
                                                                               DEFAULT_CONNECT_TIMEOUT_MILLIS)) // default is 5000
                                   .setReadTimeoutMillis(Integer.getInteger("cassandra.test.driver.read_timeout_ms",
                                                                            DEFAULT_READ_TIMEOUT_MILLIS)); // default is 12000

        logger.info("Timeouts: {} / {}", socketOptions.getConnectTimeoutMillis(), socketOptions.getReadTimeoutMillis());

        Cluster.Builder builder = Cluster.builder()
                                         .withoutJMXReporting()
                                         .addContactPoints(nativeAddr)
                                         .withClusterName("Test Cluster")
                                         .withPort(nativePort)
                                         .withSocketOptions(socketOptions);
        if (user != null)
            builder.withCredentials(user.username, user.password);

        if (version.isBeta())
            builder = builder.allowBetaProtocolVersion();
        else
            builder = builder.withProtocolVersion(com.datastax.driver.core.ProtocolVersion.fromInt(version.asInt()));

        clusterBuilderConfigurator.accept(builder);

        Cluster cluster = builder.build();

        logger.info("Started Java Driver instance for protocol version {}", version);

        return cluster;
    }

    protected void dropPerTestKeyspace() throws Throwable
    {
        execute(String.format("DROP KEYSPACE IF EXISTS %s", KEYSPACE_PER_TEST));
    }

    /**
     * Returns a copy of the specified list.
     * @return a copy of the specified list.
     */
    private static List<String> copy(List<String> list)
    {
        return list.isEmpty() ? Collections.<String>emptyList() : new ArrayList<>(list);
    }

    public ColumnFamilyStore getCurrentColumnFamilyStore()
    {
        return getCurrentColumnFamilyStore(KEYSPACE);
    }

    public ColumnFamilyStore getCurrentColumnFamilyStore(String keyspace)
    {
        String currentTable = currentTable();
        return currentTable == null
             ? null
             : Keyspace.open(keyspace).getColumnFamilyStore(currentTable);
    }

    public void flush(boolean forceFlush)
    {
        if (forceFlush)
            flush();
    }

    public void flush()
    {
        flush(KEYSPACE);
    }

    public void flush(String keyspace)
    {
        ColumnFamilyStore store = getCurrentColumnFamilyStore(keyspace);
        if (store != null)
            store.forceBlockingFlush();
    }

    public void disableCompaction(String keyspace)
    {
        ColumnFamilyStore store = getCurrentColumnFamilyStore(keyspace);
        if (store != null)
            store.disableAutoCompaction();
    }

    public void compact()
    {
         ColumnFamilyStore store = getCurrentColumnFamilyStore();
         if (store != null)
             store.forceMajorCompaction();
    }

    public void disableCompaction()
    {
        disableCompaction(KEYSPACE);
    }

    public void enableCompaction(String keyspace)
    {
        ColumnFamilyStore store = getCurrentColumnFamilyStore(keyspace);
        if (store != null)
            store.enableAutoCompaction();
    }

    public void enableCompaction()
    {
        enableCompaction(KEYSPACE);
    }

    public void cleanupCache()
    {
        ColumnFamilyStore store = getCurrentColumnFamilyStore();
        if (store != null)
            store.cleanupCache();
    }

    public static FunctionName parseFunctionName(String qualifiedName)
    {
        int i = qualifiedName.indexOf('.');
        return i == -1
               ? FunctionName.nativeFunction(qualifiedName)
               : new FunctionName(qualifiedName.substring(0, i).trim(), qualifiedName.substring(i+1).trim());
    }

    public static String shortFunctionName(String f)
    {
        return parseFunctionName(f).name;
    }

    private static void removeAllSSTables(String ks, List<String> tables)
    {
        // clean up data directory which are stored as data directory/keyspace/data files
        for (File d : Directories.getKSChildDirectories(ks))
        {
            if (d.exists() && containsAny(d.name(), tables))
                FileUtils.deleteRecursive(d);
        }
    }

    private static boolean containsAny(String filename, List<String> tables)
    {
        for (int i = 0, m = tables.size(); i < m; i++)
            // don't accidentally delete in-use directories with the
            // same prefix as a table to delete, i.e. table_1 & table_11
            if (filename.contains(tables.get(i) + "-"))
                return true;
        return false;
    }

    protected String keyspace()
    {
        return KEYSPACE;
    }

    protected String currentTable()
    {
        if (tables.isEmpty())
            return null;
        return tables.get(tables.size() - 1);
    }

    protected String currentView()
    {
        if (views.isEmpty())
            return null;
        return views.get(views.size() - 1);
    }

    protected String currentKeyspace()
    {
        if (keyspaces.isEmpty())
            return null;
        return keyspaces.get(keyspaces.size() - 1);
    }

    protected ByteBuffer unset()
    {
        return ByteBufferUtil.UNSET_BYTE_BUFFER;
    }

    protected void forcePreparedValues()
    {
        this.usePrepared = true;
    }

    protected void stopForcingPreparedValues()
    {
        this.usePrepared = USE_PREPARED_VALUES;
    }

    public static void disablePreparedReuseForTest()
    {
        reusePrepared = false;
    }

    protected String createType(String query)
    {
        return createType(KEYSPACE, query);
    }

    protected String createType(String keyspace, String query)
    {
        String typeName = createTypeName();
        String fullQuery = String.format(query, keyspace + "." + typeName);
        logger.info(fullQuery);
        schemaChange(fullQuery);
        return typeName;
    }

    protected String createTypeName()
    {
        String typeName = String.format("type_%02d", seqNumber.getAndIncrement());
        types.add(typeName);
        return typeName;
    }

    protected String createFunctionName(String keyspace)
    {
        return String.format("%s.function_%02d", keyspace, seqNumber.getAndIncrement());
    }

    protected void registerFunction(String functionName, String argTypes)
    {
        functions.add(functionName + '(' + argTypes + ')');
    }

    protected String createFunction(String keyspace, String argTypes, String query) throws Throwable
    {
        String functionName = createFunctionName(keyspace);
        createFunctionOverload(functionName, argTypes, query);
        return functionName;
    }

    protected void createFunctionOverload(String functionName, String argTypes, String query) throws Throwable
    {
        registerFunction(functionName, argTypes);
        String fullQuery = String.format(query, functionName);
        logger.info(fullQuery);
        schemaChange(fullQuery);
    }

    protected String createAggregateName(String keyspace)
    {
        return String.format("%s.aggregate_%02d", keyspace, seqNumber.getAndIncrement());
    }

    protected void registerAggregate(String aggregateName, String argTypes)
    {
        aggregates.add(aggregateName + '(' + argTypes + ')');
    }

    protected String createAggregate(String keyspace, String argTypes, String query) throws Throwable
    {
        String aggregateName = createAggregateName(keyspace);
        createAggregateOverload(aggregateName, argTypes, query);
        return aggregateName;
    }

    protected void createAggregateOverload(String aggregateName, String argTypes, String query) throws Throwable
    {
        String fullQuery = String.format(query, aggregateName);
        registerAggregate(aggregateName, argTypes);
        logger.info(fullQuery);
        schemaChange(fullQuery);
    }

    protected String createKeyspace(String query)
    {
        String currentKeyspace = createKeyspaceName();
        String fullQuery = String.format(query, currentKeyspace);
        logger.info(fullQuery);
        schemaChange(fullQuery);
        return currentKeyspace;
    }

    protected void alterKeyspace(String query)
    {
        String fullQuery = String.format(query, currentKeyspace());
        logger.info(fullQuery);
        schemaChange(fullQuery);
    }
 
    protected void alterKeyspaceMayThrow(String query) throws Throwable
    {
        String fullQuery = String.format(query, currentKeyspace());
        logger.info(fullQuery);
        QueryProcessor.executeOnceInternal(fullQuery);
    }
    
    protected String createKeyspaceName()
    {
        String currentKeyspace = String.format("keyspace_%02d", seqNumber.getAndIncrement());
        keyspaces.add(currentKeyspace);
        return currentKeyspace;
    }

    protected String createTable(String query)
    {
        return createTable(KEYSPACE, query);
    }

    protected String createTable(String keyspace, String query)
    {
        String currentTable = createTableName();
        String fullQuery = formatQuery(keyspace, query);
        logger.info(fullQuery);
        schemaChange(fullQuery);
        return currentTable;
    }

    protected String createTableName()
    {
        String currentTable = String.format("table_%02d", seqNumber.getAndIncrement());
        tables.add(currentTable);
        return currentTable;
    }

    protected void createTableMayThrow(String query) throws Throwable
    {
        String currentTable = createTableName();
        String fullQuery = formatQuery(query);
        logger.info(fullQuery);
        QueryProcessor.executeOnceInternal(fullQuery);
    }

    /**
     * Creates a materialized view, waiting for the completion of its builder tasks.
     *
     * @param query the {@code CREATE VIEW} query, with {@code %s} placeholders for the view and table names
     * @return the name of the created view
     */
    protected String createView(String query)
    {
        return createView(null, query);
    }

    /**
     * Creates a materialized view, waiting for the completion of its builder tasks.
     *
     * @param viewName the name of the view to be created, or {@code null} for using an automatically generated a name
     * @param query the {@code CREATE VIEW} query, with {@code %s} placeholders for the view and table names
     * @return the name of the created view
     */
    protected String createView(String viewName, String query)
    {
        String currentView = createViewAsync(viewName, query);
        waitForViewBuild(currentView);
        return currentView;
    }

    /**
     * Creates a materialized view, without waiting for the completion of its builder tasks.
     *
     * @param query the {@code CREATE VIEW} query, with {@code %s} placeholders for the view and table names
     * @return the name of the created view
     */
    protected String createViewAsync(String query)
    {
        return createViewAsync(null, query);
    }

    /**
     * Creates a materialized view, without waiting for the completion of its builder tasks.
     *
     * @param viewName the name of the view to be created, or {@code null} for using an automatically generated a name
     * @param query the {@code CREATE VIEW} query, with {@code %s} placeholders for the view and table names
     * @return the name of the created view
     */
    protected String createViewAsync(String viewName, String query)
    {
        String currentView = viewName == null ? createViewName() : viewName;
        String fullQuery = String.format(query, KEYSPACE + "." + currentView, KEYSPACE + "." + currentTable());
        logger.info(fullQuery);
        schemaChange(fullQuery);
        return currentView;
    }

    protected void dropView()
    {
        dropView(currentView());
    }

    protected void dropView(String view)
    {
        dropFormattedTable(String.format("DROP MATERIALIZED VIEW IF EXISTS %s.%s", KEYSPACE, view));
        views.remove(view);
    }

    protected String createViewName()
    {
        String currentView = String.format("mv_%02d", seqNumber.getAndIncrement());
        views.add(currentView);
        return currentView;
    }

    protected List<String> getViews()
    {
        return copy(views);
    }

    protected void updateView(String query, Object... params) throws Throwable
    {
        updateView(getDefaultVersion(), query, params);
    }

    protected void updateView(ProtocolVersion version, String query, Object... params) throws Throwable
    {
        executeNet(version, query, params);
        waitForViewMutations();
    }

    /**
     * Waits for any pending asynchronous materialized view mutations.
     */
    protected static void waitForViewMutations()
    {
        Awaitility.await()
                  .atMost(10, TimeUnit.MINUTES)
                  .pollDelay(0, TimeUnit.MILLISECONDS)
                  .pollInterval(1, TimeUnit.MILLISECONDS)
                  .until(() -> Stage.VIEW_MUTATION.executor().getPendingTaskCount() == 0 &&
                               Stage.VIEW_MUTATION.executor().getActiveTaskCount() == 0);
    }

    /**
     * Waits for the building tasks of the specified materialized view.
     *
     * @param view the name of the view
     */
    protected void waitForViewBuild(String view)
    {
        Awaitility.await()
                  .atMost(10, TimeUnit.MINUTES)
                  .pollDelay(0, TimeUnit.MILLISECONDS)
                  .pollInterval(10, TimeUnit.MILLISECONDS)
                  .until(() -> SystemKeyspace.isViewBuilt(keyspace(), view));
    }

    protected void alterTable(String query)
    {
        String fullQuery = formatQuery(query);
        logger.info(fullQuery);
        schemaChange(fullQuery);
    }

    protected void alterTableMayThrow(String query) throws Throwable
    {
        String fullQuery = formatQuery(query);
        logger.info(fullQuery);
        QueryProcessor.executeOnceInternal(fullQuery);
    }

    protected void dropTable(String query)
    {
        dropFormattedTable(String.format(query, KEYSPACE + "." + currentTable()));
    }

    protected void dropFormattedTable(String formattedQuery)
    {
        logger.info(formattedQuery);
        schemaChange(formattedQuery);
    }

    protected String createIndex(String query)
    {
        return createIndex(KEYSPACE, query);
    }

    protected String createIndex(String keyspace, String query)
    {
        String formattedQuery = formatQuery(keyspace, query);
        return createFormattedIndex(formattedQuery);
    }

    protected String createFormattedIndex(String formattedQuery)
    {
        logger.info(formattedQuery);
        String indexName = getCreateIndexName(formattedQuery);
        schemaChange(formattedQuery);
        return indexName;
    }

    protected static String getCreateIndexName(String formattedQuery)
    {
        Matcher matcher = CREATE_INDEX_PATTERN.matcher(formattedQuery);
        if (!matcher.find())
            throw new IllegalArgumentException("Expected valid create index query but found: " + formattedQuery);

        String index = matcher.group(2);
        if (!Strings.isNullOrEmpty(index))
            return index;

        String keyspace = matcher.group(5);
        if (Strings.isNullOrEmpty(keyspace))
            throw new IllegalArgumentException("Keyspace name should be specified: " + formattedQuery);

        String table = matcher.group(7);
        if (Strings.isNullOrEmpty(table))
            throw new IllegalArgumentException("Table name should be specified: " + formattedQuery);

        String column = matcher.group(9);

        String baseName = Strings.isNullOrEmpty(column)
                        ? IndexMetadata.generateDefaultIndexName(table)
                        : IndexMetadata.generateDefaultIndexName(table, new ColumnIdentifier(column, true));

        KeyspaceMetadata ks = Schema.instance.getKeyspaceMetadata(keyspace);
        return ks.findAvailableIndexName(baseName);
    }

    /**
     * Index creation is asynchronous, this method searches in the system table IndexInfo
     * for the specified index and returns true if it finds it, which indicates the
     * index was built. If we haven't found it after 5 seconds we give-up.
     */
    protected boolean waitForIndex(String keyspace, String table, String index) throws Throwable
    {
        long start = currentTimeMillis();
        boolean indexCreated = false;
        while (!indexCreated)
        {
            Object[][] results = getRows(execute("select index_name from system.\"IndexInfo\" where table_name = ?", keyspace));
            for(int i = 0; i < results.length; i++)
            {
                if (index.equals(results[i][0]))
                {
                    indexCreated = true;
                    break;
                }
            }

            if (currentTimeMillis() - start > 5000)
                break;

            Thread.sleep(10);
        }

        return indexCreated;
    }

    /**
     * Index creation is asynchronous, this method waits until the specified index hasn't any building task running.
     * <p>
     * This method differs from {@link #waitForIndex(String, String, String)} in that it doesn't require the index to be
     * fully nor successfully built, so it can be used to wait for failing index builds.
     *
     * @param keyspace the index keyspace name
     * @param indexName the index name
     * @return {@code true} if the index build tasks have finished in 5 seconds, {@code false} otherwise
     */
    protected boolean waitForIndexBuilds(String keyspace, String indexName) throws InterruptedException
    {
        long start = currentTimeMillis();
        SecondaryIndexManager indexManager = getCurrentColumnFamilyStore(keyspace).indexManager;

        while (true)
        {
            if (!indexManager.isIndexBuilding(indexName))
            {
                return true;
            }
            else if (currentTimeMillis() - start > 5000)
            {
                return false;
            }
            else
            {
                Thread.sleep(10);
            }
        }
    }

    protected void createIndexMayThrow(String query) throws Throwable
    {
        String fullQuery = formatQuery(query);
        logger.info(fullQuery);
        QueryProcessor.executeOnceInternal(fullQuery);
    }

    protected void dropIndex(String query) throws Throwable
    {
        String fullQuery = String.format(query, KEYSPACE);
        logger.info(fullQuery);
        schemaChange(fullQuery);
    }

    protected static void assertSchemaChange(String query,
                                             Event.SchemaChange.Change expectedChange,
                                             Event.SchemaChange.Target expectedTarget,
                                             String expectedKeyspace,
                                             String expectedName,
                                             String... expectedArgTypes)
    {
        ResultMessage actual = schemaChange(query);
        Assert.assertTrue(actual instanceof ResultMessage.SchemaChange);
        Event.SchemaChange schemaChange = ((ResultMessage.SchemaChange) actual).change;
        Assert.assertSame(expectedChange, schemaChange.change);
        Assert.assertSame(expectedTarget, schemaChange.target);
        Assert.assertEquals(expectedKeyspace, schemaChange.keyspace);
        Assert.assertEquals(expectedName, schemaChange.name);
        Assert.assertEquals(expectedArgTypes != null ? Arrays.asList(expectedArgTypes) : null, schemaChange.argTypes);
    }

    protected static void assertWarningsContain(Message.Response response, String message)
    {
        List<String> warnings = response.getWarnings();
        Assert.assertNotNull(warnings);
        assertTrue(warnings.stream().anyMatch(s -> s.contains(message)));
    }

    protected static void assertNoWarningContains(Message.Response response, String message)
    {
        List<String> warnings = response.getWarnings();
        
        if (warnings != null) 
        {
            assertFalse(warnings.stream().anyMatch(s -> s.contains(message)));
        }
    }

    protected static ResultMessage schemaChange(String query)
    {
        try
        {
            ClientState state = ClientState.forInternalCalls(SchemaConstants.SYSTEM_KEYSPACE_NAME);
            QueryState queryState = new QueryState(state);

            CQLStatement statement = QueryProcessor.parseStatement(query, queryState.getClientState());
            statement.validate(state);

            QueryOptions options = QueryOptions.forInternalCalls(Collections.<ByteBuffer>emptyList());

            return statement.executeLocally(queryState, options);
        }
        catch (Exception e)
        {
            logger.info("Error performing schema change", e);
            throw new RuntimeException("Error setting schema for test (query was: " + query + ")", e);
        }
    }

    protected TableMetadata currentTableMetadata()
    {
        return Schema.instance.getTableMetadata(KEYSPACE, currentTable());
    }

    protected com.datastax.driver.core.ResultSet executeNet(ProtocolVersion protocolVersion, String query, Object... values) throws Throwable
    {
        return sessionNet(protocolVersion).execute(formatQuery(query), values);
    }

    protected com.datastax.driver.core.ResultSet executeNet(String query, Object... values) throws Throwable
    {
        return sessionNet().execute(formatQuery(query), values);
    }

    protected com.datastax.driver.core.ResultSet executeViewNet(String query, Object... values)
    {
        return sessionNet().execute(formatViewQuery(query), values);
    }

    protected com.datastax.driver.core.ResultSet executeNet(ProtocolVersion protocolVersion, Statement statement)
    {
        return sessionNet(protocolVersion).execute(statement);
    }

    protected com.datastax.driver.core.ResultSet executeNetWithPaging(ProtocolVersion version, String query, int pageSize)
    {
        return sessionNet(version).execute(new SimpleStatement(formatQuery(query)).setFetchSize(pageSize));
    }

    protected com.datastax.driver.core.ResultSet executeNetWithPaging(String query, int pageSize)
    {
        return sessionNet().execute(new SimpleStatement(formatQuery(query)).setFetchSize(pageSize));
    }

    protected Session sessionNet()
    {
        return sessionNet(getDefaultVersion());
    }

    protected Session sessionNet(ProtocolVersion protocolVersion)
    {
        requireNetwork();

        return getSession(protocolVersion);
    }

    private Session getSession(ProtocolVersion protocolVersion)
    {
        Cluster cluster = getCluster(protocolVersion);
        return sessions.computeIfAbsent(Pair.create(user, protocolVersion), userProto -> cluster.connect());
    }

    private Cluster getCluster(ProtocolVersion protocolVersion)
    {
        return clusters.computeIfAbsent(Pair.create(user, protocolVersion), userProto -> initClientCluster(userProto.left, userProto.right));
    }

    protected SimpleClient newSimpleClient(ProtocolVersion version) throws IOException
    {
        return new SimpleClient(nativeAddr.getHostAddress(), nativePort, version, version.isBeta(), new EncryptionOptions().applyConfig())
               .connect(false, false);
    }

    protected String formatQuery(String query)
    {
        return formatQuery(KEYSPACE, query);
    }

    protected final String formatQuery(String keyspace, String query)
    {
        String currentTable = currentTable();
        return currentTable == null ? query : String.format(query, keyspace + "." + currentTable);
    }

    public String formatViewQuery(String query)
    {
        return formatViewQuery(KEYSPACE, query);
    }

    public String formatViewQuery(String keyspace, String query)
    {
        String currentView = currentView();
        return currentView == null ? query : String.format(query, keyspace + "." + currentView);
    }

    protected ResultMessage.Prepared prepare(String query) throws Throwable
    {
        return QueryProcessor.instance.prepare(formatQuery(query), ClientState.forInternalCalls());
    }

    protected UntypedResultSet execute(String query, Object... values) throws Throwable
    {
        return executeFormattedQuery(formatQuery(query), values);
    }

    public UntypedResultSet executeView(String query, Object... values) throws Throwable
    {
        return executeFormattedQuery(formatViewQuery(KEYSPACE, query), values);
    }

    protected UntypedResultSet executeFormattedQuery(String query, Object... values) throws Throwable
    {
        UntypedResultSet rs;
        if (usePrepared)
        {
            if (logger.isTraceEnabled())
                logger.trace("Executing: {} with values {}", query, formatAllValues(values));
            if (reusePrepared)
            {
                rs = QueryProcessor.executeInternal(query, transformValues(values));

                // If a test uses a "USE ...", then presumably its statements use relative table. In that case, a USE
                // change the meaning of the current keyspace, so we don't want a following statement to reuse a previously
                // prepared statement at this wouldn't use the right keyspace. To avoid that, we drop the previously
                // prepared statement.
                if (query.startsWith("USE"))
                    QueryProcessor.clearInternalStatementsCache();
            }
            else
            {
                rs = QueryProcessor.executeOnceInternal(query, transformValues(values));
            }
        }
        else
        {
            query = replaceValues(query, values);
            if (logger.isTraceEnabled())
                logger.trace("Executing: {}", query);
            rs = QueryProcessor.executeOnceInternal(query);
        }
        if (rs != null)
        {
            if (logger.isTraceEnabled())
                logger.trace("Got {} rows", rs.size());
        }
        return rs;
    }

    protected void assertRowsNet(ResultSet result, Object[]... rows)
    {
        assertRowsNet(getDefaultVersion(), result, rows);
    }

    protected void assertRowsNet(ProtocolVersion protocolVersion, ResultSet result, Object[]... rows)
    {
        // necessary as we need cluster objects to supply CodecRegistry.
        // It's reasonably certain that the network setup has already been done
        // by the time we arrive at this point, but adding this check doesn't hurt
        requireNetwork();

        if (result == null)
        {
            if (rows.length > 0)
                Assert.fail(String.format("No rows returned by query but %d expected", rows.length));
            return;
        }

        ColumnDefinitions meta = result.getColumnDefinitions();
        Iterator<Row> iter = result.iterator();
        int i = 0;
        while (iter.hasNext() && i < rows.length)
        {
            Object[] expected = rows[i];
            Row actual = iter.next();

            Assert.assertEquals(String.format("Invalid number of (expected) values provided for row %d (using protocol version %s)",
                                              i, protocolVersion),
                                meta.size(), expected.length);

            for (int j = 0; j < meta.size(); j++)
            {
                DataType type = meta.getType(j);
                com.datastax.driver.core.TypeCodec<Object> codec = getCluster(protocolVersion).getConfiguration()
                                                                                              .getCodecRegistry()
                                                                                              .codecFor(type);
                ByteBuffer expectedByteValue = codec.serialize(expected[j], com.datastax.driver.core.ProtocolVersion.fromInt(protocolVersion.asInt()));
                int expectedBytes = expectedByteValue == null ? -1 : expectedByteValue.remaining();
                ByteBuffer actualValue = actual.getBytesUnsafe(meta.getName(j));
                int actualBytes = actualValue == null ? -1 : actualValue.remaining();
                if (!Objects.equal(expectedByteValue, actualValue))
                    Assert.fail(String.format("Invalid value for row %d column %d (%s of type %s), " +
                                              "expected <%s> (%d bytes) but got <%s> (%d bytes) " +
                                              "(using protocol version %s)",
                                              i, j, meta.getName(j), type,
                                              codec.format(expected[j]),
                                              expectedBytes,
                                              codec.format(codec.deserialize(actualValue, com.datastax.driver.core.ProtocolVersion.fromInt(protocolVersion.asInt()))),
                                              actualBytes,
                                              protocolVersion));
            }
            i++;
        }

        if (iter.hasNext())
        {
            while (iter.hasNext())
            {
                iter.next();
                i++;
            }
            Assert.fail(String.format("Got less rows than expected. Expected %d but got %d (using protocol version %s).",
                                      rows.length, i, protocolVersion));
        }

        Assert.assertTrue(String.format("Got %s rows than expected. Expected %d but got %d (using protocol version %s)",
                                        rows.length>i ? "less" : "more", rows.length, i, protocolVersion), i == rows.length);
    }

    public static void assertRows(UntypedResultSet result, Object[]... rows)
    {
        if (result == null)
        {
            if (rows.length > 0)
                Assert.fail(String.format("No rows returned by query but %d expected", rows.length));
            return;
        }

        List<ColumnSpecification> meta = result.metadata();
        Iterator<UntypedResultSet.Row> iter = result.iterator();
        int i = 0;
        while (iter.hasNext() && i < rows.length)
        {
            Object[] expected = rows[i];
            UntypedResultSet.Row actual = iter.next();

            Assert.assertEquals(String.format("Invalid number of (expected) values provided for row %d", i), expected == null ? 1 : expected.length, meta.size());

            for (int j = 0; j < meta.size(); j++)
            {
                ColumnSpecification column = meta.get(j);
                ByteBuffer expectedByteValue = makeByteBuffer(expected == null ? null : expected[j], column.type);
                ByteBuffer actualValue = actual.getBytes(column.name.toString());

                if (expectedByteValue != null)
                    expectedByteValue = expectedByteValue.duplicate();
                if (!Objects.equal(expectedByteValue, actualValue))
                {
                    Object actualValueDecoded = actualValue == null ? null : column.type.getSerializer().deserialize(actualValue);
                    if (!Objects.equal(expected != null ? expected[j] : null, actualValueDecoded))
                        Assert.fail(String.format("Invalid value for row %d column %d (%s of type %s), expected <%s> but got <%s>",
                                                  i,
                                                  j,
                                                  column.name,
                                                  column.type.asCQL3Type(),
                                                  formatValue(expectedByteValue != null ? expectedByteValue.duplicate() : null, column.type),
                                                  formatValue(actualValue, column.type)));
                }
            }
            i++;
        }

        if (iter.hasNext())
        {
            while (iter.hasNext())
            {
                UntypedResultSet.Row actual = iter.next();
                i++;

                StringBuilder str = new StringBuilder();
                for (int j = 0; j < meta.size(); j++)
                {
                    ColumnSpecification column = meta.get(j);
                    ByteBuffer actualValue = actual.getBytes(column.name.toString());
                    str.append(String.format("%s=%s ", column.name, formatValue(actualValue, column.type)));
                }
                logger.info("Extra row num {}: {}", i, str.toString());
            }
            Assert.fail(String.format("Got more rows than expected. Expected %d but got %d.", rows.length, i));
        }

        Assert.assertTrue(String.format("Got %s rows than expected. Expected %d but got %d", rows.length>i ? "less" : "more", rows.length, i), i == rows.length);
    }

    /**
     * Like assertRows(), but ignores the ordering of rows.
     */
    public static void assertRowsIgnoringOrder(UntypedResultSet result, Object[]... rows)
    {
        assertRowsIgnoringOrderInternal(result, false, rows);
    }

    public static void assertRowsIgnoringOrderAndExtra(UntypedResultSet result, Object[]... rows)
    {
        assertRowsIgnoringOrderInternal(result, true, rows);
    }

    private static void assertRowsIgnoringOrderInternal(UntypedResultSet result, boolean ignoreExtra, Object[]... rows)
    {
        if (result == null)
        {
            if (rows.length > 0)
                Assert.fail(String.format("No rows returned by query but %d expected", rows.length));
            return;
        }

        List<ColumnSpecification> meta = result.metadata();

        Set<List<ByteBuffer>> expectedRows = new HashSet<>(rows.length);
        for (Object[] expected : rows)
        {
            Assert.assertEquals("Invalid number of (expected) values provided for row", expected.length, meta.size());
            List<ByteBuffer> expectedRow = new ArrayList<>(meta.size());
            for (int j = 0; j < meta.size(); j++)
                expectedRow.add(makeByteBuffer(expected[j], meta.get(j).type));
            expectedRows.add(expectedRow);
        }

        Set<List<ByteBuffer>> actualRows = new HashSet<>(result.size());
        for (UntypedResultSet.Row actual : result)
        {
            List<ByteBuffer> actualRow = new ArrayList<>(meta.size());
            for (int j = 0; j < meta.size(); j++)
                actualRow.add(actual.getBytes(meta.get(j).name.toString()));
            actualRows.add(actualRow);
        }

        com.google.common.collect.Sets.SetView<List<ByteBuffer>> extra = com.google.common.collect.Sets.difference(actualRows, expectedRows);
        com.google.common.collect.Sets.SetView<List<ByteBuffer>> missing = com.google.common.collect.Sets.difference(expectedRows, actualRows);
        if ((!ignoreExtra && !extra.isEmpty()) || !missing.isEmpty())
        {
            List<String> extraRows = makeRowStrings(extra, meta);
            List<String> missingRows = makeRowStrings(missing, meta);
            StringBuilder sb = new StringBuilder();
            if (!extra.isEmpty())
            {
                sb.append("Got ").append(extra.size()).append(" extra row(s) ");
                if (!missing.isEmpty())
                    sb.append("and ").append(missing.size()).append(" missing row(s) ");
                sb.append("in result.  Extra rows:\n    ");
                sb.append(extraRows.stream().collect(Collectors.joining("\n    ")));
                if (!missing.isEmpty())
                    sb.append("\nMissing Rows:\n    ").append(missingRows.stream().collect(Collectors.joining("\n    ")));
                Assert.fail(sb.toString());
            }

            if (!missing.isEmpty())
                Assert.fail("Missing " + missing.size() + " row(s) in result: \n    " + missingRows.stream().collect(Collectors.joining("\n    ")));
        }

        assert ignoreExtra || expectedRows.size() == actualRows.size();
    }

    protected static List<String> makeRowStrings(UntypedResultSet resultSet)
    {
        List<List<ByteBuffer>> rows = new ArrayList<>();
        for (UntypedResultSet.Row row : resultSet)
        {
            List<ByteBuffer> values = new ArrayList<>();
            for (ColumnSpecification columnSpecification : resultSet.metadata())
            {
                values.add(row.getBytes(columnSpecification.name.toString()));
            }
            rows.add(values);
        }

        return makeRowStrings(rows, resultSet.metadata());
    }

    private static List<String> makeRowStrings(Iterable<List<ByteBuffer>> rows, List<ColumnSpecification> meta)
    {
        List<String> strings = new ArrayList<>();
        for (List<ByteBuffer> row : rows)
        {
            StringBuilder sb = new StringBuilder("row(");
            for (int j = 0; j < row.size(); j++)
            {
                ColumnSpecification column = meta.get(j);
                sb.append(column.name.toString()).append("=").append(formatValue(row.get(j), column.type));
                if (j < (row.size() - 1))
                    sb.append(", ");
            }
            strings.add(sb.append(")").toString());
        }
        return strings;
    }

    protected void assertRowCount(UntypedResultSet result, int numExpectedRows)
    {
        if (result == null)
        {
            if (numExpectedRows > 0)
                Assert.fail(String.format("No rows returned by query but %d expected", numExpectedRows));
            return;
        }

        List<ColumnSpecification> meta = result.metadata();
        Iterator<UntypedResultSet.Row> iter = result.iterator();
        int i = 0;
        while (iter.hasNext() && i < numExpectedRows)
        {
            UntypedResultSet.Row actual = iter.next();
            assertNotNull(actual);
            i++;
        }

        if (iter.hasNext())
        {
            while (iter.hasNext())
            {
                iter.next();
                i++;
            }
            Assert.fail(String.format("Got less rows than expected. Expected %d but got %d.", numExpectedRows, i));
        }

        Assert.assertTrue(String.format("Got %s rows than expected. Expected %d but got %d", numExpectedRows>i ? "less" : "more", numExpectedRows, i), i == numExpectedRows);
    }

    protected Object[][] getRows(UntypedResultSet result)
    {
        if (result == null)
            return new Object[0][];

        List<Object[]> ret = new ArrayList<>();
        List<ColumnSpecification> meta = result.metadata();

        Iterator<UntypedResultSet.Row> iter = result.iterator();
        while (iter.hasNext())
        {
            UntypedResultSet.Row rowVal = iter.next();
            Object[] row = new Object[meta.size()];
            for (int j = 0; j < meta.size(); j++)
            {
                ColumnSpecification column = meta.get(j);
                ByteBuffer val = rowVal.getBytes(column.name.toString());
                row[j] = val == null ? null : column.type.getSerializer().deserialize(val);
            }

            ret.add(row);
        }

        Object[][] a = new Object[ret.size()][];
        return ret.toArray(a);
    }

    protected void assertColumnNames(UntypedResultSet result, String... expectedColumnNames)
    {
        if (result == null)
        {
            Assert.fail("No rows returned by query.");
            return;
        }

        List<ColumnSpecification> metadata = result.metadata();
        Assert.assertEquals("Got less columns than expected.", expectedColumnNames.length, metadata.size());

        for (int i = 0, m = metadata.size(); i < m; i++)
        {
            ColumnSpecification columnSpec = metadata.get(i);
            Assert.assertEquals(expectedColumnNames[i], columnSpec.name.toString());
        }
    }

    protected void assertAllRows(Object[]... rows) throws Throwable
    {
        assertRows(execute("SELECT * FROM %s"), rows);
    }

    public static Object[] row(Object... expected)
    {
        return expected;
    }

    protected void assertEmpty(UntypedResultSet result) throws Throwable
    {
        if (result != null && !result.isEmpty())
            throw new AssertionError(String.format("Expected empty result but got %d rows: %s \n", result.size(), makeRowStrings(result)));
    }

    protected void assertInvalid(String query, Object... values) throws Throwable
    {
        assertInvalidMessage(null, query, values);
    }

    protected void assertInvalidMessage(String errorMessage, String query, Object... values) throws Throwable
    {
        assertInvalidThrowMessage(errorMessage, null, query, values);
    }

    protected void assertInvalidMessageNet(String errorMessage, String query, Object... values) throws Throwable
    {
        assertInvalidThrowMessage(Optional.of(ProtocolVersion.CURRENT), errorMessage, null, query, values);
    }

    protected void assertInvalidThrow(Class<? extends Throwable> exception, String query, Object... values) throws Throwable
    {
        assertInvalidThrowMessage(null, exception, query, values);
    }

    protected void assertInvalidThrowMessage(String errorMessage, Class<? extends Throwable> exception, String query, Object... values) throws Throwable
    {
        assertInvalidThrowMessage(Optional.empty(), errorMessage, exception, query, values);
    }

    // if a protocol version > Integer.MIN_VALUE is supplied, executes
    // the query via the java driver, mimicking a real client.
    protected void assertInvalidThrowMessage(Optional<ProtocolVersion> protocolVersion,
                                             String errorMessage,
                                             Class<? extends Throwable> exception,
                                             String query,
                                             Object... values) throws Throwable
    {
        try
        {
            if (!protocolVersion.isPresent())
                execute(query, values);
            else
                executeNet(protocolVersion.get(), query, values);

            String q = USE_PREPARED_VALUES
                       ? query + " (values: " + formatAllValues(values) + ")"
                       : replaceValues(query, values);
            Assert.fail("Query should be invalid but no error was thrown. Query is: " + q);
        }
        catch (Exception e)
        {
            if (exception != null && !exception.isAssignableFrom(e.getClass()))
            {
                Assert.fail("Query should be invalid but wrong error was thrown. " +
                            "Expected: " + exception.getName() + ", got: " + e.getClass().getName() + ". " +
                            "Query is: " + queryInfo(query, values));
            }
            if (errorMessage != null)
            {
                assertMessageContains(errorMessage, e);
            }
        }
    }

    private static String queryInfo(String query, Object[] values)
    {
        return USE_PREPARED_VALUES
               ? query + " (values: " + formatAllValues(values) + ")"
               : replaceValues(query, values);
    }

    protected void assertValidSyntax(String query) throws Throwable
    {
        try
        {
            QueryProcessor.parseStatement(query);
        }
        catch(SyntaxException e)
        {
            Assert.fail(String.format("Expected query syntax to be valid but was invalid. Query is: %s; Error is %s",
                                      query, e.getMessage()));
        }
    }

    protected void assertInvalidSyntax(String query, Object... values) throws Throwable
    {
        assertInvalidSyntaxMessage(null, query, values);
    }

    protected void assertInvalidSyntaxMessage(String errorMessage, String query, Object... values) throws Throwable
    {
        try
        {
            execute(query, values);
            Assert.fail("Query should have invalid syntax but no error was thrown. Query is: " + queryInfo(query, values));
        }
        catch (SyntaxException e)
        {
            if (errorMessage != null)
            {
                assertMessageContains(errorMessage, e);
            }
        }
    }

    protected void assertInvalidRequestMessage(String errorMessage, String query, Object... values)
    {
        Assertions.assertThatThrownBy(() -> execute(query, values))
                  .isInstanceOf(InvalidRequestException.class)
                  .hasMessageContaining(errorMessage);
    }

    /**
     * Asserts that the message of the specified exception contains the specified text.
     *
     * @param text the text that the exception message must contains
     * @param e the exception to check
     */
    private static void assertMessageContains(String text, Exception e)
    {
        Assert.assertTrue("Expected error message to contain '" + text + "', but got '" + e.getMessage() + "'",
                e.getMessage().contains(text));
    }

    /**
     * Checks that the specified query is not authorized for the current user.
     * @param errorMessage The expected error message
     * @param query the query
     * @param values the query parameters
     */
    protected void assertUnauthorizedQuery(String errorMessage, String query, Object... values) throws Throwable
    {
        assertInvalidThrowMessage(Optional.of(ProtocolVersion.CURRENT),
                                  errorMessage,
                                  UnauthorizedException.class,
                                  query,
                                  values);
    }

    @FunctionalInterface
    public interface CheckedFunction {
        void apply() throws Throwable;
    }

    /**
     * Runs the given function before and after a flush of sstables.  This is useful for checking that behavior is
     * the same whether data is in memtables or sstables.
     * @param runnable
     * @throws Throwable
     */
    public void beforeAndAfterFlush(CheckedFunction runnable) throws Throwable
    {
        runnable.apply();
        flush();
        runnable.apply();
    }

    private static String replaceValues(String query, Object[] values)
    {
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int i = 0;
        int idx;
        while ((idx = query.indexOf('?', last)) > 0)
        {
            if (i >= values.length)
                throw new IllegalArgumentException(String.format("Not enough values provided. The query has at least %d variables but only %d values provided", i, values.length));

            sb.append(query.substring(last, idx));

            Object value = values[i++];

            // When we have a .. IN ? .., we use a list for the value because that's what's expected when the value is serialized.
            // When we format as string however, we need to special case to use parenthesis. Hackish but convenient.
            if (idx >= 3 && value instanceof List && query.substring(idx - 3, idx).equalsIgnoreCase("IN "))
            {
                List l = (List)value;
                sb.append("(");
                for (int j = 0; j < l.size(); j++)
                {
                    if (j > 0)
                        sb.append(", ");
                    sb.append(formatForCQL(l.get(j)));
                }
                sb.append(")");
            }
            else
            {
                sb.append(formatForCQL(value));
            }
            last = idx + 1;
        }
        sb.append(query.substring(last));
        return sb.toString();
    }

    // We're rellly only returning ByteBuffers but this make the type system happy
    private static Object[] transformValues(Object[] values)
    {
        // We could partly rely on QueryProcessor.executeOnceInternal doing type conversion for us, but
        // it would complain with ClassCastException if we pass say a string where an int is excepted (since
        // it bases conversion on what the value should be, not what it is). For testing, we sometimes
        // want to pass value of the wrong type and assert that this properly raise an InvalidRequestException
        // and executeOnceInternal goes into way. So instead, we pre-convert everything to bytes here based
        // on the value.
        // Besides, we need to handle things like TupleValue that executeOnceInternal don't know about.

        Object[] buffers = new ByteBuffer[values.length];
        for (int i = 0; i < values.length; i++)
        {
            Object value = values[i];
            if (value == null)
            {
                buffers[i] = null;
                continue;
            }
            else if (value == ByteBufferUtil.UNSET_BYTE_BUFFER)
            {
                buffers[i] = ByteBufferUtil.UNSET_BYTE_BUFFER;
                continue;
            }

            try
            {
                buffers[i] = typeFor(value).decompose(serializeTuples(value));
            }
            catch (Exception ex)
            {
                logger.info("Error serializing query parameter {}:", value, ex);
                throw ex;
            }
        }
        return buffers;
    }

    private static Object serializeTuples(Object value)
    {
        if (value instanceof TupleValue)
        {
            return ((TupleValue)value).toByteBuffer();
        }

        // We need to reach inside collections for TupleValue and transform them to ByteBuffer
        // since otherwise the decompose method of the collection AbstractType won't know what
        // to do with them
        if (value instanceof List)
        {
            List l = (List)value;
            List n = new ArrayList(l.size());
            for (Object o : l)
                n.add(serializeTuples(o));
            return n;
        }

        if (value instanceof Set)
        {
            Set s = (Set)value;
            Set n = new LinkedHashSet(s.size());
            for (Object o : s)
                n.add(serializeTuples(o));
            return n;
        }

        if (value instanceof Map)
        {
            Map m = (Map)value;
            Map n = new LinkedHashMap(m.size());
            for (Object entry : m.entrySet())
                n.put(serializeTuples(((Map.Entry)entry).getKey()), serializeTuples(((Map.Entry)entry).getValue()));
            return n;
        }
        return value;
    }

    private static String formatAllValues(Object[] values)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < values.length; i++)
        {
            if (i > 0)
                sb.append(", ");
            sb.append(formatForCQL(values[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatForCQL(Object value)
    {
        if (value == null)
            return "null";

        if (value instanceof TupleValue)
            return ((TupleValue)value).toCQLString();

        // We need to reach inside collections for TupleValue. Besides, for some reason the format
        // of collection that CollectionType.getString gives us is not at all 'CQL compatible'
        if (value instanceof Collection || value instanceof Map)
        {
            StringBuilder sb = new StringBuilder();
            if (value instanceof List)
            {
                List l = (List)value;
                sb.append("[");
                for (int i = 0; i < l.size(); i++)
                {
                    if (i > 0)
                        sb.append(", ");
                    sb.append(formatForCQL(l.get(i)));
                }
                sb.append("]");
            }
            else if (value instanceof Set)
            {
                Set s = (Set)value;
                sb.append("{");
                Iterator iter = s.iterator();
                while (iter.hasNext())
                {
                    sb.append(formatForCQL(iter.next()));
                    if (iter.hasNext())
                        sb.append(", ");
                }
                sb.append("}");
            }
            else
            {
                Map m = (Map)value;
                sb.append("{");
                Iterator iter = m.entrySet().iterator();
                while (iter.hasNext())
                {
                    Map.Entry entry = (Map.Entry)iter.next();
                    sb.append(formatForCQL(entry.getKey())).append(": ").append(formatForCQL(entry.getValue()));
                    if (iter.hasNext())
                        sb.append(", ");
                }
                sb.append("}");
            }
            return sb.toString();
        }

        AbstractType type = typeFor(value);
        String s = type.getString(type.decompose(value));

        if (type instanceof InetAddressType || type instanceof TimestampType)
            return String.format("'%s'", s);
        else if (type instanceof UTF8Type)
            return String.format("'%s'", s.replaceAll("'", "''"));
        else if (type instanceof BytesType)
            return "0x" + s;

        return s;
    }

    protected static ByteBuffer makeByteBuffer(Object value, AbstractType type)
    {
        if (value == null)
            return null;

        if (value instanceof TupleValue)
            return ((TupleValue)value).toByteBuffer();

        if (value instanceof ByteBuffer)
            return (ByteBuffer)value;

        return type.decompose(serializeTuples(value));
    }

    private static String formatValue(ByteBuffer bb, AbstractType<?> type)
    {
        if (bb == null)
            return "null";

        if (type instanceof CollectionType)
        {
            // CollectionType override getString() to use hexToBytes. We can't change that
            // without breaking SSTable2json, but the serializer for collection have the
            // right getString so using it directly instead.
            TypeSerializer ser = type.getSerializer();
            return ser.toString(ser.deserialize(bb));
        }

        return type.getString(bb);
    }

    protected TupleValue tuple(Object...values)
    {
        return new TupleValue(values);
    }

    protected Object userType(Object... values)
    {
        if (values.length % 2 != 0)
            throw new IllegalArgumentException("userType() requires an even number of arguments");

        String[] fieldNames = new String[values.length / 2];
        Object[] fieldValues = new Object[values.length / 2];
        int fieldNum = 0;
        for (int i = 0; i < values.length; i += 2)
        {
            fieldNames[fieldNum] = (String) values[i];
            fieldValues[fieldNum] = values[i + 1];
            fieldNum++;
        }
        return new UserTypeValue(fieldNames, fieldValues);
    }

    protected Object list(Object...values)
    {
        return Arrays.asList(values);
    }

    protected Object set(Object...values)
    {
        return ImmutableSet.copyOf(values);
    }

    protected Object map(Object...values)
    {
        if (values.length % 2 != 0)
            throw new IllegalArgumentException("Invalid number of arguments, got " + values.length);

        int size = values.length / 2;
        Map<Object, Object> m = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++)
            m.put(values[2 * i], values[(2 * i) + 1]);
        return m;
    }

    protected com.datastax.driver.core.TupleType tupleTypeOf(ProtocolVersion protocolVersion, com.datastax.driver.core.DataType...types)
    {
        requireNetwork();
        return getCluster(protocolVersion).getMetadata().newTupleType(types);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected static Gauge<Integer> getPausedConnectionsGauge()
    {
        String metricName = "org.apache.cassandra.metrics.Client.PausedConnections";
        Map<String, Gauge> metrics = CassandraMetricsRegistry.Metrics.getGauges((name, metric) -> name.equals(metricName));
        if (metrics.size() != 1)
            fail(String.format("Expected a single registered metric for paused client connections, found %s",
                               metrics.size()));
        return metrics.get(metricName);
    }

    // Attempt to find an AbstracType from a value (for serialization/printing sake).
    // Will work as long as we use types we know of, which is good enough for testing
    private static AbstractType typeFor(Object value)
    {
        if (value instanceof ByteBuffer || value instanceof TupleValue || value == null)
            return BytesType.instance;

        if (value instanceof Byte)
            return ByteType.instance;

        if (value instanceof Short)
            return ShortType.instance;

        if (value instanceof Integer)
            return Int32Type.instance;

        if (value instanceof Long)
            return LongType.instance;

        if (value instanceof Float)
            return FloatType.instance;

        if (value instanceof Duration)
            return DurationType.instance;

        if (value instanceof Double)
            return DoubleType.instance;

        if (value instanceof BigInteger)
            return IntegerType.instance;

        if (value instanceof BigDecimal)
            return DecimalType.instance;

        if (value instanceof String)
            return UTF8Type.instance;

        if (value instanceof Boolean)
            return BooleanType.instance;

        if (value instanceof InetAddress)
            return InetAddressType.instance;

        if (value instanceof Date)
            return TimestampType.instance;

        if (value instanceof UUID)
            return UUIDType.instance;

        if (value instanceof List)
        {
            List l = (List)value;
            AbstractType elt = l.isEmpty() ? BytesType.instance : typeFor(l.get(0));
            return ListType.getInstance(elt, true);
        }

        if (value instanceof Set)
        {
            Set s = (Set)value;
            AbstractType elt = s.isEmpty() ? BytesType.instance : typeFor(s.iterator().next());
            return SetType.getInstance(elt, true);
        }

        if (value instanceof Map)
        {
            Map m = (Map)value;
            AbstractType keys, values;
            if (m.isEmpty())
            {
                keys = BytesType.instance;
                values = BytesType.instance;
            }
            else
            {
                Map.Entry entry = (Map.Entry)m.entrySet().iterator().next();
                keys = typeFor(entry.getKey());
                values = typeFor(entry.getValue());
            }
            return MapType.getInstance(keys, values, true);
        }

        throw new IllegalArgumentException("Unsupported value type (value is " + value + ")");
    }

    private static class TupleValue
    {
        protected final Object[] values;

        TupleValue(Object[] values)
        {
            this.values = values;
        }

        public ByteBuffer toByteBuffer()
        {
            ByteBuffer[] bbs = new ByteBuffer[values.length];
            for (int i = 0; i < values.length; i++)
                bbs[i] = makeByteBuffer(values[i], typeFor(values[i]));
            return TupleType.buildValue(bbs);
        }

        public String toCQLString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("(");
            for (int i = 0; i < values.length; i++)
            {
                if (i > 0)
                    sb.append(", ");
                sb.append(formatForCQL(values[i]));
            }
            sb.append(")");
            return sb.toString();
        }

        public String toString()
        {
            return "TupleValue" + toCQLString();
        }
    }

    private static class UserTypeValue extends TupleValue
    {
        private final String[] fieldNames;

        UserTypeValue(String[] fieldNames, Object[] fieldValues)
        {
            super(fieldValues);
            this.fieldNames = fieldNames;
        }

        @Override
        public String toCQLString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean haveEntry = false;
            for (int i = 0; i < values.length; i++)
            {
                if (values[i] != null)
                {
                    if (haveEntry)
                        sb.append(", ");
                    sb.append(ColumnIdentifier.maybeQuote(fieldNames[i]));
                    sb.append(": ");
                    sb.append(formatForCQL(values[i]));
                    haveEntry = true;
                }
            }
            assert haveEntry;
            sb.append("}");
            return sb.toString();
        }

        public String toString()
        {
            return "UserTypeValue" + toCQLString();
        }
    }

    private static class User
    {
        /**
         * The user name
         */
        public final String username;

        /**
         * The user password
         */
        public final String password;

        public User(String username, String password)
        {
            this.username = username;
            this.password = password;
        }

        @Override
        public int hashCode()
        {
            return Objects.hashCode(username, password);
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;

            if (!(o instanceof User))
                return false;

            User u = (User) o;

            return Objects.equal(username, u.username)
                && Objects.equal(password, u.password);
        }
    }
}
