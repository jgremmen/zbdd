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

import java.util.function.IntConsumer;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Arrays.copyOf;


/**
 * @author Jeroen Gremmen
 * @since 0.6.0
 */
final class IntStack
{
  private int[] stack;
  private int stackSize;


  IntStack(int size) {
    stack = new int[min(max(size, 0), 24)];
  }


  @Contract(value = "_ -> param1", mutates = "this")
  int push(int value)
  {
    if (stackSize == stack.length)
      stack = copyOf(stack, stackSize * 3 / 2);

    return stack[stackSize++] = value;
  }


  @Contract(mutates = "this")
  void pushIfNotEmptyZbdd(int zbdd)
  {
    if (zbdd > 0)
      push(zbdd);
  }


  @Contract(mutates = "this")
  void pushIfNotLeafNode(int zbdd)
  {
    if (zbdd > 1)
      push(zbdd);
  }


  @Contract(mutates = "this")
  int pop() {
    return stack[--stackSize];
  }


  @Contract(mutates = "this")
  void drop() {
    stackSize--;
  }


  @Contract(pure = true)
  boolean isEmpty() {
    return stackSize == 0;
  }


  @Contract(pure = true)
  int @NotNull [] getIntArray() {
    return copyOf(stack, stackSize);
  }


  void forEach(@NotNull IntConsumer consumer)
  {
    for(var i = stackSize - 1; i >= 0; i--)
      consumer.accept(stack[i]);
  }
}
