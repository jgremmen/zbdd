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

import de.sayayi.lib.zbdd.cache.ZbddCache;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import java.util.StringJoiner;

import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation1.*;
import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation2.*;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.Integer.MIN_VALUE;
import static java.lang.Math.*;
import static java.util.Arrays.copyOf;
import static java.util.Arrays.sort;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;


/**
 * <a href="https://en.wikipedia.org/wiki/Zero-suppressed_decision_diagram">
 *   Zero-suppressed decision diagram
 * </a>
 * on Wikipedia.
 * <p>
 * This class is not thread-safe.
 *
 * @author Jeroen Gremmen
 */
@SuppressWarnings("DuplicatedCode")
public class Zbdd implements Cloneable
{
  private static final int GC_VAR_MARK_MASK = 0x80000000;
  private static final int NODE_RECORD_SIZE = 6;

  /** Maximum number of nodes. */
  public static final int MAX_NODES = MAX_VALUE / NODE_RECORD_SIZE;

  private static final int _VAR = 0;       // variable number
  private static final int _P0 = 1;        // 0-branch
  private static final int _P1 = 2;        // 1-branch
  private static final int _NEXT = 3;      // next node
  private static final int _CHAIN = 4;     // start of hash chain
  private static final int _REFCOUNT = 5;  // reference count

  protected static final int ZBDD_EMPTY = 0;
  protected static final int ZBDD_BASE = 1;

  private final @NotNull ZbddCapacityAdvisor capacityAdvisor;
  private final @NotNull Statistics statistics;

  private ZbddCache zbddCache = null;

  private int lastVarNumber;

  private int nodesCapacity;
  private int nodesFree;
  private int nodesDead;

  private int @NotNull [] nodes;
  private int nextFreeNode;

  private @NotNull ZbddLiteralResolver literalResolver = var -> "v" + var;


  public Zbdd() {
    this(DefaultCapacityAdvisor.INSTANCE);
  }


  public Zbdd(@NotNull ZbddCapacityAdvisor capacityAdvisor)
  {
    this.capacityAdvisor = capacityAdvisor;

    //noinspection ConstantConditions
    nodesCapacity = max(capacityAdvisor.getInitialCapacity(), 8);
    nodes = new int[nodesCapacity * NODE_RECORD_SIZE];

    initLeafNode(ZBDD_EMPTY);
    initLeafNode(ZBDD_BASE);

    statistics = new Statistics();

    clear();
  }


  @SuppressWarnings("CopyConstructorMissesField")
  protected Zbdd(@NotNull Zbdd zbdd)
  {
    capacityAdvisor = zbdd.capacityAdvisor;
    lastVarNumber = zbdd.lastVarNumber;
    nodesCapacity = zbdd.nodesCapacity;
    nodesFree = zbdd.nodesFree;
    nodesDead = zbdd.nodesDead;
    nodes = copyOf(zbdd.nodes, zbdd.nodes.length);
    nextFreeNode = zbdd.nextFreeNode;
    literalResolver = zbdd.literalResolver;

    statistics = new Statistics();
  }


  private void initLeafNode(int zbdd)
  {
    final int offset = zbdd * NODE_RECORD_SIZE;

    nodes[offset + _VAR] = -1;
    nodes[offset + _P0] = zbdd;
    nodes[offset + _P1] = zbdd;
  }


  /**
   * Sets or removes a zbdd cache.
   * <p>
   * The zbdd implementation without caching is already very fast. If the same operations on zbdds
   * are performed frequently then adding a cache may help to improve performance. However, if the
   * operations performed are mostly unique (like the 8-queens problem) then adding a cache will
   * reduce the overall performance.
   * <p>
   * Make sure to test your zbdd operations with and without a cache in order to find out whether
   * adding a cache is going to improve performance or not.
   *
   * @param zbddCache  zbdd cache instance or {@code null} to remove a previously assigned cache
   *
   * @since 0.1.3
   */
  @Contract(mutates = "this,param1")
  public void setZbddCache(ZbddCache zbddCache)
  {
    this.zbddCache = zbddCache;

    if (zbddCache != null)
      zbddCache.clear();
  }


  /**
   * Returns the currently assigned zbdd cache implementation.
   *
   * @return  zbdd cache instance or {@code null} if no zbdd cache was assigned
   *
   * @since 0.1.3
   */
  @Contract(pure = true)
  public ZbddCache getZbddCache() {
    return zbddCache;
  }


  @Override
  @Contract(pure = true)
  @SuppressWarnings("MethodDoesntCallSuperMethod")
  public Zbdd clone() {
    return new Zbdd(this);
  }


  /**
   * Returns the literal resolver associated with this zbdd instance.
   *
   * @return  literal resolver, never {@code null}
   */
  @Contract(pure = true)
  public @NotNull ZbddLiteralResolver getLiteralResolver() {
    return literalResolver;
  }


  @Contract(mutates = "this")
  public void setLiteralResolver(@NotNull ZbddLiteralResolver literalResolver) {
    this.literalResolver = requireNonNull(literalResolver);
  }


  /**
   * Returns the statistics for this zbdd instance. The returned object is a singleton and will
   * reflect the actual statistics at any time.
   *
   * @return  statistics, never {@code null}
   */
  @Contract(pure = true)
  public @NotNull ZbddStatistics getStatistics() {
    return statistics;
  }


  /**
   * Clear all nodes from this zbdd instance. If a zbdd cache is assigned it will be cleared as
   * well.
   * <p>
   * This method clears all variables and nodes. It does not free up allocated memory.
   *
   * @see ZbddCache#clear()
   */
  @Contract(mutates = "this")
  public void clear()
  {
    if (zbddCache != null)
      zbddCache.clear();

    lastVarNumber = 0;
    nodesDead = 0;
    nextFreeNode = 2;
    nodesFree = nodesCapacity - 2;

    for(int i = 2; i < nodesCapacity; i++)
    {
      final int offset = i * NODE_RECORD_SIZE;

      nodes[offset + _VAR] = -1;
      nodes[offset + _NEXT] = (i + 1) % nodesCapacity;
      nodes[offset + _CHAIN] = 0;
      nodes[offset + _REFCOUNT] = 0;
    }

    statistics.clear();
  }


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
  public static boolean isEmpty(@Range(from = 0, to = MAX_NODES) int zbdd) {
    return zbdd == ZBDD_EMPTY;
  }


  /**
   * @see #base()
   */
  @Contract(pure = true)
  public static boolean isBase(@Range(from = 0, to = MAX_NODES) int zbdd) {
    return zbdd == ZBDD_BASE;
  }


  /**
   * Create a new literal/variable.
   *
   * @return  variable number
   */
  @Contract(mutates = "this")
  public @Range(from = 1, to = MAX_VALUE) int createVar()
  {
    if (lastVarNumber == MAX_VALUE)
      throw new ZbddException("variable count exceeded");

    return ++lastVarNumber;
  }


  /**
   * Returns the empty zbdd set.
   *
   * @return  empty zbdd set
   *
   * @see #isEmpty(int)
   */
  @Contract(pure = true)
  public final int empty() {
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
  public final int base() {
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
  @Range(from = 0, to = MAX_NODES)
  public int cube(@Range(from = 1, to = MAX_VALUE) int var) {
    return getNode(checkVar(var), ZBDD_EMPTY, ZBDD_BASE);
  }


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
  @Range(from = 0, to = MAX_NODES)
  public int cube(int @NotNull ... cubeVars)
  {
    final int n = cubeVars.length;

    if (n == 0)
      return base();

    if (n >= 2)
      sort(cubeVars = copyOf(cubeVars, n));

    int r = ZBDD_BASE;

    for(int var: cubeVars)
      if (checkVar(var) != getVar(r))
        r = getNode(var, ZBDD_EMPTY, r);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int subset0(@Range(from = 0, to = MAX_NODES) int zbdd, @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd, "zbdd");
    checkVar(var);

    return zbddCache != null ? __subset0_cache(zbdd, var) : __subset0(zbdd, var);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected int __subset0_cache(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return zbdd;

    if (top == var)
      return getP0(zbdd);

    int r = zbddCache.getResult(SUBSET0, zbdd, var);
    if (r == MIN_VALUE)
    {
      __incRef(zbdd);

      final int p0 = __incRef(__subset0_cache(getP0(zbdd), var));
      final int p1 = __subset0_cache(getP1(zbdd), var);

      zbddCache.putResult(SUBSET0, zbdd, var, r = getNode(top, __decRef(p0), p1));

      __decRef(zbdd);
    }

    return r;
  }


  @Contract(mutates = "this")
  protected int __subset0(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return zbdd;

    if (top == var)
      return getP0(zbdd);

    __incRef(zbdd);

    final int p0 = __incRef(__subset0(getP0(zbdd), var));
    final int p1 = __subset0(getP1(zbdd), var);
    final int r = getNode(top, __decRef(p0), p1);

    __decRef(zbdd);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int subset1(@Range(from = 0, to = MAX_NODES) int zbdd, @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd, "zbdd");
    checkVar(var);

    return zbddCache != null ? __subset1_cache(zbdd, var) : __subset1(zbdd, var);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected int __subset1_cache(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return ZBDD_EMPTY;

    if (top == var)
      return getP1(zbdd);

    int r = zbddCache.getResult(SUBSET1, zbdd, var);
    if (r == MIN_VALUE)
    {
      __incRef(zbdd);

      final int p0 = __incRef(__subset1_cache(getP0(zbdd), var));
      final int p1 = __subset1_cache(getP1(zbdd), var);

      zbddCache.putResult(SUBSET1, zbdd, var, r = getNode(top, __decRef(p0), p1));

      __decRef(zbdd);
    }

    return r;
  }


  @Contract(mutates = "this")
  protected int __subset1(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return ZBDD_EMPTY;

    if (top == var)
      return getP1(zbdd);

    __incRef(zbdd);

    final int p0 = __incRef(__subset1(getP0(zbdd), var));
    final int p1 = __subset1(getP1(zbdd), var);
    final int r = getNode(top, __decRef(p0), p1);

    __decRef(zbdd);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int change(@Range(from = 0, to = MAX_NODES) int zbdd, @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd, "zbdd");
    checkVar(var);

    return zbddCache != null ? __change_cache(zbdd, var) : __change(zbdd, var);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected int __change_cache(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return getNode(var, ZBDD_EMPTY, zbdd);

    int r = zbddCache.getResult(CHANGE, zbdd, var);
    if (r == MIN_VALUE)
    {
      __incRef(zbdd);

      if (top == var)
        r = getNode(var, getP1(zbdd), getP0(zbdd));
      else
      {
        final int p0 = __incRef(__change_cache(getP0(zbdd), var));
        final int p1 = __change_cache(getP1(zbdd), var);

        r = getNode(top, __decRef(p0), p1);
      }

      zbddCache.putResult(CHANGE, zbdd, var, r);

      __decRef(zbdd);
    }

    return r;
  }


  @Contract(mutates = "this")
  protected int __change(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return getNode(var, ZBDD_EMPTY, zbdd);

    final int r;

    __incRef(zbdd);

    if (top == var)
      r = getNode(var, getP1(zbdd), getP0(zbdd));
    else
    {
      final int p0 = __incRef(__change(getP0(zbdd), var));
      final int p1 = __change(getP1(zbdd), var);

      r = getNode(top, __decRef(p0), p1);
    }

    __decRef(zbdd);

    return r;
  }


  @Contract(pure = true)
  @Range(from = 0, to = MAX_NODES + 1)
  public int count(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    checkZbdd(zbdd, "zbdd");

    return zbddCache != null ? __count_cache(zbdd) : __count(zbdd);
  }


  /**
   * @since 0.1.3
   */
  @Contract(pure = true)
  protected int __count_cache(int zbdd)
  {
    if (zbdd < 2)
      return zbdd;

    int r = zbddCache.getResult(COUNT, zbdd);
    if (r == MIN_VALUE)
    {
      final int offset = zbdd * NODE_RECORD_SIZE;

      zbddCache.putResult(COUNT, zbdd, r = __count(nodes[offset + _P0]) + __count(nodes[offset + _P1]));
    }

    return r;
  }


  @Contract(pure = true)
  protected int __count(int zbdd)
  {
    if (zbdd < 2)
      return zbdd;

    final int offset = zbdd * NODE_RECORD_SIZE;

    return __count(nodes[offset + _P0]) + __count(nodes[offset + _P1]);
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int union(int... p)
  {
    for(var pn: p)
      __incRef(checkZbdd(pn, "p"));

    var r = p[0];

    for(int i = 1, n = p.length; i < n; i++)
    {
      final var pi = p[i];

      r = zbddCache != null ? __union_cache(r, pi) : __union(r, pi);
    }

    for(var pn: p)
      __decRef(pn);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int union(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __union_cache(p, q) : __union(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected int __union_cache(int p, int q)
  {
    if (q == ZBDD_EMPTY || p == q)
      return p;
    if (p == ZBDD_EMPTY)
      return q;

    int ptop = getVar(p);
    int qtop = getVar(q);

    if (ptop > qtop)
    {
      // swap p <-> q, ptop <-> qtop
      int tmp = p; p = q; q = tmp; tmp = ptop; ptop = qtop; qtop = tmp;
    }

    int r = zbddCache.getResult(UNION, p, q);
    if (r == MIN_VALUE)
    {
      __incRef(p);
      __incRef(q);

      if (ptop < qtop)
        r = getNode(qtop, __union_cache(p, getP0(q)), getP1(q));
      else
      {
        final int p0 = __incRef(__union_cache(getP0(p), getP0(q)));
        final int p1 = __union_cache(getP1(p), getP1(q));

        r = getNode(ptop, __decRef(p0), p1);
      }

      zbddCache.putResult(UNION, p, q, r);

      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  protected int __union(int p, int q)
  {
    if (q == ZBDD_EMPTY || p == q)
      return p;
    if (p == ZBDD_EMPTY)
      return q;

    int ptop = getVar(p);
    int qtop = getVar(q);

    if (ptop > qtop)
    {
      // swap p <-> q, ptop <-> qtop
      int tmp = p; p = q; q = tmp; tmp = ptop; ptop = qtop; qtop = tmp;
    }

    __incRef(p);
    __incRef(q);

    int r;

    if (ptop < qtop)
      r = getNode(qtop, __union(p, getP0(q)), getP1(q));
    else
    {
      final int p0 = __incRef(__union(getP0(p), getP0(q)));
      final int p1 = __union(getP1(p), getP1(q));

      r = getNode(ptop, __decRef(p0), p1);
    }

    __decRef(q);
    __decRef(p);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int intersect(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __intersect_cache(p, q) : __intersect(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected int __intersect_cache(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == q)
      return p;

    int r = zbddCache.getResult(INTERSECT, p, q);
    if (r == MIN_VALUE)
    {
      final int ptop = getVar(p);
      final int qtop = getVar(q);

      __incRef(p);
      __incRef(q);

      if (ptop > qtop)
        r = __intersect_cache(getP0(p), q);
      else if (ptop < qtop)
        r = __intersect_cache(p, getP0(q));
      else
      {
        final int p0 = __incRef(__intersect_cache(getP0(p), getP0(q)));
        final int p1 = __intersect_cache(getP1(p), getP1(q));

        r = getNode(ptop, __decRef(p0), p1);
      }

      zbddCache.putResult(INTERSECT, p, q, r);

      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  protected int __intersect(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == q)
      return p;

    final int ptop = getVar(p);
    final int qtop = getVar(q);
    final int r;

    __incRef(p);
    __incRef(q);

    if (ptop > qtop)
      r = __intersect(getP0(p), q);
    else if (ptop < qtop)
      r = __intersect(p, getP0(q));
    else
    {
      final int p0 = __incRef(__intersect(getP0(p), getP0(q)));
      final int p1 = __intersect(getP1(p), getP1(q));

      r = getNode(ptop, __decRef(p0), p1);
    }

    __decRef(q);
    __decRef(p);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int difference(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __difference_cache(p, q) : __difference(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected int __difference_cache(int p, int q)
  {
    if (p == ZBDD_EMPTY || p == q)
      return ZBDD_EMPTY;
    if (q == ZBDD_EMPTY)
      return p;

    int r = zbddCache.getResult(DIFFERENCE, p, q);
    if (r == MIN_VALUE)
    {
      final int ptop = getVar(p);
      final int qtop = getVar(q);

      __incRef(p);
      __incRef(q);

      if (ptop < qtop)
        r = __difference_cache(p, getP0(q));
      else if (ptop > qtop)
        r = getNode(ptop, __difference_cache(getP0(p), getP0(q)), getP1(p));
      else
      {
        final int p0 = __incRef(__difference_cache(getP0(p), getP0(q)));
        final int p1 = __difference_cache(getP1(p), getP1(q));

        r = getNode(ptop, __decRef(p0), p1);
      }

      zbddCache.putResult(DIFFERENCE, p, q, r);

      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  protected int __difference(int p, int q)
  {
    if (p == ZBDD_EMPTY || p == q)
      return ZBDD_EMPTY;
    if (q == ZBDD_EMPTY)
      return p;

    final int ptop = getVar(p);
    final int qtop = getVar(q);
    final int r;

    __incRef(p);
    __incRef(q);

    if (ptop < qtop)
      r = __difference(p, getP0(q));
    else if (ptop > qtop)
      r = getNode(ptop, __difference(getP0(p), getP0(q)), getP1(p));
    else
    {
      final int p0 = __incRef(__difference(getP0(p), getP0(q)));
      final int p1 = __difference(getP1(p), getP1(q));

      r = getNode(ptop, __decRef(p0), p1);
    }

    __decRef(q);
    __decRef(p);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int multiply(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __multiply_cache(p, q) : __multiply(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected int __multiply_cache(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == ZBDD_BASE)
      return q;
    if (q == ZBDD_BASE)
      return p;

    final int ptop = getVar(p);
    final int qtop = getVar(q);

    if (ptop > qtop)
      return __multiply_cache(q, p);

    int r = zbddCache.getResult(MULTIPLY, p, q);
    if (r == MIN_VALUE)
    {
      __incRef(p);
      __incRef(q);

      // factor P = p0 + v * p1
      final int p0 = __incRef(__subset0_cache(p, ptop));
      final int p1 = __incRef(__subset1_cache(p, ptop));

      // factor Q = q0 + v * q1
      final int q0 = __incRef(__subset0_cache(q, ptop));
      final int q1 = __incRef(__subset1_cache(q, ptop));

      // r = (p0 + v * p1) * (q0 + v * q1) = p0q0 + v * (p0q1 + p1q0 + p1q1)
      final int p0q0 = __incRef(__multiply_cache(p0, q0));
      final int p0q1 = __incRef(__multiply_cache(p0, q1));
      final int p1q0 = __incRef(__multiply_cache(p1, q0));
      final int p1q1 = __incRef(__multiply_cache(p1, q1));

      zbddCache.putResult(MULTIPLY, p, q, r = __union_cache(p0q0,
          __change_cache(__union_cache(__union_cache(p0q1, p1q0), p1q1), ptop)));

      __decRef(p1q1);
      __decRef(p1q0);
      __decRef(p0q1);
      __decRef(p0q0);
      __decRef(q1);
      __decRef(q0);
      __decRef(p1);
      __decRef(p0);
      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  protected int __multiply(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == ZBDD_BASE)
      return q;
    if (q == ZBDD_BASE)
      return p;

    final int ptop = getVar(p);
    final int qtop = getVar(q);

    if (ptop > qtop)
      return __multiply(q, p);

    __incRef(p);
    __incRef(q);

    // factor P = p0 + v * p1
    final int p0 = __incRef(__subset0(p, ptop));
    final int p1 = __incRef(__subset1(p, ptop));

    // factor Q = q0 + v * q1
    final int q0 = __incRef(__subset0(q, ptop));
    final int q1 = __incRef(__subset1(q, ptop));

    // r = (p0 + v * p1) * (q0 + v * q1) = p0q0 + v * (p0q1 + p1q0 + p1q1)
    final int p0q0 = __incRef(__multiply(p0, q0));
    final int p0q1 = __incRef(__multiply(p0, q1));
    final int p1q0 = __incRef(__multiply(p1, q0));
    final int p1q1 = __incRef(__multiply(p1, q1));
    final int r = __union(p0q0, __change(__union(__union(p0q1, p1q0), p1q1), ptop));

    __decRef(p1q1);
    __decRef(p1q0);
    __decRef(p0q1);
    __decRef(p0q0);
    __decRef(q1);
    __decRef(q0);
    __decRef(p1);
    __decRef(p0);
    __decRef(q);
    __decRef(p);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int divide(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __divide_cache(p, q) : __divide(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected int __divide_cache(int p, int q)
  {
    if (p < 2)
      return ZBDD_EMPTY;
    if (p == q)
      return ZBDD_BASE;
    if (q == ZBDD_BASE)
      return p;

    int r = zbddCache.getResult(DIVIDE, p, q);
    if (r == MIN_VALUE)
    {
      __incRef(p);
      __incRef(q);

      final int v = getVar(q);

      // factor P = p0 + v * p1
      final int p0 = __incRef(__subset0_cache(p, v));
      final int p1 = __incRef(__subset1_cache(p, v));

      // factor Q = q0 + v * q1
      final int q0 = __incRef(__subset0_cache(q, v));
      final int q1 = __subset1_cache(q, v);

      final int r1 = __divide_cache(__decRef(p1), q1);

      if (r1 != ZBDD_EMPTY && q0 != ZBDD_EMPTY)
      {
        __incRef(r1);

        final int r0 = __divide_cache(p0, q0);

        r = __intersect_cache(__decRef(r1), r0);
      }
      else
        r = r1;

      zbddCache.putResult(DIVIDE, p, q, r);

      __decRef(q0);
      __decRef(p0);
      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  protected int __divide(int p, int q)
  {
    if (p < 2)
      return ZBDD_EMPTY;
    if (p == q)
      return ZBDD_BASE;
    if (q == ZBDD_BASE)
      return p;

    __incRef(p);
    __incRef(q);

    final int v = getVar(q);

    // factor P = p0 + v * p1
    final int p0 = __incRef(__subset0(p, v));
    final int p1 = __incRef(__subset1(p, v));

    // factor Q = q0 + v * q1
    final int q0 = __incRef(__subset0(q, v));
    final int q1 = __subset1(q, v);

    final int r1 = __divide(__decRef(p1), q1);
    final int r;

    if (r1 != ZBDD_EMPTY && q0 != ZBDD_EMPTY)
    {
      __incRef(r1);

      final int r0 = __divide(p0, q0);

      r = __intersect(__decRef(r1), r0);
    }
    else
      r = r1;

    __decRef(q0);
    __decRef(p0);
    __decRef(q);
    __decRef(p);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int modulo(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return zbddCache != null ? __modulo_cache(p, q) : __modulo(p, q);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected int __modulo_cache(int p, int q)
  {
    int r = zbddCache.getResult(MODULO, p, q);
    if (r == MIN_VALUE)
    {
      __incRef(p);
      __incRef(q);

      zbddCache.putResult(MODULO, p, q, r = __difference_cache(p, __multiply_cache(q, __divide_cache(p, q))));

      __decRef(q);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  protected int __modulo(int p, int q)
  {
    __incRef(p);
    __incRef(q);

    final int r = __difference(p, __multiply(q, __divide(p, q)));

    __decRef(q);
    __decRef(p);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int atomize(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    checkZbdd(zbdd, "zbdd");

    return zbddCache != null ? __atomize_cache(zbdd) : __atomize(zbdd);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected int __atomize_cache(int zbdd)
  {
    if (zbdd < 2)
      return ZBDD_EMPTY;

    int r = zbddCache.getResult(ATOMIZE, zbdd);
    if (r == MIN_VALUE)
    {
      __incRef(zbdd);

      final int p0 = __incRef(__atomize_cache(getP0(zbdd)));
      final int p1 = __atomize_cache(getP1(zbdd));

      zbddCache.putResult(ATOMIZE, zbdd, r = getNode(getVar(zbdd), __union_cache(__decRef(p0), p1), ZBDD_BASE));

      __decRef(zbdd);
    }

    return r;
  }


  @Contract(mutates = "this")
  protected int __atomize(int zbdd)
  {
    if (zbdd < 2)
      return ZBDD_EMPTY;

    __incRef(zbdd);

    final int p0 = __incRef(__atomize(getP0(zbdd)));
    final int p1 = __atomize(getP1(zbdd));
    final int r = getNode(getVar(zbdd), __union(__decRef(p0), p1), ZBDD_BASE);

    __decRef(zbdd);

    return r;
  }




  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int removeBase(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    checkZbdd(zbdd, "zbdd");

    return zbddCache != null ? __removeBase_cache(zbdd) : __removeBase(zbdd);
  }


  /**
   * @since 0.1.3
   */
  @Contract(mutates = "this")
  protected int __removeBase_cache(int zbdd)
  {
    if (zbdd < 2)
      return ZBDD_EMPTY;

    int r = zbddCache.getResult(REMOVE_BASE, zbdd);
    if (r == MIN_VALUE)
    {
      __incRef(zbdd);

      zbddCache.putResult(REMOVE_BASE, zbdd, r = getNode(getVar(zbdd), __removeBase_cache(getP0(zbdd)), getP1(zbdd)));

      __decRef(zbdd);
    }

    return r;
  }


  @Contract(mutates = "this")
  protected int __removeBase(int zbdd)
  {
    if (zbdd < 2)
      return ZBDD_EMPTY;

    __incRef(zbdd);

    final int r = getNode(getVar(zbdd), __removeBase(getP0(zbdd)), getP1(zbdd));

    __decRef(zbdd);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public boolean contains(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q) {
    return __contains(checkZbdd(p, "p"), checkZbdd(q, "q"));
  }


  @Contract(mutates = "this")
  protected boolean __contains(int p, int q)
  {
    return p != ZBDD_EMPTY && q != ZBDD_EMPTY &&
        (p == q || (zbddCache != null ? __intersect_cache(p, q) : __intersect(p, q)) == q);
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  protected int getNode(@Range(from = 1, to = MAX_VALUE) int var,
                        @Range(from = 0, to = MAX_NODES) int p0,
                        @Range(from = 0, to = MAX_NODES) int p1)
  {
    statistics.nodeLookups++;

    // suppress 0's
    if (p1 == ZBDD_EMPTY)
    {
      statistics.nodeLookupHitCount++;
      return p0;
    }

    int hash = hash(var, p0, p1);

    // find node in chain...
    for(int r = nodes[hash * NODE_RECORD_SIZE + _CHAIN]; r != 0;)
    {
      final int offset = r * NODE_RECORD_SIZE;

      if (nodes[offset + _VAR] == var && nodes[offset + _P0] == p0 && nodes[offset + _P1] == p1)
      {
        statistics.nodeLookupHitCount++;
        return r;
      }

      r = nodes[offset + _NEXT];
    }

    // increase number of free nodes if there are not enough available
    if (nodesFree < 2)
    {
      __incRef(p0);
      __incRef(p1);

      ensureCapacity();

      __decRef(p1);
      __decRef(p0);

      if (nodesFree == 0)
        throw new ZbddException("nodes capacity exhausted");

      // may have changed due to nodes capacity increase
      hash = hash(var, p0, p1);
    }

    final int r = nextFreeNode;
    final int offset = r * NODE_RECORD_SIZE;
    nextFreeNode = nodes[offset + _NEXT];
    nodesFree--;

    // set new node
    nodes[offset + _VAR] = var;
    nodes[offset + _P0] = p0;
    nodes[offset + _P1] = p1;
    nodes[offset + _REFCOUNT] = -1;

    prependHashChain(r, hash);

    return r;
  }


  @Contract(pure = true)
  protected int getVar(int zbdd) {
    return zbdd < 2 ? -1 : nodes[zbdd * NODE_RECORD_SIZE + _VAR];
  }


  @Contract(pure = true)
  @Range(from = 0, to = MAX_NODES)
  protected int getP0(int zbdd) {
    return nodes[zbdd * NODE_RECORD_SIZE + _P0];
  }


  @Contract(pure = true)
  @Range(from = 0, to = MAX_NODES)
  protected int getP1(int zbdd) {
    return nodes[zbdd * NODE_RECORD_SIZE + _P1];
  }


  @Contract(pure = true)
  @Range(from = 0, to = MAX_NODES)
  protected int hash(int var, int p0, int p1) {
    return ((var * 12582917 + p0 * 4256249 + p1 * 741457) & MAX_VALUE) % nodesCapacity;
  }


  @Range(from = 0, to = MAX_NODES - 2)
  @SuppressWarnings("UnusedReturnValue")
  public int gc()
  {
    if (zbddCache != null)
      zbddCache.clear();

    statistics.gcCount++;

    // mark referenced nodes...
    for(int i = nodesCapacity, offset = (i - 1) * NODE_RECORD_SIZE; i-- > 0; offset -= NODE_RECORD_SIZE)
    {
      if (nodes[offset + _VAR] != -1 && nodes[offset + _REFCOUNT] > 0)
        gc_markTree(i);

      nodes[offset + _CHAIN] = 0;
    }

    final int oldNodesFree = nodesFree;
    nextFreeNode = nodesFree = 0;

    for(int i = nodesCapacity, offset = (i - 1) * NODE_RECORD_SIZE; i-- > 2; offset -= NODE_RECORD_SIZE)
    {
      if ((nodes[offset + _VAR] & GC_VAR_MARK_MASK) != 0 && nodes[offset + _VAR] != -1)
      {
        // remove mark and chain valid node
        prependHashChain(i,
            hash(nodes[offset + _VAR] &= ~GC_VAR_MARK_MASK, nodes[offset + _P0], nodes[offset + _P1]));
      }
      else
      {
        // garbage collect node
        nodes[offset + _VAR] = -1;
        nodes[offset + _NEXT] = nextFreeNode;
        nodes[offset + _REFCOUNT] = 0;

        nextFreeNode = i;
        nodesFree++;
      }
    }

    nodesDead = 0;

    final int gcFreedNodesCount = nodesFree - oldNodesFree;
    statistics.gcFreedNodes += gcFreedNodesCount;

    return gcFreedNodesCount;
  }


  private void gc_markTree(int zbdd)
  {
    if (zbdd >= 2)
    {
      final int offset = zbdd * NODE_RECORD_SIZE;

      if ((nodes[offset + _VAR] & GC_VAR_MARK_MASK) == 0)
      {
        nodes[offset + _VAR] |= GC_VAR_MARK_MASK;

        gc_markTree(nodes[offset + _P0]);
        gc_markTree(nodes[offset + _P1]);
      }
    }
  }


  @Contract(mutates = "this")
  protected void ensureCapacity()
  {
    if (nodesDead > 0 && capacityAdvisor.isGCRequired(statistics))
    {
      gc();
      if (nodesFree >= capacityAdvisor.getMinimumFreeNodes(statistics))
        return;
    }

    final int oldNodesCapacity = nodesCapacity;

    nodesCapacity = min(nodesCapacity + capacityAdvisor.adviseIncrement(statistics), MAX_NODES);
    nodes = copyOf(nodes, nodesCapacity * NODE_RECORD_SIZE);

    nextFreeNode = 0;
    nodesFree = nodesCapacity - oldNodesCapacity;

    // initialize new nodes
    for(int i = nodesCapacity, offset = (i - 1) * NODE_RECORD_SIZE; i-- > oldNodesCapacity; offset -= NODE_RECORD_SIZE)
    {
      nodes[offset + _VAR] = -1;
      nodes[offset + _NEXT] = nextFreeNode;

      nextFreeNode = i;
    }

    // unchain old nodes
    for(int i = 0, end = oldNodesCapacity * NODE_RECORD_SIZE; i < end; i += NODE_RECORD_SIZE)
      nodes[i + _CHAIN] = 0;

    // re-chain old nodes
    for(int i = oldNodesCapacity, offset = (i - 1) * NODE_RECORD_SIZE; i-- > 2; offset -= NODE_RECORD_SIZE)
    {
      if (nodes[offset + _VAR] != -1)
        prependHashChain(i, hash(nodes[offset + _VAR], nodes[offset + _P0], nodes[offset + _P1]));
      else
      {
        nodes[offset + _NEXT] = nextFreeNode;
        nodes[offset + _REFCOUNT] = 0;

        nextFreeNode = i;
        nodesFree++;
      }
    }
  }


  private void prependHashChain(int zbdd, int hash)
  {
    final int hashChain = hash * NODE_RECORD_SIZE + _CHAIN;

    nodes[zbdd * NODE_RECORD_SIZE + _NEXT] = nodes[hashChain];
    nodes[hashChain] = zbdd;
  }


  @Contract(value = "_ -> param1", mutates = "this")
  public int incRef(@Range(from = 0, to = MAX_NODES) int zbdd) {
    return __incRef(checkZbdd(zbdd, "zbdd"));
  }


  @Contract(value = "_ -> param1", mutates = "this")
  protected int __incRef(int zbdd)
  {
    final int offset;

    if (zbdd >= 2 && nodes[(offset = zbdd * NODE_RECORD_SIZE) + _VAR] != -1)
    {
      final int refCountOffset = offset + _REFCOUNT;
      final int ref = nodes[refCountOffset];

      if (ref == -1)  // new node
        nodes[refCountOffset] = 1;
      else
      {
        if (ref == 0)
          nodesDead--;

        nodes[refCountOffset] = ref + 1;
      }
    }

    return zbdd;
  }


  @Contract(value = "_ -> param1", mutates = "this")
  @SuppressWarnings("UnusedReturnValue")
  public int decRef(@Range(from = 0, to = MAX_NODES) int zbdd) {
    return __decRef(checkZbdd(zbdd, "zbdd"));
  }


  @Contract(value = "_ -> param1", mutates = "this")
  protected int __decRef(int zbdd)
  {
    final int offset;

    if (zbdd >= 2 && nodes[(offset = zbdd * NODE_RECORD_SIZE) + _VAR] != -1)
    {
      final int refCountOffset = offset + _REFCOUNT;
      final int ref = nodes[refCountOffset];

      if (ref > 0)
      {
        if (ref == 1)
          nodesDead++;

        nodes[refCountOffset] = ref - 1;
      }
    }

    return zbdd;
  }


  @Contract(value = "_, _ -> param1")
  @MustBeInvokedByOverriders
  protected int checkZbdd(int zbdd, @NotNull String param)
  {
    if (zbdd < 0 || zbdd >= nodesCapacity)
      throw new ZbddException(param + " must be in range 0.." + (nodesCapacity - 1));

    if (zbdd >= 2 && nodes[zbdd * NODE_RECORD_SIZE + _VAR] == -1)
      throw new ZbddException("invalid " + param + " node " + zbdd);

    return zbdd;
  }


  @Contract(value = "_ -> param1")
  @MustBeInvokedByOverriders
  protected int checkVar(int var)
  {
    if (var <= 0 || var > lastVarNumber)
      throw new ZbddException("var must be in range 1.." + var);

    return var;
  }


  @Contract(value = "_ -> new", pure = true)
  public @NotNull String toString(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    final var s = new StringJoiner(", ", "{ ", " }");

    visitCubes(zbdd, cube -> s.add(literalResolver.getCubeName(cube)));

    return s.toString();
  }


  public void visitCubes(@Range(from = 0, to = MAX_NODES) int zbdd, @NotNull CubeVisitor visitor)
  {
    __incRef(checkZbdd(zbdd, "zbdd"));
    try {
      visitCubes0(visitor, new CubeVisitorStack(lastVarNumber), zbdd);
    } finally {
      __decRef(zbdd);
    }
  }


  @Contract(mutates = "param2")
  private void visitCubes0(@NotNull CubeVisitor visitor, @NotNull CubeVisitorStack vars, int zbdd)
  {
    if (zbdd == ZBDD_BASE)
      visitor.visitCube(vars.getCube());
    else if (zbdd != ZBDD_EMPTY)
    {
      final int offset = zbdd * NODE_RECORD_SIZE;

      // walk 1-branch
      vars.push(nodes[offset + _VAR]);
      visitCubes0(visitor, vars, nodes[offset + _P1]);
      vars.pop();

      // walk 0-branch
      visitCubes0(visitor, vars, nodes[offset + _P0]);
    }
  }




  /**
   * Cube visitor interface to be used with {@link #visitCubes(int, CubeVisitor)}.
   */
  @FunctionalInterface
  public interface CubeVisitor
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




  private static final class CubeVisitorStack
  {
    private final int[] stack;
    private int stackSize;


    private CubeVisitorStack(int size) {
      stack = new int[size];
    }


    private void push(int value) {
      stack[stackSize++] = value;
    }


    private void pop() {
      stackSize--;
    }


    private int @NotNull [] getCube() {
      return copyOf(stack, stackSize);
    }
  }




  private final class Statistics implements ZbddStatistics
  {
    private int nodeLookups;
    private int nodeLookupHitCount;
    private int gcCount;
    private long gcFreedNodes;


    private Statistics() {
    }


    private void clear()
    {
      nodeLookups = 0;
      nodeLookupHitCount = 0;
      gcCount = 0;
      gcFreedNodes = 0;
    }


    @Override
    public int getNodesCapacity() {
      return nodesCapacity;
    }


    @Override
    public int getFreeNodes() {
      return nodesFree;
    }


    @Override
    public int getDeadNodes() {
      return nodesDead;
    }


    @Override
    public int getNodeLookups() {
      return nodeLookups;
    }


    @Override
    public int getNodeLookupHitCount() {
      return nodeLookupHitCount;
    }


    @Override
    public int getGCCount() {
      return gcCount;
    }


    @Override
    public long getGCFreedNodes() {
      return gcFreedNodes;
    }


    @Override
    public long getMemoryUsage() {
      return nodes.length * 4L;
    }


    @Override
    public int getRegisteredVars() {
      return lastVarNumber;
    }


    @Override
    public String toString()
    {
      return "Statistics(node={capacity=" + getNodesCapacity() + ", occupied=" + getOccupiedNodes() +
          ", free=" + getFreeNodes() + ", dead=" + getDeadNodes() + "}, hitRatio=" +
          round(getNodeLookupHitRatio() * 1000) / 10.0 + "%, gcCount=" + getGCCount() +
          ", mem=" + String.format(ROOT, "%.1fKB", getMemoryUsage() / 1024.0) +
          ")";
    }
  }




  private enum DefaultCapacityAdvisor implements ZbddCapacityAdvisor
  {
    INSTANCE;


    @Override
    public @Range(from = 4, to = MAX_NODES) int getInitialCapacity() {
      return 128;
    }


    @Override
    public @Range(from = 1, to = MAX_NODES) int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) {
      return statistics.getNodesCapacity() / 20;  // 5%
    }


    @Override
    public int adviseIncrement(@NotNull ZbddStatistics statistics)
    {
      final int capacity = statistics.getNodesCapacity();

      // size < 500000 -> increase by 150%
      // size > 500000 -> increase by 30%
      return capacity < 500000 ? capacity * 3 / 2 : (capacity / 10) * 3;
    }


    @Override
    public boolean isGCRequired(@NotNull ZbddStatistics statistics)
    {
      final int capacity = statistics.getNodesCapacity();

      // capacity > 250000
      // dead nodes > 10% of total capacity
      return capacity > 250000 || statistics.getDeadNodes() > (capacity / 10);
    }
  }
}
