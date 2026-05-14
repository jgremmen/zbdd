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
 * <p>
 * All values returned by methods of this interface reflect the current state of the underlying ZBDD at the time
 * of invocation. Because this is a live view, values may change between successive calls if the ZBDD is modified
 * concurrently.
 *
 * @author Jeroen Gremmen
 *
 * @see Zbdd#getStatistics()
 */
public interface ZbddStatistics
{
  /**
   * Returns the current maximum number of nodes that can be stored without requiring a capacity increase.
   *
   * @return  node capacity, always &gt; 0
   */
  @Contract(pure = true)
  int getNodesCapacity();


  /**
   * Returns the number of nodes that are currently not in use and available for immediate allocation.
   *
   * @return  free node count, always &ge; 0
   */
  @Contract(pure = true)
  int getFreeNodes();


  /**
   * Returns the current number of dead nodes. Dead nodes are nodes that are no longer referenced but have not
   * yet been reclaimed by garbage collection.
   *
   * @return  dead node count, always &ge; 0
   */
  @Contract(pure = true)
  int getDeadNodes();


  /**
   * Returns the number of available nodes, which is the sum of {@linkplain #getFreeNodes() free} and
   * {@linkplain #getDeadNodes() dead} nodes. These nodes can potentially be reused without increasing capacity.
   *
   * @return  available node count, always &ge; 0
   */
  @Contract(pure = true)
  default int getAvailableNodes() {
    return getFreeNodes() + getDeadNodes();
  }


  /**
   * Returns the number of occupied (actively used) nodes. This is the difference between the
   * {@linkplain #getNodesCapacity() capacity} and the {@linkplain #getAvailableNodes() available} nodes.
   *
   * @return  occupied node count, always &ge; 0
   */
  @Contract(pure = true)
  default int getOccupiedNodes() {
    return getNodesCapacity() - getAvailableNodes();
  }


  /**
   * Returns the total number of node lookups performed. A node lookup occurs when the ZBDD searches for an
   * existing node matching a given variable and sub-nodes.
   *
   * @return  number of node lookups, always &ge; 0
   */
  @Contract(pure = true)
  int getNodeLookups();


  /**
   * Returns the number of node lookups that resulted in a cache hit, meaning an existing node was reused
   * rather than creating a new one.
   *
   * @return  node lookup hit count, always &ge; 0
   */
  @Contract(pure = true)
  int getNodeLookupHitCount();


  /**
   * Returns the ratio of node lookups that resulted in a cache hit. A higher ratio indicates better node reuse.
   *
   * @return  hit ratio in the range {@code 0.0} to {@code 1.0}, or {@code NaN} if no lookups have been performed
   */
  @Contract(pure = true)
  default double getNodeLookupHitRatio() {
    return getNodeLookupHitCount() / (double)getNodeLookups();
  }


  /**
   * Returns the ratio of node lookups that resulted in a cache miss. A lower ratio indicates better node reuse.
   *
   * @return  miss ratio in the range {@code 0.0} to {@code 1.0}, or {@code NaN} if no lookups have been performed
   */
  @Contract(pure = true)
  default double getNodeLookupMissRatio() {
    return 1.0 - getNodeLookupHitRatio();
  }


  /**
   * Returns the total number of garbage collection runs that have been performed. Garbage collection reclaims
   * dead nodes and makes them available for reuse.
   *
   * @return  garbage collection count, always &ge; 0
   */
  @Contract(pure = true)
  int getGCCount();


  /**
   * Returns the cumulative number of nodes freed across all garbage collection runs.
   *
   * @return  cumulative number of freed nodes, always &ge; 0
   */
  @Contract(pure = true)
  long getGCFreedNodes();


  /**
   * Returns the number of times the internal node storage capacity has been increased to accommodate more nodes.
   *
   * @return  number of capacity increases, always &ge; 0
   *
   * @since 0.5.0
   */
  @Contract(pure = true)
  int getCapacityIncreaseCount();


  /**
   * Returns an estimation of the total number of bytes used by the ZBDD, including internal data structures
   * and node storage.
   *
   * @return  estimated memory usage in bytes, always &gt; 0
   */
  @Contract(pure = true)
  long getMemoryUsage();


  /**
   * Returns the number of variables that have been registered with the ZBDD. Variables are the building blocks
   * used to construct combinations within the diagram.
   *
   * @return  registered variable count, always &ge; 0
   */
  @Contract(pure = true)
  int getRegisteredVars();
}
