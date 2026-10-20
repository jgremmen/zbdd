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

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Random;

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
  @Test
  @DisplayName("Terminal nodes only")
  void terminalNodesOnly() {
    assertArrayEquals(new int[] { 0, 1 }, ZbddFactory.create().calculateNodeDependency());
  }


  @Test
  @DisplayName("Generation sequence after node reuse")
  void generationSequence()
  {
    final var zbdd = ZbddFactory.create();
    final var vars = new int[12];

    for(var i = 0; i < vars.length; i++)
      vars[i] = zbdd.createVar();

    final var random = new Random(4711);
    var set = Zbdd.EMPTY;

    // build sets in several rounds, so freed nodes get reused by nodes with a lower variable
    for(var round = 0; round < 5; round++)
    {
      for(var n = 0; n < 40; n++)
      {
        final var cube = zbdd.cube(vars[random.nextInt(vars.length)], vars[random.nextInt(vars.length)]);
        final var newSet = zbdd.incRef(zbdd.union(set, cube));

        zbdd.decRef(set);
        set = newSet;
      }

      zbdd.decRef(set);
      set = zbdd.incRef(zbdd.subset0(set, vars[random.nextInt(vars.length)]));
      zbdd.gc();
    }

    final var sequence = zbdd.calculateNodeDependency();

    assertEquals(Zbdd.EMPTY, sequence[0]);
    assertEquals(Zbdd.BASE, sequence[1]);

    // all nodes that are not free, including unreferenced nodes reachable from referenced nodes
    final var statistics = zbdd.getStatistics();
    assertEquals(statistics.getNodesCapacity() - statistics.getFreeNodes(), sequence.length);
    assertSequence(zbdd, sequence);
  }


  private static void assertSequence(@NotNull Zbdd zbdd, int @NotNull [] sequence)
  {
    final var index = new HashMap<Integer,Integer>();

    for(var i = 0; i < sequence.length; i++)
      assertNull(index.put(sequence[i], i), () -> "duplicate zbdd node in sequence");

    for(var i = 2; i < sequence.length; i++)
    {
      final var node = sequence[i];

      assertTrue(index.get(zbdd.getP0(node)) < i);
      assertTrue(index.get(zbdd.getP1(node)) < i);
    }
  }
}
