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

package org.apache.cassandra.distributed.upgrade;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.distributed.UpgradeableCluster;
import org.apache.cassandra.distributed.api.ConsistencyLevel;
import org.apache.cassandra.distributed.api.Feature;

import static org.apache.cassandra.distributed.shared.AssertUtils.*;

public class MixedModeMessageForwardTest extends UpgradeTestBase
{
    private static final Logger logger = LoggerFactory.getLogger(MixedModeMessageForwardTest.class);
    private static int nextKey = 1;
    private static final String TABLE = "tbl";
    private static final String INSERT_QUERY = String.format("INSERT INTO %s.%s(pk) VALUES (?)", KEYSPACE, TABLE);
    private static final String CHECK_QUERY = String.format("SELECT pk FROM %s.%s WHERE pk = ?", KEYSPACE, TABLE);

    private void writeReadTest(UpgradeableCluster cluster)
    {
        // Coordinate a write from each node and then check present on all replicas
        int readKey = nextKey;
        for (int coordId = 1; coordId <= cluster.size(); coordId++)
        {
            logger.info("Coordinating CL.ALL Insert from node{} ", coordId);
            cluster.get(coordId).coordinator().execute(INSERT_QUERY, ConsistencyLevel.ALL, nextKey++);
        }

        for (int coordId = 1; coordId <= cluster.size(); coordId++)
        {
            for (int nodeId = 1; nodeId <= cluster.size(); nodeId++) {
                Object[][] results = cluster.get(nodeId).executeInternal(CHECK_QUERY, readKey);
                assertRows(results, row(readKey));
            }
            readKey++;
        }
    }

    /* Verify that messages sent with sendToHintedReplicas to non-local DCs
     * are forwarded on to the hosts there.
     *
     * 1) creates a mixed cluster with multiple datacenters and a keyspace
     *    configured to write to all replicas in the datacenter
     * 2) check the original single-version cluster by issuing an INSERT
     *    mutation from a coordinator on each node, then check that value
     *    has locally been written to each of the nodes.
     * 3) Upgrade nodes one at a time, rechecking that all writes are forwarded.
     */
    @Test
    public void checkWritesForwardedToOtherDcTest() throws Throwable
    {
        int numDCs = 2;
        int nodesPerDc = 2;
        String ntsArgs = IntStream.range(1, numDCs + 1)
                                  .mapToObj(dc -> String.format("'datacenter%d' : %d", dc, nodesPerDc))
                                  .collect(Collectors.joining(","));

        new TestCase()
        .withConfig(c -> c.with(Feature.GOSSIP, Feature.NETWORK).set("request_timeout", "30000ms"))
        .withBuilder(b -> b.withRacks(numDCs, 1, nodesPerDc))
        .nodes(numDCs * nodesPerDc)
        .singleUpgrade(v30)
        .setup(cluster -> {
            cluster.schemaChange("ALTER KEYSPACE " + KEYSPACE +
                " WITH replication = {'class': 'NetworkTopologyStrategy', " + ntsArgs + " };");

            cluster.schemaChange(String.format("CREATE TABLE %s.%s (pk int, PRIMARY KEY(pk))", KEYSPACE, TABLE));

            logger.info("Testing after setup, all nodes running {}", cluster.get(1).getReleaseVersionString());
            writeReadTest(cluster);
        })
        .runAfterNodeUpgrade((UpgradeableCluster cluster, int nodeId) -> {
            // Should be able to coordinate a write to any node and have a copy appear locally on all others
            logger.info("Testing after upgrading node{} to {}", nodeId, cluster.get(nodeId).getReleaseVersionString());
            writeReadTest(cluster);
        })
        .run();
    }
}
