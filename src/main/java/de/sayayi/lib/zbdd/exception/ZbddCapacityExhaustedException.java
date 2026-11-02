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
package de.sayayi.lib.zbdd.exception;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;


/**
 * Thrown when a zbdd operation requires a new node, but no free node is left and the node capacity cannot be
 * increased, either because the {@link de.sayayi.lib.zbdd.ZbddCapacityAdvisor capacity advisor} advises no increment
 * or because the {@linkplain de.sayayi.lib.zbdd.ZbddCapacityAdvisor#MAX_CAPACITY maximum capacity} has been reached.
 * <p>
 * Unlike most other zbdd exceptions, this one does not indicate a programming error. The zbdd instance remains intact,
 * so the caller may, for instance, release references to zbdds no longer needed and retry the operation.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
public final class ZbddCapacityExhaustedException extends ZbddException
{
  @Serial
  private static final long serialVersionUID = -2773947678819652914L;

  private final int nodesCapacity;


  /**
   * Constructs an exception for an exhausted node capacity.
   *
   * @param nodesCapacity  node capacity at the time the exception was thrown
   * @param message        exception message, not {@code null}
   */
  public ZbddCapacityExhaustedException(int nodesCapacity, @NotNull String message)
  {
    super(message);

    this.nodesCapacity = nodesCapacity;
  }


  /**
   * Returns the node capacity at the time the exception was thrown.
   *
   * @return  node capacity
   */
  @Contract(pure = true)
  public int getNodesCapacity() {
    return nodesCapacity;
  }
}
