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
package de.sayayi.lib.zbdd.benchmark;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.function.IntUnaryOperator;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;


/**
 * Runs operations on several threads at once for a short period of time and counts how many of them were completed.
 * <p>
 * The JMH option for running a benchmark with multiple threads is not used for operations that wait for a lock, as it
 * calculates a score for each thread based on the time that thread was measuring. As the zbdd lock is not fair, a
 * thread may wait for almost the entire iteration and complete a few operations in the little time remaining. Its score
 * is then very high, so the sum of the scores is far above the throughput actually achieved. The number of operations
 * completed by all threads in a given period of time isn't affected by this.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
final class ConcurrentRunner
{
  private static final long PERIOD_MILLIS = 20;

  private final ExecutorService executor;
  private final int threads;
  private final CyclicBarrier start;

  private volatile boolean stop;

  /** Keeps the results of the operations alive. */
  static volatile int sink;


  ConcurrentRunner(int threads)
  {
    this.threads = threads;

    executor = newFixedThreadPool(threads);
    start = new CyclicBarrier(threads + 1);
  }


  /**
   * Runs the given operations, one per thread. An operation receives a pseudo-random number and returns a value that
   * is used to keep it from being optimized away.
   *
   * @param operations  operations to run, exactly one for each thread
   *
   * @return  the number of times each operation has been completed, never {@code null}
   */
  long @NotNull [] run(@NotNull IntUnaryOperator @NotNull ... operations)
  {
    if (operations.length != threads)
      throw new IllegalArgumentException("expected " + threads + " operations");

    stop = false;

    final var futures = new ArrayList<Future<Long>>(threads);

    for(var t = 0; t < threads; t++)
    {
      final var operation = operations[t];
      final var seed = 0x2545f491 * (t + 1);

      futures.add(executor.submit(() -> runOperation(operation, seed)));
    }

    try {
      start.await();
      MILLISECONDS.sleep(PERIOD_MILLIS);
      stop = true;

      final var completed = new long[threads];

      for(var t = 0; t < threads; t++)
        completed[t] = futures.get(t).get();

      return completed;
    } catch(InterruptedException | BrokenBarrierException | ExecutionException ex) {
      throw new IllegalStateException(ex);
    }
  }


  private long runOperation(@NotNull IntUnaryOperator operation, int seed)
      throws InterruptedException, BrokenBarrierException
  {
    start.await();

    var random = seed;
    var result = 0;
    var completed = 0L;

    while(!stop)
    {
      random ^= random << 13;
      random ^= random >>> 17;
      random ^= random << 5;

      result ^= operation.applyAsInt(random);
      completed++;
    }

    sink = result;

    return completed;
  }


  /**
   * Returns the sum of the numbers returned by {@link #run(IntUnaryOperator...)}, for all threads in the given range.
   */
  static long sum(long @NotNull [] completed, int from, int to)
  {
    var sum = 0L;

    for(var t = from; t < to; t++)
      sum += completed[t];

    return sum;
  }


  void shutdown() {
    executor.shutdownNow();
  }
}
