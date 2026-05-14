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
 * Cache interface for storing and retrieving results of ZBDD operations, enabling memoization to improve performance.
 * <p>
 * ZBDD operations are identified by their {@link Operation1} or {@link Operation2} type, depending on the number of
 * parameters. A return value of {@link Integer#MIN_VALUE} from the {@code getResult} methods indicates a cache miss.
 * <p>
 * Implementations must ensure that the {@link #clear()} method does not throw any exceptions, as it may be called
 * during garbage collection or ZBDD reset.
 *
 * @author Jeroen Gremmen
 * @since 0.1.3
 *
 * @see de.sayayi.lib.zbdd.ZbddFactory#createCached(ZbddCache)
 */
public interface ZbddCache
{
  /**
   * Retrieves the cached result for a single-parameter operation.
   *
   * @param operation  ZBDD operation, not {@code null}
   * @param p          operation parameter
   *
   * @return  {@link Integer#MIN_VALUE} if the result is not cached, otherwise the cached result
   */
  @Contract(pure = true)
  int getResult(@NotNull Operation1 operation, int p);


  /**
   * Retrieves the cached result for a two-parameter operation.
   *
   * @param operation  ZBDD operation, not {@code null}
   * @param p1         1st operation parameter
   * @param p2         2nd operation parameter
   *
   * @return  {@link Integer#MIN_VALUE} if the result is not cached, otherwise the cached result
   */
  @Contract(pure = true)
  int getResult(@NotNull Operation2 operation, int p1, int p2);


  /**
   * Stores the result of a single-parameter operation in the cache.
   *
   * @param operation  ZBDD operation, not {@code null}
   * @param p          operation parameter
   * @param result     result to cache
   */
  @Contract(mutates = "this")
  void putResult(@NotNull Operation1 operation, int p, int result);


  /**
   * Stores the result of a two-parameter operation in the cache.
   *
   * @param operation  ZBDD operation, not {@code null}
   * @param p1         1st operation parameter
   * @param p2         2nd operation parameter
   * @param result     result to cache
   */
  @Contract(mutates = "this")
  void putResult(@NotNull Operation2 operation, int p1, int p2, int result);


  /**
   * Clears all cached results.
   * <p>
   * Implementations must ensure that this method does not throw any exceptions.
   */
  @Contract(mutates = "this")
  void clear();




  /**
   * Enumeration of cacheable ZBDD operations that take a single parameter.
   */
  enum Operation1
  {
    /** Count the number of combinations. */
    COUNT,

    /** Extract individual variables from a ZBDD. */
    ATOMIZE,

    /** Remove the base element from a ZBDD. */
    REMOVE_BASE
  }




  /**
   * Enumeration of cacheable ZBDD operations that take two parameters.
   */
  enum Operation2
  {
    /** Subset restricting a variable to 0 (excluded). */
    SUBSET0,

    /** Subset restricting a variable to 1 (included). */
    SUBSET1,

    /** Toggle the presence of a variable. */
    CHANGE,

    /** Set union of two ZBDDs. */
    UNION,

    /** Set intersection of two ZBDDs. */
    INTERSECT,

    /** Set difference of two ZBDDs. */
    DIFFERENCE,

    /** Set multiplication (product) of two ZBDDs. */
    MULTIPLY,

    /** Set division (quotient) of two ZBDDs. */
    DIVIDE,

    /** Set modulo of two ZBDDs. */
    MODULO
  }
}
