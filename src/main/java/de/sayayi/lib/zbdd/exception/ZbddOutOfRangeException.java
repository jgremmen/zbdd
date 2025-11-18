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

import de.sayayi.lib.zbdd.Zbdd;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;


/**
 * Zbdd related exception stating that the {@code zbdd} is outside the valid range.
 *
 * @author Jeroen Gremmen
 * @since 0.6.0
 */
public class ZbddOutOfRangeException extends InvalidZbddException
{
  private final int highBoundZbdd;


  /**
   * Construct a zbdd exception with the given {@code message}.
   *
   * @param message  exception message, not {@code null}
   */
  public ZbddOutOfRangeException(int zbdd, int highBoundZbdd, @NotNull String message)
  {
    super(zbdd, message);
    this.highBoundZbdd = highBoundZbdd;
  }


  /**
   * Return the lowest possible zbdd.
   *
   * @return  low bound zbdd
   */
  @Contract(pure = true)
  public int getLowBoundZbdd() {
    return Zbdd.BASE;
  }


  /**
   * Return the highest possible zbdd.
   *
   * @return  high bound zbdd
   */
  @Contract(pure = true)
  public int getHighBoundZbdd() {
    return highBoundZbdd;
  }
}
