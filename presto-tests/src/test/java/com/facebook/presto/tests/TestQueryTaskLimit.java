/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.tests;

import com.facebook.presto.Session;
import com.facebook.presto.server.BasicQueryInfo;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.tpch.TpchPlugin;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static com.facebook.presto.execution.QueryState.FAILED;
import static com.facebook.presto.execution.QueryState.QUEUED;
import static com.facebook.presto.execution.QueryState.RUNNING;
import static com.facebook.presto.execution.TestQueryRunnerUtil.createQuery;
import static com.facebook.presto.execution.TestQueryRunnerUtil.waitForQueryState;
import static com.facebook.presto.execution.TestQueues.newSession;
import static com.facebook.presto.spi.StandardErrorCode.CLUSTER_HAS_TOO_MANY_RUNNING_TASKS;
import static com.facebook.presto.testing.TestingSession.testSessionBuilder;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestQueryTaskLimit
{
    public static final String LONG_LASTING_QUERY = "SELECT COUNT(*) FROM lineitem";

    private ExecutorService executor;
    private Session defaultSession;

    @BeforeClass
    public void setUp()
    {
        executor = newCachedThreadPool();
        defaultSession = testSessionBuilder()
                .setCatalog("tpch")
                .setSchema("sf1000")
                .build();
    }

    @AfterClass(alwaysRun = true)
    public void shutdown()
            throws Exception
    {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, SECONDS));
        executor = null;
    }

    @Test(timeOut = 90_000, expectedExceptions = ExecutionException.class, expectedExceptionsMessageRegExp = ".*Query killed because the cluster is overloaded with too many tasks.*")
    public void testExceedTaskLimit()
            throws Exception
    {
        ImmutableMap<String, String> extraProperties = ImmutableMap.<String, String>builder()
                .put("max-total-running-task-count-to-kill-query", "4")
                .put("max-query-running-task-count", "4")
                .build();

        try (DistributedQueryRunner queryRunner = createQueryRunner(defaultSession, extraProperties)) {
            Future<?> query = executor.submit(() -> queryRunner.execute("SELECT COUNT(*), clerk FROM orders GROUP BY clerk"));

            waitForQueryToBeKilled(queryRunner);

            query.get();
        }
    }

    @Test(timeOut = 90_000)
    public void testQueuingWhenTaskLimitExceeds()
            throws Exception
    {
        ImmutableMap<String, String> extraProperties = ImmutableMap.<String, String>builder()
                .put("experimental.spill-enabled", "false")
                .put("experimental.max-total-running-task-count-to-not-execute-new-query", "2")
                .build();

        try (DistributedQueryRunner queryRunner = createQueryRunner(defaultSession, extraProperties)) {
            QueryId firstQuery = createQuery(queryRunner, newSession("test", ImmutableSet.of(), null),
                    LONG_LASTING_QUERY);
            waitForQueryState(queryRunner, firstQuery, RUNNING);

            // wait for the first query to schedule more than 2 tasks, so exceed resource group manager's total task limit
            confirmQueryScheduledTasksGreaterThan(queryRunner, firstQuery, 2);

            QueryId secondQuery = createQuery(queryRunner, newSession("test", ImmutableSet.of(), null), LONG_LASTING_QUERY);

            // When current running tasks exceeded limit, the following query would be queued
            waitForQueryState(queryRunner, secondQuery, QUEUED);

            // Cancel the first query
            queryRunner.getCoordinator().getDispatchManager().cancelQuery(firstQuery);
            waitForQueryState(queryRunner, firstQuery, FAILED);

            // When first query is cancelled, the second query would be pass to run
            waitForQueryState(queryRunner, secondQuery, RUNNING);
        }
    }

    public static DistributedQueryRunner createQueryRunner(Session session, Map<String, String> properties)
            throws Exception
    {
        DistributedQueryRunner queryRunner = new DistributedQueryRunner(session, 2, properties);

        try {
            queryRunner.installPlugin(new TpchPlugin());
            queryRunner.createCatalog("tpch", "tpch");
            return queryRunner;
        }
        catch (Exception e) {
            queryRunner.close();
            throw e;
        }
    }

    private void waitForQueryToBeKilled(DistributedQueryRunner queryRunner)
            throws InterruptedException
    {
        while (true) {
            for (BasicQueryInfo info : queryRunner.getCoordinator().getQueryManager().getQueries()) {
                if (info.getState().isDone()) {
                    assertNotNull(info.getErrorCode());
                    assertEquals(info.getErrorCode().getCode(), CLUSTER_HAS_TOO_MANY_RUNNING_TASKS.toErrorCode().getCode());
                    MILLISECONDS.sleep(100);
                    return;
                }
            }
            MILLISECONDS.sleep(10);
        }
    }

    private void confirmQueryScheduledTasksGreaterThan(DistributedQueryRunner queryRunner, QueryId queryId, int minTaskCount)
            throws InterruptedException
    {
        while (true) {
            BasicQueryInfo info = queryRunner.getCoordinator().getDispatchManager().getQueryInfo(queryId);
            if (info.getQueryStats().getRunningTasks() > minTaskCount) {
                // still wait for a while to ensure resource group manager to refresh
                MILLISECONDS.sleep(10);
                break;
            }
            MILLISECONDS.sleep(100);
        }
    }
}
