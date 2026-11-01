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

import de.sayayi.lib.zbdd.ZbddCapacityAdvisor;
import de.sayayi.lib.zbdd.ZbddStatistics;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import static de.sayayi.lib.zbdd.internal.ZbddImpl.MAX_NODES;
import static java.lang.Math.max;
import static java.lang.Math.min;


/**
 * The {@link ZbddCapacityAdvisor} implementations offered as constants by {@link ZbddCapacityAdvisor}, which differ in
 * the trade-off made between memory usage and performance. {@link #DEFAULT} is used when no custom advisor is provided
 * to the {@link de.sayayi.lib.zbdd.ZbddFactory ZbddFactory}.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@ApiStatus.Internal
public enum PresetCapacityAdvisor implements ZbddCapacityAdvisor
{
  /** @see ZbddCapacityAdvisor#DEFAULT */
  DEFAULT(128, 150, 500_000, 30, 25, 10),

  /** @see ZbddCapacityAdvisor#MINIMAL_MEMORY */
  MINIMAL_MEMORY(64, 12, 10, 10),

  /** @see ZbddCapacityAdvisor#LOW_MEMORY */
  LOW_MEMORY(128, 25, 15, 10),

  /** @see ZbddCapacityAdvisor#BALANCED */
  BALANCED(128, 50, 25, 10),

  /** @see ZbddCapacityAdvisor#HIGH_PERFORMANCE */
  HIGH_PERFORMANCE(128, 150, 4_000_000, 50, 50, 25);


  private static final int MINIMUM_INCREMENT = 8;

  private final int initialCapacity;
  private final int growthPercent;
  private final int largeTableNodes;
  private final int largeTableGrowthPercent;
  private final int minimumFreePercent;
  private final int gcThresholdPercent;


  PresetCapacityAdvisor(int initialCapacity, int growthPercent, int minimumFreePercent, int gcThresholdPercent) {
    this(initialCapacity, growthPercent, MAX_NODES, growthPercent, minimumFreePercent, gcThresholdPercent);
  }


  PresetCapacityAdvisor(int initialCapacity, int growthPercent, int largeTableNodes, int largeTableGrowthPercent,
                        int minimumFreePercent, int gcThresholdPercent)
  {
    this.initialCapacity = initialCapacity;
    this.growthPercent = growthPercent;
    this.largeTableNodes = largeTableNodes;
    this.largeTableGrowthPercent = largeTableGrowthPercent;
    this.minimumFreePercent = minimumFreePercent;
    this.gcThresholdPercent = gcThresholdPercent;
  }


  /** {@inheritDoc} */
  @Override
  public int getInitialCapacity() {
    return initialCapacity;
  }


  /** {@inheritDoc} */
  @Override
  public int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) {
    return max(percentOf(statistics.getNodesCapacity(), minimumFreePercent), 1);
  }


  /** {@inheritDoc} */
  @Override
  public int adviseIncrement(@NotNull ZbddStatistics statistics)
  {
    final var capacity = statistics.getNodesCapacity();

    return max(
        percentOf(capacity, capacity < largeTableNodes ? growthPercent : largeTableGrowthPercent),
        MINIMUM_INCREMENT);
  }


  /** {@inheritDoc} */
  @Override
  public boolean isGCRequired(@NotNull ZbddStatistics statistics)
  {
    final var capacity = statistics.getNodesCapacity();

    // new nodes that survived the last garbage collection are most likely still in use
    final var freeableNodes =
        statistics.getDeadNodes() +
        min(statistics.getNewNodes(), statistics.getNodesAllocatedSinceGC());

    // the capacity can't be increased any further, so garbage collection is the only way left to obtain free nodes
    return capacity == MAX_NODES || freeableNodes > percentOf(capacity, gcThresholdPercent);
  }


  @Contract(pure = true)
  private static int percentOf(int nodes, int percent) {
    return (int)(nodes * (long)percent / 100);
  }
}
