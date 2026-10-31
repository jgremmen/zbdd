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

import java.util.*;

import static de.sayayi.lib.zbdd.Zbdd.BASE;
import static de.sayayi.lib.zbdd.Zbdd.EMPTY;
import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests for the zbdd operation {@code contains}.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Operation 'contains'")
class ZbddContainsTest
{
  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Empty set and base set")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void emptyAndBase(String variant)
  {
    final var zbdd = create(variant);
    final var a = zbdd.createVar();
    final var cube = zbdd.incRef(zbdd.cube(a));
    final var cubeAndBase = zbdd.incRef(zbdd.union(cube, BASE));

    // the empty set is contained in every set
    assertTrue(zbdd.contains(EMPTY, EMPTY));
    assertTrue(zbdd.contains(BASE, EMPTY));
    assertTrue(zbdd.contains(cube, EMPTY));

    // only the empty set is contained in the empty set
    assertFalse(zbdd.contains(EMPTY, BASE));
    assertFalse(zbdd.contains(EMPTY, cube));

    assertTrue(zbdd.contains(BASE, BASE));
    assertFalse(zbdd.contains(cube, BASE));
    assertTrue(zbdd.contains(cubeAndBase, BASE));
    assertFalse(zbdd.contains(BASE, cube));
    assertTrue(zbdd.contains(cubeAndBase, cube));
    assertFalse(zbdd.contains(cube, cubeAndBase));
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Random sets compared with the subset relation of their combinations")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void randomSets(String variant)
  {
    final var zbdd = create(variant);
    final var vars = new int[10];

    for(var i = 0; i < vars.length; i++)
      vars[i] = zbdd.createVar();

    final var random = new Random(42);
    final var sets = new ArrayList<Integer>();

    for(var n = 0; n < 40; n++)
    {
      final var set = zbdd.incRef(randomSet(zbdd, vars, random));
      sets.add(set);

      // a random subset of the set, so the test covers positive cases as well
      sets.add(zbdd.incRef(randomSubset(zbdd, set, random)));
    }

    var containedCount = 0;

    for(var p: sets)
      for(var q: sets)
      {
        final var expected = combinations(zbdd, p).containsAll(combinations(zbdd, q));
        assertEquals(expected, zbdd.contains(p, q), () -> zbdd.toString(q) + " in " + zbdd.toString(p));

        if (expected)
          containedCount++;
      }

    assertTrue(containedCount > sets.size());

    // computing the combinations creates nodes, so measure contains separately
    final var statistics = zbdd.getStatistics();
    final var nodeLookupsBefore = statistics.getNodeLookups();

    for(var p: sets)
      for(var q: sets)
        zbdd.contains(p, q);

    assertEquals(nodeLookupsBefore, statistics.getNodeLookups(), "contains must not create nodes");
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("Base set reached through many nodes sharing a 0-branch chain")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void sharedZeroBranchChain(String variant)
  {
    final var zbdd = create(variant);
    final var size = 200;
    final var chainVars = new int[size];
    final var branchVars = new int[size];
    final var topVars = new int[size];

    for(var i = 0; i < size; i++)
      chainVars[i] = zbdd.createVar();
    for(var i = 0; i < size; i++)
      branchVars[i] = zbdd.createVar();
    for(var i = 0; i < size; i++)
      topVars[i] = zbdd.createVar();

    var chain = EMPTY;

    for(var chainVar: chainVars)
      chain = zbdd.incRef(zbdd.union(chain, zbdd.cube(chainVar)));

    final var chainAndBase = zbdd.incRef(zbdd.union(chain, BASE));
    var withBase = EMPTY;
    var withoutBase = EMPTY;
    var tops = EMPTY;

    for(var i = 0; i < size; i++)
    {
      final var top = zbdd.incRef(zbdd.cube(topVars[i]));
      final var branch = zbdd.incRef(zbdd.cube(branchVars[i]));

      withBase = zbdd.incRef(zbdd.union(withBase,
          zbdd.incRef(zbdd.multiply(top, zbdd.incRef(zbdd.union(chainAndBase, branch))))));
      withoutBase = zbdd.incRef(zbdd.union(withoutBase,
          zbdd.incRef(zbdd.multiply(top, zbdd.incRef(zbdd.union(chain, branch))))));
      tops = zbdd.incRef(zbdd.union(tops, top));
    }

    // every top variable alone requires the empty combination below it, at the end of the shared chain
    assertTrue(zbdd.contains(withBase, tops));
    assertFalse(zbdd.contains(withoutBase, tops));
  }


  private static int randomSet(@NotNull Zbdd zbdd, int @NotNull [] vars, @NotNull Random random)
  {
    var set = random.nextBoolean() ? BASE : EMPTY;

    for(int n = random.nextInt(8); n-- > 0;)
    {
      final var cubeVars = new int[random.nextInt(4)];

      for(var i = 0; i < cubeVars.length; i++)
        cubeVars[i] = vars[random.nextInt(vars.length)];

      set = zbdd.union(set, zbdd.cube(cubeVars));
    }

    return set;
  }


  private static int randomSubset(@NotNull Zbdd zbdd, int set, @NotNull Random random)
  {
    final var cubes = zbdd.asSingleCubeZbdds(set);
    var subset = EMPTY;

    zbdd.incRef(cubes);

    for(var cube: cubes)
      if (random.nextBoolean())
        subset = zbdd.union(subset, cube);

    zbdd.incRef(subset);
    zbdd.decRef(cubes);

    return zbdd.decRef(subset);
  }


  private static @NotNull Set<List<Integer>> combinations(@NotNull Zbdd zbdd, int set)
  {
    final var combinations = new HashSet<List<Integer>>();

    zbdd.visitCubes(set, cubeVars -> {
      combinations.add(Arrays.stream(cubeVars).sorted().boxed().toList());
      return true;
    });

    return combinations;
  }


  @Contract(pure = true)
  private static @NotNull Zbdd create(@NotNull String variant)
  {
    return switch(variant) {
      case "cached" -> ZbddFactory.createCached(new ZbddFastCache(1024));
      case "concurrent" -> ZbddFactory.asConcurrent(ZbddFactory.create());
      case "cached+concurrent" -> ZbddFactory.asConcurrent(ZbddFactory.createCached(new ZbddFastCache(1024)));
      default -> ZbddFactory.create();
    };
  }
}
