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
package de.sayayi.lib.zbdd.internal;

import de.sayayi.lib.zbdd.Zbdd;
import de.sayayi.lib.zbdd.ZbddCapacityAdvisor;
import de.sayayi.lib.zbdd.ZbddLiteralResolver;
import de.sayayi.lib.zbdd.ZbddStatistics;
import de.sayayi.lib.zbdd.cache.ZbddCache;
import de.sayayi.lib.zbdd.exception.InvalidVarException;
import de.sayayi.lib.zbdd.exception.InvalidZbddException;
import de.sayayi.lib.zbdd.exception.ZbddException;
import de.sayayi.lib.zbdd.exception.ZbddOutOfRangeException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import static java.lang.Boolean.TRUE;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.Math.*;
import static java.lang.System.arraycopy;
import static java.util.Arrays.copyOf;
import static java.util.Arrays.sort;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;


/**
 * Core implementation of the {@link Zbdd} interface, providing the fundamental operations on Zero-suppressed
 * Binary Decision Diagrams.
 * <p>
 * This class is not thread-safe. Use {@link de.sayayi.lib.zbdd.ZbddFactory#asConcurrent(Zbdd)} to obtain a
 * thread-safe wrapper.
 * <p>
 * Instances are created through {@link de.sayayi.lib.zbdd.ZbddFactory#create() ZbddFactory.create}.
 * <p>
 * Nodes are stored as fixed-size records in a flat {@code int[]} array. Each node number addresses one record:
 * <pre>
 * offset  field      meaning
 *   +0    _VAR       variable number (high bit temporarily marks a node during GC)
 *   +1    _P0        0-branch node
 *   +2    _P1        1-branch node
 *   +3    _NEXT      free-list or hash-chain link
 *   +4    _CHAIN     head of the hash chain for this bucket
 *   +5    _REFCOUNT  reference count for an allocated node
 * </pre>
 * The record size and offsets below are measured in {@code int}s.
 *
 * @author Jeroen Gremmen
 */
@ApiStatus.Internal
@SuppressWarnings("DuplicatedCode")
public sealed class ZbddImpl implements Zbdd permits ZbddCachedImpl
{
  private static final int GC_VAR_MARK_MASK = 0x8000_0000;  // high-bit mark in _VAR during GC
  private static final int NODE_RECORD_SIZE = 6;            // ints per node record

  /** Maximum number of nodes supported by a single ZBDD instance. */
  public static final int MAX_NODES = MAX_VALUE / NODE_RECORD_SIZE;

  private static final int _VAR = 0;       // variable number
  private static final int _P0 = 1;        // 0-branch node
  private static final int _P1 = 2;        // 1-branch node
  private static final int _NEXT = 3;      // free-list or hash-chain link
  private static final int _CHAIN = 4;     // hash-bucket chain head
  private static final int _REFCOUNT = 5;  // allocated-node reference count

  private final @NotNull ZbddCapacityAdvisor capacityAdvisor;
  private final @NotNull Statistics statistics;
  private final @NotNull List<ZbddCallback> callbacks;
  private final @NotNull IntObjectMap varObjectMap;

  /**
   * Nodes protected from garbage collection by currently running zbdd operations. Each operation saves the stack
   * size on entry and truncates the stack to that size on exit, so an exception never leaves nodes protected.
   */
  private final @NotNull IntStack protectedNodes;

  private int lastVarNumber;

  private int nodesCapacity;
  private int nodesFree;
  private int nodesDead;
  private int nodesNew;

  private int @NotNull [] nodes;
  private int nextFreeNode;

  private @NotNull ZbddLiteralResolver literalResolver = var -> "v" + var;


  /**
   * Creates a new ZBDD instance with the given capacity advisor.
   *
   * @param capacityAdvisor  capacity advisor, not {@code null}
   */
  public ZbddImpl(@NotNull ZbddCapacityAdvisor capacityAdvisor)
  {
    this.capacityAdvisor = capacityAdvisor;

    //noinspection ConstantConditions
    nodesCapacity = max(capacityAdvisor.getInitialCapacity(), 8);
    nodes = new int[nodesCapacity * NODE_RECORD_SIZE];

    initTerminalNode(EMPTY);
    initTerminalNode(BASE);

    statistics = new Statistics();
    callbacks = new ArrayList<>();
    varObjectMap = new IntObjectMap();
    protectedNodes = new IntStack(24);

    clearInternal();
  }


  private void initTerminalNode(int zbdd)
  {
    final var offset = zbdd * NODE_RECORD_SIZE;

    nodes[offset + _VAR] = -1;
    nodes[offset + _P0] = zbdd;
    nodes[offset + _P1] = zbdd;
    nodes[offset + _REFCOUNT] = 1;
  }


  /** {@inheritDoc} */
  @Override
  public void registerCallback(@NotNull ZbddCallback callback) {
    callbacks.add(requireNonNull(callback));
  }


  /**
   * Returns the literal resolver associated with this ZBDD instance.
   *
   * @return  literal resolver, never {@code null}
   */
  @Override
  @Contract(pure = true)
  public @NotNull ZbddLiteralResolver getLiteralResolver() {
    return literalResolver;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public void setLiteralResolver(@NotNull ZbddLiteralResolver literalResolver) {
    this.literalResolver = requireNonNull(literalResolver);
  }


  /**
   * Returns the statistics for this ZBDD instance. The returned object is a singleton and will
   * reflect the actual statistics at any time.
   *
   * @return  statistics, never {@code null}
   */
  @Override
  @Contract(pure = true)
  public @NotNull ZbddStatistics getStatistics() {
    return statistics;
  }


  /**
   * Clears all nodes from this ZBDD instance. If a ZBDD cache is assigned it will be cleared as well.
   * <p>
   * This method clears all variables and nodes. It does not free up allocated memory.
   *
   * @see ZbddCache#clear()
   */
  @Override
  @Contract(mutates = "this")
  @MustBeInvokedByOverriders
  public void clear()
  {
    callbacks.forEach(ZbddCallback::beforeClear);
    clearInternal();
    callbacks.forEach(ZbddCallback::afterClear);
  }


  /**
   * Resets all variables, nodes and statistics without invoking any callbacks.
   *
   * @since 0.8.0
   */
  private void clearInternal()
  {
    lastVarNumber = 0;
    nodesDead = 0;
    nodesNew = 0;
    nextFreeNode = 2;
    nodesFree = nodesCapacity - 2;

    // clear the hash chain heads for zbdd < 2
    nodes[EMPTY * NODE_RECORD_SIZE + _CHAIN] = 0;
    nodes[BASE * NODE_RECORD_SIZE + _CHAIN] = 0;

    for(int zbdd = 2, offset = 2 * NODE_RECORD_SIZE; zbdd++ < nodesCapacity; offset += NODE_RECORD_SIZE)
    {
      nodes[offset + _VAR] = -1;
      nodes[offset + _NEXT] = zbdd == nodesCapacity ? -1 : zbdd;
      nodes[offset + _CHAIN] = 0;
    }

    varObjectMap.clear();
    protectedNodes.truncate(0);
    statistics.clear();
  }


  /**
   * Create a new literal/variable.
   *
   * @return  variable number >= 1
   */
  @Override
  @Contract(mutates = "this")
  @MustBeInvokedByOverriders
  public int createVar()
  {
    if (lastVarNumber == MAX_VALUE)
      throw new ZbddException("variable count exceeded");

    return ++lastVarNumber;
  }


  /** {@inheritDoc} */
  @Override
  public int createVar(@NotNull Object varObject)
  {
    requireNonNull(varObject);

    final var var = createVar();
    varObjectMap.put(var, varObject);

    return var;
  }


  /** {@inheritDoc} */
  @Override
  @SuppressWarnings("unchecked")
  public <T> T getVarObject(int var) {
    return (T)varObjectMap.get(checkVar(var));
  }


  /**
   * Returns a ZBDD set with the given {@code var} as its only element.
   * <p>
   * Example:
   * <pre>
   *   Zbdd zbdd = ZbddFactory.create();
   *   int v1 = zbdd.createVar();
   *   int singleton = zbdd.cube(v1);
   *   String s = zbdd.toString(singleton);  // = "{ v1 }"
   * </pre>
   *
   * @param var  valid variable
   *
   * @return  ZBDD set with {@code var} as its only element
   */
  @Override
  @Contract(mutates = "this")
  public int cube(int var)
  {
    final var mark = protectedNodes.size();
    try {
      return __getNode(checkVar(var), EMPTY, BASE);
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Returns a ZBDD set with the given {@code vars} combined as its only element.
   * <p>
   * Example:
   * <pre>
   *   Zbdd zbdd = ZbddFactory.create();
   *   int v1 = zbdd.createVar();
   *   int v2 = zbdd.createVar();
   *   int v3 = zbdd.createVar();
   *   int singleton = zbdd.cube(v1, v3);
   *   String s = zbdd.toString(singleton);  // = "{ v1.v3 }"
   * </pre>
   *
   * @param cubeVars  valid variables
   *
   * @return  ZBDD set with {@code cubeVars} as its only element
   */
  @Override
  @Contract(mutates = "this")
  public int cube(int @NotNull ... cubeVars)
  {
    final var n = cubeVars.length;
    var r = BASE;

    if (n > 0)
    {
      if (n >= 2)
        sort(cubeVars = copyOf(cubeVars, n));

      final var mark = protectedNodes.size();
      try {
        for(int i = 0, topVar = -1, var; i < n; i++)
          if ((var = cubeVars[i]) != topVar)
            r = __getNode(topVar = checkVar(var), EMPTY, r);
      } finally {
        protectedNodes.truncate(mark);
      }
    }

    return r;
  }


  /**
   * {@inheritDoc}
   *
   * @since 0.3.1
   */
  @Override
  @Contract(pure = true)
  public boolean hasCubeWithVar(int zbdd, int var) {
    return __hasCubeWithVar(checkZbdd(zbdd, "zbdd"), checkVar(var));
  }


  /**
   * Internal implementation of {@link #hasCubeWithVar(int, int)} without parameter validation.
   * <p>
   * The nodes above {@code var} are traversed depth-first. Each node is visited at most once, so the running time is
   * linear in the number of nodes and not in the (potentially exponential) number of paths.
   *
   * @since 0.3.1
   */
  @Contract(pure = true)
  protected final boolean __hasCubeWithVar(final int zbdd, final int var)
  {
    if (zbdd >= 2)
    {
      final var zbddVar = __getVar(zbdd);
      if (zbddVar == var)
        return true;

      if (zbddVar > var)
      {
        final var stack = new IntStack((zbddVar - var) * 2);
        final var visited = new IntObjectMap();

        visited.put(stack.push(zbdd), TRUE);

        do {
          final var offset = stack.pop() * NODE_RECORD_SIZE;
          final var currentVar = nodes[offset + _VAR];

          if (currentVar == var)
            return true;

          if (currentVar > var)
          {
            __hasCubeWithVar_pushUnvisited(stack, visited, nodes[offset + _P0]);
            __hasCubeWithVar_pushUnvisited(stack, visited, nodes[offset + _P1]);
          }
        } while(stack.isNotEmpty());
      }
    }

    return false;
  }


  /**
   * Pushes the given {@code zbdd} onto the {@code stack} and records it as visited, unless it is a terminal node or
   * has been visited before.
   *
   * @param stack    traversal stack, not {@code null}
   * @param visited  visited nodes, not {@code null}
   * @param zbdd     zbdd node
   */
  private static void __hasCubeWithVar_pushUnvisited(@NotNull IntStack stack, @NotNull IntObjectMap visited, int zbdd)
  {
    if (zbdd >= 2 && visited.get(zbdd) == null)
      visited.put(stack.push(zbdd), TRUE);
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int subset0(int zbdd, int var)
  {
    final var mark = protectedNodes.size();
    try {
      return __subset0(checkZbdd(zbdd, "zbdd"), checkVar(var));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #subset0(int, int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __subset0(int zbdd, int var)
  {
    final int top = __getVar(zbdd);

    if (top < var)
      return zbdd;

    if (top == var)
      return __getP0(zbdd);

    final var mark = saveAndProtect(zbdd);

    final var p0 = protect(__subset0(__getP0(zbdd), var));
    final var p1 = __subset0(__getP1(zbdd), var);
    final var r = __getNode(top, p0, p1);

    protectedNodes.truncate(mark);

    return r;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int subset1(int zbdd, int var)
  {
    final var mark = protectedNodes.size();
    try {
      return __subset1(checkZbdd(zbdd, "zbdd"), checkVar(var));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #subset1(int, int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __subset1(int zbdd, int var)
  {
    final var top = __getVar(zbdd);

    if (top < var)
      return EMPTY;

    if (top == var)
      return __getP1(zbdd);

    final var mark = saveAndProtect(zbdd);

    final var p0 = protect(__subset1(__getP0(zbdd), var));
    final var p1 = __subset1(__getP1(zbdd), var);
    final var r = __getNode(top, p0, p1);

    protectedNodes.truncate(mark);

    return r;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int change(int zbdd, int var)
  {
    final var mark = protectedNodes.size();
    try {
      return __change(checkZbdd(zbdd, "zbdd"), checkVar(var));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #change(int, int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __change(final int zbdd, final int var)
  {
    final var top = __getVar(zbdd);

    if (top < var)
      return __getNode(var, EMPTY, zbdd);

    final var mark = saveAndProtect(zbdd);
    final int r;

    if (top == var)
      r = __getNode(var, __getP1(zbdd), __getP0(zbdd));
    else
    {
      final var p0 = protect(__change(__getP0(zbdd), var));
      final var p1 = __change(__getP1(zbdd), var);

      r = __getNode(top, p0, p1);
    }

    protectedNodes.truncate(mark);

    return r;
  }


  /**
   * Returns the number of cubes in the given ZBDD.
   *
   * @param zbdd  valid ZBDD node
   *
   * @return  number of cubes &ge; 0
   */
  @Override
  @Contract(pure = true)
  public long count(int zbdd) {
    return __count(checkZbdd(zbdd, "zbdd"));
  }


  /**
   * Internal implementation of {@link #count(int)} without parameter validation.
   * <p>
   * Counts are computed bottom-up and memoized per distinct node.
   */
  @Contract(pure = true)
  protected long __count(int zbdd)
  {
    if (zbdd < 2)
      return zbdd;

    final var zbddCountMap = new IntObjectMap();
    final var stack = new IntStack(__getVar(zbdd) + 1);

    stack.push(zbdd);

    do {
      final var z = stack.pop();

      if (zbddCountMap.get(z) == null)
      {
        final var offset = z * NODE_RECORD_SIZE;
        final var p0 = nodes[offset + _P0];
        final var p1 = nodes[offset + _P1];
        final var p0Count = p0 < 2 ? Long.valueOf(p0) : (Long)zbddCountMap.get(p0);
        final var p1Count = p1 < 2 ? Long.valueOf(p1) : (Long)zbddCountMap.get(p1);

        if (p0Count != null && p1Count != null)
          zbddCountMap.put(z, p0Count + p1Count);
        else
        {
          stack.push(z);

          if (p0Count == null)
            stack.push(p0);

          if (p1Count == null)
            stack.push(p1);
        }
      }
    } while(stack.isNotEmpty());

    return (Long)zbddCountMap.get(zbdd);
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int union(int... p)
  {
    final var n = p.length;
    if (n == 0)
      throw new IllegalArgumentException("at least one ZBDD node must be provided");

    // check validity first before protecting nodes
    for(var i = 0; i < n; i++)
      checkZbdd(p[i], "p[" + i + ']');

    final var mark = protectedNodes.size();
    try {
      for(var pn: p)
        protect(pn);

      var r = p[0];

      for(var i = 1; i < n; i++)
        r = __union(r, p[i]);

      return r;
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int union(int p, int q)
  {
    final var mark = protectedNodes.size();
    try {
      return __union(checkZbdd(p, "p"), checkZbdd(q, "q"));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #union(int, int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __union(int p, int q)
  {
    if (q == EMPTY || p == q)
      return p;
    if (p == EMPTY)
      return q;

    var p_var = __getVar(p);
    var q_var = __getVar(q);

    if (p_var > q_var)
    {
      // swap p <-> q, p_var <-> q_var
      var tmp = p; p = q; q = tmp;
      tmp = p_var; p_var = q_var; q_var = tmp;
    }

    final var mark = saveAndProtect(p, q);
    final int r;

    if (p_var < q_var)
    {
      final var p0 = __union(p, __getP0(q));

      r = __getNode(q_var, p0, __getP1(q));
    }
    else
    {
      // p_var = q_var
      final var p0 = protect(__union(__getP0(p), __getP0(q)));
      final var p1 = __union(__getP1(p), __getP1(q));

      r = __getNode(p_var, p0, p1);
    }

    protectedNodes.truncate(mark);

    return r;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int intersect(int p, int q)
  {
    final var mark = protectedNodes.size();
    try {
      return __intersect(checkZbdd(p, "p"), checkZbdd(q, "q"));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #intersect(int, int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __intersect(int p, int q)
  {
    if (p == EMPTY || q == EMPTY)
      return EMPTY;
    if (p == q)
      return p;

    final var p_var = __getVar(p);
    final var q_var = __getVar(q);
    final int r;

    final var mark = saveAndProtect(p, q);

    if (p_var > q_var)
      r = __intersect(__getP0(p), q);
    else if (p_var < q_var)
      r = __intersect(p, __getP0(q));
    else
    {
      final var p0 = protect(__intersect(__getP0(p), __getP0(q)));
      final var p1 = __intersect(__getP1(p), __getP1(q));

      r = __getNode(p_var, p0, p1);
    }

    protectedNodes.truncate(mark);

    return r;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int difference(int p, int q)
  {
    final var mark = protectedNodes.size();
    try {
      return __difference(checkZbdd(p, "p"), checkZbdd(q, "q"));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #difference(int, int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __difference(int p, int q)
  {
    if (p == EMPTY || p == q)
      return EMPTY;
    if (q == BASE)
      return __removeBase(p);
    if (q == EMPTY)
      return p;

    final var p_var = __getVar(p);
    final var q_var = __getVar(q);
    final int r;

    final var mark = saveAndProtect(p, q);

    if (p_var < q_var)
      r = __difference(p, __getP0(q));
    else if (p_var > q_var)
      r = __getNode(p_var, __difference(__getP0(p), q), __getP1(p));
    else
    {
      final var p0 = protect(__difference(__getP0(p), __getP0(q)));
      final var p1 = __difference(__getP1(p), __getP1(q));

      r = __getNode(p_var, p0, p1);
    }

    protectedNodes.truncate(mark);

    return r;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int multiply(int p, int q)
  {
    final var mark = protectedNodes.size();
    try {
      return __multiply(checkZbdd(p, "p"), checkZbdd(q, "q"));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #multiply(int, int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __multiply(int p, int q)
  {
    if (p == EMPTY || q == EMPTY)
      return EMPTY;
    if (p == BASE)
      return q;
    if (q == BASE)
      return p;

    var p_var = __getVar(p);
    var q_var = __getVar(q);

    if (p_var > q_var)
    {
      // swap p <-> q, p_var <-> q_var
      var tmp = p; p = q; q = tmp;
      p_var = q_var;
    }

    final var mark = saveAndProtect(p, q);

    // factor P = p0 + v * p1
    final var p0 = protect(__subset0(p, p_var));
    final var p1 = protect(__subset1(p, p_var));

    // factor Q = q0 + v * q1
    final var q0 = protect(__subset0(q, p_var));
    final var q1 = protect(__subset1(q, p_var));

    // r = (p0 + v * p1) * (q0 + v * q1) = p0q0 + v * (p0q1 + p1q0 + p1q1)
    final var p0q0 = protect(__multiply(p0, q0));
    final var p0q1 = protect(__multiply(p0, q1));
    final var p1q0 = protect(__multiply(p1, q0));
    final var p1q1 = protect(__multiply(p1, q1));
    final var r = __union(p0q0, __change(__union(__union(p0q1, p1q0), p1q1), p_var));

    protectedNodes.truncate(mark);

    return r;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int divide(int p, int q)
  {
    final var mark = protectedNodes.size();
    try {
      return __divide(checkZbdd(p, "p"), checkZbdd(q, "q"));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #divide(int, int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __divide(int p, int q)
  {
    if (p < 2)
      return EMPTY;
    if (p == q)
      return BASE;
    if (q == BASE)
      return p;

    final var mark = saveAndProtect(p, q);
    final var v = __getVar(q);

    // factor P = p0 + v * p1
    final var p0 = protect(__subset0(p, v));
    final var p1 = protect(__subset1(p, v));

    // factor Q = q0 + v * q1
    final var q0 = protect(__subset0(q, v));
    final var q1 = __subset1(q, v);

    final var r1 = __divide(p1, q1);
    final int r;

    if (r1 != EMPTY && q0 != EMPTY)
    {
      protect(r1);

      final var r0 = __divide(p0, q0);

      r = __intersect(r1, r0);
    }
    else
      r = r1;

    protectedNodes.truncate(mark);

    return r;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int modulo(int p, int q)
  {
    final var mark = protectedNodes.size();
    try {
      return __modulo(checkZbdd(p, "p"), checkZbdd(q, "q"));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #modulo(int, int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __modulo(int p, int q)
  {
    final var mark = saveAndProtect(p, q);
    final var r = __difference(p, __multiply(q, __divide(p, q)));

    protectedNodes.truncate(mark);

    return r;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int atomize(int zbdd)
  {
    final var mark = protectedNodes.size();
    try {
      return __atomize(checkZbdd(zbdd, "zbdd"));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #atomize(int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __atomize(int zbdd)
  {
    if (zbdd < 2)
      return EMPTY;

    final var mark = saveAndProtect(zbdd);
    final var offset = zbdd * NODE_RECORD_SIZE;
    final var p0_atomized = protect(__atomize(nodes[offset + _P0]));
    final var p1_atomized = __atomize(nodes[offset + _P1]);

    final var p0 = __atomize_union(p0_atomized, p1_atomized);
    final var r = __getNode(nodes[offset + _VAR], p0, BASE);

    protectedNodes.truncate(mark);

    return r;
  }


  // union optimized for atomization; the 1-branch for every node points to base(1)
  @Contract(mutates = "this")
  private int __atomize_union(int p, int q)
  {
    // trivial cases: remove base from union
    if (p < 2)
      return q < 2 ? EMPTY : q;
    if (q < 2 || p == q)
      return p;

    var p_var = __getVar(p);
    var q_var = __getVar(q);

    if (p_var > q_var)
    {
      // swap p <-> q, p_var <-> q_var
      var tmp = p; p = q; q = tmp;
      tmp = p_var; p_var = q_var; q_var = tmp;
    }

    final var mark = saveAndProtect(p, q);
    final var p0 = __atomize_union(p_var < q_var ? p : __getP0(p), __getP0(q));
    final var r = __getNode(q_var, p0, BASE);

    protectedNodes.truncate(mark);

    return r;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int removeBase(int zbdd)
  {
    final var mark = protectedNodes.size();
    try {
      return __removeBase(checkZbdd(zbdd, "zbdd"));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #removeBase(int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __removeBase(int zbdd)
  {
    if (zbdd < 2)
      return EMPTY;

    final var mark = saveAndProtect(zbdd);
    final var offset = zbdd * NODE_RECORD_SIZE;
    final var p0 = __removeBase(nodes[offset + _P0]);
    final var r = __getNode(nodes[offset + _VAR], p0, nodes[offset + _P1]);

    protectedNodes.truncate(mark);

    return r;
  }


  /**
   * Tells if the given ZBDD set {@code q} is contained in ZBDD set {@code p}.
   *
   * @param p  provided ZBDD set to test
   * @param q  ZBDD set which is expected to be part of ZBDD set {@code p}
   *
   * @return  {@code true} if both ZBDD sets {@code p} and {@code q} are not empty and ZBDD set {@code q} is
   *          contained in ZBDD set {@code p}, {@code false} otherwise
   */
  @Override
  @Contract(mutates = "this")
  public boolean contains(int p, int q)
  {
    final var mark = protectedNodes.size();
    try {
      return __contains(checkZbdd(p, "p"), checkZbdd(q, "q"));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #contains(int, int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected boolean __contains(int p, int q) {
    return p != EMPTY && q != EMPTY && (p == q || __intersect(p, q) == q);
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int getNode(int var, int p0, int p1)
  {
    final var mark = protectedNodes.size();
    try {
      return __getNode(checkVar(var), checkZbdd(p0, "p0"), checkZbdd(p1, "p1"));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #getNode(int, int, int)} without parameter validation.
   */
  @Contract(mutates = "this")
  protected int __getNode(int var, int p0, int p1)
  {
    statistics.nodeLookups++;

    // suppress 0's
    if (p1 == EMPTY)
    {
      statistics.nodeLookupHitCount++;
      return p0;
    }

    var hash = hash(var, p0, p1);

    // find node in the hash chain...
    for(int r = nodes[hash * NODE_RECORD_SIZE + _CHAIN]; r != 0;)
    {
      final var offset = r * NODE_RECORD_SIZE;

      if (nodes[offset + _VAR] == var && nodes[offset + _P0] == p0 && nodes[offset + _P1] == p1)
      {
        statistics.nodeLookupHitCount++;
        return r;
      }

      r = nodes[offset + _NEXT];
    }

    // increase the number of free nodes if there are not enough nodes available
    if (nodesFree < 2)
    {
      final var mark = saveAndProtect(p0, p1);

      ensureCapacity();

      protectedNodes.truncate(mark);

      if (nodesFree == 0)
        throw new ZbddException("nodes capacity exhausted");

      // hash may have changed due to nodes capacity increase
      hash = hash(var, p0, p1);
    }

    final var r = nextFreeNode;
    final var offset = r * NODE_RECORD_SIZE;

    nextFreeNode = nodes[offset + _NEXT];
    nodesFree--;
    nodesNew++;

    // set new node
    nodes[offset + _VAR] = var;
    nodes[offset + _P0] = p0;
    nodes[offset + _P1] = p1;
    nodes[offset + _REFCOUNT] = -1;

    prependHashChain(r, hash);

    return r;
  }


  /**
   * Returns the variable for the given {@code zbdd} node.
   *
   * @param zbdd  ZBDD node
   *
   * @return  variable or {@code -1} in case {@code zbdd} is the empty or base node
   */
  @Override
  @Contract(pure = true)
  public int getVar(int zbdd) {
    return __getVar(checkZbdd(zbdd, "zbdd"));
  }


  @Contract(pure = true)
  protected int __getVar(int zbdd) {
    return zbdd < 2 ? -1 : nodes[zbdd * NODE_RECORD_SIZE + _VAR];
  }


  /**
   * Returns the ZBDD node for the 0-branch of the given {@code zbdd} node.
   *
   * @param zbdd  ZBDD node
   *
   * @return  ZBDD node for the 0-branch
   */
  @Override
  @Contract(pure = true)
  public int getP0(int zbdd) {
    return __getP0(checkZbdd(zbdd, "zbdd"));
  }


  @Contract(pure = true)
  protected final int __getP0(int zbdd) {
    return nodes[zbdd * NODE_RECORD_SIZE + _P0];
  }


  /**
   * Returns the ZBDD node for the 1-branch of the given {@code zbdd} node.
   *
   * @param zbdd  ZBDD node
   *
   * @return  ZBDD node for the 1-branch
   */
  @Override
  @Contract(pure = true)
  public int getP1(int zbdd) {
    return __getP1(checkZbdd(zbdd, "zbdd"));
  }


  @Contract(pure = true)
  protected final int __getP1(int zbdd) {
    return nodes[zbdd * NODE_RECORD_SIZE + _P1];
  }


  @Contract(pure = true)
  protected final int hash(int var, int p0, int p1) {
    return ((var * 12582917 + p0 * 4256249 + p1 * 741457) & MAX_VALUE) % nodesCapacity;
  }


  /**
   * Performs garbage collection on the internal ZBDD structure.
   * <p>
   * After garbage collection, all dead and unreferenced nodes have been freed and the statistics have been updated
   * accordingly.
   *
   * @return  the number of freed ZBDD nodes
   *
   * @see #incRef(int)
   * @see #decRef(int)
   * @see #getNode(int, int, int)
   */
  @Override
  @Contract(mutates = "this")
  @MustBeInvokedByOverriders
  @SuppressWarnings("UnusedReturnValue")
  public int gc()
  {
    callbacks.forEach(ZbddCallback::beforeGc);

    final var oldNodesFree = nodesFree;

    gc_markReferencedNodes();
    gc_freeUnreferencedNodes();

    final var gcFreedNodesCount = nodesFree - oldNodesFree;

    statistics.gcFreedNodes += gcFreedNodesCount;
    statistics.gcCount++;

    callbacks.forEach(ZbddCallback::afterGc);

    return gcFreedNodesCount;
  }


  private void gc_markReferencedNodes()
  {
    final var markNodeStack = new IntStack(24);

    // nodes protected by currently running zbdd operations
    protectedNodes.forEach(zbdd -> gc_markNode(markNodeStack, zbdd));

    for(int zbdd = 0, offset = 0; zbdd < nodesCapacity; zbdd++, offset += NODE_RECORD_SIZE)
    {
      if (nodes[offset + _VAR] != -1 && nodes[offset + _REFCOUNT] > 0)
        gc_markNode(markNodeStack, zbdd);

      // clear hash chain
      nodes[offset + _CHAIN] = 0;
    }
  }


  /**
   * Marks the given {@code zbdd} and all nodes reachable from it as being in use, so they survive the sweep phase of
   * the garbage collection. Nodes which are already marked are not traversed again.
   *
   * @param markNodeStack  empty work stack, reused across invocations to avoid allocations, not {@code null}
   * @param zbdd           zbdd node to mark, must not be a terminal node
   */
  private void gc_markNode(@NotNull IntStack markNodeStack, int zbdd)
  {
    for(markNodeStack.push(zbdd); markNodeStack.isNotEmpty();)
    {
      final var offset = markNodeStack.pop() * NODE_RECORD_SIZE;

      if ((nodes[offset + _VAR] & GC_VAR_MARK_MASK) == 0)
      {
        nodes[offset + _VAR] |= GC_VAR_MARK_MASK;

        markNodeStack.pushIfNotLeafNode(nodes[offset + _P0]);
        markNodeStack.pushIfNotLeafNode(nodes[offset + _P1]);
      }
    }
  }


  private void gc_freeUnreferencedNodes()
  {
    nextFreeNode = -1;
    nodesFree = 0;
    nodesDead = 0;
    nodesNew = 0;

    for(int zbdd = nodesCapacity - 1, offset = zbdd * NODE_RECORD_SIZE; zbdd >= 2; zbdd--, offset -= NODE_RECORD_SIZE)
    {
      final var markedVar = nodes[offset + _VAR];

      if (markedVar != -1 && (markedVar & GC_VAR_MARK_MASK) != 0)
      {
        // remove mark and chain valid node
        prependHashChain(zbdd,
            hash(nodes[offset + _VAR] &= ~GC_VAR_MARK_MASK, nodes[offset + _P0], nodes[offset + _P1]));

        // dead or new node reachable from referenced or protected node
        final var refCount = nodes[offset + _REFCOUNT];
        if (refCount == 0)
          nodesDead++;
        else if (refCount == -1)
          nodesNew++;
      }
      else
      {
        // garbage collect node
        nodes[offset + _VAR] = -1;
        nodes[offset + _NEXT] = nextFreeNode;
        nextFreeNode = zbdd;
        nodesFree++;
      }
    }
  }


  @Contract(mutates = "this")
  private void ensureCapacity()
  {
    if (nodesDead + nodesNew > 0 && capacityAdvisor.isGCRequired(statistics))
    {
      gc();
      if (nodesFree >= capacityAdvisor.getMinimumFreeNodes(statistics))
        return;
    }

    final var oldNodesCapacity = nodesCapacity;
    final var newNodesCapacity =
        clamp((long)nodesCapacity + capacityAdvisor.adviseIncrement(statistics), nodesCapacity, MAX_NODES);

    // no capacity increase advised or possible
    if (newNodesCapacity == oldNodesCapacity)
      return;

    statistics.capacityIncreaseCount++;

    nodesCapacity = newNodesCapacity;
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
        nextFreeNode = i;
        nodesFree++;
      }
    }
  }


  private void prependHashChain(int zbdd, int hash)
  {
    final var hashChain = hash * NODE_RECORD_SIZE + _CHAIN;

    nodes[zbdd * NODE_RECORD_SIZE + _NEXT] = nodes[hashChain];
    nodes[hashChain] = zbdd;
  }


  /**
   * Protects the given {@code zbdd} from garbage collection until the protection stack is truncated to a size
   * below the current size. Unlike {@link #incRef(int)}, this does not change the node's reference count.
   *
   * @param zbdd  zbdd node
   *
   * @return  zbdd node
   */
  @Contract(value = "_ -> param1", mutates = "this")
  private int protect(int zbdd)
  {
    protectedNodes.pushIfNotLeafNode(zbdd);
    return zbdd;
  }


  /**
   * Returns the current size of the protection stack and protects the given {@code zbdd} from garbage collection.
   * <p>
   * The returned mark must be passed to {@link IntStack#truncate(int) protectedNodes.truncate(mark)} once the
   * protection is no longer required. This releases {@code zbdd} together with all nodes protected after it.
   *
   * @param zbdd  zbdd node to protect
   *
   * @return  protection stack size before protecting {@code zbdd}
   *
   * @see #protect(int)
   */
  @Contract(mutates = "this")
  private int saveAndProtect(int zbdd)
  {
    final var mark = protectedNodes.size();

    protectedNodes.pushIfNotLeafNode(zbdd);

    return mark;
  }


  /**
   * Returns the current size of the protection stack and protects the given zbdds {@code p} and {@code q} from
   * garbage collection.
   * <p>
   * The returned mark must be passed to {@link IntStack#truncate(int) protectedNodes.truncate(mark)} once the
   * protection is no longer required. This releases {@code p} and {@code q} together with all nodes protected after
   * them.
   *
   * @param p  1st zbdd node to protect
   * @param q  2nd zbdd node to protect
   *
   * @return  protection stack size before protecting {@code p} and {@code q}
   *
   * @see #protect(int)
   */
  @Contract(mutates = "this")
  private int saveAndProtect(int p, int q)
  {
    final var mark = protectedNodes.size();

    protectedNodes.pushIfNotLeafNode(p);
    protectedNodes.pushIfNotLeafNode(q);

    return mark;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(value = "_ -> param1", mutates = "this")
  public int incRef(int zbdd) {
    return __incRef(checkZbdd(zbdd, "zbdd"));
  }


  /**
   * Internal implementation of {@link #incRef(int)} without parameter validation.
   */
  @Contract(value = "_ -> param1", mutates = "this")
  protected int __incRef(final int zbdd)
  {
    final int offset;

    if (zbdd >= 2 && nodes[(offset = zbdd * NODE_RECORD_SIZE) + _VAR] != -1)
    {
      final var refCountOffset = offset + _REFCOUNT;
      final var ref = nodes[refCountOffset];

      if (ref == -1)  // new node
      {
        nodesNew--;
        nodes[refCountOffset] = 1;
      }
      else
      {
        if (ref == 0)
          nodesDead--;

        nodes[refCountOffset] = ref + 1;
      }
    }

    return zbdd;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(value = "_ -> param1", mutates = "this")
  @SuppressWarnings("UnusedReturnValue")
  public int decRef(int zbdd) {
    return __decRef(checkZbdd(zbdd, "zbdd"));
  }


  /**
   * Internal implementation of {@link #decRef(int)} without parameter validation.
   */
  @Contract(value = "_ -> param1", mutates = "this")
  protected int __decRef(final int zbdd)
  {
    final int offset;

    if (zbdd >= 2 && nodes[(offset = zbdd * NODE_RECORD_SIZE) + _VAR] != -1)
    {
      final var refCountOffset = offset + _REFCOUNT;
      final var newRef = nodes[refCountOffset] - 1;

      if (newRef >= 0)
      {
        if (newRef == 0)
          nodesDead++;

        nodes[refCountOffset] = newRef;
      }
      else
      {
        // underflow checked only if assertions are enabled (-ea)
        assert false : "refcount underflow on zbdd node " + zbdd + " (decRef without matching incRef)";
      }
    }

    return zbdd;
  }


  /** {@inheritDoc} */
  @Override
  public boolean isValidZbdd(int zbdd)
  {
    return zbdd < 2
        ? zbdd >= 0
        : zbdd < nodesCapacity && nodes[zbdd * NODE_RECORD_SIZE + _VAR] > 0;
  }


  /**
   * Validates the given ZBDD node and returns it, or throws an exception if it is invalid.
   *
   * @param zbdd   ZBDD node to validate
   * @param param  parameter name for the exception message, not {@code null}
   *
   * @return  the validated ZBDD node
   *
   * @throws ZbddOutOfRangeException  if the ZBDD node is out of range
   * @throws InvalidZbddException     if the ZBDD node is not valid
   */
  @Contract(value = "_, _ -> param1")
  @MustBeInvokedByOverriders
  protected int checkZbdd(int zbdd, @NotNull String param)
  {
    if (zbdd < 0 || zbdd >= nodesCapacity)
      throw new ZbddOutOfRangeException(zbdd, nodesCapacity - 1, param + " must be in range 0.." + (nodesCapacity - 1));

    if (zbdd >= 2 && nodes[zbdd * NODE_RECORD_SIZE + _VAR] <= 0)
      throw new InvalidZbddException(zbdd, "invalid " + param + " node " + zbdd);

    return zbdd;
  }


  /** {@inheritDoc} */
  @Override
  public boolean isValidVar(int var) {
    return var > 0 && var <= lastVarNumber;
  }


  /**
   * Validates the given variable and returns it, or throws an exception if it is invalid.
   *
   * @param var  variable to validate
   *
   * @return  the validated variable
   *
   * @throws InvalidVarException  if the variable is not valid
   */
  @Contract(value = "_ -> param1")
  @MustBeInvokedByOverriders
  protected int checkVar(int var)
  {
    if (var <= 0 || var > lastVarNumber)
      throw new InvalidVarException(var, lastVarNumber, "var must be in range 1.." + lastVarNumber);

    return var;
  }


  /** {@inheritDoc} */
  @Override
  public @NotNull Zbdd.ZbddNodeInfo getZbddNodeInfo(int zbdd) {
    return new ZbddNodeInfoDelegate(checkZbdd(zbdd, "zbdd"));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(value = "_ -> new", pure = true)
  public @NotNull String toString(int zbdd)
  {
    final var s = new StringJoiner(", ", "{ ", " }");
    final var resolver = getLiteralResolver();

    visitCubes(zbdd, cube -> {
      s.add(resolver.getCubeName(cube));
      return true;
    });

    return s.toString();
  }


  /** {@inheritDoc} */
  @Override
  public boolean visitCubeZbdds(int zbdd, @NotNull ZbddVisitor visitor)
  {
    requireNonNull(visitor, "visitor must not be null");

    return visitCubes(zbdd, cubeVars -> {
      var cubeZbdd = BASE;

      for(var n = cubeVars.length; n-- > 0;)
        cubeZbdd = __getNode(cubeVars[n], EMPTY, cubeZbdd);

      return visitor.visitZbdd(cubeZbdd);
    });
  }


  /** {@inheritDoc} */
  @Override
  public boolean visitCubes(int zbdd, @NotNull CubeVisitor visitor)
  {
    return zbdd == EMPTY ||
        __visitCubes(checkZbdd(zbdd, "zbdd"), requireNonNull(visitor, "visitor must not be null"));
  }


  protected boolean __visitCubes(int zbdd, @NotNull CubeVisitor visitor)
  {
    final var mark = saveAndProtect(zbdd);

    try {
      final var vars = new IntStack(__getVar(zbdd));
      final var workStack = new IntStack(24);
      int _zbdd;

      for(workStack.pushIfNotEmptyZbdd(zbdd); workStack.isNotEmpty();)
      {
        if ((_zbdd = workStack.pop()) == -1)
          vars.drop();
        else if (_zbdd != BASE)
        {
          final var offset = _zbdd * NODE_RECORD_SIZE;

          // 0-branch
          workStack.pushIfNotEmptyZbdd(nodes[offset + _P0]);

          // 1-branch
          workStack.push(-1);
          workStack.pushIfNotEmptyZbdd(nodes[offset + _P1]);
          vars.push(nodes[offset + _VAR]);
        }
        else if (!visitor.visitCube(vars.getIntArray()))
          return false;
      }

      return true;
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Calculates a list of ZBDD nodes, where each ZBDD node references 2 other ZBDD nodes in the list with a lower
   * index.
   * <p>
   * Initially, each newly created ZBDD node references other ZBDD nodes with lower ZBDD numbers. Starting with the
   * first garbage collection, freed ZBDD numbers are going to be reused, if possible. This means that the initial
   * contract is no longer valid and new ZBDD nodes may have a lower number than the ZBDD nodes it references.
   * <p>
   * The list returned by this method essentially describes the generation sequence for all ZBDD nodes in the correct
   * order.
   * <p>
   * Note: this method will always perform a garbage collection
   *
   * @return  array with the generation sequence for all ZBDD nodes, never {@code null}
   *
   * @see #gc()
   *
   * @since 0.3.1
   */
  @Override
  @Contract(value = "-> new")
  public int @NotNull [] calculateNodeDependency()
  {
    // make sure we're only dealing with valid nodes
    gc();

    final var zbddCount = nodesCapacity - nodesFree;
    final var zbddSequence = new int[zbddCount];
    final var sortedZbdds = new int[zbddCount];

    zbddSequence[1] = 1;

    var resultIdx = 2;
    var startZbdd = 2;

    do {
      for(int zbdd = startZbdd, offset = startZbdd * NODE_RECORD_SIZE;
          zbdd < nodesCapacity && resultIdx < zbddCount;
          offset += NODE_RECORD_SIZE, zbdd++)
      {
        zbddProcessed: {
          if (calculateNodeDependency_findZbdd(sortedZbdds, resultIdx, zbdd) || nodes[offset + _VAR] == -1)
            break zbddProcessed;

          if (calculateNodeDependency_findZbdd(sortedZbdds, resultIdx, nodes[offset + _P0]) &&
              calculateNodeDependency_findZbdd(sortedZbdds, resultIdx, nodes[offset + _P1]))
          {
            calculateNodeDependency_addZbdd(sortedZbdds, resultIdx, zbdd);
            zbddSequence[resultIdx++] = zbdd;
            break zbddProcessed;
          }

          continue;
        }

        if (zbdd == startZbdd)
          startZbdd++;
      }
    } while(resultIdx < zbddCount);

    return zbddSequence;
  }


  @Contract(pure = true)
  private boolean calculateNodeDependency_findZbdd(int[] sortedResults, int resultCount, int zbdd)
  {
    if (zbdd < 2)
      return true;

    for(int low = 2, high = resultCount - 1; low <= high;)
    {
      final var mid = (low + high) >>> 1;
      final var midZbdd = sortedResults[mid];

      if (midZbdd < zbdd)
        low = mid + 1;
      else if (midZbdd > zbdd)
        high = mid - 1;
      else
        return true;
    }

    return false;
  }


  @Contract(mutates = "param1")
  private void calculateNodeDependency_addZbdd(int[] sortedResults, int resultCount, int zbdd)
  {
    var low = 2;

    for(var high = resultCount - 1; low <= high;)
    {
      final var mid = (low + high) >>> 1;
      final var midZbdd = sortedResults[mid];

      if (midZbdd < zbdd)
        low = mid + 1;
      else if (midZbdd > zbdd)
        high = mid - 1;
    }

    if (low < resultCount)
      arraycopy(sortedResults, low, sortedResults, low + 1, resultCount - low);

    sortedResults[low] = zbdd;
  }


  /** {@inheritDoc} */
  @Override
  public int @NotNull [] asSingleCubeZbdds(int zbdd)
  {
    final var cubeZbdds = new IntStack(16);
    final var mark = protectedNodes.size();

    try {
      visitCubeZbdds(zbdd, cubeZbdd -> {
        protect(cubeZbdds.push(cubeZbdd));
        return true;
      });
    } finally {
      protectedNodes.truncate(mark);
    }

    return cubeZbdds.getIntArray();
  }




  /**
   * Live statistics view for this ZBDD instance, implementing {@link ZbddStatistics}.
   */
  private final class Statistics implements ZbddStatistics
  {
    private long nodeLookups;
    private long nodeLookupHitCount;
    private int gcCount;
    private int capacityIncreaseCount;
    private long gcFreedNodes;


    private Statistics() {
    }


    private void clear()
    {
      nodeLookups = 0;
      nodeLookupHitCount = 0;
      gcCount = 0;
      gcFreedNodes = 0;
      capacityIncreaseCount = 0;
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
    public int getNewNodes() {
      return nodesNew;
    }


    @Override
    public long getNodeLookups() {
      return nodeLookups;
    }


    @Override
    public long getNodeLookupHitCount() {
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
    public int getCapacityIncreaseCount() {
      return capacityIncreaseCount;
    }


    @Override
    public long getMemoryUsage() {
      return nodes.length * (long)Integer.BYTES + varObjectMap.getMemoryUsage();
    }


    @Override
    public int getRegisteredVars() {
      return lastVarNumber;
    }


    @Override
    public String toString()
    {
      return "Statistics(node={capacity=" + getNodesCapacity() + ", occupied=" + getOccupiedNodes() +
          ", free=" + getFreeNodes() + ", dead=" + getDeadNodes() + ", new=" + getNewNodes() + "}, hitRatio=" +
          round(getNodeLookupHitRatio() * 1000) / 10.0 + "%, gcCount=" + getGCCount() + ", capIncCount=" +
          getCapacityIncreaseCount() + ", mem=" +
          String.format(ROOT, "%.1fKB", getMemoryUsage() / 1024.0) + ")";
    }
  }




  /**
   * Live view delegate for a ZBDD node, providing access to its properties via the {@link ZbddNodeInfo} interface.
   */
  private final class ZbddNodeInfoDelegate implements ZbddNodeInfo
  {
    private final int zbdd;


    private ZbddNodeInfoDelegate(int zbdd) {
      this.zbdd = zbdd;
    }


    @Override
    public int getZbdd() {
      return checkZbdd(zbdd, "zbdd");
    }


    @Override
    public int getVar() {
      return ZbddImpl.this.getVar(zbdd);
    }


    @Override
    public int getP0() {
      return ZbddImpl.this.getP0(zbdd);
    }


    @Override
    public int getP1() {
      return ZbddImpl.this.getP1(zbdd);
    }


    @Override
    public int getReferenceCount() {
      return nodes[(getZbdd() * NODE_RECORD_SIZE) + _REFCOUNT];
    }


    @Override
    public @NotNull String getLiteral()
    {
      return switch(zbdd) {
        case EMPTY -> "";
        case BASE -> literalResolver.getBaseName();
        default -> literalResolver.getLiteralName(getVar());
      };
    }


    @Override
    public String toString()
    {
      final var var = getVar();
      final var s = new StringBuilder("ZbddNode(zbdd=").append(zbdd).append(",var=").append(var);

      final var literal = getLiteral();
      if (!literal.isBlank())
        s.append(':').append(literal);

      final var p0 = __getP0(zbdd);
      s.append(",P0=").append(p0);
      final var p0var = ZbddImpl.this.getVar(p0);
      if (p0var != -1)
      {
        final var p0literal = literalResolver.getLiteralName(p0var);
        if (!p0literal.isBlank())
          s.append(':').append(p0literal);
      }

      final var p1 = __getP1(zbdd);
      s.append(",P1=").append(p1);
      final var p1var = ZbddImpl.this.getVar(p1);
      if (p1var != -1)
      {
        final var p1literal = literalResolver.getLiteralName(p1var);
        if (!p1literal.isBlank())
          s.append(':').append(p1literal);
      }

      if (zbdd >= 2)
      {
        final var refCount = getReferenceCount();
        s.append(",refCount=").append(switch(refCount) {
          case -1 -> "new";
          case 0 -> "dead";
          default -> Integer.toString(refCount);
        });
      }

      return s.append(')').toString();
    }
  }
}
