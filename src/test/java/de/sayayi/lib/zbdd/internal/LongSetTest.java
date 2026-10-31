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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


@DisplayName("LongSet")
class LongSetTest
{
  @Test
  @DisplayName("Adds values once")
  void addsValuesOnce()
  {
    final var set = new LongSet();
    final var values = new long[] { Long.MIN_VALUE, -17, -1, 1, 17, 1L << 32, Long.MAX_VALUE };

    for(final var value: values)
      assertTrue(set.add(value));

    for(final var value: values)
      assertFalse(set.add(value));
  }


  @Test
  @DisplayName("Rejects the free slot marker")
  void rejectsZero() {
    assertThrowsExactly(IllegalArgumentException.class, () -> new LongSet().add(0));
  }


  @Test
  @DisplayName("Resizes storage and preserves values")
  void resizesAndPreservesValues()
  {
    final var set = new LongSet();
    final var valueCount = 20_000;

    // node pairs, as used by contains
    for(long value = 1; value <= valueCount; value++)
      assertTrue(set.add((value << 32) | (value & 7)));

    for(long value = 1; value <= valueCount; value++)
      assertFalse(set.add((value << 32) | (value & 7)));

    assertTrue(set.add(((long)valueCount + 1) << 32));
  }
}
