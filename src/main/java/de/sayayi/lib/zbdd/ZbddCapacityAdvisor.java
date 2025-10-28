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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;


/**
 * A zbdd capacity advisor manages the initial nodes capacity and how it grows over time.
 * It also determines the conditions for activating the garbage collector.
 *
 * @author Jeroen Gremmen
 *
 * @see ZbddFactory#create(ZbddCapacityAdvisor)
 */
public interface ZbddCapacityAdvisor
{
  /**
   * Return the initial number of nodes available for zbdd operations.
   *
   * @return  initial capacity, at least 8
   */
  @Contract(pure = true)
  int getInitialCapacity();


  /**
   * Returns the minimum number of free nodes. This method is invoked directly after node
   * garbage collection. If the remaining number of free nodes is less than the value returned
   * by this method, the zbdd node capacity is increased.
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
   * Calculate the number of nodes to incease the zbdd node capacity by.
   *
   * @param statistics  current zbdd statistics, not {@code null}
   *
   * @return  number of nodes (&gt;= 0) to increase the capacity by
   */
  @Contract(pure = true)
  int adviseIncrement(@NotNull ZbddStatistics statistics);


  /**
   * Tells whether dead nodes should be garbage collected. This method is invoked as soon as a new
   * node is created and the number of free nodes is less than 2.
   * <p>
   * Garbage collection is an expensive operation and only useful if a substantial number of nodes
   * are exopected to be freed in the process. The current number of dead nodes may be a good
   * indicator but is not a guarantee that those nodes are invalidated.
   *
   * @param statistics  current zbdd statistics, not {@code null}
   *
   * @return  {@code true} if garbage collection is required, {@code false} otherwise
   */
  @Contract(pure = true)
  boolean isGCRequired(@NotNull ZbddStatistics statistics);
}
