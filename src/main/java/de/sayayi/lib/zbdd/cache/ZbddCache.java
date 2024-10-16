/*
 * Copyright 2022 Jeroen Gremmen
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
package de.sayayi.lib.zbdd.cache;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;


/**
 * @author Jeroen Gremmen
 * @since 0.1.3
 */
public interface ZbddCache
{
  /**
   * Retrieve result from the cache.
   *
   * @param operation  zbdd operation
   * @param p          operation parameter
   *
   * @return  {@link Integer#MIN_VALUE} if the result is not cached. Oherwise the cached result is returned
   */
  int getResult(@NotNull Operation1 operation, int p);


  /**
   * Retrieve result from the cache.
   *
   * @param operation  zbdd operation
   * @param p1         1st operation parameter
   * @param p2         2nd operation parameter
   *
   * @return  {@link Integer#MIN_VALUE} if the result is not cached. Oherwise the cached result is returned
   */
  int getResult(@NotNull Operation2 operation, int p1, int p2);


  @Contract(mutates = "this")
  void putResult(@NotNull Operation1 operation, int p, int result);


  @Contract(mutates = "this")
  void putResult(@NotNull Operation2 operation, int p1, int p2, int result);


  @Contract(mutates = "this")
  void clear();




  enum Operation1
  {
    COUNT,
    ATOMIZE,
    REMOVE_BASE
  }




  enum Operation2
  {
    SUBSET0,
    SUBSET1,
    CHANGE,
    UNION,
    INTERSECT,
    DIFFERENCE,
    MULTIPLY,
    DIVIDE,
    MODULO
  }
}
