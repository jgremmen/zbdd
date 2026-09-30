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
package de.sayayi.lib.zbdd.cache;

import de.sayayi.lib.zbdd.cache.ZbddCache.Operation2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation1.COUNT;
import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation2.INTERSECT;
import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation2.UNION;
import static java.lang.Integer.MIN_VALUE;
import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Hash distribution tests for {@link ZbddFastCache}. Node ids in a zbdd are allocated sequentially, so the
 * cache hash functions must distribute sequential (and strided) ids evenly over the available slots.
 *
 * @author Jeroen Gremmen
 * @since 0.6.1
 */
@DisplayName("Fast cache")
class ZbddFastCacheTest
{
  private static final int CACHE_SIZE = 512 * 1024;

  /** Mirrors the slot calculation in {@link ZbddFastCache}. */
  private static final int SLOTS = CACHE_SIZE / (3 * 8 + 4 * 8);
  private static final int CHAIN_CAPACITY = 8;

  /** Number of entries stored; 50% load means an ideal hash evicts nothing at all. */
  private static final int ENTRIES = SLOTS * CHAIN_CAPACITY / 2;


  @DisplayName("Unary operation hash distributes strided node ids")
  @ParameterizedTest(name = "stride = {0}")
  @ValueSource(ints = { 1, 2, 3, 4, 8, 17, 256, 1024, 65536 })
  void hash1Distribution(int stride)
  {
    final var cache = new ZbddFastCache(CACHE_SIZE);

    for(int n = 0; n < ENTRIES; n++)
      cache.putResult(COUNT, 2 + n * stride, n);

    var retained = 0;
    for(int n = 0; n < ENTRIES; n++)
      if (cache.getResult(COUNT, 2 + n * stride) == n)
        retained++;

    assertRetained(retained, ENTRIES);
  }


  @DisplayName("Binary operation hash distributes strided node id pairs")
  @ParameterizedTest(name = "stride = {0}")
  @ValueSource(ints = { 1, 2, 3, 4, 8, 17, 256, 1024, 65536 })
  void hash2Distribution(int stride)
  {
    final var cache = new ZbddFastCache(CACHE_SIZE);

    for(int n = 0; n < ENTRIES; n++)
      cache.putResult(UNION, 2 + n * stride, 2 + (n + 1) * stride, n);

    var retained = 0;
    for(int n = 0; n < ENTRIES; n++)
      if (cache.getResult(UNION, 2 + n * stride, 2 + (n + 1) * stride) == n)
        retained++;

    assertRetained(retained, ENTRIES);
  }


  @DisplayName("Binary operation hash distributes a sequential cross product")
  @Test
  void hash2CrossProductDistribution()
  {
    final var cache = new ZbddFastCache(CACHE_SIZE);
    final var width = (int)Math.sqrt(ENTRIES);

    for(int p1 = 0; p1 < width; p1++)
      for(int p2 = 0; p2 < width; p2++)
        cache.putResult(INTERSECT, 2 + p1, 2 + p2, p1 * width + p2);

    var retained = 0;
    for(int p1 = 0; p1 < width; p1++)
      for(int p2 = 0; p2 < width; p2++)
        if (cache.getResult(INTERSECT, 2 + p1, 2 + p2) == p1 * width + p2)
          retained++;

    assertRetained(retained, width * width);
  }


  @DisplayName("Operations with identical parameters are distinguished")
  @Test
  void operationsAreDistinguished()
  {
    final var cache = new ZbddFastCache(CACHE_SIZE);

    for(final var operation: Operation2.values())
      cache.putResult(operation, 12345, 67890, operation.ordinal() + 100);

    for(final var operation: Operation2.values())
      assertEquals(operation.ordinal() + 100, cache.getResult(operation, 12345, 67890));

    assertEquals(MIN_VALUE, cache.getResult(UNION, 12345, 67891));
  }


  private static void assertRetained(int retained, int entries)
  {
    assertTrue(retained >= (int)(entries * 0.98),
        () -> format("expected at least 98%% retention, got %d of %d (%.1f%%)",
            retained, entries, retained * 100.0 / entries));
  }
}
