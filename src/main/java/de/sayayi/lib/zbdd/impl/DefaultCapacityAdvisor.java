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
package de.sayayi.lib.zbdd.impl;

import de.sayayi.lib.zbdd.ZbddCapacityAdvisor;
import de.sayayi.lib.zbdd.ZbddStatistics;
import org.jetbrains.annotations.NotNull;


/**
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
public enum DefaultCapacityAdvisor implements ZbddCapacityAdvisor
{
  INSTANCE;


  @Override
  public int getInitialCapacity() {
    return 128;
  }


  @Override
  public int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) {
    return statistics.getNodesCapacity() / 20;  // 5%
  }


  @Override
  public int adviseIncrement(@NotNull ZbddStatistics statistics)
  {
    final int capacity = statistics.getNodesCapacity();

    // size < 500000 -> increase by 150%
    // size > 500000 -> increase by 30%
    return capacity < 500000 ? capacity * 3 / 2 : (capacity / 10) * 3;
  }


  @Override
  public boolean isGCRequired(@NotNull ZbddStatistics statistics)
  {
    final int capacity = statistics.getNodesCapacity();

    // capacity > 250000
    // dead nodes > 10% of total capacity
    return capacity > 250000 || statistics.getDeadNodes() > (capacity / 10);
  }
}
