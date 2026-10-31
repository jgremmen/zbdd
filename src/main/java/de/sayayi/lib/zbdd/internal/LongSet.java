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

import static java.lang.Long.numberOfLeadingZeros;


/**
 * Minimalistic open-addressing set implementation for native {@code long} values. The value {@code 0} marks a free slot
 * and therefore cannot be stored. This set does not support removal.
 * <p>
 * This class is used internally by the ZBDD library and is not part of the public API.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
final class LongSet
{
  private static final int MAX_CAPACITY = 1 << 30;
  private static final double LOAD_FACTOR = 0.7;

  private int threshold;
  private int hashShift, hashMask;

  private long[] values;
  private int size;


  LongSet() {
    allocate(16);
  }


  @Contract(mutates = "this")
  private void allocate(int capacity)
  {
    values = new long[capacity];
    threshold = (int)(capacity * LOAD_FACTOR);
    hashMask = capacity - 1;
    hashShift = numberOfLeadingZeros(hashMask);
  }


  /**
   * Adds the given {@code value} to this set.
   *
   * @param value  value, not {@code 0}
   *
   * @return  {@code true} if the value was added, {@code false} if it was already present
   *
   * @throws IllegalArgumentException  if {@code value} is {@code 0}
   * @throws IllegalStateException     if the set has reached its maximum size
   */
  @Contract(mutates = "this")
  boolean add(long value)
  {
    if (value == 0)
      throw new IllegalArgumentException("value must not be 0");

    final var idx = indexOf(value);
    if (values[idx] != 0)
      return false;

    if (size == threshold)
    {
      grow();
      values[indexOf(value)] = value;
    }
    else
      values[idx] = value;

    size++;

    return true;
  }


  @Contract(mutates = "this")
  private void grow()
  {
    if (values.length == MAX_CAPACITY)
      throw new IllegalStateException("set is full");

    final var oldValues = values;

    allocate(oldValues.length << 1);

    for(final var value: oldValues)
      if (value != 0)
        values[indexOf(value)] = value;
  }


  @Contract(pure = true)
  private int indexOf(long value)
  {
    var i = hash(value);

    while(values[i] != 0 && values[i] != value)
      i = (i - 1) & hashMask;

    return i;
  }


  @Contract(pure = true)
  private int hash(long value) {
    return (int)((value * 0x9e3779b97f4a7c15L) >>> hashShift);
  }
}
