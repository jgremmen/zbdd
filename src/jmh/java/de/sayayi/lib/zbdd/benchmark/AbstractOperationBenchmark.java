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

import java.util.Random;

import static de.sayayi.lib.zbdd.benchmark.Workloads.createVars;
import static de.sayayi.lib.zbdd.benchmark.Workloads.randomSet;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;


/**
 * All operations which create nodes or use the operation cache, on sets of random cubes.
 * <p>
 * Whether these operations have to do any work depends on the state of the zbdd instance. The subclasses therefore run
 * them in the states of interest.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 *
 * @see ColdOperationBenchmark
 * @see WarmOperationBenchmark
 */
@State(Thread)
@BenchmarkMode(AverageTime)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public abstract class AbstractOperationBenchmark
{
  private static final int VAR_COUNT = 64;

  @Param
  public ZbddVariant variant;

  @Param("5000")
  public int cubes;

  protected Zbdd zbdd;

  private int[] vars;
  private int[] filterVars;
  private int[] cubeVars;
  private int var, topVar;

  // sets of cubes, about half of them are in both sets
  private int p, q, r;

  // product factors having no variables in common and the product, extended with cubes not being a multiple
  private int factor1, factor2, dividend;

  // sets using the same variables, which makes a product considerably more expensive
  private int overlapping1, overlapping2;


  @Setup(Level.Trial)
  public void createSets()
  {
    zbdd = variant.create();
    vars = createVars(zbdd, VAR_COUNT);
    topVar = zbdd.createVar();
    var = vars[VAR_COUNT / 2];
    filterVars = new int[] { vars[8], vars[24], vars[40], vars[56] };
    cubeVars = new int[] { vars[3], vars[11], vars[19], vars[27], vars[35], vars[43], vars[51], vars[59] };

    final var random = new Random(1);
    final var common = randomSet(zbdd, vars, 0, VAR_COUNT, random, cubes / 2, 3, 12);
    final var factorCubes = Math.max(cubes / 100, 2);

    p = zbdd.incRef(zbdd.union(common, randomSet(zbdd, vars, 0, VAR_COUNT, random, cubes / 2, 3, 12)));
    q = zbdd.incRef(zbdd.union(common, randomSet(zbdd, vars, 0, VAR_COUNT, random, cubes / 2, 3, 12)));
    r = randomSet(zbdd, vars, 0, VAR_COUNT, random, cubes / 2, 3, 12);

    factor1 = randomSet(zbdd, vars, 0, VAR_COUNT / 2, random, factorCubes, 1, 4);
    factor2 = randomSet(zbdd, vars, VAR_COUNT / 2, VAR_COUNT, random, factorCubes, 1, 4);
    dividend = zbdd.incRef(zbdd.union(zbdd.incRef(zbdd.multiply(factor1, factor2)),
        randomSet(zbdd, vars, 0, VAR_COUNT, random, cubes / 10, 3, 12)));

    overlapping1 = randomSet(zbdd, vars, 0, VAR_COUNT, random, factorCubes, 1, 4);
    overlapping2 = randomSet(zbdd, vars, 0, VAR_COUNT, random, factorCubes, 1, 4);
  }


  @Benchmark
  public int cubeWithOneVar() {
    return zbdd.cube(var);
  }


  @Benchmark
  public int cubeWithManyVars() {
    return zbdd.cube(cubeVars);
  }


  @Benchmark
  public int getNode() {
    return zbdd.getNode(topVar, p, q);
  }


  @Benchmark
  public int subset0() {
    return zbdd.subset0(p, var);
  }


  @Benchmark
  public int subset0WithManyVars() {
    return zbdd.subset0(p, filterVars);
  }


  @Benchmark
  public int subset1() {
    return zbdd.subset1(p, var);
  }


  @Benchmark
  public int change() {
    return zbdd.change(p, var);
  }


  @Benchmark
  public int count() {
    return zbdd.count(p);
  }


  @Benchmark
  public int union() {
    return zbdd.union(p, q);
  }


  @Benchmark
  public int unionOfManySets() {
    return zbdd.union(p, q, r);
  }


  @Benchmark
  public int intersect() {
    return zbdd.intersect(p, q);
  }


  @Benchmark
  public int difference() {
    return zbdd.difference(p, q);
  }


  @Benchmark
  public int multiply() {
    return zbdd.multiply(factor1, factor2);
  }


  @Benchmark
  public int multiplyWithCommonVars() {
    return zbdd.multiply(overlapping1, overlapping2);
  }


  @Benchmark
  public int divide() {
    return zbdd.divide(dividend, factor2);
  }


  @Benchmark
  public int modulo() {
    return zbdd.modulo(dividend, factor2);
  }


  @Benchmark
  public int atomize() {
    return zbdd.atomize(p);
  }


  @Benchmark
  public int removeBase() {
    return zbdd.removeBase(p);
  }


  @Benchmark
  public int[] asSingleCubeZbdds() {
    return zbdd.asSingleCubeZbdds(p);
  }


  @Benchmark
  public boolean visitCubeZbdds(Blackhole blackhole)
  {
    return zbdd.visitCubeZbdds(p, cube -> {
      blackhole.consume(cube);
      return true;
    });
  }
}
