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
package de.sayayi.lib.zbdd.exception;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;


/**
 * Zbdd related exception stating that the {@code var} is outside the valid range.
 *
 * @author Jeroen Gremmen
 */
public final class InvalidVarException extends ZbddException
{
  private final int var;
  private final int highBoundVar;


  /**
   * Construct a zbdd exception with the given {@code message}.
   *
   * @param var           invalid var
   * @param highBoundVar  high bound var
   * @param message       exception message, not {@code null}
   */
  public InvalidVarException(int var, int highBoundVar, @NotNull String message)
  {
    super(message);

    this.var = var;
    this.highBoundVar = highBoundVar;
  }


  @Contract(pure = true)
  public int getVar() {
    return var;
  }


  /**
   * Return the lowest possible var.
   *
   * @return  low bound var
   */
  @Contract(pure = true)
  public int getLowBoundVar() {
    return 1;
  }


  /**
   * Return the highest possible var.
   *
   * @return  high bound var
   */
  @Contract(pure = true)
  public int getHighBoundVar() {
    return highBoundVar;
  }
}
