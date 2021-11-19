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
import org.jetbrains.annotations.Range;

import static de.sayayi.lib.zbdd.Zbdd.MAX_NODES;


/**
 * @author Jeroen Gremmen
 */
public interface ZbddNodesAdvisor
{
  @Contract(pure = true)
  @Range(from = 4, to = MAX_NODES)
  int getInitialNodes();


  @Contract(pure = true)
  @Range(from = 1, to = MAX_NODES)
  int getMinimumFreeNodes(@NotNull ZbddStatistics statistics);


  @Contract(pure = true)
  @Range(from = 1, to = MAX_NODES)
  int adviseNodesGrowth(@NotNull ZbddStatistics statistics);


  /**
   * <p>
   *   Tells whether dead nodes should be garbage collected. This method is invoked as soon as a new node is
   *   created and the number of free nodes is 1 or 0.
   * </p>
   * <p>
   *   Garbage collection is an expensive operation and only useful if a substantial number of nodes are
   *   exopected to be freed in the process. The current number of dead nodes may be a good indicator but is not
   *   a guarantee that those nodes are invalidated..
   * </p>
   *
   * @param statistics  current zbdd statistics, not {@code null}
   *
   * @return  {@code true} if the garbage collection is required, {@code false} otherwise
   */
  @Contract(pure = true)
  boolean isGCRequired(@NotNull ZbddStatistics statistics);
}
