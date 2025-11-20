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

import java.util.function.Consumer;
import java.util.function.Function;


/**
 * <a href="https://en.wikipedia.org/wiki/Zero-suppressed_decision_diagram">
 *   Zero-suppressed decision diagram
 * </a>
 * on Wikipedia.
 *
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
public interface Zbdd
{
  int EMPTY = 0;
  int BASE = 1;


  /**
   * Registers a zbdd callback instance.
   *
   * @param callback  callback, not {@code null}
   */
  @Contract(mutates = "this")
  void registerCallback(@NotNull ZbddCallback callback);


  /**
   * Returns the literal resolver associated with this zbdd instance.
   *
   * @return  literal resolver, never {@code null}
   */
  @Contract(pure = true)
  @NotNull ZbddLiteralResolver getLiteralResolver();


  /**
   * Sets the literal resolver.
   *
   * @param literalResolver  literal resolver, not {@code null}
   */
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
    return zbdd == EMPTY;
  }


  /**
   * @see #base()
   */
  @Contract(pure = true)
  static boolean isBase(int zbdd) {
    return zbdd == BASE;
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
   * Create a new literal/variable with an associated {@code varObject}.
   *
   * @param varObject  object to be associated with the created variable
   *
   * @return  variable number
   * 
   * @see #getVarObject(int)
   *
   * @since 0.5.0
   */
  @Contract(mutates = "this")
  @MustBeInvokedByOverriders
  int createVar(@NotNull Object varObject);


  /**
   * Return the associated object for the given {@code var}.
   *
   * @param var  var to get the object for
   *
   * @return  object associated with {@code var} or {@code null} if the variable was created without an object
   *
   * @see #createVar() 
   * @see #createVar(Object)
   */
  @Contract(pure = true)
  <T> T getVarObject(int var);


  /**
   * Returns the empty zbdd set.
   *
   * @return  empty zbdd set
   *
   * @see #isEmpty(int)
   */
  @Contract(pure = true)
  static int empty() {
    return EMPTY;
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
    return BASE;
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
  int cube(int var);


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
   * @param cubeVars  an array of valid variables
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


  /**
   * Returns the number of cubes in the given {@code zbdd}.
   *
   * @param zbdd  zbdd
   *
   * @return  cube count
   */
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


  /**
   * Removes the base element from the given {@code zbdd}.
   *
   * @param zbdd  zbdd
   *
   * @return  zbdd node representing {@code zbdd} without {@link #base()}
   */
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
   * Tells if the given {@code zbdd} is a valid node.
   *
   * @param zbdd  zbdd node to check for validity
   *
   * @return  {@code true} if the zbdd node is valid, {@code false} otherwise
   *
   * @since 0.5.0
   */
  @Contract(pure = true)
  boolean isValidZbdd(int zbdd);


  /**
   * Tells if the given {@code var} is valid.
   *
   * @param var  var to check for validity
   *
   * @return  {@code true} if the var is valid, {@code false} otherwise
   *
   * @since 0.5.0
   */
  @Contract(pure = true)
  boolean isValidVar(int var);


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
  @MustBeInvokedByOverriders
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
  int gc();


  /**
   * Increments the reference count for the given {@code zbdd}.
   * <p>
   * When a new zbdd is constructed (see {@link #getNode(int, int, int)}) the capacity advisor may suggest garbage
   * collection to free unused zbdd noodes before the decision is made to increase the node capacity. All zbdd nodes
   * that have an incremented reference count are protected from garabage collection.
   * <p>
   * All functions in this interface that accept one or more zbdds as a parameter will protect those zbdds by
   * internally increasing the reference count on entry and decreasing it on exit.
   *
   * @param zbdd  zbdd node
   *
   * @return  zbdd node
   *
   * @see #gc()
   * @see #incRef(int[])
   * @see #decRef(int)
   */
  @Contract(value = "_ -> param1", mutates = "this")
  @MustBeInvokedByOverriders
  int incRef(int zbdd);


  /**
   * Increase the reference count for all zbdd nodes in {@code zbdds}.
   *
   * @param zbdds  array of zbdd nodes, not {@code null}
   *
   * @return  zbdds
   *
   * @see #incRef(int)
   *
   * @since 0.5.1
   */
  @Contract(mutates = "this")
  @MustBeInvokedByOverriders
  default int @NotNull [] incRef(int @NotNull [] zbdds)
  {
    for(var zbdd: zbdds)
      incRef(zbdd);

    return zbdds;
  }


  /**
   * Decrements the reference count for the given {@code zbdd}.
   *
   * @param zbdd  zbdd node
   *
   * @return  zbdd node
   *
   * @see #gc()
   * @see #incRef(int)
   * @see #decRef(int[])
   */
  @Contract(value = "_ -> param1", mutates = "this")
  @MustBeInvokedByOverriders
  int decRef(int zbdd);


  /**
   * Decrease the reference count for all zbdd nodes in {@code zbdds}.
   *
   * @param zbdds  array of zbdd nodes, not {@code null}
   *
   * @return  zbdds
   *
   * @see #decRef(int)
   * 
   * @since 0.5.1
   */
  @Contract(mutates = "this")
  @MustBeInvokedByOverriders
  default int @NotNull [] decRef(int @NotNull [] zbdds)
  {
    for(var zbdd: zbdds)
      decRef(zbdd);

    return zbdds;
  }


  /**
   * Returns an object that provides a "live" view of the given {@code zbdd} node.
   *
   * @param zbdd  zbdd node info to get
   *
   * @return  zbdd node info, never {@code null}
   *
   * @since 0.5.0
   */
  @Contract(pure = true)
  @NotNull ZbddNodeInfo getZbddNodeInfo(int zbdd);


  /**
   * Returns a string representation of the set denoted by {@code zbdd}, using the currently associated
   * {@link ZbddLiteralResolver}.
   *
   * @param zbdd  zbdd node
   *
   * @return  string representation of the set denoted by {@code zbdd}
   */
  @Contract(value = "_ -> new", pure = true)
  @NotNull String toString(int zbdd);


  /**
   * Visit all cubes in the set represented by {@code zbdd}. For each cube
   * {@link CubeVisitor#visitCube(int[]) visitCube(int[])} is invoked. For the {@link #base()} node,
   * {@code visitCube} is invoked with an empty array.
   *
   * @param zbdd     zbdd node
   * @param visitor  cube visitor, not {@code null}
   *
   * @return {@code false} if the {@code visitor} has aborted the continuation, {@code true} otherwise
   */
  boolean visitCubes(int zbdd, @NotNull CubeVisitor visitor);


  /**
   * Visit all elements in the set represented by {@code zbdd}. For each element {@link ZbddVisitor#visitZbdd(int)}
   * is invoked. The zbdd node passed to this function represents a set with only the element visited.
   *
   * @param zbdd     zbdd node
   * @param visitor  zbdd visitor, not {@code null}
   *
   * @return {@code false} if the {@code visitor} has aborted the continuation, {@code true} otherwise
   *
   * @since 0.6.0
   */
  boolean visitCubeZbdds(int zbdd, @NotNull ZbddVisitor visitor);


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
  @Contract(value = "-> new", mutates = "this")
  int @NotNull [] calculateNodeDependency();


  /**
   * Returns an array of zbdd nodes, where each zbdd node represents a single cube in the given {@code zbdd}.
   * <p>
   * For an empty zbdd, the returned array is empty.
   *
   * @param zbdd  zbdd node
   *
   * @return  an array of single cube zbdd nodes, never {@code null}
   *
   * @since 0.6.0
   */
  int @NotNull [] asSingleCubeZbdds(int zbdd);




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
     * @return  {@code true} to continue visiting, {@code false} to abort
     *
     * @see #visitCubes(int, CubeVisitor)
     * @see #base()
     */
    boolean visitCube(int @NotNull [] vars);
  }




  /**
   * Zbdd node visitor interface to be used with {@link #visitCubeZbdds(int, ZbddVisitor)}.
   *
   * @since 0.6.0
   */
  @FunctionalInterface
  interface ZbddVisitor
  {
    /**
     * This method is invoked for each zbdd node representing a single cube in the zbdd set.
     * <p>
     * The zbdd node has no increased reference count.
     *
     * @param zbdd  zbdd node representing a single cube
     *
     * @return  {@code true} to continue visiting, {@code false} to abort
     *
     * @see #visitCubeZbdds(int, ZbddVisitor)
     */
    boolean visitZbdd(int zbdd);
  }




  /**
   * This interface provides various callback functions that might be of interest to classes working with Zbdd
   * instances.
   * <p>
   * Implementing classes must assure that all methods in this interface return without throwing an exception!
   *
   * @author Jeroen Gremmen
   * @since 0.5.0
   */
  interface ZbddCallback
  {
    /**
     * This method is invoked before all zbdd nodes are cleared.
     */
    default void beforeClear() {
    }


    /**
     * This method is invoked after all zbdd nodes have been cleared.
     */
    default void afterClear() {
    }


    /**
     * This method is invoked before zbdd garbage collection.
     */
    default void beforeGc() {
    }


    /**
     * This method is invoked after zbdd garbage collection. At this point dead and newly created nodes are
     * nonexistent anymore.
     */
    default void afterGc() {
    }
  }




  /**
   * Zbdd implementation that caches results of zbdd operations.
   *
   * @author Jeroen Gremmen
   * @since 0.5.0
   */
  interface WithCache extends Zbdd
  {
    /**
     * Returns the current zbdd cache instance used for caching.
     *
     * @return  current zbdd cache instance, never {@code null}
     */
    @Contract(pure = true)
    @NotNull ZbddCache getZbddCache();


    /**
     * Sets the given {@code zbddCache}.
     *
     * @param zbddCache  new zbdd cache instance to use for caching, not {@code null}
     */
    @Contract(mutates = "this,param1")
    void setZbddCache(@NotNull ZbddCache zbddCache);
  }




  /**
   * Zbdd implementation that is thread-safe.
   *
   * @author Jeroen Gremmen
   * @since 0.5.0
   */
  interface Concurrent extends Zbdd
  {
    /**
     * Perform operations within a locked context, acting like one atomic operation.
     * <p>
     * When executing multiple zbdd operations on this concurrent instance, a lock is aquired for each operation
     * on entry and released on exit. For multiple zbdd operations, there's always a chance another thread is given
     * the next lock, allowing it to perform its operations.
     * <p>
     * Note: the {@code operation} function should do its work as fast as possible. Essentially, it should perform
     * zbdd operations only and then exit.
     *
     * @param operation  operation function, performing zbdd operations that are required to be atomic
     *
     * @return  return value from the {@code operation} function
     *
     * @param <T>  return value type
     */
    <T> T doAtomic(@NotNull Function<Zbdd,T> operation);


    /**
     * Perform operations within a locked context, acting like one atomic operation.
     * <p>
     * When executing multiple zbdd operations on this concurrent instance, a lock is aquired for each operation
     * on entry and released on exit. For multiple zbdd operations, there's always a chance another thread is given
     * the next lock, allowing it to perform its operations.
     * <p>
     * Note: the {@code operation} function should do its work as fast as possible. Essentially, it should perform
     * zbdd operations only and then exit.
     *
     * @param operation  operation function, performing zbdd operations that are required to be atomic
     */
    void doAtomic(@NotNull Consumer<Zbdd> operation);
  }




  /**
   * A ZbddNode provides a "live view" on a zbdd node. As the reference count or literal resolver changes, those
   * changes reflect in the return values for the associated methods.
   * <p>
   * If the zbdd node was garbage collected, all methods in this interface, including {@code toString()},
   * throw an exception stating that the zbdd is not valid.
   *
   * @author Jeroen Gremmen
   * @since 0.5.0
   */
  interface ZbddNodeInfo
  {
    /**
     * Returns the zbdd for this instance.
     *
     * @return  zbdd node
     */
    @Contract(pure = true)
    int getZbdd();


    /**
     * Returns the var of the zbdd node.
     *
     * @return  var of the zbdd node
     */
    @Contract(pure = true)
    int getVar();


    /**
     * Returns the zbdd for the 0-edge of the zbdd node.
     *
     * @return  zbdd for the 0-edge
     */
    @Contract(pure = true)
    int getP0();


    /**
     * Returns the zbdd for the 1-edge of the zbdd node.
     *
     * @return  zbdd for the 1-edge
     */
    @Contract(pure = true)
    int getP1();


    /**
     * Returns the reference count of the zbdd node.
     * <p>
     * If the reference count equals {@code -1}, the node has been newly created. If the reference count equals
     * {@code 0}, the node is dead.
     *
     * @return  reference count of the zbdd node
     *
     * @see #isNewNode()
     * @see #isDeadNode()
     */
    @Contract(pure = true)
    int getReferenceCount();


    /**
     * Returns the literal for the var of the zbdd node.
     *
     * @return  literal of the var, never {@code null}
     */
    @Contract(pure = true)
    @NotNull String getLiteral();


    /**
     * Tells whether the zbdd node is a newly created node.
     *
     * @return  {@code true} if the zbdd node is valid and was newly created, {@code false} otherwise
     *
     * @see #getReferenceCount()
     */
    @Contract(pure = true)
    default boolean isNewNode() {
      return getReferenceCount() == -1;
    }


    /**
     * Tells whether the zbdd node is a dead node.
     * <p>
     * A node becomes dead as soon as the reference count decreases from {@code 1} to {@code 0}.
     *
     * @return  {@code true} if the zbdd node is valid and dead, {@code false} otherwise
     *
     * @see #getReferenceCount()
     */
    @Contract(pure = true)
    default boolean isDeadNode() {
      return getReferenceCount() == 0;
    }
  }
}
