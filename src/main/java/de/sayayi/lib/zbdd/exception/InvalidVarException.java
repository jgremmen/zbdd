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
 * Thrown when an invalid variable identifier is used in a ZBDD operation. The valid range of variable identifiers
 * is from {@link #getLowBoundVar()} to {@link #getHighBoundVar()} inclusive.
 *
 * @author Jeroen Gremmen
 */
public final class InvalidVarException extends ZbddException
{
  private final int var;
  private final int highBoundVar;


  /**
   * Constructs an exception for an invalid variable identifier.
   *
   * @param var           the invalid variable identifier
   * @param highBoundVar  the highest valid variable identifier
   * @param message       exception message, not {@code null}
   */
  public InvalidVarException(int var, int highBoundVar, @NotNull String message)
  {
    super(message);

    this.var = var;
    this.highBoundVar = highBoundVar;
  }


  /**
   * Returns the invalid variable identifier that caused this exception.
   *
   * @return  invalid variable identifier
   */
  @Contract(pure = true)
  public int getVar() {
    return var;
  }


  /**
   * Returns the lowest valid variable identifier, which is always {@code 1}.
   *
   * @return  lowest valid variable identifier
   */
  @Contract(pure = true)
  public int getLowBoundVar() {
    return 1;
  }


  /**
   * Returns the highest valid variable identifier at the time the exception was thrown.
   *
   * @return  highest valid variable identifier
   */
  @Contract(pure = true)
  public int getHighBoundVar() {
    return highBoundVar;
  }
}
