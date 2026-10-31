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
import org.openjdk.jmh.infra.Blackhole;

import java.math.BigInteger;
import java.util.Random;

import static de.sayayi.lib.zbdd.benchmark.Workloads.createVars;
import static de.sayayi.lib.zbdd.benchmark.Workloads.randomSet;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;


/**
 * Operations which neither create nodes nor use the operation cache, so the state of the zbdd instance has no influence
 * on them.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@State(Thread)
@BenchmarkMode(AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ReadOperationBenchmark
{
  private static final int VAR_COUNT = 64;

  @Param
  public ZbddVariant variant;

  @Param("5000")
  public int cubes;

  private Zbdd zbdd;
  private int var;
  private int absentVar;
  private int p, q, subset, small;


  @Setup(Level.Trial)
  public void createSets()
  {
    zbdd = variant.create();

    final var vars = createVars(zbdd, VAR_COUNT);
    final var random = new Random(1);

    absentVar = zbdd.createVar();
    var = vars[0];
    p = randomSet(zbdd, vars, 0, VAR_COUNT, random, cubes, 3, 12);
    q = randomSet(zbdd, vars, 0, VAR_COUNT, random, cubes, 3, 12);
    subset = zbdd.incRef(zbdd.subset0(p, vars[VAR_COUNT / 2]));
    small = randomSet(zbdd, vars, 0, VAR_COUNT, random, 50, 3, 12);

    if (!zbdd.contains(p, subset) || zbdd.contains(p, q))
      throw new IllegalStateException("unexpected contains result");
  }


  @Benchmark
  public boolean containsSubset() {
    return zbdd.contains(p, subset);
  }


  @Benchmark
  public boolean containsOtherSet() {
    return zbdd.contains(p, q);
  }


  @Benchmark
  public boolean containsBase() {
    return zbdd.contains(p, Zbdd.BASE);
  }


  @Benchmark
  public boolean hasCubeWithVar() {
    return zbdd.hasCubeWithVar(p, var);
  }


  @Benchmark
  public boolean hasCubeWithAbsentVar() {
    return zbdd.hasCubeWithVar(p, absentVar);
  }


  @Benchmark
  public BigInteger countExact() {
    return zbdd.countExact(p);
  }


  @Benchmark
  public int getVar() {
    return zbdd.getVar(p);
  }


  @Benchmark
  public int getP0() {
    return zbdd.getP0(p);
  }


  @Benchmark
  public int getP1() {
    return zbdd.getP1(p);
  }


  @Benchmark
  public boolean isValidZbdd() {
    return zbdd.isValidZbdd(p);
  }


  @Benchmark
  public int incRefAndDecRef() {
    return zbdd.decRef(zbdd.incRef(p));
  }


  @Benchmark
  public boolean visitCubes(Blackhole blackhole)
  {
    return zbdd.visitCubes(p, vars -> {
      blackhole.consume(vars);
      return true;
    });
  }


  @Benchmark
  public String toStringOfSmallSet() {
    return zbdd.toString(small);
  }


  @Benchmark
  public int[] calculateNodeDependency() {
    return zbdd.calculateNodeDependency();
  }
}
