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
import de.sayayi.lib.zbdd.exception.ZbddException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static de.sayayi.lib.zbdd.ZbddFactory.asConcurrent;
import static de.sayayi.lib.zbdd.ZbddFactory.createCached;
import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests that a zbdd cache can only be used by a single zbdd instance at a time.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Zbdd cache ownership")
class ZbddCacheOwnershipTest
{
  @Test
  @DisplayName("Cache cannot be shared through factory")
  void sharedCacheInFactory()
  {
    final var cache = new ZbddFastCache();

    createCached(cache);

    assertThrowsExactly(ZbddException.class, () -> createCached(cache));
  }


  @Test
  @DisplayName("Cache cannot be shared through setter")
  void sharedCacheInSetter()
  {
    final var cache = new ZbddFastCache();
    final var zbdd1 = createCached(cache);
    final var zbdd2 = createCached(new ZbddFastCache());

    assertThrowsExactly(ZbddException.class, () -> zbdd2.setZbddCache(cache));
    assertSame(cache, zbdd1.getZbddCache());
  }


  @Test
  @DisplayName("Cache cannot be shared through concurrent wrapper")
  void sharedCacheInConcurrentWrapper()
  {
    final var cache = new ZbddFastCache();
    final var zbdd1 = asConcurrent(createCached(cache));
    final var zbdd2 = asConcurrent(createCached(new ZbddFastCache()));

    assertThrowsExactly(ZbddException.class, () -> zbdd2.setZbddCache(cache));
    assertSame(cache, zbdd1.getZbddCache());
  }


  @Test
  @DisplayName("Reassigning own cache is permitted")
  void reassignOwnCache()
  {
    final var cache = new ZbddFastCache();
    final var zbdd = createCached(cache);

    assertDoesNotThrow(() -> zbdd.setZbddCache(cache));
    assertSame(cache, zbdd.getZbddCache());
  }


  @Test
  @DisplayName("Replaced cache can be used by another instance")
  void replacedCacheIsReleased()
  {
    final var cache = new ZbddFastCache();
    final var zbdd1 = createCached(cache);

    zbdd1.setZbddCache(new ZbddFastCache());

    final var zbdd2 = assertDoesNotThrow(() -> createCached(cache));
    assertSame(cache, zbdd2.getZbddCache());
  }


  @Test
  @DisplayName("Released cache does not leak results of previous owner")
  void releasedCacheIsCleared()
  {
    final var cache = new ZbddFastCache();
    final var zbdd1 = createCached(cache);
    final var a1 = zbdd1.createVar();
    final var b1 = zbdd1.createVar();
    final var ab1 = zbdd1.union(zbdd1.incRef(zbdd1.cube(a1)), zbdd1.incRef(zbdd1.cube(b1)));

    zbdd1.setZbddCache(new ZbddFastCache());

    // create the same node numbers in reverse variable order
    final var zbdd2 = createCached(cache);
    final var a2 = zbdd2.createVar();
    final var b2 = zbdd2.createVar();
    final var cubeB2 = zbdd2.incRef(zbdd2.cube(b2));
    final var cubeA2 = zbdd2.incRef(zbdd2.cube(a2));
    final var ab2 = zbdd2.union(cubeB2, cubeA2);

    assertTrue(zbdd2.isValidZbdd(ab2));
    assertEquals(2, zbdd2.count(ab2));
    assertEquals(ab1, zbdd1.union(zbdd1.cube(a1), zbdd1.cube(b1)));
  }
}
