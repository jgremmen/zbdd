/*
 * Copyright 2022 Jeroen Gremmen
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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import static java.lang.Integer.MAX_VALUE;
import static java.lang.Integer.MIN_VALUE;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.lang.System.arraycopy;
import static java.util.Locale.US;


/**
 * Fast zbdd cache implementation.
 *
 * @author Jeroen Gremmen
 * @since 0.1.3
 */
@Deprecated(forRemoval = true)
public final class ZbddFastCache implements ZbddCache
{
  public static final int MIN_CACHE_SIZE = 1024;

  private static final int CHAIN_CAPACITY = 8;

  private static final int SLOT_ENTRY_SIZE1 = 3;
  private static final int SLOT_ENTRY_SIZE2 = 4;
  private static final int SLOT_CHAIN_SIZE1 = SLOT_ENTRY_SIZE1 * CHAIN_CAPACITY;
  private static final int SLOT_CHAIN_SIZE2 = SLOT_ENTRY_SIZE2 * CHAIN_CAPACITY;

  private final int slots;
  private final int capacity;

  private final int[] cache1;
  private int lookup1Count;
  private int cacheElementCount1;

  private final int[] cache2;
  private int lookup2Count;
  private int cacheElementCount2;

  private int lookupHitCount;


  public ZbddFastCache() {
    this(MIN_CACHE_SIZE);
  }


  public ZbddFastCache(int size)
  {
    slots = max(size, MIN_CACHE_SIZE) / (SLOT_CHAIN_SIZE1 + SLOT_CHAIN_SIZE2);
    capacity = 2 * slots * CHAIN_CAPACITY;

    cache1 = new int[slots * SLOT_CHAIN_SIZE1];
    cache2 = new int[slots * SLOT_CHAIN_SIZE2];
  }


  @Override
  public void clear()
  {
    for(int n = 0, l = cache1.length; n < l; n += SLOT_ENTRY_SIZE1)
      cache1[n] = 0;

    for(int n = 0, l = cache2.length; n < l; n += SLOT_ENTRY_SIZE2)
      cache2[n] = 0;

    cacheElementCount1 = cacheElementCount2 = 0;
  }


  @Override
  public int getResult(@NotNull Operation1 operation, int p)
  {
    lookup1Count++;

    final int slotIndex = hash1(operation, p) * SLOT_CHAIN_SIZE1;
    final int operationNumber = operation.ordinal() + 1;

    for(int i = slotIndex, n = 0, op; n < CHAIN_CAPACITY && (op = cache1[i]) != 0; i += SLOT_ENTRY_SIZE1, n++)
      if (operationNumber == op && cache1[i + 1] == p)
      {
        // move the entry to the front of the chain
        if (n >= CHAIN_CAPACITY / 2)
        {
          var tmp = new int[SLOT_ENTRY_SIZE1];

          arraycopy(cache1, i, tmp, 0, SLOT_ENTRY_SIZE1);
          arraycopy(cache1, slotIndex, cache1, slotIndex + SLOT_ENTRY_SIZE1, n * SLOT_ENTRY_SIZE1);
          arraycopy(tmp, 0, cache1, slotIndex, SLOT_ENTRY_SIZE1);

          i = slotIndex;
        }

        lookupHitCount++;

        return cache1[i + 2];
      }

    return MIN_VALUE;
  }


  @Override
  public void putResult(@NotNull Operation1 operation, int p, int result)
  {
    final int slotIndex = hash1(operation, p) * SLOT_CHAIN_SIZE1;
    final int operationNumber = operation.ordinal() + 1;
    int n = 0, i = slotIndex, op;

    for(; n < CHAIN_CAPACITY && (op = cache1[i]) != 0; i += SLOT_ENTRY_SIZE1, n++)
      if (operationNumber == op && cache1[i + 1] == p)
        return;

    if (n > 0)
    {
      arraycopy(cache1, slotIndex, cache1, slotIndex + SLOT_ENTRY_SIZE1,
          min(n, CHAIN_CAPACITY - 1) * SLOT_ENTRY_SIZE1);
    }

    cache1[slotIndex] = operationNumber;
    cache1[slotIndex + 1] = p;
    cache1[slotIndex + 2] = result;

    if (n < CHAIN_CAPACITY)
      cacheElementCount1++;
  }


  @Override
  public int getResult(@NotNull Operation2 operation, int p1, int p2)
  {
    lookup2Count++;

    final int slotIndex = hash2(operation, p1, p2) * SLOT_CHAIN_SIZE2;
    final int operationNumber = operation.ordinal() + 1;

    for(int i = slotIndex, n = 0, op; n < CHAIN_CAPACITY && (op = cache2[i]) != 0; i += SLOT_ENTRY_SIZE2, n++)
      if (operationNumber == op && cache2[i + 1] == p1 && cache2[i + 2] == p2)
      {
        // move the entry to the front of the chain
        if (n >= CHAIN_CAPACITY / 2)
        {
          var tmp = new int[SLOT_ENTRY_SIZE2];

          arraycopy(cache2, i, tmp, 0, SLOT_ENTRY_SIZE2);
          arraycopy(cache2, slotIndex, cache2, slotIndex + SLOT_ENTRY_SIZE2, n * SLOT_ENTRY_SIZE2);
          arraycopy(tmp, 0, cache2, slotIndex, SLOT_ENTRY_SIZE2);

          i = slotIndex;
        }

        lookupHitCount++;

        return cache2[i + 3];
      }

    return MIN_VALUE;
  }


  @Override
  public void putResult(@NotNull Operation2 operation, int p1, int p2, int result)
  {
    final int slotIndex = hash2(operation, p1, p2) * SLOT_CHAIN_SIZE2;
    final int operationNumber = operation.ordinal() + 1;
    int n = 0, i = slotIndex, op;

    for(; n < CHAIN_CAPACITY && (op = cache2[i]) != 0; i += SLOT_ENTRY_SIZE2, n++)
      if (operationNumber == op && cache2[i + 1] == p1 && cache2[i + 2] == p2)
        return;

    if (n > 0)
    {
      arraycopy(cache2, slotIndex, cache2, slotIndex + SLOT_ENTRY_SIZE2,
          min(n, CHAIN_CAPACITY - 1) * SLOT_ENTRY_SIZE2);
    }

    cache2[slotIndex] = operationNumber;
    cache2[slotIndex + 1] = p1;
    cache2[slotIndex + 2] = p2;
    cache2[slotIndex + 3] = result;

    if (n < CHAIN_CAPACITY)
      cacheElementCount2++;
  }


  @Contract(pure = true)
  private int hash1(@NotNull Operation1 operation, int p) {
    return ((operation.ordinal() * 4256249 + p * 741457) & MAX_VALUE) % slots;
  }


  @Contract(pure = true)
  private int hash2(@NotNull Operation2 operation, int p1, int p2) {
    return ((operation.ordinal() * 12582917 + p1 * 4256249 + p2 * 741457) & MAX_VALUE) % slots;
  }


  public String toString()
  {
    return getClass().getSimpleName() + "[hits=" +
        format(US, "%.1f%%", lookupHitCount / (double)(lookup1Count + lookup2Count) * 100.0) +
        ",capacity=" + format(US, "%.1f%%", (cacheElementCount1 + cacheElementCount2) / (double)capacity * 100.0) +
        ']';
  }
}
