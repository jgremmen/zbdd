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
 * Operations that don't change the state of a zbdd instance, run by several threads at once. The score is the secondary
 * result {@code operations}; see {@link ConcurrentRunner} for the reason.
 * <p>
 * Comparing {@link Access#LOCKED} with {@link Access#UNLOCKED} shows what the lock costs. Without any writers, direct
 * access to the instance stands in for a read lock. This is only valid for these operations and not a supported way to
 * use an instance.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 *
 * @see ConcurrentWriteBenchmark
 * @see ConcurrentMixedBenchmark
 */
@State(Scope.Benchmark)
@BenchmarkMode(Throughput)
@OutputTimeUnit(MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ConcurrentReadBenchmark
{
  private static final int VAR_COUNT = 64;

  @Param
  public Access access;

  @Param({ "1", "2", "4", "8" })
  public int threads;

  private Zbdd zbdd;
  private ConcurrentRunner runner;
  private int var;
  private int p, subset;


  @Setup(Level.Trial)
  public void createSets()
  {
    zbdd = access == Access.LOCKED ? ZbddVariant.CONCURRENT.create() : ZbddVariant.PLAIN.create();
    runner = new ConcurrentRunner(threads);

    final var vars = createVars(zbdd, VAR_COUNT);

    var = vars[0];
    p = randomSet(zbdd, vars, 0, VAR_COUNT, new Random(1), 5000, 3, 12);
    subset = zbdd.incRef(zbdd.subset0(p, vars[VAR_COUNT / 2]));
  }


  @TearDown(Level.Trial)
  public void shutdown() {
    runner.shutdown();
  }


  private void run(@NotNull Counters counters, @NotNull IntUnaryOperator operation)
  {
    final var operations = new IntUnaryOperator[threads];

    Arrays.fill(operations, operation);
    counters.operations += sum(runner.run(operations), 0, threads);
  }


  @Benchmark
  public void contains(Counters counters) {
    run(counters, random -> zbdd.contains(p, subset) ? 1 : 0);
  }


  @Benchmark
  public void hasCubeWithVar(Counters counters) {
    run(counters, random -> zbdd.hasCubeWithVar(p, var) ? 1 : 0);
  }


  @Benchmark
  public void countExact(Counters counters) {
    run(counters, random -> zbdd.countExact(p).signum());
  }


  @Benchmark
  public void getVar(Counters counters) {
    run(counters, random -> zbdd.getVar(p));
  }




  public enum Access {
    LOCKED, UNLOCKED
  }




  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.OPERATIONS)
  public static class Counters
  {
    public long operations;


    @Setup(Level.Iteration)
    public void reset() {
      operations = 0;
    }
  }
}
