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
package de.sayayi.lib.zbdd;

import de.sayayi.lib.zbdd.cache.ZbddFastCache;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static de.sayayi.lib.zbdd.Zbdd.BASE;
import static de.sayayi.lib.zbdd.Zbdd.EMPTY;
import static java.time.Duration.ofSeconds;
import static java.util.Arrays.sort;
import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests for {@link Zbdd#calculateNodeDependency()}.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Node dependency")
class ZbddNodeDependencyTest
{
  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Terminal nodes only")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void terminalNodesOnly(String variant) {
    assertArrayEquals(new int[] { EMPTY, BASE }, create(variant).calculateNodeDependency());
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Terminal nodes only after clear")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void terminalNodesOnlyAfterClear(String variant)
  {
    final var zbdd = create(variant);
    zbdd.incRef(zbdd.cube(zbdd.createVar(), zbdd.createVar()));
    zbdd.clear();

    assertArrayEquals(new int[] { EMPTY, BASE }, zbdd.calculateNodeDependency());
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Nodes created in ascending order are sequenced by node number")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void ascendingNodeNumbers(String variant)
  {
    final var zbdd = create(variant);
    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();

    final var root = zbdd.incRef(zbdd.union(zbdd.cube(a, b), zbdd.cube(b, c), zbdd.cube(c)));
    final var sequence = zbdd.calculateNodeDependency();
    final var sorted = sequence.clone();

    sort(sorted);

    assertArrayEquals(sorted, sequence);
    assertSequence(zbdd, sequence, Set.of(root));
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Shared nodes are sequenced exactly once")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void sharedNodes(String variant)
  {
    final var zbdd = create(variant);
    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();
    final var d = zbdd.createVar();

    // both roots share the sub-zbdd { {a, b}, {b} }
    final var shared = zbdd.union(zbdd.cube(a, b), zbdd.cube(b));
    final var root1 = zbdd.incRef(zbdd.change(shared, c));
    final var root2 = zbdd.incRef(zbdd.union(zbdd.change(shared, d), shared));
    final var sequence = zbdd.calculateNodeDependency();

    assertSequence(zbdd, sequence, Set.of(root1, root2));
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Unreferenced nodes are not sequenced")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void unreferencedNodes(String variant)
  {
    final var zbdd = create(variant);
    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();

    final var referenced = zbdd.incRef(zbdd.cube(a, b));
    final var unreferenced = zbdd.cube(b);
    final var released = zbdd.cube(c);

    zbdd.decRef(zbdd.incRef(released));

    final var sequence = zbdd.calculateNodeDependency();
    final var nodes = new HashSet<Integer>();

    for(var node: sequence)
      nodes.add(node);

    assertFalse(nodes.contains(unreferenced));
    assertFalse(nodes.contains(released));
    assertSequence(zbdd, sequence, Set.of(referenced));
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Repeated calculation returns the same sequence")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void repeatedCalculation(String variant)
  {
    final var zbdd = create(variant);
    final var root = randomSet(zbdd, 12, new Random(815));

    final var sequence = zbdd.calculateNodeDependency();

    assertArrayEquals(sequence, zbdd.calculateNodeDependency());
    assertSequence(zbdd, sequence, Set.of(root));
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Generation sequence after node reuse")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void generationSequence(String variant)
  {
    final var zbdd = create(variant);
    final var root = randomSet(zbdd, 12, new Random(4711));
    final var sequence = zbdd.calculateNodeDependency();

    // node reuse has created at least one node with a lower number than one of its branches
    var outOfOrder = false;
    for(var i = 2; i < sequence.length && !outOfOrder; i++)
    {
      final var node = sequence[i];
      outOfOrder = node < zbdd.getP0(node) || node < zbdd.getP1(node);
    }

    assertTrue(outOfOrder, "node numbers are not out of order");
    assertSequence(zbdd, sequence, Set.of(root));
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Sequence rebuilds identical nodes in a new instance")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void rebuildFromSequence(String variant)
  {
    final var zbdd = create(variant);
    final var root = randomSet(zbdd, 12, new Random(42));
    final var sequence = zbdd.calculateNodeDependency();

    final var copy = create(variant);
    for(var i = zbdd.getStatistics().getRegisteredVars(); i-- > 0;)
      copy.createVar();

    // each node can be rebuilt from its branches, which have been rebuilt before
    final var copyNodes = new HashMap<Integer,Integer>();
    copyNodes.put(EMPTY, EMPTY);
    copyNodes.put(BASE, BASE);

    for(var i = 2; i < sequence.length; i++)
    {
      final var node = sequence[i];
      final var var = zbdd.getVar(node);
      final var p0 = copyNodes.get(zbdd.getP0(node));
      final var p1 = copyNodes.get(zbdd.getP1(node));

      assertNotNull(p0);
      assertNotNull(p1);

      // node = p0 + var * p1
      final var copyNode = copy.incRef(copy.union(p0, copy.change(p1, var)));

      assertEquals(var, copy.getVar(copyNode));
      assertEquals(p0, copy.getP0(copyNode));
      assertEquals(p1, copy.getP1(copyNode));

      copyNodes.put(node, copyNode);
    }

    assertEquals(zbdd.count(root), copy.count(copyNodes.get(root)));
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Deep and wide zbdd")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void deepAndWide(String variant)
  {
    final var zbdd = create(variant);
    final var vars = new int[50_000];

    for(var i = 0; i < vars.length; i++)
      vars[i] = zbdd.createVar();

    // a chain of 1-branches and a chain of 0-branches
    final var cube = zbdd.incRef(zbdd.cube(vars));
    var singletons = EMPTY;

    for(var var: vars)
    {
      final var next = zbdd.incRef(zbdd.union(singletons, zbdd.cube(var)));

      zbdd.decRef(singletons);
      singletons = next;
    }

    final var roots = Set.of(cube, singletons);
    final var sequence = assertTimeout(ofSeconds(2), zbdd::calculateNodeDependency);

    assertEquals(2 + 2 * vars.length - 1, sequence.length);  // {v1} is shared by both roots
    assertSequence(zbdd, sequence, roots);
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Chain with descending node numbers")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void descendingNodeNumbers(String variant)
  {
    final var zbdd = create(variant);
    final var vars = new int[20_000];

    for(var i = 0; i < vars.length; i++)
      vars[i] = zbdd.createVar();

    // reserve ascending node numbers, which are released in descending order while the chain grows
    final var reserved = new int[vars.length];

    for(var i = 1; i < vars.length; i++)
      reserved[i] = zbdd.incRef(zbdd.cube(vars[i]));

    var chain = zbdd.incRef(zbdd.cube(vars[0]));

    for(var i = 1; i < vars.length; i++)
    {
      zbdd.decRef(reserved[vars.length - i]);
      zbdd.gc();

      // the new top node reuses the lowest free node, which is the node released last
      final var next = zbdd.incRef(zbdd.change(chain, vars[i]));

      assertEquals(reserved[vars.length - i], next);
      assertTrue(next < chain);

      zbdd.decRef(chain);
      chain = next;
    }

    final var top = chain;
    final var sequence = assertTimeout(ofSeconds(2), zbdd::calculateNodeDependency);

    assertSequence(zbdd, sequence, Set.of(top));
  }


  /**
   * Builds a referenced set in several rounds with garbage collections in between, so freed nodes get reused by nodes
   * with a lower variable.
   */
  private static int randomSet(@NotNull Zbdd zbdd, int varCount, @NotNull Random random)
  {
    final var vars = new int[varCount];

    for(var i = 0; i < varCount; i++)
      vars[i] = zbdd.createVar();

    var set = EMPTY;

    for(var round = 0; round < 5; round++)
    {
      for(var n = 0; n < 40; n++)
      {
        final var cube = zbdd.cube(vars[random.nextInt(varCount)], vars[random.nextInt(varCount)]);
        final var newSet = zbdd.incRef(zbdd.union(set, cube));

        zbdd.decRef(set);
        set = newSet;
      }

      final var newSet = zbdd.incRef(zbdd.subset0(set, vars[random.nextInt(varCount)]));

      zbdd.decRef(set);
      set = newSet;
      zbdd.gc();
    }

    return set;
  }


  /**
   * Asserts that the sequence starts with the terminal nodes, contains every node reachable from the given roots
   * exactly once and nothing else, and lists both branches of each node before the node itself.
   */
  private static void assertSequence(@NotNull Zbdd zbdd, int @NotNull [] sequence, @NotNull Set<Integer> roots)
  {
    assertEquals(EMPTY, sequence[0]);
    assertEquals(BASE, sequence[1]);

    final var index = new HashMap<Integer,Integer>();

    for(var i = 0; i < sequence.length; i++)
      assertNull(index.put(sequence[i], i), "duplicate zbdd node in sequence");

    for(var i = 2; i < sequence.length; i++)
    {
      final var node = sequence[i];

      assertTrue(index.get(zbdd.getP0(node)) < i, "0-branch sequenced after node");
      assertTrue(index.get(zbdd.getP1(node)) < i, "1-branch sequenced after node");
    }

    // the sequence contains exactly the nodes reachable from the roots
    final var reachable = new HashSet<Integer>();
    final var pending = new ArrayDeque<>(roots);

    reachable.add(EMPTY);
    reachable.add(BASE);

    while(!pending.isEmpty())
    {
      final int node = pending.pop();

      if (reachable.add(node))
      {
        pending.push(zbdd.getP0(node));
        pending.push(zbdd.getP1(node));
      }
    }

    assertEquals(reachable, index.keySet());

    final var statistics = zbdd.getStatistics();
    assertEquals(statistics.getNodesCapacity() - statistics.getFreeNodes(), sequence.length);
  }


  @Contract(pure = true)
  private static @NotNull Zbdd create(@NotNull String variant)
  {
    return switch(variant) {
      case "cached" -> ZbddFactory.createCached(new ZbddFastCache());
      case "concurrent" -> ZbddFactory.asConcurrent(ZbddFactory.create());
      case "cached+concurrent" -> ZbddFactory.asConcurrent(ZbddFactory.createCached(new ZbddFastCache()));
      default -> ZbddFactory.create();
    };
  }
}
