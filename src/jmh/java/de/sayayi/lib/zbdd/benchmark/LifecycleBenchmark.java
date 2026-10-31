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
import static de.sayayi.lib.zbdd.benchmark.Workloads.fillNodes;
import static de.sayayi.lib.zbdd.benchmark.Workloads.randomSet;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;


/**
 * Creating, filling and clearing a zbdd instance. Instances start with a small table, so filling them includes all
 * capacity increments and garbage collections on the way.
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
public class LifecycleBenchmark
{
  private static final int CREATED_VARS = 1000;

  @Param
  public ZbddVariant variant;

  @Param("5000")
  public int cubes;


  /**
   * Creating an instance and building a set of cubes, which is the typical sequence of incremental unions.
   */
  @Benchmark
  public int buildSet()
  {
    final var zbdd = variant.create(null, 1 << 16);

    return randomSet(zbdd, createVars(zbdd, 64), 0, 64, new Random(1), cubes, 3, 12);
  }


  /**
   * Growing the table to hold a large number of live nodes, including the required garbage collections.
   */
  @Benchmark
  public Zbdd growTable()
  {
    final var zbdd = variant.create(null, 1 << 16);

    fillNodes(zbdd, createVars(zbdd, 3000), new Random(1), 200_000);

    return zbdd;
  }


  @Benchmark
  @OperationsPerInvocation(CREATED_VARS)
  @OutputTimeUnit(NANOSECONDS)
  public void createVar(Instance instance, Blackhole blackhole)
  {
    for(var n = 0; n < CREATED_VARS; n++)
      blackhole.consume(instance.zbdd.createVar());
  }


  @Benchmark
  @OperationsPerInvocation(CREATED_VARS)
  @OutputTimeUnit(NANOSECONDS)
  public void createVarWithObject(Instance instance, Blackhole blackhole)
  {
    for(var n = 0; n < CREATED_VARS; n++)
      blackhole.consume(instance.zbdd.createVar(Boolean.TRUE));
  }


  @Benchmark
  public void clear(FilledInstance instance) {
    instance.zbdd.clear();
  }




  /**
   * An empty instance before each invocation.
   */
  @State(Thread)
  public static class Instance
  {
    private Zbdd zbdd;


    @Setup(Level.Trial)
    public void create(LifecycleBenchmark benchmark) {
      zbdd = benchmark.variant.create();
    }


    @Setup(Level.Invocation)
    public void clear() {
      zbdd.clear();
    }
  }




  /**
   * An instance with nodes before each invocation.
   */
  @State(Thread)
  public static class FilledInstance
  {
    private Zbdd zbdd;


    @Setup(Level.Trial)
    public void create(LifecycleBenchmark benchmark) {
      zbdd = benchmark.variant.create();
    }


    @Setup(Level.Invocation)
    public void fill() {
      randomSet(zbdd, createVars(zbdd, 64), 0, 64, new Random(1), 200, 3, 12);
    }
  }
}
