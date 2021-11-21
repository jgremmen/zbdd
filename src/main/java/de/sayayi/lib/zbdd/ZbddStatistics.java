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
 * @author Jeroen Gremmen
 */
public interface ZbddStatistics
{
  @Contract(pure = true)
  int getNodesCapacity();


  @Contract(pure = true)
  int getFreeNodes();


  @Contract(pure = true)
  int getDeadNodes();


  @Contract(pure = true)
  default int getAvailableNodes() {
    return getFreeNodes() + getDeadNodes();
  }


  @Contract(pure = true)
  default int getOccupiedNodes() {
    return getNodesCapacity() - getAvailableNodes();
  }


  @Contract(pure = true)
  int getNodeLookups();


  @Contract(pure = true)
  int getNodeLookupHitCount();


  @Contract(pure = true)
  default double getNodeLookupHitRatio() {
    return getNodeLookupHitCount() / (double)getNodeLookups();
  }


  @Contract(pure = true)
  default double getNodeLookupMissRatio() {
    return 1.0 - getNodeLookupHitRatio();
  }


  @Contract(pure = true)
  int getGCCount();


  @Contract(pure = true)
  long getGCFreedNodes();


  @Contract(pure = true)
  long getMemoryUsage();


  @Contract(pure = true)
  int getRegisteredVars();
}
