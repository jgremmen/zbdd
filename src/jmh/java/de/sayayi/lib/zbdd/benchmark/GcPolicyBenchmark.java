/*
 * Copyright 2026 Jeroen Gremmen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.sayayi.lib.zbdd.benchmark;

import de.sayayi.lib.zbdd.Zbdd;
import de.sayayi.lib.zbdd.ZbddCapacityAdvisor;
import de.sayayi.lib.zbdd.ZbddStatistics;
import org.jetbrains.annotations.NotNull;
import org.openjdk.jmh.annotations.*;

import java.util.Random;

import static de.sayayi.lib.zbdd.benchmark.Workloads.createVars;
import static de.sayayi.lib.zbdd.benchmark.Workloads.fillNodes;
import static de.sayayi.lib.zbdd.benchmark.Workloads.randomSet;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.AuxCounters.Type.EVENTS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;


/**
 * Working on a table that is nearly full of live nodes, repeating an operation and creating some garbage. The result
 * shows what the policy of the capacity advisor costs, as each garbage collection scans the entire table and empties
 * the operation cache. The number of garbage collections is reported as a secondary result.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 *
 * @see GarbageCollectionBenchmark
 */
@State(Thread)
@BenchmarkMode(AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class GcPolicyBenchmark
{
  private static final int VAR_COUNT = 3000;
  private static final int SET_VAR_COUNT = 200;

  @Param
  public Policy policy;

  @Param("1100000")
  public int liveNodes;

  private Zbdd zbdd;
  private ZbddStatistics statistics;
  private int[] vars;
  private int set;
  private Random random;


  @Setup(Level.Trial)
  public void createNodes()
  {
    zbdd = ZbddVariant.CACHED.create(policy.advisor, 1 << 22);
    statistics = zbdd.getStatistics();
    vars = createVars(zbdd, VAR_COUNT);
    random = new Random(1);
    set = randomSet(zbdd, vars, 0, SET_VAR_COUNT, random, 4000, 4, 4);

    fillNodes(zbdd, vars, random, liveNodes);
  }


  @Benchmark
  public int repeatedOperationAndGarbage(Counters counters)
  {
    final var result = zbdd.subset0(set, vars[random.nextInt(50)]);

    zbdd.cube(vars[garbageVar()], vars[garbageVar()], vars[garbageVar()]);

    counters.gcCount = statistics.getGCCount() - counters.initialGcCount;

    return result;
  }


  private int garbageVar() {
    return SET_VAR_COUNT + random.nextInt(VAR_COUNT - SET_VAR_COUNT);
  }




  /**
   * The capacity advisors to compare.
   */
  public enum Policy
  {
    /** The advisor used by default. */
    DEFAULT(null),

    /** The same as the default advisor, except for a minimum of 5% of free nodes after a garbage collection. */
    FREE_5_PERCENT(new ZbddCapacityAdvisor() {
      @Override
      public int getInitialCapacity() {
        return 128;
      }


      @Override
      public int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) {
        return statistics.getNodesCapacity() / 20;
      }


      @Override
      public int adviseIncrement(@NotNull ZbddStatistics statistics)
      {
        final var capacity = statistics.getNodesCapacity();

        return capacity < 500000 ? capacity * 3 / 2 : (capacity / 10) * 3;
      }


      @Override
      public boolean isGCRequired(@NotNull ZbddStatistics statistics)
      {
        final var capacity = statistics.getNodesCapacity();

        return capacity > 250000 || statistics.getUnreferencedNodes() > (capacity / 10);
      }
    });


    private final ZbddCapacityAdvisor advisor;


    Policy(ZbddCapacityAdvisor advisor) {
      this.advisor = advisor;
    }
  }




  /**
   * Secondary result, summed over all iterations.
   */
  @State(Thread)
  @AuxCounters(EVENTS)
  public static class Counters
  {
    public long gcCount;

    private int initialGcCount;


    @Setup(Level.Iteration)
    public void reset(GcPolicyBenchmark benchmark)
    {
      gcCount = 0;
      initialGcCount = benchmark.statistics.getGCCount();
    }
  }
}
