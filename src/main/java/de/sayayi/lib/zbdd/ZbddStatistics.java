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


/**
 * Provides a live view of the statistics for a {@link Zbdd} instance, including node capacity, usage, garbage
 * collection activity, and node lookup performance.
 *
 * @author Jeroen Gremmen
 *
 * @see Zbdd#getStatistics()
 */
public interface ZbddStatistics
{
  /**
   * Returns the current node capacity.
   *
   * @return  node capacity
   */
  @Contract(pure = true)
  int getNodesCapacity();


  /**
   * Returns the number of free nodes.
   *
   * @return  free node count
   */
  @Contract(pure = true)
  int getFreeNodes();


  /**
   * Returns the current number of dead nodes.
   *
   * @return  dead node count
   */
  @Contract(pure = true)
  int getDeadNodes();


  /**
   * Returns the number of available nodes, which is the sum of free and dead nodes.
   *
   * @return  available node count
   */
  @Contract(pure = true)
  default int getAvailableNodes() {
    return getFreeNodes() + getDeadNodes();
  }


  /**
   * Returns the number of occupied (actively used) nodes.
   *
   * @return  occupied node count
   */
  @Contract(pure = true)
  default int getOccupiedNodes() {
    return getNodesCapacity() - getAvailableNodes();
  }


  /**
   * Return the number of node lookups.
   *
   * @return  number of node lookups
   */
  @Contract(pure = true)
  int getNodeLookups();


  /**
   * Returns the number of node lookups that resulted in a cache hit.
   *
   * @return  node lookup hit count
   */
  @Contract(pure = true)
  int getNodeLookupHitCount();


  /**
   * Returns the ratio of node lookups that resulted in a cache hit.
   *
   * @return  hit ratio in the range {@code 0.0} to {@code 1.0}
   */
  @Contract(pure = true)
  default double getNodeLookupHitRatio() {
    return getNodeLookupHitCount() / (double)getNodeLookups();
  }


  /**
   * Returns the ratio of node lookups that resulted in a cache miss.
   *
   * @return  miss ratio in the range {@code 0.0} to {@code 1.0}
   */
  @Contract(pure = true)
  default double getNodeLookupMissRatio() {
    return 1.0 - getNodeLookupHitRatio();
  }


  /**
   * Returns the total number of garbage collection calls.
   *
   * @return  garbage collection count
   */
  @Contract(pure = true)
  int getGCCount();


  /**
   * Returns the cumulative number of nodes freed by garbage collection.
   *
   * @return  cumulative number of freed nodes
   */
  @Contract(pure = true)
  long getGCFreedNodes();


  /**
   * Returns the number of capacity increases.
   *
   * @return  number of capacity increases
   *
   * @since 0.5.0
   */
  @Contract(pure = true)
  int getCapacityIncreaseCount();


  /**
   * Returns an estimation of the number of bytes used by the zbdd.
   *
   * @return  estimated memory usage in bytes
   */
  @Contract(pure = true)
  long getMemoryUsage();


  /**
   * Returns the number of registered variables.
   *
   * @return  registered variable count
   */
  @Contract(pure = true)
  int getRegisteredVars();
}