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
 * Thrown when an invalid or corrupted ZBDD node reference is encountered during an operation. This typically
 * indicates that a ZBDD node has been garbage collected or was never properly created.
 *
 * @author Jeroen Gremmen
 */
public class InvalidZbddException extends ZbddException
{
  private final int zbdd;


  /**
   * Constructs an exception for an invalid ZBDD node reference.
   *
   * @param zbdd     the invalid ZBDD node reference
   * @param message  exception message, not {@code null}
   */
  public InvalidZbddException(int zbdd, @NotNull String message)
  {
    super(message);
    this.zbdd = zbdd;
  }


  /**
   * Returns the invalid ZBDD node reference that caused this exception.
   *
   * @return  invalid ZBDD node reference
   */
  @Contract(pure = true)
  public int getZbdd() {
    return zbdd;
  }
}
