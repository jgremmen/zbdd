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

import static de.sayayi.lib.zbdd.Zbdd.BASE;
import static de.sayayi.lib.zbdd.Zbdd.EMPTY;
import static java.util.Arrays.copyOfRange;
import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * Tests for zbdd operations on a cube with more variables than a recursive implementation could handle.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Operations on a deep cube")
class ZbddDeepCubeTest
{
  private static final int VAR_COUNT = 100_000;


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("subset0, subset1 and change of the lowest variable")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void subsetAndChange(String variant)
  {
    final var zbdd = create(variant);
    final var vars = new int[VAR_COUNT];

    for(var i = 0; i < VAR_COUNT; i++)
      vars[i] = zbdd.createVar();

    final var lowestVar = vars[0];
    final var cube = zbdd.incRef(zbdd.cube(vars));
    final var cubeWithoutLowestVar = zbdd.incRef(zbdd.cube(copyOfRange(vars, 1, VAR_COUNT)));

    assertEquals(EMPTY, zbdd.subset0(cube, lowestVar));
    assertEquals(cubeWithoutLowestVar, zbdd.subset0(cubeWithoutLowestVar, lowestVar));
    assertEquals(cubeWithoutLowestVar, zbdd.subset1(cube, lowestVar));
    assertEquals(EMPTY, zbdd.subset1(cubeWithoutLowestVar, lowestVar));
    assertEquals(cubeWithoutLowestVar, zbdd.change(cube, lowestVar));
    assertEquals(cube, zbdd.change(cubeWithoutLowestVar, lowestVar));
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("union, intersect and difference")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void unionIntersectDifference(String variant)
  {
    final var zbdd = create(variant);
    final var vars = new int[VAR_COUNT];

    for(var i = 0; i < VAR_COUNT; i++)
      vars[i] = zbdd.createVar();

    final var lowestVar = zbdd.incRef(zbdd.cube(vars[0]));
    final var cube = zbdd.incRef(zbdd.cube(vars));
    final var cubeWithoutLowestVar = zbdd.incRef(zbdd.cube(copyOfRange(vars, 1, VAR_COUNT)));

    // both cubes share all variables except the lowest one
    final var cubes = zbdd.incRef(zbdd.union(cube, cubeWithoutLowestVar));

    assertEquals(cube, zbdd.intersect(cubes, cube));
    assertEquals(EMPTY, zbdd.intersect(cube, cubeWithoutLowestVar));
    assertEquals(cubeWithoutLowestVar, zbdd.difference(cubes, cube));
    assertEquals(cube, zbdd.difference(cube, cubeWithoutLowestVar));
    assertEquals(cube, zbdd.difference(cube, lowestVar));

    // { {v1}, {v2}, ..., {vn} } has a 0-branch chain of all variables
    final var singletons = singletons(zbdd, vars);

    final var singletonsWithoutLowestVar = zbdd.incRef(zbdd.difference(singletons, lowestVar));

    assertEquals(VAR_COUNT - 1, zbdd.count(singletonsWithoutLowestVar));
    assertEquals(lowestVar, zbdd.intersect(singletons, lowestVar));
    assertEquals(EMPTY, zbdd.intersect(singletonsWithoutLowestVar, lowestVar));
    assertEquals(singletons, zbdd.union(singletonsWithoutLowestVar, lowestVar));
    assertEquals(singletons, zbdd.union(lowestVar, singletonsWithoutLowestVar));
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("removeBase")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void removeBase(String variant)
  {
    final var zbdd = create(variant);
    final var vars = new int[VAR_COUNT];

    for(var i = 0; i < VAR_COUNT; i++)
      vars[i] = zbdd.createVar();

    // { {}, {v1}, {v2}, ..., {vn} } has a 0-branch chain of all variables, ending in the base set
    final var singletons = singletons(zbdd, vars);
    final var singletonsAndBase = zbdd.incRef(zbdd.union(singletons, BASE));

    assertEquals(singletons, zbdd.removeBase(singletonsAndBase));
    assertEquals(singletons, zbdd.removeBase(singletons));
    assertEquals(singletons, zbdd.difference(singletonsAndBase, BASE));
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("multiply, divide and modulo")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void multiplyDivideModulo(String variant)
  {
    final var zbdd = create(variant);
    final var vars = new int[VAR_COUNT];

    for(var i = 0; i < VAR_COUNT; i++)
      vars[i] = zbdd.createVar();

    final var lowestVar = zbdd.incRef(zbdd.cube(vars[0]));
    final var cube = zbdd.incRef(zbdd.cube(vars));
    final var cubeWithoutLowestVar = zbdd.incRef(zbdd.cube(copyOfRange(vars, 1, VAR_COUNT)));
    final var cubes = zbdd.incRef(zbdd.union(cube, cubeWithoutLowestVar));

    final var evenVars = new int[(VAR_COUNT + 1) / 2];
    final var oddVars = new int[VAR_COUNT / 2];

    for(var i = 0; i < VAR_COUNT; i++)
      if (i % 2 == 0)
        evenVars[i / 2] = vars[i];
      else
        oddVars[i / 2] = vars[i];

    final var evenCube = zbdd.incRef(zbdd.cube(evenVars));
    final var oddCube = zbdd.incRef(zbdd.cube(oddVars));

    // the operands have different top variables on every level
    assertEquals(cube, zbdd.multiply(evenCube, oddCube));
    assertEquals(cube, zbdd.multiply(oddCube, evenCube));

    // both operands share the top variable on every level
    assertEquals(cube, zbdd.multiply(cube, cube));
    assertEquals(cube, zbdd.multiply(cubes, cube));
    assertEquals(cube, zbdd.multiply(cubeWithoutLowestVar, lowestVar));

    assertEquals(lowestVar, zbdd.divide(cube, cubeWithoutLowestVar));
    assertEquals(EMPTY, zbdd.divide(cubeWithoutLowestVar, cube));
    assertEquals(EMPTY, zbdd.modulo(cube, cubeWithoutLowestVar));
    assertEquals(zbdd.union(lowestVar, BASE), zbdd.divide(cubes, cubeWithoutLowestVar));
    assertEquals(EMPTY, zbdd.modulo(cubes, cubeWithoutLowestVar));
  }


  @ParameterizedTest(name = "variant = {0}")
  @DisplayName("atomize")
  @ValueSource(strings = { "plain", "cached", "concurrent", "cached+concurrent" })
  void atomize(String variant)
  {
    final var zbdd = create(variant);
    final var vars = new int[VAR_COUNT];

    for(var i = 0; i < VAR_COUNT; i++)
      vars[i] = zbdd.createVar();

    final var evenVars = new int[(VAR_COUNT + 1) / 2];
    final var oddVars = new int[VAR_COUNT / 2];

    for(var i = 0; i < VAR_COUNT; i++)
      if (i % 2 == 0)
        evenVars[i / 2] = vars[i];
      else
        oddVars[i / 2] = vars[i];

    final var singletons = singletons(zbdd, vars);
    final var cube = zbdd.incRef(zbdd.cube(vars));

    // the atomized branches of both interleaved cubes are merged over all variable levels
    final var evenCube = zbdd.incRef(zbdd.cube(evenVars));
    final var interleavedCubes = zbdd.incRef(zbdd.union(evenCube, zbdd.cube(oddVars)));

    assertEquals(singletons, zbdd.atomize(cube));
    assertEquals(singletons, zbdd.atomize(singletons));
    assertEquals(singletons, zbdd.atomize(interleavedCubes));
  }


  /**
   * Returns the referenced set { {v1}, {v2}, ..., {vn} } for the given variables. The partial result is referenced
   * while it is built, so it survives garbage collections triggered by subsequent operations.
   */
  private static int singletons(@NotNull Zbdd zbdd, int @NotNull [] vars)
  {
    var singletons = EMPTY;

    for(var var: vars)
    {
      final var next = zbdd.incRef(zbdd.union(singletons, zbdd.cube(var)));

      zbdd.decRef(singletons);
      singletons = next;
    }

    return singletons;
  }


  @Contract(pure = true)
  private static @NotNull Zbdd create(@NotNull String variant)
  {
    return switch(variant) {
      case "cached" -> ZbddFactory.createCached(new ZbddFastCache(65536));
      case "concurrent" -> ZbddFactory.asConcurrent(ZbddFactory.create());
      case "cached+concurrent" -> ZbddFactory.asConcurrent(ZbddFactory.createCached(new ZbddFastCache(65536)));
      default -> ZbddFactory.create();
    };
  }
}
