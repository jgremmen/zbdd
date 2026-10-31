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

import java.util.Arrays;
import java.util.Random;
import java.util.function.IntUnaryOperator;

import static de.sayayi.lib.zbdd.benchmark.ConcurrentRunner.sum;
import static de.sayayi.lib.zbdd.benchmark.Workloads.createVars;
import static de.sayayi.lib.zbdd.benchmark.Workloads.randomSet;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.openjdk.jmh.annotations.Mode.Throughput;


/**
 * Three threads reading a zbdd instance while a fourth thread changes it, showing how much the readers suffer from a
 * writer and the writer from the readers. The secondary results {@code reads} and {@code writes} are the scores; see
 * {@link ConcurrentRunner} for the reason. {@link Writer#NONE} provides the baseline for the readers.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 *
 * @see ConcurrentReadBenchmark
 * @see ConcurrentWriteBenchmark
 */
@State(Scope.Benchmark)
@BenchmarkMode(Throughput)
@OutputTimeUnit(MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ConcurrentMixedBenchmark
{
  private static final int VAR_COUNT = 40;
  private static final int READERS = 3;

  @Param({ "CONCURRENT", "CACHED_CONCURRENT" })
  public ZbddVariant variant;

  @Param
  public Writer writer;

  private Zbdd zbdd;
  private ConcurrentRunner runner;
  private int[] sets;
  private int subset;


  @Setup(Level.Trial)
  public void createSets()
  {
    zbdd = variant.create();
    runner = new ConcurrentRunner(writer == Writer.NONE ? READERS : READERS + 1);
    sets = new int[32];

    final var vars = createVars(zbdd, VAR_COUNT);
    final var random = new Random(1);

    for(var i = 0; i < sets.length; i++)
      sets[i] = randomSet(zbdd, vars, 0, VAR_COUNT, random, 200, 3, 10);

    subset = zbdd.incRef(zbdd.subset0(sets[0], vars[VAR_COUNT / 2]));
  }


  @TearDown(Level.Trial)
  public void shutdown() {
    runner.shutdown();
  }


  @Benchmark
  public void readersAndWriter(Counters counters)
  {
    final var operations = new IntUnaryOperator[writer == Writer.NONE ? READERS : READERS + 1];

    Arrays.fill(operations, (IntUnaryOperator)random -> zbdd.contains(sets[0], subset) ? 1 : 0);

    if (writer == Writer.UNION)
      operations[READERS] = random -> zbdd.union(sets[random & 31], sets[(random >>> 5) & 31]);
    else if (writer == Writer.GC)
      operations[READERS] = random -> zbdd.gc();

    final var completed = runner.run(operations);

    counters.reads += sum(completed, 0, READERS);
    counters.writes += sum(completed, READERS, completed.length);
  }




  public enum Writer {
    NONE, UNION, GC
  }




  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.OPERATIONS)
  public static class Counters
  {
    public long reads;
    public long writes;


    @Setup(Level.Iteration)
    public void reset() {
      reads = writes = 0;
    }
  }
}
