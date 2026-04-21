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

import static java.lang.Math.clamp;
import static java.util.Arrays.copyOf;


/**
 * A simple, growable stack of {@code int} values used internally by ZBDD operations.
 *
 * @author Jeroen Gremmen
 * @since 0.6.0
 */
final class IntStack
{
  private int[] stack;
  private int stackSize;


  /**
   * Creates a new stack with the given initial capacity.
   *
   * @param size  initial capacity (clamped to the range 0–24)
   */
  IntStack(int size) {
    stack = new int[clamp(size, 0, 24)];
  }


  /**
   * Pushes a value onto the stack.
   *
   * @param value  value to push
   *
   * @return  the pushed value
   */
  @Contract(value = "_ -> param1", mutates = "this")
  int push(int value)
  {
    if (stackSize == stack.length)
      stack = copyOf(stack, stackSize * 3 / 2);

    return stack[stackSize++] = value;
  }


  /**
   * Pushes the given zbdd onto the stack if it is not the empty zbdd.
   *
   * @param zbdd  zbdd node
   */
  @Contract(mutates = "this")
  void pushIfNotEmptyZbdd(int zbdd)
  {
    if (zbdd > 0)
      push(zbdd);
  }


  /**
   * Pushes the given zbdd onto the stack if it is not a leaf node (empty or base).
   *
   * @param zbdd  zbdd node
   */
  @Contract(mutates = "this")
  void pushIfNotLeafNode(int zbdd)
  {
    if (zbdd > 1)
      push(zbdd);
  }


  /**
   * Removes and returns the top value from the stack.
   *
   * @return  the value at the top of the stack
   */
  @Contract(mutates = "this")
  int pop() {
    return stack[--stackSize];
  }


  /**
   * Removes the top value from the stack without returning it.
   */
  @Contract(mutates = "this")
  void drop() {
    stackSize--;
  }


  /**
   * Tells whether the stack is not empty.
   *
   * @return  {@code true} if the stack contains at least one element, {@code false} otherwise
   */
  @Contract(pure = true)
  boolean isNotEmpty() {
    return stackSize != 0;
  }


  /**
   * Returns the stack contents as an {@code int} array.
   *
   * @return  array containing all values currently on the stack, never {@code null}
   */
  @Contract(pure = true)
  int @NotNull [] getIntArray() {
    return copyOf(stack, stackSize);
  }


  /**
   * Iterates over all values on the stack from top to bottom.
   *
   * @param consumer  consumer to receive each value, not {@code null}
   */
  void forEach(@NotNull IntConsumer consumer)
  {
    for(var i = stackSize - 1; i >= 0; i--)
      consumer.accept(stack[i]);
  }
}
