/*
 * Copyright 2021 Jeroen Gremmen
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
package de.sayayi.lib.zbdd;

import de.sayayi.lib.zbdd.exception.ZbddCapacityExhaustedException;
import de.sayayi.lib.zbdd.internal.PresetCapacityAdvisor;
import de.sayayi.lib.zbdd.internal.ZbddImpl;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;


/**
 * A zbdd capacity advisor manages the initial nodes capacity and how it grows over time.
 * It also determines the conditions for activating the garbage collector.
 * <p>
 * A generic advisor is never the best choice for a particular application, but the constants provided by this interface
 * are sensible starting points, each making a different trade-off between memory usage and performance:
 * <ul>
 *   <li>{@link #DEFAULT}: the advisor used if none is provided</li>
 *   <li>{@link #MINIMAL_MEMORY}: the smallest possible node table, whatever the costs</li>
 *   <li>{@link #LOW_MEMORY}: nearly the same memory savings at a much lower cost</li>
 *   <li>{@link #BALANCED}: memory savings with hardly any loss of performance</li>
 *   <li>{@link #HIGH_PERFORMANCE}: the fastest, at the cost of a larger node table</li>
 * </ul>
 * The figures given by these constants are relative to {@link #DEFAULT} and were measured with the workloads
 * of the {@code CapacityAdvisorBenchmark}. They are geometric means, so individual applications will differ.
 *
 * @author Jeroen Gremmen
 *
 * @see ZbddFactory#create(ZbddCapacityAdvisor)
 */
public interface ZbddCapacityAdvisor
{
  /**
   * The advisor used by {@link ZbddFactory} if no advisor is provided. It's what the figures of the other constants are
   * relative to.
   * <p>
   * The table grows by 150%, by 30% from 500000 nodes (12 MB) on, and leaves 25% of the nodes free after a garbage
   * collection, which happens if this is expected to free more than 10% of the nodes.
   *
   * @since 0.8.0
   */
  ZbddCapacityAdvisor DEFAULT = PresetCapacityAdvisor.DEFAULT;

  /**
   * Keeps the node table as small as possible, for an application where memory is scarce and run time is secondary.
   * <p>
   * The table grows by 12% only and is garbage collected as soon as this is expected to free 10% of the nodes. The
   * table is about 40% smaller and an average workload takes about 40% more time, depending on the workload up to
   * twice as much.
   *
   * @since 0.8.0
   */
  ZbddCapacityAdvisor MINIMAL_MEMORY = PresetCapacityAdvisor.MINIMAL_MEMORY;

  /**
   * Saves a substantial amount of memory at a moderate cost, for an application having to keep an eye on its memory
   * usage.
   * <p>
   * The table grows by 25% and leaves 15% of the nodes free after a garbage collection. The table is about 35% smaller
   * and an average workload takes about 20% more time.
   *
   * @since 0.8.0
   */
  ZbddCapacityAdvisor LOW_MEMORY = PresetCapacityAdvisor.LOW_MEMORY;

  /**
   * Saves memory with hardly any loss of performance. This is the advisor to start with if there's no particular reason
   * to prefer one of the others.
   * <p>
   * The table grows by 50% and leaves 25% of the nodes free after a garbage collection. The table is about 25% smaller
   * and an average workload takes about 3% more time.
   *
   * @since 0.8.0
   */
  ZbddCapacityAdvisor BALANCED = PresetCapacityAdvisor.BALANCED;

  /**
   * Avoids garbage collections and table growth where possible, for an application where run time is what counts and
   * memory is available.
   * <p>
   * The table grows by 150%, by 50% from 4 million nodes (96 MB) on, and leaves half of the nodes free after a garbage
   * collection. An average workload takes about 10% less time and has about 20% more nodes in its table. A workload
   * keeping more than half of its table in use can end up with a table that is 2.5 times as large.
   *
   * @since 0.8.0
   */
  ZbddCapacityAdvisor HIGH_PERFORMANCE = PresetCapacityAdvisor.HIGH_PERFORMANCE;

  /**
   * The maximum node capacity of a zbdd instance. It is the largest node table the JVM is able to allocate; neither the
   * initial capacity nor a capacity increase can exceed it.
   *
   * @see #getInitialCapacity()
   * @see #adviseIncrement(ZbddStatistics)
   *
   * @since 0.8.0
   */
  int MAX_CAPACITY = ZbddImpl.MAX_NODES;


  /**
   * Return the initial number of nodes available for zbdd operations.
   * <p>
   * A value below {@code 8} is treated as {@code 8}, a value above {@link #MAX_CAPACITY} as {@code MAX_CAPACITY}.
   *
   * @return  initial capacity, at least 8
   */
  @Contract(pure = true)
  int getInitialCapacity();


  /**
   * Returns the minimum number of free nodes. This method is invoked directly after a garbage collection that was
   * {@linkplain #isGCRequired(ZbddStatistics) required} to provide a new node. If the remaining number of free nodes is
   * less than the value returned by this method, the zbdd node capacity is increased.
   * <p>
   * This method is not invoked after a garbage collection performed explicitly by {@link Zbdd#gc()} or
   * {@link Zbdd#calculateNodeDependency()}.
   *
   * @param statistics  current zbdd statistics, not {@code null}
   *
   * @return  minimum number of free nodes (&gt; 0)
   *
   * @see #adviseIncrement(ZbddStatistics)
   */
  @Contract(pure = true)
  int getMinimumFreeNodes(@NotNull ZbddStatistics statistics);


  /**
   * Calculate the number of nodes to increase the zbdd node capacity by.
   * <p>
   * A negative value is treated as {@code 0}. If the capacity is not increased and no free node is left, the zbdd
   * operation requiring a new node fails with a {@link ZbddCapacityExhaustedException ZbddCapacityExhaustedException}.
   *
   * @param statistics  current zbdd statistics, not {@code null}
   *
   * @return  number of nodes (&gt;= 0) to increase the capacity by
   */
  @Contract(pure = true)
  int adviseIncrement(@NotNull ZbddStatistics statistics);


  /**
   * Tells whether unreferenced nodes should be garbage collected. This method is invoked as soon as a new node is
   * created, the number of free nodes is less than 2 and there is at least one
   * {@linkplain ZbddStatistics#getUnreferencedNodes() unreferenced} node.
   * <p>
   * While a {@linkplain Zbdd.ZbddCallback callback} is running, garbage collection is not possible. This method is not
   * invoked then and the capacity is increased instead.
   * <p>
   * Garbage collection is an expensive operation and only useful if a substantial number of nodes are expected to be
   * freed in the process. The current number of unreferenced nodes is an upper limit only, as those nodes may still be
   * reachable from referenced nodes. {@link ZbddStatistics#getNodesAllocatedSinceGC()} gives a better estimate.
   *
   * @param statistics  current zbdd statistics, not {@code null}
   *
   * @return  {@code true} if garbage collection is required, {@code false} otherwise
   */
  @Contract(pure = true)
  boolean isGCRequired(@NotNull ZbddStatistics statistics);
}
