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
package de.sayayi.lib.zbdd;

import de.sayayi.lib.zbdd.cache.ZbddCache;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import static java.lang.Integer.MAX_VALUE;


/**
 * <a href="https://en.wikipedia.org/wiki/Zero-suppressed_decision_diagram">
 *   Zero-suppressed decision diagram
 * </a>
 * on Wikipedia.
 *
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
public interface Zbdd extends Cloneable
{
  int ZBDD_EMPTY = 0;
  int ZBDD_BASE = 1;


  @Contract(pure = true)
  @NotNull Zbdd clone();


  /**
   * Returns the literal resolver associated with this zbdd instance.
   *
   * @return  literal resolver, never {@code null}
   */
  @Contract(pure = true)
  @NotNull ZbddLiteralResolver getLiteralResolver();


  @Contract(mutates = "this")
  void setLiteralResolver(@NotNull ZbddLiteralResolver literalResolver);


  /**
   * Returns the statistics for this zbdd instance. The returned object is a singleton and will
   * reflect the actual statistics at any time.
   *
   * @return  statistics, never {@code null}
   */
  @Contract(pure = true)
  @NotNull ZbddStatistics getStatistics();


  /**
   * Clear all nodes from this zbdd instance. If a zbdd cache is assigned it will be cleared as
   * well.
   * <p>
   * This method clears all variables and nodes. It does not free up allocated memory.
   *
   * @see ZbddCache#clear()
   */
  @Contract(mutates = "this")
  @MustBeInvokedByOverriders
  void clear();


  /**
   * Tells if the zbdd set identified by {@code zbdd} is empty.
   *
   * @param zbdd  zbdd node
   *
   * @return  {@code true} if the set is empty, {@code false} otherwise
   *
   * @see #empty()
   */
  @Contract(pure = true)
  static boolean isEmpty(int zbdd) {
    return zbdd == ZBDD_EMPTY;
  }


  /**
   * @see #base()
   */
  @Contract(pure = true)
  static boolean isBase(int zbdd) {
    return zbdd == ZBDD_BASE;
  }


  /**
   * Create a new literal/variable.
   *
   * @return  variable number
   */
  @Contract(mutates = "this")
  @MustBeInvokedByOverriders
  int createVar();


  /**
   * Returns the empty zbdd set.
   *
   * @return  empty zbdd set
   *
   * @see #isEmpty(int)
   */
  @Contract(pure = true)
  static int empty() {
    return ZBDD_EMPTY;
  }


  /**
   * Returns the base zbdd set.
   *
   * @return  base zbdd set
   *
   * @see #isBase(int)
   */
  @Contract(pure = true)
  static int base() {
    return ZBDD_BASE;
  }


  /**
   * Returns a zbdd set with the given {@code var} as its only element.
   * <p>
   * Example:
   * <pre>
   *   Zbdd zbdd = new Zbdd();
   *   int v1 = zbdd.createVar();
   *   int singleton = zbdd.cube(v1);
   *   String s = zbdd.toString(singleton);  // = "{ v1 }"
   * </pre>
   *
   * @param var  valid variable
   *
   * @return  zbdd set with {@code var} as its only element
   */
  @Contract(mutates = "this")
  int cube(@Range(from = 1, to = MAX_VALUE) int var);


  /**
   * Returns a zbdd set with the given {@code vars} combined as its only element.
   * <p>
   * Example:
   * <pre>
   *   Zbdd zbdd = new Zbdd();
   *   int v1 = zbdd.createVar();
   *   int v2 = zbdd.createVar();
   *   int v3 = zbdd.createVar();
   *   int singleton = zbdd.cube(v1, v3);
   *   String s = zbdd.toString(singleton);  // = "{ v1.v3 }"
   * </pre>
   *
   * @param cubeVars  valid variables
   *
   * @return  zbdd set with {@code cubeVars} as its only element
   */
  @Contract(mutates = "this")
  int cube(int @NotNull ... cubeVars);


  /**
   * @since 0.3.1
   */
  @Contract(pure = true)
  boolean hasCubeWithVar(int zbdd, int var);


  @Contract(mutates = "this")
  int subset0(int zbdd, int var);


  @Contract(mutates = "this")
  int subset1(int zbdd, int var);


  @Contract(mutates = "this")
  int change(int zbdd, int var);


  @Contract(pure = true)
  int count(int zbdd);


  @Contract(mutates = "this")
  int union(int... p);


  @Contract(mutates = "this")
  int union(int p, int q);


  @Contract(mutates = "this")
  int intersect(int p, int q);


  @Contract(mutates = "this")
  int difference(int p, int q);


  @Contract(mutates = "this")
  int multiply(int p, int q);


  @Contract(mutates = "this")
  int divide(int p, int q);


  @Contract(mutates = "this")
  int modulo(int p, int q);


  @Contract(mutates = "this")
  int atomize(int zbdd);


  @Contract(mutates = "this")
  int removeBase(int zbdd);


  /**
   * Tells if the given zbdd set {@code q} is contained in zbdd  {@code p}.
   *
   * @param p  provided zbdd set to test
   * @param q  zbdd set which is expected to be part of zbdd set {@code p}
   *
   * @return  {@code true} if both zbdd sets {@code p} and {@code q} are not empty and zbdd set {@code q} is
   *          contained in zbdd set {@code p}, {@code false} otherwise
   */
  @Contract(mutates = "this")
  boolean contains(int p, int q);


  /**
   * Returns the variable for the given {@code zbdd} node.
   *
   * @param zbdd  zbdd node
   *
   * @return  variable or {@code -1} in case {@code zbdd} is the empty or base node
   */
  @Contract(pure = true)
  int getVar(int zbdd);


  /**
   * Returns the zbdd node for the 0-branch of the given {@code zbdd} node.
   *
   * @param zbdd  zbdd node
   *
   * @return  zbdd node for the 1-branch
   */
  @Contract(pure = true)
  int getP0(int zbdd);


  /**
   * Returns the zbdd node for the 1-branch of the given {@code zbdd} node.
   *
   * @param zbdd  zbdd node
   *
   * @return  zbdd node for the 1-branch
   */
  @Contract(pure = true)
  int getP1(int zbdd);


  @Contract(mutates = "this")
  int getNode(int var, int p0, int p1);


  /**
   * Perform garbage collection on the internal zbdd structure.
   * <p>
   * After garbage collection, all dead and not referenced nodes have been freed and the statistics have been updated
   * accordingly.
   *
   * @return  the number of freed zbdd nodes
   *
   * @see #incRef(int)
   * @see #decRef(int)
   */
  @Contract(mutates = "this")
  @MustBeInvokedByOverriders
  @SuppressWarnings("UnusedReturnValue")
  int gc();


  @Contract(value = "_ -> param1", mutates = "this")
  @MustBeInvokedByOverriders
  int incRef(int zbdd);


  @Contract(value = "_ -> param1", mutates = "this")
  @MustBeInvokedByOverriders
  @SuppressWarnings("UnusedReturnValue")
  int decRef(int zbdd);


  @Contract(value = "_ -> new", pure = true)
  @NotNull String toString(int zbdd);


  void visitCubes(int zbdd, @NotNull CubeVisitor visitor);


  /**
   * Calculates a list of zbdd nodes, where each zbdd node references 2 other zbdd nodes in the list with a lower index.
   * <p>
   * Initially, each newly created zbdd node references other zbdd nodes with lower zbdd numbers. Starting with the
   * first garbage collection, freed zbdd numbers are going to be reused, if possible. This means that the initial
   * contract is no longer valid and new zbdd nodes may have a lower number than the zbdd nodes it references.
   * The list returned by this method essentially describes the generation sequence for all zbdd nodes in the correct
   * order.
   * <p>
   * Note: this method will always perform a garbage collection
   *
   * @return  array with the generation sequence for all zbdd nodes, never {@code null}
   *
   * @see #gc()
   *
   * @since 0.3.1
   */
  @Contract(value = "-> new")
  int @NotNull [] calculateNodeDependency();




  /**
   * Cube visitor interface to be used with {@link #visitCubes(int, CubeVisitor)}.
   */
  @FunctionalInterface
  interface CubeVisitor
  {
    /**
     * This method is invoked for each cube in the zbdd set.
     * The variables in array {@code vars} are sorted in descendant order.
     * <p>
     * If vars is an empty array, it represents the base node.
     *
     * @param vars  cube variables or empty array, never {@code null}
     *
     * @see #visitCubes(int, CubeVisitor)
     * @see #base()
     */
    void visitCube(int @NotNull [] vars);
  }




  /**
   * @author Jeroen Gremmen
   * @since 0.5.0
   */
  interface WithCache extends Zbdd
  {
    @Contract(pure = true)
    @NotNull ZbddCache getZbddCache();
  }
}
