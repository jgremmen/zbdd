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
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.Random;

import static java.math.BigInteger.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;


/**
 * Tests for the zbdd operations {@code count} and {@code countExact}.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Operations 'count' and 'countExact'")
class ZbddCountTest
{
  @ParameterizedTest(name = "cached = {0}")
  @DisplayName("Terminal nodes")
  @ValueSource(booleans = { false, true })
  void terminalNodes(boolean cached)
  {
    final var zbdd = create(cached);

    assertEquals(0, zbdd.count(Zbdd.EMPTY));
    assertEquals(1, zbdd.count(Zbdd.BASE));
    assertEquals(ZERO, zbdd.countExact(Zbdd.EMPTY));
    assertEquals(ONE, zbdd.countExact(Zbdd.BASE));
  }


  @ParameterizedTest(name = "cached = {0}")
  @DisplayName("Exact count equals count for random sets")
  @ValueSource(booleans = { false, true })
  void exactCountEqualsCount(boolean cached)
  {
    final var zbdd = create(cached);
    final var vars = new int[12];

    for(var i = 0; i < vars.length; i++)
      vars[i] = zbdd.createVar();

    final var random = new Random(7);

    for(var s = 0; s < 50; s++)
    {
      var set = Zbdd.EMPTY;

      for(var n = 0; n < 30; n++)
      {
        final var cubeVars = new int[random.nextInt(5)];

        for(var i = 0; i < cubeVars.length; i++)
          cubeVars[i] = vars[random.nextInt(vars.length)];

        final var union = zbdd.incRef(zbdd.union(set, zbdd.cube(cubeVars)));

        zbdd.decRef(set);
        set = union;
      }

      assertEquals(BigInteger.valueOf(zbdd.count(set)), zbdd.countExact(set));
      zbdd.decRef(set);
    }
  }


  @ParameterizedTest(name = "cached = {0}")
  @DisplayName("Largest count representable as long")
  @ValueSource(booleans = { false, true })
  void largestLongCount(boolean cached)
  {
    final var zbdd = create(cached);
    final var powerSet = zbdd.incRef(powerSet(zbdd, 31));  // 2^31 cubes
    final var withoutBase = zbdd.incRef(zbdd.removeBase(powerSet));  // 2^31 - 1 cubes

    assertEquals(Integer.MAX_VALUE, zbdd.count(withoutBase));
    assertEquals(BigInteger.valueOf(Integer.MAX_VALUE), zbdd.countExact(withoutBase));
  }


  @ParameterizedTest(name = "cached = {0}")
  @DisplayName("Count exceeding long range")
  @ValueSource(booleans = { false, true })
  void countOverflow(boolean cached)
  {
    final var zbdd = create(cached);

    for(var varCount: new int[] { 63, 64, 65, 200 })
    {
      zbdd.clear();

      final var powerSet = zbdd.incRef(powerSet(zbdd, varCount));

      assertThrowsExactly(ArithmeticException.class, () -> zbdd.count(powerSet));
      // a failed count must not leave a (wrong) result in the cache
      assertThrowsExactly(ArithmeticException.class, () -> zbdd.count(powerSet));
      assertEquals(TWO.pow(varCount), zbdd.countExact(powerSet));
    }
  }


  @Test
  @DisplayName("Concurrent instance")
  void concurrent()
  {
    final var zbdd = ZbddFactory.asConcurrent(ZbddFactory.createCached(new ZbddFastCache()));
    final var powerSet = zbdd.doAtomic(z -> z.incRef(powerSet(z, 64)));

    assertThrowsExactly(ArithmeticException.class, () -> zbdd.count(powerSet));
    assertEquals(TWO.pow(64), zbdd.countExact(powerSet));
  }


  /**
   * Creates the set of all combinations of {@code varCount} new variables, containing 2<sup>varCount</sup> cubes.
   */
  private static int powerSet(@NotNull Zbdd zbdd, int varCount)
  {
    var powerSet = Zbdd.BASE;

    for(var n = 0; n < varCount; n++)
      powerSet = zbdd.getNode(zbdd.createVar(), powerSet, powerSet);

    return powerSet;
  }


  private static @NotNull Zbdd create(boolean cached) {
    return cached ? ZbddFactory.createCached(new ZbddFastCache()) : ZbddFactory.create();
  }
}
