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
package de.sayayi.lib.zbdd.internal;

import de.sayayi.lib.zbdd.Zbdd;
import de.sayayi.lib.zbdd.exception.ZbddException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * Runs random operation sequences against {@link ZbddImpl} and {@link ZbddNative}. Results, node numbers, reference
 * counts, statistics, exception types and messages must be identical, except for the node lookups answered by the
 * operation cache of the native implementation.
 *
 * @author Jeroen Gremmen
 */
@DisplayName("Native zbdd matches the java implementation")
class ZbddNativeDifferentialTest
{
  private static final int VARS = 24;
  private static final int SMALL_VARS = 32767;
  private static final int SMALL_CAPACITY = 32768;


  @BeforeAll
  static void nativeLibraryAvailable()
  {
    var available = true;

    try {
      ZbddNative.liveContexts();
    } catch(ZbddException ex) {
      if (ex.getMessage().contains("not available"))
        available = false;
      else
        throw ex;
    }

    assumeTrue(available);
  }


  @ParameterizedTest(name = "past small limit: {0}, seed {1}")
  @CsvSource({
      "false, 1", "false, 2", "false, 3", "false, 4", "false, 5", "false, 6", "false, 7", "false, 8",
      "true, 1", "true, 2", "true, 3", "true, 4", "true, 5", "true, 6", "true, 7", "true, 8",
      "true, 1001", "true, 1002"
  })
  void randomOperations(boolean large, long seed)
  {
    final var java = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);

    try(var nativeZbdd = new ZbddNative(DefaultCapacityAdvisor.INSTANCE)) {
      // exceeding the variables while there are no nodes yet interrupts no operation, so node numbers stay identical
      if (large)
        for(var i = 0; i <= SMALL_VARS; i++)
          assertEquals(java.createVar(), nativeZbdd.createVar());

      run(new Random(seed), java, nativeZbdd, seed > 1000 ? 12000 : 3000);
    }
  }


  @ParameterizedTest(name = "seed {0}")
  @CsvSource({ "1", "2", "3" })
  @DisplayName("Crossing the format change without freed intermediate results, node numbers stay identical")
  void acrossFormatChange(long seed)
  {
    final var java = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);

    try(var nativeZbdd = new ZbddNative(DefaultCapacityAdvisor.INSTANCE)) {
      final var random = new Random(seed);
      final var vars = 12000;

      for(var i = 0; i < vars; i++)
        assertEquals(java.createVar(), nativeZbdd.createVar());

      var all = Zbdd.empty();

      // union and change only release results reachable from their parents, so a garbage collection in the operation
      // interrupted by the format change frees nothing the second run needs
      for(var step = 0; step < 40000 && nativeZbdd.getStatistics().getNodesCapacity() <= SMALL_CAPACITY; step++)
      {
        final var var = 1 + random.nextInt(vars);
        final var p = all;
        final var next = both(java, nativeZbdd, "step " + step,
            z -> z.incRef(z.union(p, z.incRef(z.change(Zbdd.base(), var)))));

        all = next;

        assertStatistics(java, nativeZbdd, "step " + step);
      }

      assertTrue(nativeZbdd.getStatistics().getNodesCapacity() > SMALL_CAPACITY);

      for(var step = 0; step < 200; step++)
      {
        final var var = 1 + random.nextInt(vars);
        final var p = all;

        all = both(java, nativeZbdd, "after " + step, z -> z.incRef(z.union(p, z.incRef(z.cube(var)))));
      }

      assertStatistics(java, nativeZbdd, "end");
      assertEquals(java.countExact(all), nativeZbdd.countExact(all));
      assertArrayEquals(java.calculateNodeDependency(), nativeZbdd.calculateNodeDependency());
    }
  }


  @ParameterizedTest(name = "seed {0}")
  @CsvSource({ "1", "2", "3", "4", "5", "6" })
  @DisplayName("Crossing the format change with any operation, results stay the same family of sets")
  void anyOperationAcrossFormatChange(long seed)
  {
    final var java = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);

    try(var nativeZbdd = new ZbddNative(DefaultCapacityAdvisor.INSTANCE)) {
      final var random = new Random(seed);
      final var vars = 40;

      for(var i = 0; i < vars; i++)
      {
        java.createVar();
        nativeZbdd.createVar();
      }

      // node numbers can differ after an interrupted operation, so each implementation has its own numbers
      final var javaNodes = new ArrayList<Integer>(List.of(Zbdd.base()));
      final var nativeNodes = new ArrayList<Integer>(List.of(Zbdd.base()));

      for(var step = 0; step < 60000 && (nativeZbdd.getStatistics().getNodesCapacity() <= SMALL_CAPACITY || step % 50 != 0); step++)
      {
        final var i = random.nextInt(javaNodes.size());
        final var j = random.nextInt(javaNodes.size());
        final var var = 1 + random.nextInt(vars);
        final var operation = random.nextInt(5);
        final var cube = random.ints(6, 1, vars + 1).toArray();

        javaNodes.add(java.incRef(apply(java, operation, javaNodes.get(i), javaNodes.get(j), var, cube)));
        nativeNodes.add(nativeZbdd.incRef(apply(nativeZbdd, operation, nativeNodes.get(i), nativeNodes.get(j), var, cube)));

        if (step % 100 == 0)
          assertEquals(java.countExact(javaNodes.getLast()), nativeZbdd.countExact(nativeNodes.getLast()));
      }

      assertTrue(nativeZbdd.getStatistics().getNodesCapacity() > SMALL_CAPACITY);

      for(var n = 0; n < javaNodes.size(); n += 1 + n / 8)
      {
        final var javaNode = javaNodes.get(n);
        final var nativeNode = nativeNodes.get(n);

        assertEquals(java.countExact(javaNode), nativeZbdd.countExact(nativeNode));

        if (java.count(javaNode) < 2000)
          assertEquals(java.toString(javaNode), nativeZbdd.toString(nativeNode));
      }
    }
  }


  /** Intermediate results stay referenced, as creating the next one can trigger a garbage collection. */
  private static int apply(Zbdd zbdd, int operation, int p, int q, int var, int[] vars)
  {
    return switch(operation) {
      case 0 -> zbdd.union(p, zbdd.incRef(zbdd.change(q, var)));
      case 1 -> {
        final var subset = zbdd.incRef(zbdd.subset1(q, var));
        final var cube = zbdd.incRef(zbdd.cube(var, 1 + var % 39));

        yield zbdd.union(p, zbdd.incRef(zbdd.multiply(subset, cube)));
      }
      case 2 -> {
        final var changed = zbdd.incRef(zbdd.change(p, var));

        yield zbdd.union(changed, zbdd.incRef(zbdd.atomize(q)));
      }
      case 3 -> zbdd.incRef(zbdd.cube(vars));
      default -> {
        final var subset = zbdd.incRef(zbdd.subset0(q, var));
        final var remaining = zbdd.incRef(zbdd.difference(p, subset));
        final var cube = zbdd.incRef(zbdd.cube(var));

        yield zbdd.union(remaining, zbdd.incRef(zbdd.divide(p, cube)));
      }
    };
  }


  private static void run(Random random, Zbdd java, Zbdd nativeZbdd, int steps)
  {
    for(var i = 0; i < VARS; i++)
      assertEquals(java.createVar(), nativeZbdd.createVar());

    // the first elements are referenced, the others are operands valid until the next garbage collection
    final var pool = new ArrayList<Integer>(List.of(Zbdd.empty(), Zbdd.base()));
    final var referenced = new ArrayList<Integer>();

    for(var step = 0; step < steps; step++)
    {
      final var p = pool.get(random.nextInt(pool.size()));
      final var q = pool.get(random.nextInt(pool.size()));
      final var var = 1 + random.nextInt(VARS);
      final var context = "step " + step;

      switch(random.nextInt(24)) {
        case 0 -> keep(pool, both(java, nativeZbdd, context, z -> z.union(p, q)));
        case 1 -> keep(pool, both(java, nativeZbdd, context, z -> z.intersect(p, q)));
        case 2 -> keep(pool, both(java, nativeZbdd, context, z -> z.difference(p, q)));
        case 3 -> {
          // products grow quickly, limited as there is no operation cache
          if (java.count(p) < 64 && java.count(q) < 64)
            keep(pool, both(java, nativeZbdd, context, z -> z.multiply(p, q)));
        }
        case 4 -> keep(pool, both(java, nativeZbdd, context, z -> z.divide(p, q)));
        case 5 -> {
          if (java.count(p) < 64 && java.count(q) < 64)
            keep(pool, both(java, nativeZbdd, context, z -> z.modulo(p, q)));
        }
        case 6 -> keep(pool, both(java, nativeZbdd, context, z -> z.subset0(p, var)));
        case 7 -> keep(pool, both(java, nativeZbdd, context, z -> z.subset1(p, var)));
        case 8, 9, 10 -> keep(pool, both(java, nativeZbdd, context, z -> z.change(p, var)));
        case 11 -> keep(pool, both(java, nativeZbdd, context, z -> z.atomize(p)));
        case 12 -> keep(pool, both(java, nativeZbdd, context, z -> z.removeBase(p)));
        case 13, 14 -> keep(pool, both(java, nativeZbdd, context, z -> z.cube(var)));
        case 15 -> {
          final var vars = random.ints(random.nextInt(5), 1, VARS + 1).toArray();
          keep(pool, both(java, nativeZbdd, context, z -> z.cube(vars)));
        }
        case 16 -> {
          final var vars = random.ints(random.nextInt(4), 1, VARS + 1).toArray();
          keep(pool, both(java, nativeZbdd, context, z -> z.subset0(p, vars)));
        }
        case 17 -> keep(pool, both(java, nativeZbdd, context, z -> z.union(p, q, var > 12 ? p : q)));
        case 18 -> {
          both(java, nativeZbdd, context, z -> z.hasCubeWithVar(p, var) ? 1 : 0);
          both(java, nativeZbdd, context, z -> z.contains(p, q) ? 1 : 0);
          both(java, nativeZbdd, context, z -> z.count(p));
          assertEquals(java.countExact(p), nativeZbdd.countExact(p), context);
          assertEquals(java.toString(p), nativeZbdd.toString(p), context);
        }
        case 19 -> {
          if (p >= 2)
          {
            both(java, nativeZbdd, context, z -> z.incRef(p));
            referenced.add(p);
          }
        }
        case 20 -> {
          if (!referenced.isEmpty())
          {
            final var node = referenced.remove(random.nextInt(referenced.size()));

            both(java, nativeZbdd, context, z -> z.decRef(node));
          }
        }
        case 21 -> {
          assertEquals(java.gc(), nativeZbdd.gc(), context);
          pool.removeIf(z -> !java.isValidZbdd(z));
        }
        case 22 -> {
          assertArrayEquals(java.asSingleCubeZbdds(p), nativeZbdd.asSingleCubeZbdds(p), context);
        }
        default -> {
          assertEquals(java.getVar(p), nativeZbdd.getVar(p), context);
          assertEquals(java.getP0(p), nativeZbdd.getP0(p), context);
          assertEquals(java.getP1(p), nativeZbdd.getP1(p), context);
          assertEquals(java.getZbddNodeInfo(p).getReferenceCount(), nativeZbdd.getZbddNodeInfo(p).getReferenceCount(),
              context);
          assertEquals(java.getZbddNodeInfo(p).toString(), nativeZbdd.getZbddNodeInfo(p).toString(), context);
          assertEquals(java.isValidZbdd(step), nativeZbdd.isValidZbdd(step), context);

          // invalid arguments, which also covers freed node numbers
          both(java, nativeZbdd, context, z -> z.union(p, -1 - var));
          both(java, nativeZbdd, context, z -> z.getVar(1000000 + var));
          both(java, nativeZbdd, context, z -> z.cube(VARS + var));
          both(java, nativeZbdd, context, z -> z.cube(0));
          both(java, nativeZbdd, context, z -> z.divide(p, Zbdd.empty()));
          both(java, nativeZbdd, context, z -> z.getNode(var, q, p));
          both(java, nativeZbdd, context, z -> z.subset0(p, new int[] { var, VARS + var }));
          both(java, nativeZbdd, context, z -> z.union(new int[] { p, q, -var }));
        }
      }

      assertStatistics(java, nativeZbdd, context);

      if (pool.size() > 200)
        pool.subList(2, 100).clear();
    }

    assertArrayEquals(java.calculateNodeDependency(), nativeZbdd.calculateNodeDependency());
    assertStatistics(java, nativeZbdd, "end");
  }


  private static void keep(List<Integer> pool, int zbdd)
  {
    if (zbdd >= 2)
      pool.add(zbdd);
  }


  /**
   * Runs the operation on both implementations, which must have the same result or fail in the same way.
   *
   * @return  result of the operation or {@code -1} if it has failed
   */
  private static int both(Zbdd java, Zbdd nativeZbdd, String context, ToIntFunction<Zbdd> operation)
  {
    final int expected;

    try {
      expected = operation.applyAsInt(java);
    } catch(RuntimeException ex) {
      final var actual = assertThrows(RuntimeException.class, () -> operation.applyAsInt(nativeZbdd), context);

      assertEquals(ex.getClass(), actual.getClass(), context);
      assertEquals(ex.getMessage(), actual.getMessage(), context);

      return -1;
    }

    assertEquals(expected, operation.applyAsInt(nativeZbdd), context);

    return expected;
  }


  private static void assertStatistics(Zbdd java, Zbdd nativeZbdd, String context)
  {
    final var expected = java.getStatistics();
    final var actual = nativeZbdd.getStatistics();

    assertEquals(expected.getNodesCapacity(), actual.getNodesCapacity(), context);
    assertEquals(expected.getFreeNodes(), actual.getFreeNodes(), context);
    assertEquals(expected.getDeadNodes(), actual.getDeadNodes(), context);
    assertEquals(expected.getNewNodes(), actual.getNewNodes(), context);

    // the operation cache only avoids lookups finding an existing node, the same nodes must be created
    assertTrue(actual.getNodeLookups() <= expected.getNodeLookups(), context);
    assertEquals(expected.getNodeLookups() - expected.getNodeLookupHitCount(),
        actual.getNodeLookups() - actual.getNodeLookupHitCount(), context);

    assertEquals(expected.getGCCount(), actual.getGCCount(), context);
    assertEquals(expected.getGCFreedNodes(), actual.getGCFreedNodes(), context);
    assertEquals(expected.getCapacityIncreaseCount(), actual.getCapacityIncreaseCount(), context);
    assertEquals(expected.getRegisteredVars(), actual.getRegisteredVars(), context);
  }
}
