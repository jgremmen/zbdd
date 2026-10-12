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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;


@DisplayName("IntObjectMap")
class IntObjectMapTest
{
  @Test
  @DisplayName("Starts empty")
  void startsEmpty()
  {
    final var map = new IntObjectMap();

    assertNull(map.get(0));
    assertEquals("{}", map.toString());
  }


  @Test
  @DisplayName("Stores values for integer keys")
  void storesAndRetrievesValues()
  {
    final var map = new IntObjectMap();
    final var keys = new int[] { Integer.MIN_VALUE, -17, -1, 0, 1, 17, Integer.MAX_VALUE };

    for(final var key: keys)
      map.put(key, "value-" + key);

    for(final var key: keys)
      assertEquals("value-" + key, map.get(key));

    assertNull(map.get(123456789));
  }


  @Test
  @DisplayName("Rejects duplicate keys without replacing their values")
  void rejectsDuplicateKeys()
  {
    final var map = new IntObjectMap();
    map.put(42, "original");

    final var exception = assertThrowsExactly(IllegalStateException.class, () -> map.put(42, "replacement"));

    assertEquals("value already present for key 42", exception.getMessage());
    assertEquals("original", map.get(42));
  }


  @Test
  @DisplayName("Resizes storage and preserves entries")
  void resizesAndPreservesEntries()
  {
    final var map = new IntObjectMap();
    final var entryCount = 20_000;

    for(int key = 0; key < 11; key++)
      map.put(key, "value-" + key);

    map.put(11, "value-11");

    for(int key = 12; key < entryCount; key++)
      map.put(key, "value-" + key);

    for(int key = 0; key < entryCount; key++)
      assertEquals("value-" + key, map.get(key));

    assertNull(map.get(entryCount));
  }


  @Test
  @DisplayName("Clears entries and restores initial storage")
  void clearsEntries()
  {
    final var map = new IntObjectMap();
    for(int key = 0; key < 100; key++)
      map.put(key, "value-" + key);

    map.clear();

    assertNull(map.get(0));
    assertNull(map.get(99));
    assertEquals("{}", map.toString());

    map.put(-1, "reused");
    assertEquals("reused", map.get(-1));
  }


  @Test
  @DisplayName("Formats entries without depending on their storage order")
  void formatsEntries()
  {
    final var map = new IntObjectMap();
    map.put(3, "three");
    map.put(-5, "minus five");
    map.put(12, "twelve");

    final var entries = map.toString();
    final var formattedEntries = Set.of(entries.substring(1, entries.length() - 1).split(", "));

    assertEquals(Set.of("3=three", "-5=minus five", "12=twelve"), formattedEntries);
  }
}
