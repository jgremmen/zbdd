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

import static java.lang.Integer.MAX_VALUE;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;


/**
 * Resolves ZBDD variable literals and cubes to human-readable string representations.
 * <p>
 * A literal resolver is used by {@link Zbdd#toString(int)} to convert internal variable numbers into meaningful
 * names. It also controls how cubes (combinations of variables) and the base element are represented as strings.
 * <p>
 * This is a {@linkplain FunctionalInterface functional interface} whose functional method is
 * {@link #getLiteralName(int)}.
 *
 * @author Jeroen Gremmen
 *
 * @see Zbdd#setLiteralResolver(ZbddLiteralResolver)
 * @see Zbdd#toString(int)
 */
@FunctionalInterface
public interface ZbddLiteralResolver
{
  /**
   * Returns the human-readable name for the given ZBDD variable.
   *
   * @param var  registered variable number, must be &ge; 1
   *
   * @return  literal name, never {@code null}
   */
  @Contract(pure = true)
  @NotNull String getLiteralName(@Range(from = 1, to = MAX_VALUE) int var);


  /**
   * Returns the string representation of a cube, which is a combination of variables. The default implementation
   * joins the {@linkplain #getLiteralName(int) literal names} of all variables with a dot ({@code "."}) separator,
   * or returns the {@linkplain #getBaseName() base name} if the cube has no variables.
   *
   * @param cubeVars  ZBDD variables making up the cube, sorted in descending order
   *
   * @return  cube name, never {@code null}
   */
  @Contract(pure = true)
  default @NotNull String getCubeName(int @NotNull [] cubeVars)
  {
    return cubeVars.length == 0
        ? getBaseName()
        : stream(cubeVars).sorted().mapToObj(this::getLiteralName).collect(joining("."));
  }


  /**
   * Returns the string representation for the base element, which represents the empty set.
   * The default implementation returns {@code "{}"}.
   *
   * @return  base name, never {@code null}
   *
   * @see Zbdd#base()
   *
   * @since 0.2.2
   */
  @Contract(pure = true)
  default @NotNull String getBaseName() {
    return "{}";
  }
}
