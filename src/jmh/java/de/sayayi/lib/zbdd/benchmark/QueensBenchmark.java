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

import org.openjdk.jmh.annotations.*;

import static de.sayayi.lib.zbdd.benchmark.Workloads.queens;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;


/**
 * Solving the n-queens problem from scratch, as an example of a complete workload including table growth and garbage
 * collections.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 *
 * @see CacheSizeBenchmark
 */
@State(Thread)
@BenchmarkMode(AverageTime)
@OutputTimeUnit(MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class QueensBenchmark
{
  @Param
  public ZbddVariant variant;

  @Param({ "8", "10" })
  public int n;


  @Benchmark
  public int queensFromScratch() {
    return queens(variant.create(null, 1 << 16), n);
  }
}
