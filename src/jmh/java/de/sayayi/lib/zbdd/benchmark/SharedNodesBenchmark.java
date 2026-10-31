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

import static de.sayayi.lib.zbdd.benchmark.Workloads.createVars;
import static de.sayayi.lib.zbdd.benchmark.Workloads.powerSet;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;


/**
 * Operations on the set of all combinations of {@code n} variables, which has only {@code n} nodes, each reachable
 * through many paths. Comparing the scores for different {@code n} shows whether the running time of an operation
 * follows the number of nodes or the number of paths.
 * <p>
 * The cost of the uncached operations doubles with each variable, so be careful with larger values of {@code n}.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@State(Thread)
@BenchmarkMode(AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class SharedNodesBenchmark
{
  @Param({ "PLAIN", "CACHED" })
  public ZbddVariant variant;

  @Param({ "12", "16", "20" })
  public int n;

  private Zbdd zbdd;
  private int var;
  private int[] filterVar;
  private int powerSet, withoutBase;


  @Setup(Level.Trial)
  public void createSets()
  {
    zbdd = variant.create();

    final var vars = createVars(zbdd, n);

    var = vars[0];
    filterVar = new int[] { var };
    powerSet = powerSet(zbdd, vars);
    withoutBase = zbdd.incRef(zbdd.removeBase(powerSet));
  }


  @Setup(Level.Invocation)
  public void collectGarbage() {
    zbdd.gc();
  }


  @Benchmark
  public int subset0() {
    return zbdd.subset0(powerSet, var);
  }


  @Benchmark
  public int subset0WithManyVars() {
    return zbdd.subset0(powerSet, filterVar);
  }


  @Benchmark
  public int subset1() {
    return zbdd.subset1(powerSet, var);
  }


  @Benchmark
  public int change() {
    return zbdd.change(powerSet, var);
  }


  @Benchmark
  public int atomize() {
    return zbdd.atomize(powerSet);
  }


  @Benchmark
  public int union() {
    return zbdd.union(powerSet, zbdd.change(withoutBase, var));
  }


  @Benchmark
  public int intersect() {
    return zbdd.intersect(powerSet, zbdd.change(withoutBase, var));
  }


  @Benchmark
  public int difference() {
    return zbdd.difference(powerSet, zbdd.change(withoutBase, var));
  }


  @Benchmark
  public boolean contains() {
    return zbdd.contains(powerSet, withoutBase);
  }


  @Benchmark
  public boolean hasCubeWithVar() {
    return zbdd.hasCubeWithVar(powerSet, var);
  }
}
