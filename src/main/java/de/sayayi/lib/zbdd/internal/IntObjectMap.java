/*
 * Copyright 2025 Jeroen Gremmen
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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.StringJoiner;

import static java.lang.Integer.bitCount;


/**
 * Minimalistic open-addressing map implementation for mapping native {@code int} keys to object values. This map
 * does not support {@code null} values or key removal.
 * <p>
 * This class is used internally by the ZBDD library and is not part of the public API.
 *
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
final class IntObjectMap
{
  private static final int MAX_CAPACITY = 1 << 30;
  private static final double LOAD_FACTOR = 0.7;

  private int capacity, threshold;
  private int hashShift, hashMask;

  private int[] keys;
  private Object[] values;
  private int size;


  /** Creates a new empty map with a default initial capacity. */
  IntObjectMap() {
    ensureCapacity(16);
  }


  @Contract(mutates = "this")
  private void ensureCapacity(int newCapacity)
  {
    if (newCapacity < size)
      newCapacity = size;

    int newPow2Capacity;

    //noinspection StatementWithEmptyBody
    for(newPow2Capacity = 2;
        (int)(newPow2Capacity * LOAD_FACTOR) < newCapacity && newPow2Capacity < MAX_CAPACITY;
        newPow2Capacity <<= 1);

    if (newPow2Capacity != capacity)
    {
      capacity = newPow2Capacity;
      threshold = (int)(capacity * LOAD_FACTOR);
      hashMask = capacity - 1;
      hashShift = 31 - bitCount(hashMask);

      final var oldKeys = keys;
      final var oldValues = values;

      size = 0;
      keys = new int[capacity];
      values = new Object[capacity];

      if (oldKeys != null)
      {
        assert oldValues != null;
        Object value;

        for(int idx = 0, length = oldKeys.length; idx < length; idx++)
          if ((value = oldValues[idx]) != null)
            put(oldKeys[idx], value);
      }
    }
  }


  /**
   * Associates the given {@code value} with the given {@code key}.
   *
   * @param key    key
   * @param value  value to associate with the key, not {@code null}
   *
   * @throws IllegalStateException  if the key is already present
   */
  @Contract(mutates = "this")
  void put(int key, @NotNull Object value)
  {
    final var idx = indexOf(key);
    if (values[idx] != null)
      throw new IllegalStateException();

    keys[idx] = key;
    values[idx] = value;

    if (++size > threshold)
      ensureCapacity(size);
  }


  /**
   * Returns the value associated with the given {@code key}, or {@code null} if the key is not present.
   *
   * @param key  key
   *
   * @return  associated value or {@code null}
   */
  @Contract(pure = true)
  Object get(int key) {
    return values[indexOf(key)];
  }


  @Contract(pure = true)
  private int indexOf(int key)
  {
    var i = hash(key);

    for(; values[i] != null; i = (i - 1) & hashMask)
      if (key == keys[i])
        break;

    return i;
  }


  @Contract(pure = true)
  private int hash(int key) {
    return ((1327217885 * key) >> hashShift) & hashMask;
  }


  /**
   * Returns a string representation of this map.
   *
   * @return  string representation
   */
  @Override
  public String toString()
  {
    final var s = new StringJoiner(", ", "{", "}");

    for(int i = 0, length = keys.length; i < length; i++)
    {
      var value = values[i];
      if (value != null)
        s.add(Integer.toString(keys[i]) + '=' + value);
    }

    return s.toString();
  }
}
