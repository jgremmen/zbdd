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

import static de.sayayi.lib.zbdd.internal.IntIntMap.NO_VALUE;
import static org.junit.jupiter.api.Assertions.*;


@DisplayName("IntIntMap")
class IntIntMapTest
{
  @Test
  @DisplayName("Starts empty")
  void startsEmpty()
  {
    final var map = new IntIntMap();

    assertEquals(NO_VALUE, map.get(0));
    assertEquals("{}", map.toString());
  }


  @Test
  @DisplayName("Stores values for integer keys")
  void storesAndRetrievesValues()
  {
    final var map = new IntIntMap();
    final var keys = new int[] { Integer.MIN_VALUE, -17, -1, 0, 1, 17, Integer.MAX_VALUE };

    for(final var key: keys)
      map.put(key, key >> 1);

    for(final var key: keys)
      assertEquals(key >> 1, map.get(key));

    assertEquals(NO_VALUE, map.get(123456789));
  }


  @Test
  @DisplayName("Rejects the absent value marker")
  void rejectsNoValue()
  {
    final var map = new IntIntMap();

    assertThrowsExactly(IllegalArgumentException.class, () -> map.put(42, NO_VALUE));
    assertEquals(NO_VALUE, map.get(42));
  }


  @Test
  @DisplayName("Rejects duplicate keys without replacing their values")
  void rejectsDuplicateKeys()
  {
    final var map = new IntIntMap();
    map.put(42, 1);

    final var exception = assertThrowsExactly(IllegalStateException.class, () -> map.put(42, 2));

    assertEquals("value already present for key 42", exception.getMessage());
    assertEquals(1, map.get(42));
  }


  @Test
  @DisplayName("Resizes storage and preserves entries")
  void resizesAndPreservesEntries()
  {
    final var map = new IntIntMap();
    final var entryCount = 20_000;

    for(int key = 0; key < entryCount; key++)
      map.put(key, key * 3);

    for(int key = 0; key < entryCount; key++)
      assertEquals(key * 3, map.get(key));

    assertEquals(NO_VALUE, map.get(entryCount));
  }


  @Test
  @DisplayName("Clears entries and restores initial storage")
  void clearsEntries()
  {
    final var map = new IntIntMap();
    for(int key = 0; key < 100; key++)
      map.put(key, key);

    map.clear();

    assertEquals(NO_VALUE, map.get(0));
    assertEquals(NO_VALUE, map.get(99));
    assertEquals("{}", map.toString());

    map.put(-1, 7);
    assertEquals(7, map.get(-1));
  }


  @Test
  @DisplayName("Formats entries without depending on their storage order")
  void formatsEntries()
  {
    final var map = new IntIntMap();
    map.put(3, 30);
    map.put(-5, -50);
    map.put(12, 120);

    final var entries = map.toString();
    final var formattedEntries = Set.of(entries.substring(1, entries.length() - 1).split(", "));

    assertEquals(Set.of("3=30", "-5=-50", "12=120"), formattedEntries);
  }
}
