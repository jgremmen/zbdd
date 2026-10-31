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

import de.sayayi.lib.zbdd.ZbddCapacityAdvisor;
import de.sayayi.lib.zbdd.ZbddStatistics;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import static de.sayayi.lib.zbdd.internal.ZbddImpl.MAX_NODES;
import static java.lang.Math.min;


/**
 * Default {@link ZbddCapacityAdvisor} implementation used when no custom advisor is provided to the
 * {@link de.sayayi.lib.zbdd.ZbddFactory ZbddFactory}. It starts with a low initial capacity and grows aggressively at
 * first, then more conservatively once the zbdd collection reaches a larger size.
 *
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
@ApiStatus.Internal
public enum DefaultCapacityAdvisor implements ZbddCapacityAdvisor
{
  /** Singleton instance of this advisor. */
  INSTANCE;


  /** {@inheritDoc} */
  @Override
  public int getInitialCapacity() {
    return 128;
  }


  /** {@inheritDoc} */
  @Override
  public int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) {
    return statistics.getNodesCapacity() / 4;  // 25%
  }


  /** {@inheritDoc} */
  @Override
  public int adviseIncrement(@NotNull ZbddStatistics statistics)
  {
    final int capacity = statistics.getNodesCapacity();

    // size < 500000 -> increase by 150%
    // size > 500000 -> increase by 30%
    return capacity < 500000 ? capacity * 3 / 2 : (capacity / 10) * 3;
  }


  /** {@inheritDoc} */
  @Override
  public boolean isGCRequired(@NotNull ZbddStatistics statistics)
  {
    final var capacity = statistics.getNodesCapacity();

    // new nodes that survived the last garbage collection are most likely still in use
    final var freeableNodes = statistics.getDeadNodes() +
        min(statistics.getNewNodes(), statistics.getNodesAllocatedSinceGC());

    // the capacity can't be increased any further, so garbage collection is the only way left to obtain free nodes
    return capacity == MAX_NODES || freeableNodes > (capacity / 10);
  }
}
