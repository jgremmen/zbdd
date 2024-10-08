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
 * Resolve zbdd literal and cube names.
 *
 * @author Jeroen Gremmen
 *
 * @see Zbdd#setLiteralResolver(ZbddLiteralResolver)
 */
@FunctionalInterface
public interface ZbddLiteralResolver
{
  /**
   * Returns the literal name for zbdd variable {@code var}.
   *
   * @param var  registered variable
   *
   * @return  literal name, never {@code null}
   */
  @Contract(pure = true)
  @NotNull String getLiteralName(@Range(from = 1, to = MAX_VALUE) int var);


  /**
   * Return the string representation of a cube.
   *
   * @param cubeVars  zbdd veriables
   *
   * @return  cube name, never {@code null}
   */
  @Contract(pure = true)
  default @NotNull String getCubeName(int @NotNull [] cubeVars)
  {
    return cubeVars.length == 0
        ? "{}" : stream(cubeVars).sorted().mapToObj(this::getLiteralName).collect(joining("."));
  }
}
