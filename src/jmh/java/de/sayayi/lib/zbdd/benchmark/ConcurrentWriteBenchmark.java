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
import org.jetbrains.annotations.NotNull;
import org.openjdk.jmh.annotations.*;

import java.util.Arrays;
import java.util.Random;
import java.util.function.IntUnaryOperator;

import static de.sayayi.lib.zbdd.benchmark.ConcurrentRunner.sum;
import static de.sayayi.lib.zbdd.benchmark.Workloads.createVars;
import static de.sayayi.lib.zbdd.benchmark.Workloads.randomSet;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.openjdk.jmh.annotations.Mode.Throughput;


/**
 * Operations that change the state of a zbdd instance, run by several threads at once on a pool of sets. The lock
 * serializes these operations, so the total throughput should stay level at best. The score is the secondary result
 * {@code operations}; see {@link ConcurrentRunner} for the reason.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 *
 * @see ConcurrentReadBenchmark
 * @see ConcurrentMixedBenchmark
 */
@State(Scope.Benchmark)
@BenchmarkMode(Throughput)
@OutputTimeUnit(MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ConcurrentWriteBenchmark
{
  private static final int VAR_COUNT = 40;

  @Param({ "CONCURRENT", "CACHED_CONCURRENT" })
  public ZbddVariant variant;

  @Param({ "1", "2", "4", "8" })
  public int threads;

  private Zbdd.Concurrent zbdd;
  private ConcurrentRunner runner;
  private int[] vars;
  private int[] sets;


  @Setup(Level.Trial)
  public void createSets()
  {
    zbdd = (Zbdd.Concurrent)variant.create();
    runner = new ConcurrentRunner(threads);
    vars = createVars(zbdd, VAR_COUNT);
    sets = new int[32];

    final var random = new Random(1);

    for(var i = 0; i < sets.length; i++)
      sets[i] = randomSet(zbdd, vars, 0, VAR_COUNT, random, 200, 3, 10);
  }


  @TearDown(Level.Trial)
  public void shutdown() {
    runner.shutdown();
  }


  private void run(@NotNull ConcurrentReadBenchmark.Counters counters, @NotNull IntUnaryOperator operation)
  {
    final var operations = new IntUnaryOperator[threads];

    Arrays.fill(operations, operation);
    counters.operations += sum(runner.run(operations), 0, threads);
  }


  @Benchmark
  public void union(ConcurrentReadBenchmark.Counters counters) {
    run(counters, random -> zbdd.union(sets[random & 31], sets[(random >>> 5) & 31]));
  }


  @Benchmark
  public void subset0(ConcurrentReadBenchmark.Counters counters) {
    run(counters, random -> zbdd.subset0(sets[random & 31], vars[(random >>> 5) & 31]));
  }


  @Benchmark
  public void count(ConcurrentReadBenchmark.Counters counters) {
    run(counters, random -> zbdd.count(sets[random & 31]));
  }


  /**
   * Keeping a result, which requires both operations to be protected by a single lock acquisition.
   */
  @Benchmark
  public void unionAndIncRefAtomic(ConcurrentReadBenchmark.Counters counters)
  {
    run(counters, random -> zbdd.doAtomic(
        z -> z.decRef(z.incRef(z.union(sets[random & 31], sets[(random >>> 5) & 31])))));
  }
}
