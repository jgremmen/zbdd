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
import org.openjdk.jmh.annotations.*;

import java.util.Random;

import static de.sayayi.lib.zbdd.benchmark.Workloads.createVars;
import static de.sayayi.lib.zbdd.benchmark.Workloads.fillNodes;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;


/**
 * The cost of a single garbage collection, depending on the size of the table and the amount of garbage. The cached
 * variant includes emptying the operation cache.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 *
 * @see GcPolicyBenchmark
 */
@State(Thread)
@BenchmarkMode(AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class GarbageCollectionBenchmark
{
  @Param({ "PLAIN", "CACHED" })
  public ZbddVariant variant;

  @Param({ "100000", "1000000" })
  public int liveNodes;

  private Zbdd zbdd;


  @Setup(Level.Trial)
  public void createNodes()
  {
    zbdd = variant.create();
    fillNodes(zbdd, createVars(zbdd, 3000), new Random(1), liveNodes);
  }


  /**
   * Only the live nodes need to be marked and the table is scanned without finding anything to free.
   */
  @Benchmark
  public int gcWithoutGarbage() {
    return zbdd.gc();
  }


  /**
   * @see Garbage
   */
  @Benchmark
  public int gcWithGarbage(Garbage garbage) {
    return zbdd.gc();
  }




  /**
   * Creates unreferenced cubes before each invocation, using variables which are unlikely to have been combined yet.
   */
  @State(Thread)
  public static class Garbage
  {
    private static final int GARBAGE_CUBES = 5_000;

    private int[] vars;
    private final Random random = new Random(2);


    @Setup(Level.Invocation)
    public void createGarbage(GarbageCollectionBenchmark benchmark)
    {
      final var zbdd = benchmark.zbdd;

      if (vars == null)
        vars = createVars(zbdd, 3000);

      for(var n = 0; n < GARBAGE_CUBES; n++)
      {
        zbdd.cube(vars[random.nextInt(vars.length)], vars[random.nextInt(vars.length)],
            vars[random.nextInt(vars.length)]);
      }
    }
  }
}
