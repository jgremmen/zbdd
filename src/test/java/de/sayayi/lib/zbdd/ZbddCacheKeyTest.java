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

import de.sayayi.lib.zbdd.cache.ZbddCache;
import de.sayayi.lib.zbdd.cache.ZbddFastCache;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.function.IntBinaryOperator;

import static de.sayayi.lib.zbdd.ZbddFactory.createCached;
import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation2.INTERSECT;
import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation2.UNION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Tests that the cache keys of commutative operations do not depend on the order of the operands.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Zbdd cache keys")
class ZbddCacheKeyTest
{
  @ParameterizedTest(name = "operation = {0}")
  @DisplayName("Commutative operations share the cache entry for swapped operands")
  @EnumSource(value = ZbddCache.Operation2.class, names = { "UNION", "INTERSECT", "MULTIPLY" })
  void swappedOperands(ZbddCache.Operation2 operation)
  {
    final var cache = new RecordingCache();
    final var zbdd = createCached(cache);
    final IntBinaryOperator op = operation == UNION
        ? zbdd::union
        : operation == INTERSECT ? zbdd::intersect : zbdd::multiply;

    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();

    // both operands have the same top variable
    final var p = zbdd.incRef(zbdd.union(zbdd.cube(a, c), zbdd.cube(b, c)));
    final var q = zbdd.incRef(zbdd.union(zbdd.cube(a, c), zbdd.cube(c)));

    cache.operation = operation;

    final var r = op.applyAsInt(p, q);
    final var puts = cache.putCount;

    assertTrue(puts > 0);
    assertEquals(r, op.applyAsInt(q, p));
    assertEquals(puts, cache.putCount);  // the root result was found in the cache
  }




  private static final class RecordingCache implements ZbddCache
  {
    private final ZbddCache cache = new ZbddFastCache();
    private Operation2 operation;
    private int putCount;


    @Override
    public int getResult(@NotNull Operation1 operation, int p) {
      return cache.getResult(operation, p);
    }


    @Override
    public int getResult(@NotNull Operation2 operation, int p1, int p2)
    {
      if (operation == this.operation)
        assertTrue(p1 <= p2, "cache key not normalized");

      return cache.getResult(operation, p1, p2);
    }


    @Override
    public void putResult(@NotNull Operation1 operation, int p, int result) {
      cache.putResult(operation, p, result);
    }


    @Override
    public void putResult(@NotNull Operation2 operation, int p1, int p2, int result)
    {
      if (operation == this.operation)
      {
        assertTrue(p1 <= p2, "cache key not normalized");
        putCount++;
      }

      cache.putResult(operation, p1, p2, result);
    }


    @Override
    public void clear() {
      cache.clear();
    }
  }
}
