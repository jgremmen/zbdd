package de.sayayi.lib.zbdd;

import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;


final class IntStack
{
  private int stackSize;
  private int[] stack;


  public IntStack(int size) {
    stack = new int[Math.max(size, 4)];
  }


  public void push(int value)
  {
    if (stackSize >= stack.length)
      stack = Arrays.copyOf(stack, stackSize + 4);

    stack[stackSize++] = value;
  }


  @SuppressWarnings("UnusedReturnValue")
  public int pop()
  {
    if (stackSize == 0)
      throw new IllegalStateException("stack is empty");

    return stack[--stackSize];
  }


  public void drop(int n) {
    stackSize = Math.max(stackSize - n, 0);
  }


  public boolean isEmpty() {
    return stackSize == 0;
  }


  public int[] getStack() {
    return Arrays.copyOf(stack, stackSize);
  }


  public IntStream stream() {
    return Arrays.stream(stack, 0, stackSize);
  }


  public void forEach(IntConsumer action)
  {
    for(int i = 0; i < stackSize; i++)
      action.accept(stack[i]);
  }
}
