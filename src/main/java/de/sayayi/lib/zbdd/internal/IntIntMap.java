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

import org.jetbrains.annotations.Contract;

import java.util.StringJoiner;

import static java.lang.Integer.MIN_VALUE;
import static java.lang.Integer.bitCount;
import static java.util.Arrays.fill;


/**
 * Minimalistic open-addressing map implementation for mapping native {@code int} keys to native {@code int} values.
 * The value {@link Integer#MIN_VALUE} marks an absent entry and therefore cannot be stored. This map does not support
 * key removal.
 * <p>
 * This class is used internally by the ZBDD library and is not part of the public API.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
final class IntIntMap
{
  /** Value returned by {@link #get(int)} for an absent key. */
  static final int NO_VALUE = MIN_VALUE;

  private static final int MAX_CAPACITY = 1 << 30;
  private static final double LOAD_FACTOR = 0.7;

  private int capacity, threshold;
  private int hashShift, hashMask;

  private int[] keys;
  private int[] values;
  private int size;


  IntIntMap() {
    clear();
  }


  @Contract(mutates = "this")
  void clear()
  {
    size = 0;
    capacity = 16;

    keys = new int[capacity];
    fill(values = new int[capacity], NO_VALUE);
    threshold = (int)(capacity * LOAD_FACTOR);
    hashMask = capacity - 1;
    hashShift = 31 - bitCount(hashMask);
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
      fill(values = new int[capacity], NO_VALUE);

      int value;

      for(int idx = 0, length = oldKeys.length; idx < length; idx++)
        if ((value = oldValues[idx]) != NO_VALUE)
          put(oldKeys[idx], value);
    }
  }


  /**
   * Associates the given {@code value} with {@code key}.
   *
   * @param key    key
   * @param value  value, not {@link #NO_VALUE}
   *
   * @throws IllegalArgumentException  if {@code value} is {@link #NO_VALUE}
   * @throws IllegalStateException     if a value is already present for {@code key}
   */
  @Contract(mutates = "this")
  void put(int key, int value)
  {
    if (value == NO_VALUE)
      throw new IllegalArgumentException("value must not be " + NO_VALUE);

    final var idx = indexOf(key);
    if (values[idx] != NO_VALUE)
      throw new IllegalStateException("value already present for key " + key);

    keys[idx] = key;
    values[idx] = value;

    if (++size > threshold)
      ensureCapacity(size);
  }


  /**
   * Returns the value associated with {@code key}.
   *
   * @param key  key
   *
   * @return  value associated with {@code key} or {@link #NO_VALUE} if the key is absent
   */
  @Contract(pure = true)
  int get(int key) {
    return values[indexOf(key)];
  }


  @Contract(pure = true)
  private int indexOf(int key)
  {
    var i = hash(key);

    for(; values[i] != NO_VALUE; i = (i - 1) & hashMask)
      if (key == keys[i])
        break;

    return i;
  }


  @Contract(pure = true)
  private int hash(int key) {
    return ((1327217885 * key) >> hashShift) & hashMask;
  }


  @Override
  public String toString()
  {
    final var s = new StringJoiner(", ", "{", "}");

    for(int i = 0, length = keys.length; i < length; i++)
    {
      var value = values[i];
      if (value != NO_VALUE)
        s.add(Integer.toString(keys[i]) + '=' + value);
    }

    return s.toString();
  }
}
