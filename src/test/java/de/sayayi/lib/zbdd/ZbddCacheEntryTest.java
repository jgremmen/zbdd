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
import org.junit.jupiter.api.Test;

import static de.sayayi.lib.zbdd.Zbdd.BASE;
import static de.sayayi.lib.zbdd.ZbddFactory.createCached;
import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation2.CHANGE;
import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation2.DIFFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Tests that results which are as cheap to compute as to look up do not take up entries in the operation cache.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Zbdd cache entries")
class ZbddCacheEntryTest
{
  @Test
  @DisplayName("Changing the top variable of a node stores no result")
  void changeTopVar()
  {
    final var cache = new RecordingCache();
    final var zbdd = createCached(cache);

    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();

    final var p = zbdd.incRef(zbdd.union(zbdd.cube(a, c), zbdd.cube(b)));
    final var q = zbdd.incRef(zbdd.union(zbdd.cube(a, b), zbdd.cube(a)));

    cache.operation = CHANGE;

    // top variable of the operand
    final var pc = zbdd.incRef(zbdd.change(p, c));
    assertEquals(0, cache.putCount);
    assertEquals(zbdd.union(zbdd.cube(a), zbdd.cube(b, c)), pc);
    assertEquals(p, zbdd.change(pc, c));

    // top variable of the nodes below the operand: only the root is stored
    final var qa = zbdd.incRef(zbdd.change(q, a));
    assertEquals(1, cache.putCount);
    assertEquals(zbdd.union(zbdd.cube(b), Zbdd.base()), qa);

    final var pb = zbdd.incRef(zbdd.change(p, b));
    assertEquals(2, cache.putCount);
    assertEquals(zbdd.union(zbdd.cube(a, b, c), Zbdd.base()), pb);
    assertEquals(p, zbdd.change(pb, b));
  }


  @Test
  @DisplayName("Difference with the base set stores no result")
  void differenceWithBase()
  {
    final var cache = new RecordingCache();
    final var zbdd = createCached(cache);

    final var a = zbdd.createVar();
    final var b = zbdd.createVar();

    final var cubeA = zbdd.incRef(zbdd.cube(a));
    final var cubeB = zbdd.incRef(zbdd.cube(b));
    final var p = zbdd.incRef(zbdd.union(cubeA, zbdd.union(cubeB, Zbdd.base())));
    final var q = zbdd.incRef(zbdd.union(cubeA, Zbdd.base()));

    cache.operation = DIFFERENCE;

    // the base set is the operand
    assertEquals(zbdd.union(cubeA, cubeB), zbdd.difference(p, Zbdd.base()));
    assertEquals(cubeA, zbdd.difference(cubeA, Zbdd.base()));
    assertEquals(Zbdd.empty(), zbdd.difference(Zbdd.base(), Zbdd.base()));
    assertEquals(0, cache.putCount);

    // the base set is reached by descending into the 0-branch of q
    assertEquals(cubeB, zbdd.difference(p, q));
    assertTrue(cache.putCount > 0);
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
    public int getResult(@NotNull Operation2 operation, int p1, int p2) {
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
        if (operation == DIFFERENCE)
          assertTrue(p2 != BASE, "difference with the base set stored");

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
