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
import org.openjdk.jmh.annotations.*;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.openjdk.jmh.annotations.AuxCounters.Type.EVENTS;
import static org.openjdk.jmh.annotations.Mode.SingleShotTime;
import static org.openjdk.jmh.annotations.Scope.Thread;


/**
 * Complete workloads with each of the provided capacity advisors. The final table size in nodes, the number of garbage
 * collections and the number of capacity increases are reported as secondary results, summed over all measurement
 * iterations of all forks: divide them by 30 to get the values for a single workload.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 *
 * @see GcPolicyBenchmark
 */
@State(Thread)
@BenchmarkMode(SingleShotTime)
@OutputTimeUnit(MILLISECONDS)
@Fork(2)
@Warmup(iterations = 10)
@Measurement(iterations = 15)
public class CapacityAdvisorBenchmark
{
  @Param
  public AdvisorWorkload workload;

  // a plain instance takes minutes on LARGE_TABLE, so it has to be selected explicitly
  @Param("CACHED")
  public ZbddVariant variant;

  @Param
  public Advisor advisor;


  @Benchmark
  public int run(Counters counters)
  {
    final var instance = new Zbdd[1];
    final var checksum = workload.run(() -> instance[0] = variant.create(advisor.advisor, workload.cacheSize()));
    final var statistics = instance[0].getStatistics();

    counters.capacity = statistics.getNodesCapacity();
    counters.gcCount = statistics.getGCCount();
    counters.capacityIncreases = statistics.getCapacityIncreaseCount();

    return checksum;
  }




  /**
   * The capacity advisors to compare.
   */
  public enum Advisor
  {
    /** The advisor used by default. */
    DEFAULT(ZbddCapacityAdvisor.DEFAULT),

    MINIMAL_MEMORY(ZbddCapacityAdvisor.MINIMAL_MEMORY),

    LOW_MEMORY(ZbddCapacityAdvisor.LOW_MEMORY),

    BALANCED(ZbddCapacityAdvisor.BALANCED),

    HIGH_PERFORMANCE(ZbddCapacityAdvisor.HIGH_PERFORMANCE);


    private final ZbddCapacityAdvisor advisor;


    Advisor(ZbddCapacityAdvisor advisor) {
      this.advisor = advisor;
    }
  }




  /**
   * Secondary results, summed over all measurement iterations of all forks.
   */
  @State(Thread)
  @AuxCounters(EVENTS)
  public static class Counters
  {
    public long capacity;
    public long gcCount;
    public long capacityIncreases;
  }
}
