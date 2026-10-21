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
import de.sayayi.lib.zbdd.exception.InvalidVarException;
import de.sayayi.lib.zbdd.exception.InvalidZbddException;
import de.sayayi.lib.zbdd.exception.ZbddException;
import de.sayayi.lib.zbdd.exception.ZbddOutOfRangeException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;

import static java.lang.Boolean.TRUE;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.Math.*;
import static java.lang.System.arraycopy;
import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
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
  private static final int FREE_LIST_END = -1;              // _NEXT of the last node in the free list

  /** Maximum number of nodes supported by a single ZBDD instance. */
  public static final int MAX_NODES = MAX_VALUE / NODE_RECORD_SIZE;

  /**
   * Highest variable number supported by a single ZBDD instance. {@link Integer#MAX_VALUE} is excluded, as marking it
   * during garbage collection ({@code MAX_VALUE | GC_VAR_MARK_MASK}) results in {@code -1}, which denotes a free node.
   */
  public static final int MAX_VAR = MAX_VALUE - 1;

  private static final int _VAR = 0;       // variable number
  private static final int _P0 = 1;        // 0-branch node
  private static final int _P1 = 2;        // 1-branch node
  private static final int _NEXT = 3;      // free-list or hash-chain link
  private static final int _CHAIN = 4;     // hash-bucket chain head
  private static final int _REFCOUNT = 5;  // allocated-node reference count

  // task kinds of the iterative operations
  static final int TASK_COMPUTE = 0;           // node or p, q: compute the result for the node or the operands p and q
  static final int TASK_COMBINE = 1;           // node or var: combine the results of both branches
  static final int TASK_COMBINE_P1 = 2;        // p1, var: combine the result of the 0-branch with the 1-branch p1
  static final int TASK_CONTINUE = 3;          // p0, q0: continue a division with the quotient p0 / q0, if required
  static final int TASK_COMBINE_PRODUCTS = 4;  // var: combine the products p0q0, p0q1, p1q0 and p1q1

  private final @NotNull ZbddCapacityAdvisor capacityAdvisor;
  private final @NotNull Statistics statistics;
  private final @NotNull IntObjectMap varObjectMap;

  /**
   * Nodes protected from garbage collection by currently running zbdd operations. Each operation saves the stack
   * size on entry and truncates the stack to that size on exit, so an exception never leaves nodes protected.
   */
  final @NotNull IntStack protectedNodes;

  private final @NotNull List<ZbddCallback> callbacks;
  private boolean callbacksRunning;

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
    callbacks = new CopyOnWriteArrayList<>();
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
  @Contract(mutates = "this")
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


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public void clear()
  {
    if (callbacksRunning)
      throw new ZbddException("clear must not be invoked from a zbdd callback");

    invokeCallbacks(ZbddCallback::beforeClear);
    clearInternal();
    invokeCallbacks(ZbddCallback::afterClear);
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
      nodes[offset + _NEXT] = zbdd == nodesCapacity ? FREE_LIST_END : zbdd;
      nodes[offset + _CHAIN] = 0;
    }

    varObjectMap.clear();
    protectedNodes.truncate(0);
    statistics.clear();
  }


  /**
   * Invokes {@code action} for all registered callbacks. Callbacks registered during the invocation are not invoked
   * until the next event.
   *
   * @param action  callback method to invoke, not {@code null}
   */
  private void invokeCallbacks(@NotNull Consumer<ZbddCallback> action)
  {
    callbacksRunning = true;
    try {
      callbacks.forEach(action);
    } finally {
      callbacksRunning = false;
    }
  }


  /**
   * Create a new literal/variable.
   *
   * @return  variable number >= 1
   */
  @Override
  @Contract(mutates = "this")
  public int createVar()
  {
    if (lastVarNumber == MAX_VAR)
      throw new ZbddException("variable count exceeded");

    return ++lastVarNumber;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int createVar(@NotNull Object varObject)
  {
    requireNonNull(varObject);

    final var var = createVar();
    varObjectMap.put(var, varObject);

    return var;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
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
        final var stack = new IntStack(min(zbddVar - var, 12) * 2);
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
   * <p>
   * The nodes above {@code var} are traversed depth-first using an explicit stack instead of recursion, so the number
   * of variable levels is not limited by the thread's stack size.
   */
  @Contract(mutates = "this")
  protected int __subset0(int zbdd, int var)
  {
    var top = __getVar(zbdd);

    if (top < var)
      return zbdd;

    if (top == var)
      return __getP0(zbdd);

    final var mark = saveAndProtect(zbdd);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(zbdd);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final var node = tasks.pop();
      final int r;

      if (task == TASK_COMBINE)
      {
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(__getVar(node), p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));
      }
      else if ((top = __getVar(node)) < var)
        r = node;
      else if (top == var)
        r = __getP0(node);
      else
      {
        tasks.push(node);
        tasks.push(TASK_COMBINE);
        tasks.push(__getP1(node));
        tasks.push(TASK_COMPUTE);
        tasks.push(__getP0(node));
        tasks.push(TASK_COMPUTE);
        continue;
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
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
   * <p>
   * The nodes above {@code var} are traversed depth-first using an explicit stack instead of recursion, so the number
   * of variable levels is not limited by the thread's stack size.
   */
  @Contract(mutates = "this")
  protected int __subset1(int zbdd, int var)
  {
    var top = __getVar(zbdd);

    if (top < var)
      return EMPTY;

    if (top == var)
      return __getP1(zbdd);

    final var mark = saveAndProtect(zbdd);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(zbdd);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final var node = tasks.pop();
      final int r;

      if (task == TASK_COMBINE)
      {
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(__getVar(node), p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));
      }
      else if ((top = __getVar(node)) < var)
        r = EMPTY;
      else if (top == var)
        r = __getP1(node);
      else
      {
        tasks.push(node);
        tasks.push(TASK_COMBINE);
        tasks.push(__getP1(node));
        tasks.push(TASK_COMPUTE);
        tasks.push(__getP0(node));
        tasks.push(TASK_COMPUTE);
        continue;
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
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
   * <p>
   * The nodes above {@code var} are traversed depth-first using an explicit stack instead of recursion, so the number
   * of variable levels is not limited by the thread's stack size.
   */
  @Contract(mutates = "this")
  protected int __change(int zbdd, int var)
  {
    var top = __getVar(zbdd);

    if (top < var)
      return __getNode(var, EMPTY, zbdd);

    if (top == var)
      return __getNode(var, __getP1(zbdd), __getP0(zbdd));

    final var mark = saveAndProtect(zbdd);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(zbdd);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final var node = tasks.pop();
      final int r;

      if (task == TASK_COMBINE)
      {
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(__getVar(node), p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));
      }
      else if ((top = __getVar(node)) < var)
        r = __getNode(var, EMPTY, node);
      else if (top == var)
        r = __getNode(var, __getP1(node), __getP0(node));
      else
      {
        tasks.push(node);
        tasks.push(TASK_COMBINE);
        tasks.push(__getP1(node));
        tasks.push(TASK_COMPUTE);
        tasks.push(__getP0(node));
        tasks.push(TASK_COMPUTE);
        continue;
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /**
   * Returns the number of cubes in the given ZBDD.
   *
   * @param zbdd  valid ZBDD node
   *
   * @return  number of cubes &ge; 0
   *
   * @throws ArithmeticException  if the number of cubes exceeds {@link Long#MAX_VALUE}
   */
  @Override
  public int count(int zbdd) {
    return __count(checkZbdd(zbdd, "zbdd"));
  }


  /**
   * Internal implementation of {@link #count(int)} without parameter validation.
   *
   * @throws ArithmeticException  if the number of cubes exceeds {@link Long#MAX_VALUE}
   */
  @Contract(pure = true)
  protected int __count(int zbdd)
  {
    return __countCubes(zbdd, 0, 1, (count1, count2) -> {
      final var count = count1 + count2;

      if (count < 0)
        throw new ArithmeticException("number of cubes exceeds " + Integer.MAX_VALUE + ", use countExact instead");

      return count;
    });
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public @NotNull BigInteger countExact(int zbdd) {
    return __countCubes(checkZbdd(zbdd, "zbdd"), ZERO, ONE, BigInteger::add);
  }


  /**
   * Counts the cubes in the given {@code zbdd} using the given arithmetic.
   * <p>
   * Counts are computed bottom-up and memoized per distinct node. The count of a node is the sum of the counts of its
   * 0- and 1-branch, with {@code emptyCount} and {@code baseCount} as the counts of the terminal nodes.
   *
   * @param zbdd        valid zbdd node
   * @param emptyCount  count for the {@link #EMPTY} node, not {@code null}
   * @param baseCount   count for the {@link #BASE} node, not {@code null}
   * @param add         function adding two counts, not {@code null}
   *
   * @return  number of cubes, never {@code null}
   *
   * @param <T>  count type
   */
  @Contract(pure = true)
  @SuppressWarnings("unchecked")
  private <T> @NotNull T __countCubes(int zbdd, @NotNull T emptyCount, @NotNull T baseCount,
                                      @NotNull BinaryOperator<@NotNull T> add)
  {
    if (zbdd == EMPTY)
      return emptyCount;
    else if (zbdd == BASE)
      return baseCount;

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
        final var p0Count = p0 < 2 ? (p0 == EMPTY ? emptyCount : baseCount) : (T)zbddCountMap.get(p0);
        final var p1Count = p1 < 2 ? (p1 == EMPTY ? emptyCount : baseCount) : (T)zbddCountMap.get(p1);

        if (p0Count != null && p1Count != null)
          zbddCountMap.put(z, add.apply(p0Count, p1Count));
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

    return (T)zbddCountMap.get(zbdd);
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
   * <p>
   * The nodes of {@code p} and {@code q} are traversed depth-first using an explicit stack instead of recursion, so the
   * number of variable levels is not limited by the thread's stack size.
   */
  @Contract(mutates = "this")
  protected int __union(int p, int q)
  {
    if (q == EMPTY || p == q)
      return p;
    if (p == EMPTY)
      return q;

    final var mark = saveAndProtect(p, q);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(p);
    tasks.push(q);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final int r;

      if (task == TASK_COMBINE)
      {
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(tasks.pop(), p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));
      }
      else if (task == TASK_COMBINE_P1)
      {
        final var var = tasks.pop();
        final var p1 = tasks.pop();
        final var p0 = results.pop();

        r = __getNode(var, p0, p1);

        // release the protection of the branch result (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0));
      }
      else
      {
        q = tasks.pop();
        p = tasks.pop();

        if (q == EMPTY || p == q)
          r = p;
        else if (p == EMPTY)
          r = q;
        else
        {
          var p_var = __getVar(p);
          var q_var = __getVar(q);

          if (p_var > q_var)
          {
            // swap p <-> q, p_var <-> q_var
            var tmp = p; p = q; q = tmp;
            tmp = p_var; p_var = q_var; q_var = tmp;
          }

          if (p_var < q_var)
          {
            // union(p, q) = getNode(q_var, union(p, q0), q1)
            tasks.push(__getP1(q));
            tasks.push(q_var);
            tasks.push(TASK_COMBINE_P1);
            tasks.push(p);
            tasks.push(__getP0(q));
            tasks.push(TASK_COMPUTE);
          }
          else
          {
            tasks.push(p_var);
            tasks.push(TASK_COMBINE);
            tasks.push(__getP1(p));
            tasks.push(__getP1(q));
            tasks.push(TASK_COMPUTE);
            tasks.push(__getP0(p));
            tasks.push(__getP0(q));
            tasks.push(TASK_COMPUTE);
          }

          continue;
        }
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
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
   * <p>
   * The nodes of {@code p} and {@code q} are traversed depth-first using an explicit stack instead of recursion, so the
   * number of variable levels is not limited by the thread's stack size.
   */
  @Contract(mutates = "this")
  protected int __intersect(int p, int q)
  {
    if (p == EMPTY || q == EMPTY)
      return EMPTY;
    if (p == q)
      return p;

    final var mark = saveAndProtect(p, q);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(p);
    tasks.push(q);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final int r;

      if (task == TASK_COMBINE)
      {
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(tasks.pop(), p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));
      }
      else
      {
        q = tasks.pop();
        p = tasks.pop();

        if (p == EMPTY || q == EMPTY)
          r = EMPTY;
        else if (p == q)
          r = p;
        else
        {
          final var p_var = __getVar(p);
          final var q_var = __getVar(q);

          if (p_var > q_var)
          {
            tasks.push(__getP0(p));
            tasks.push(q);
          }
          else if (p_var < q_var)
          {
            tasks.push(p);
            tasks.push(__getP0(q));
          }
          else
          {
            tasks.push(p_var);
            tasks.push(TASK_COMBINE);
            tasks.push(__getP1(p));
            tasks.push(__getP1(q));
            tasks.push(TASK_COMPUTE);
            tasks.push(__getP0(p));
            tasks.push(__getP0(q));
          }

          tasks.push(TASK_COMPUTE);
          continue;
        }
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
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
   * <p>
   * The nodes of {@code p} and {@code q} are traversed depth-first using an explicit stack instead of recursion, so the
   * number of variable levels is not limited by the thread's stack size.
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

    final var mark = saveAndProtect(p, q);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(p);
    tasks.push(q);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final int r;

      if (task == TASK_COMBINE)
      {
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(tasks.pop(), p0, p1);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));
      }
      else if (task == TASK_COMBINE_P1)
      {
        final var var = tasks.pop();
        final var p1 = tasks.pop();
        final var p0 = results.pop();

        r = __getNode(var, p0, p1);

        // release the protection of the branch result (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0));
      }
      else
      {
        q = tasks.pop();
        p = tasks.pop();

        if (p == EMPTY || p == q)
          r = EMPTY;
        else if (q == BASE)
          r = __removeBase(p);
        else if (q == EMPTY)
          r = p;
        else
        {
          final var p_var = __getVar(p);
          final var q_var = __getVar(q);

          if (p_var < q_var)
          {
            tasks.push(p);
            tasks.push(__getP0(q));
          }
          else if (p_var > q_var)
          {
            // difference(p, q) = getNode(p_var, difference(p0, q), p1)
            tasks.push(__getP1(p));
            tasks.push(p_var);
            tasks.push(TASK_COMBINE_P1);
            tasks.push(__getP0(p));
            tasks.push(q);
          }
          else
          {
            tasks.push(p_var);
            tasks.push(TASK_COMBINE);
            tasks.push(__getP1(p));
            tasks.push(__getP1(q));
            tasks.push(TASK_COMPUTE);
            tasks.push(__getP0(p));
            tasks.push(__getP0(q));
          }

          tasks.push(TASK_COMPUTE);
          continue;
        }
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
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
   * <p>
   * Both operands are factored by the higher of their top variables {@code v}, so the factors are the branches of the
   * operand nodes. If {@code v} is the top variable of {@code q} only, the product is
   * {@code getNode(v, p * q0, p * q1)} and no union is required.
   * <p>
   * The products of the factored operands are computed depth-first using an explicit stack instead of recursion, so the
   * number of variable levels is not limited by the thread's stack size.
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

    final var mark = saveAndProtect(p, q);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(p);
    tasks.push(q);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final int r;

      if (task == TASK_COMBINE)
      {
        final var var = tasks.pop();
        final var resultsMark = tasks.pop();
        final var pq1 = results.pop();
        final var pq0 = results.pop();

        r = __getNode(var, pq0, pq1);

        // release the protection of the products
        protectedNodes.truncate(resultsMark);
      }
      else if (task == TASK_COMBINE_PRODUCTS)
      {
        final var var = tasks.pop();
        final var resultsMark = tasks.pop();
        final var p1q1 = results.pop();
        final var p1q0 = results.pop();
        final var p0q1 = results.pop();
        final var p0q0 = results.pop();

        // r = (p0 + v * p1) * (q0 + v * q1) = p0q0 + v * (p0q1 + p1q0 + p1q1)
        r = __getNode(var, p0q0, __union(__union(p0q1, p1q0), p1q1));

        // release the protection of the products
        protectedNodes.truncate(resultsMark);
      }
      else
      {
        q = tasks.pop();
        p = tasks.pop();

        if (p == EMPTY || q == EMPTY)
          r = EMPTY;
        else if (p == BASE)
          r = q;
        else if (q == BASE)
          r = p;
        else
        {
          var p_var = __getVar(p);
          var q_var = __getVar(q);

          if (p_var > q_var)
          {
            // swap p <-> q, p_var <-> q_var
            var tmp = p; p = q; q = tmp;
            tmp = p_var; p_var = q_var; q_var = tmp;
          }

          tasks.push(protectedNodes.size());
          tasks.push(q_var);

          if (p_var < q_var)
          {
            // v = q_var does not occur in p: r = p * (q0 + v * q1) = p * q0 + v * (p * q1)
            tasks.push(TASK_COMBINE);
            tasks.push(p);
            tasks.push(__getP1(q));
            tasks.push(TASK_COMPUTE);
            tasks.push(p);
            tasks.push(__getP0(q));
            tasks.push(TASK_COMPUTE);
          }
          else
          {
            // compute the products p0q0, p0q1, p1q0 and p1q1 in this order
            final var p0 = __getP0(p);
            final var p1 = __getP1(p);
            final var q0 = __getP0(q);
            final var q1 = __getP1(q);

            tasks.push(TASK_COMBINE_PRODUCTS);
            tasks.push(p1);
            tasks.push(q1);
            tasks.push(TASK_COMPUTE);
            tasks.push(p1);
            tasks.push(q0);
            tasks.push(TASK_COMPUTE);
            tasks.push(p0);
            tasks.push(q1);
            tasks.push(TASK_COMPUTE);
            tasks.push(p0);
            tasks.push(q0);
            tasks.push(TASK_COMPUTE);
          }

          continue;
        }
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int divide(int p, int q)
  {
    final var mark = protectedNodes.size();
    try {
      return __divide(checkZbdd(p, "p"), checkDivisor(q));
    } finally {
      protectedNodes.truncate(mark);
    }
  }


  /**
   * Internal implementation of {@link #divide(int, int)} without parameter validation.
   * <p>
   * The quotients of the factored operands are computed depth-first using an explicit stack instead of recursion, so
   * the number of variable levels is not limited by the thread's stack size.
   */
  @Contract(mutates = "this")
  protected int __divide(int p, int q)
  {
    if (q == BASE)
      return p;  // p / { ø } = p
    else if (p == EMPTY || p == BASE)
      return EMPTY;  // {} / q = {} or { ø } / q = {} for q != { ø }
    else if (p == q)
      return BASE;  // p / p = { ø }

    final var mark = saveAndProtect(p, q);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(p);
    tasks.push(q);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      int r;

      if (task == TASK_CONTINUE)
      {
        final var q0 = tasks.pop();
        final var p0 = tasks.pop();

        if ((r = results.pop()) != EMPTY && q0 != EMPTY)
        {
          // r = r1 ∩ (p0 / q0); r1 remains protected on top of the results stack
          results.push(r);
          tasks.push(TASK_COMBINE);
          tasks.push(p0);
          tasks.push(q0);
          tasks.push(TASK_COMPUTE);
          continue;
        }

        // r = r1; release the protection of the factored operands and r1
        protectedNodes.truncate(tasks.pop());
      }
      else if (task == TASK_COMBINE)
      {
        final var r0 = results.pop();
        final var r1 = results.pop();

        r = __intersect(r1, r0);

        // release the protection of the factored operands, r1 and r0
        protectedNodes.truncate(tasks.pop());
      }
      else
      {
        q = tasks.pop();
        p = tasks.pop();

        if (q == BASE)
          r = p;  // p / { ø } = p
        else if (p == EMPTY || p == BASE)
          r = EMPTY;  // {} / q = {} or { ø } / q = {} for q != { ø }
        else if (p == q)
          r = BASE;  // p / p = { ø }
        else
        {
          final var v = __getVar(q);

          tasks.push(protectedNodes.size());

          // factor P = p0 + v * p1
          final var p0 = protect(__subset0(p, v));
          final var p1 = protect(__subset1(p, v));

          // factor Q = q0 + v * q1
          final var q0 = protect(__subset0(q, v));
          final var q1 = protect(__subset1(q, v));

          // r1 = p1 / q1 is computed first; the continuation decides whether p0 / q0 is required
          tasks.push(p0);
          tasks.push(q0);
          tasks.push(TASK_CONTINUE);
          tasks.push(p1);
          tasks.push(q1);
          tasks.push(TASK_COMPUTE);
          continue;
        }
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int modulo(int p, int q)
  {
    final var mark = protectedNodes.size();
    try {
      return __modulo(checkZbdd(p, "p"), checkDivisor(q));
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
   * <p>
   * The nodes of {@code zbdd} are traversed depth-first using an explicit stack instead of recursion, so the number of
   * variable levels is not limited by the thread's stack size.
   */
  @Contract(mutates = "this")
  protected int __atomize(int zbdd)
  {
    if (zbdd < 2)
      return EMPTY;

    final var mark = saveAndProtect(zbdd);
    final var tasks = new IntStack(24);
    final var results = new IntStack(24);

    tasks.push(zbdd);
    tasks.push(TASK_COMPUTE);

    do {
      final var task = tasks.pop();
      final var node = tasks.pop();
      final int r;

      if (task == TASK_COMBINE)
      {
        final var p1 = results.pop();
        final var p0 = results.pop();

        r = __getNode(nodes[node * NODE_RECORD_SIZE + _VAR], __atomize_union(p0, p1), BASE);

        // release the protection of the branch results (leaf nodes are not protected)
        protectedNodes.truncate(protectedNodes.size() - (p0 >= 2 ? 1 : 0) - (p1 >= 2 ? 1 : 0));
      }
      else if (node < 2)
        r = EMPTY;
      else
      {
        final var offset = node * NODE_RECORD_SIZE;

        tasks.push(node);
        tasks.push(TASK_COMBINE);
        tasks.push(nodes[offset + _P1]);
        tasks.push(TASK_COMPUTE);
        tasks.push(nodes[offset + _P0]);
        tasks.push(TASK_COMPUTE);
        continue;
      }

      results.push(protect(r));
    } while(tasks.isNotEmpty());

    protectedNodes.truncate(mark);

    return results.pop();
  }


  /**
   * Union optimized for atomization: the 1-branch of every node in {@code p} and {@code q} points to base.
   * <p>
   * The 0-branch chains of {@code p} and {@code q} are traversed iteratively, so the number of variable levels is not
   * limited by the thread's stack size.
   *
   * @param p  atomized zbdd node
   * @param q  atomized zbdd node
   *
   * @return  atomized union of {@code p} and {@code q}
   */
  @Contract(mutates = "this")
  final int __atomize_union(int p, int q)
  {
    final var mark = saveAndProtect(p, q);
    IntStack vars = null;
    int r;

    // descend the 0-branch chains, collecting the variables of the result
    for(;;)
    {
      // trivial cases: remove base from union
      if (p < 2)
      {
        r = q < 2 ? EMPTY : q;
        break;
      }

      if (q < 2 || p == q)
      {
        r = p;
        break;
      }

      var p_var = __getVar(p);
      var q_var = __getVar(q);

      if (p_var > q_var)
      {
        // swap p <-> q, p_var <-> q_var
        var tmp = p; p = q; q = tmp;
        tmp = p_var; p_var = q_var; q_var = tmp;
      }

      if (vars == null)
        vars = new IntStack(24);

      vars.push(q_var);

      if (p_var == q_var)
        p = __getP0(p);
      q = __getP0(q);
    }

    // rebuild the result bottom-up
    if (vars != null)
    {
      do {
        r = __getNode(vars.pop(), r, BASE);
      } while(vars.isNotEmpty());
    }

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
   * <p>
   * The 0-branch chain of {@code zbdd} is traversed using an explicit stack instead of recursion, so the number of
   * variable levels is not limited by the thread's stack size.
   */
  @Contract(mutates = "this")
  protected int __removeBase(int zbdd)
  {
    if (zbdd < 2)
      return EMPTY;

    final var mark = saveAndProtect(zbdd);
    final var stack = new IntStack(24);

    do {
      stack.push(zbdd);
    } while((zbdd = nodes[zbdd * NODE_RECORD_SIZE + _P0]) >= 2);

    // rebuild the 0-branch chain bottom-up, ending in the empty set instead of the base set
    var r = EMPTY;

    do {
      final var offset = stack.pop() * NODE_RECORD_SIZE;
      r = __getNode(nodes[offset + _VAR], r, nodes[offset + _P1]);
    } while(stack.isNotEmpty());

    protectedNodes.truncate(mark);

    return r;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public boolean contains(int p, int q) {
    return __contains(checkZbdd(p, "p"), checkZbdd(q, "q"));
  }


  /**
   * Internal implementation of {@link #contains(int, int)} without parameter validation.
   * <p>
   * Both ZBDDs are traversed in parallel, depth-first. Each pair of nodes is checked at most once, so the running time
   * is linear in the number of distinct node pairs and not in the (potentially exponential) number of paths.
   *
   * @since 0.8.0
   */
  @Contract(pure = true)
  protected final boolean __contains(int p, int q)
  {
    final var stack = new IntStack(24);
    final var visited = new HashSet<Long>();

    for(;;)
    {
      // q is contained in p, if q = {} or p = q
      if (q != EMPTY && p != q)
      {
        if (p == EMPTY)
          return false;

        if (q == BASE)
        {
          // the empty combination is reachable from p following the 0-branches only
          while(p >= 2)
            p = __getP0(p);

          if (p == EMPTY)
            return false;
        }
        else if (p == BASE)
          return false;
        else if (visited.add(((long)p << 32) | q))
        {
          final var pVar = __getVar(p);
          final var qVar = __getVar(q);

          // q has a combination with qVar, which p does not have
          if (qVar > pVar)
            return false;

          if (qVar < pVar)
          {
            // combinations of q do not contain pVar
            p = __getP0(p);
            continue;
          }

          // same top variable: q0 must be contained in p0 and q1 in p1
          stack.push(__getP1(q));
          stack.push(__getP1(p));

          p = __getP0(p);
          q = __getP0(q);
          continue;
        }
      }

      if (!stack.isNotEmpty())
        return true;

      p = stack.pop();
      q = stack.pop();
    }
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int getNode(int var, int p0, int p1)
  {
    final var mark = protectedNodes.size();
    try {
      return __getNode(
          checkVar(var),
          checkVarOrder(var, p0, "p0"),
          checkVarOrder(var, p1, "p1"));
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
  protected final int __getVar(int zbdd) {
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
   * @throws ZbddException  if a callback is running
   *
   * @see #incRef(int)
   * @see #decRef(int)
   * @see #getNode(int, int, int)
   */
  @Override
  @Contract(mutates = "this")
  @SuppressWarnings("UnusedReturnValue")
  public int gc()
  {
    if (callbacksRunning)
      throw new ZbddException("gc must not be invoked from a zbdd callback");

    invokeCallbacks(ZbddCallback::beforeGc);

    final var oldNodesFree = nodesFree;

    try {
      gc_markReferencedNodes();
    } catch(Throwable ex) {
      gc_unmarkNodes();
      throw ex;
    }

    gc_freeUnreferencedNodes();

    final var gcFreedNodesCount = nodesFree - oldNodesFree;

    statistics.gcFreedNodes += gcFreedNodesCount;
    statistics.gcCount++;

    invokeCallbacks(ZbddCallback::afterGc);

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


  /**
   * Restores the node table after an aborted mark phase: mark bits are removed and all hash chains are rebuilt. Left
   * in place, the mark bits would make a later garbage collection skip the children of marked nodes and free
   * reachable nodes.
   */
  private void gc_unmarkNodes()
  {
    for(int i = 0, end = nodesCapacity * NODE_RECORD_SIZE; i < end; i += NODE_RECORD_SIZE)
      nodes[i + _CHAIN] = 0;

    for(int zbdd = nodesCapacity - 1, offset = zbdd * NODE_RECORD_SIZE; zbdd >= 2; zbdd--, offset -= NODE_RECORD_SIZE)
    {
      final var markedVar = nodes[offset + _VAR];

      if (markedVar != -1)
      {
        prependHashChain(zbdd,
            hash(nodes[offset + _VAR] = markedVar & ~GC_VAR_MARK_MASK, nodes[offset + _P0], nodes[offset + _P1]));
      }
    }
  }


  private void gc_freeUnreferencedNodes()
  {
    nextFreeNode = FREE_LIST_END;
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
    // garbage collection is not possible while callbacks are running, so new nodes are provided by growing only
    if (!callbacksRunning && nodesDead + nodesNew > 0 && capacityAdvisor.isGCRequired(statistics))
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

    // allocate first, so the instance is left intact if the allocation fails with an OutOfMemoryError
    nodes = copyOf(nodes, newNodesCapacity * NODE_RECORD_SIZE);
    nodesCapacity = newNodesCapacity;

    statistics.capacityIncreaseCount++;

    nextFreeNode = FREE_LIST_END;
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
  final int protect(int zbdd)
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
  final int saveAndProtect(int zbdd)
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
  final int saveAndProtect(int p, int q)
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
  @Contract(pure = true)
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
  @Contract(pure = true)
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
  protected int checkVar(int var)
  {
    if (var <= 0 || var > lastVarNumber)
      throw new InvalidVarException(var, lastVarNumber, "var must be in range 1.." + lastVarNumber);

    return var;
  }


  /**
   * Validates that the variable of the given child node {@code zbdd} is lower than {@code var}, which is required for
   * a node with variable {@code var} to reference {@code zbdd} as one of its branches.
   *
   * @param var    variable of the node referencing {@code zbdd}
   * @param zbdd   valid zbdd child node
   * @param param  parameter name for the exception message, not {@code null}
   *
   * @return  the validated zbdd child node
   *
   * @throws InvalidZbddException  if the variable of {@code zbdd} is not lower than {@code var}
   */
  @Contract(value = "_, _, _ -> param2", pure = true)
  private int checkVarOrder(int var, int zbdd, @NotNull String param)
  {
    final var zbddVar = __getVar(checkZbdd(zbdd, param));

    if (zbddVar >= var)
    {
      throw new InvalidZbddException(zbdd, param + " node " + zbdd + " has var " + zbddVar +
          ", which must be lower than var " + var);
    }

    return zbdd;
  }


  /**
   * Validates the given divisor {@code q} for {@link #divide(int, int)} and {@link #modulo(int, int)}. Division by the
   * empty set is undefined.
   * <p>
   * Internally, the divisor never becomes empty: the 1-branch of a node is never {@link #EMPTY} due to zero
   * suppression, and the 0-branch is only used as a divisor if it is not empty.
   *
   * @param q  divisor zbdd node
   *
   * @return  the validated divisor
   *
   * @throws ZbddOutOfRangeException  if the divisor is out of range
   * @throws InvalidZbddException     if the divisor is not valid
   * @throws ZbddException            if the divisor is the empty set
   */
  @Contract(value = "_ -> param1")
  private int checkDivisor(int q)
  {
    if (checkZbdd(q, "q") == EMPTY)
      throw new ZbddException("division by empty set");

    return q;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public @NotNull Zbdd.ZbddNodeInfo getZbddNodeInfo(int zbdd) {
    return new ZbddNodeInfoDelegate(checkZbdd(zbdd, "zbdd"));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(value = "_ -> new", pure = true)
  public @NotNull String toString(int zbdd)
  {
    final var s = new StringJoiner(", ", "{ ", " }").setEmptyValue("{ }");
    final var resolver = getLiteralResolver();

    visitCubes(zbdd, cube -> {
      s.add(resolver.getCubeName(cube));
      return true;
    });

    return s.toString();
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
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
    requireNonNull(visitor, "visitor must not be null");

    return zbdd == EMPTY || __visitCubes(checkZbdd(zbdd, "zbdd"), visitor);
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
   * Calculates a list of ZBDD nodes, where each non-terminal ZBDD node references 2 other ZBDD nodes in the list with
   * a lower index.
   * <p>
   * Initially, each newly created ZBDD node references other ZBDD nodes with lower ZBDD numbers. Starting with the
   * first garbage collection, freed ZBDD numbers are going to be reused, if possible. This means that the initial
   * contract is no longer valid and new ZBDD nodes may have a lower number than the ZBDD nodes it references.
   * <p>
   * The list returned by this method essentially describes the generation sequence for all ZBDD nodes in the correct
   * order.
   * <p>
   * The list always starts with the terminal nodes {@link #EMPTY} and {@link #BASE} at index 0 and 1, followed by all
   * other ZBDD nodes remaining after the garbage collection, each of them exactly once.
   * <p>
   * Note: this method will always perform a garbage collection.
   *
   * @return  array with the generation sequence for all ZBDD nodes, starting with {@code 0, 1}, never {@code null}
   *
   * @see #gc()
   *
   * @since 0.3.1
   */
  @Override
  @Contract(value = "-> new", mutates = "this")
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
  @Contract(value = "_ -> new", mutates = "this")
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
    @Contract(pure = true)
    public int getZbdd() {
      return checkZbdd(zbdd, "zbdd");
    }


    @Override
    @Contract(pure = true)
    public int getVar() {
      return ZbddImpl.this.getVar(zbdd);
    }


    @Override
    @Contract(pure = true)
    public int getP0() {
      return ZbddImpl.this.getP0(zbdd);
    }


    @Override
    @Contract(pure = true)
    public int getP1() {
      return ZbddImpl.this.getP1(zbdd);
    }


    @Override
    @Contract(pure = true)
    public int getReferenceCount() {
      return nodes[(getZbdd() * NODE_RECORD_SIZE) + _REFCOUNT];
    }


    @Override
    @Contract(pure = true)
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
