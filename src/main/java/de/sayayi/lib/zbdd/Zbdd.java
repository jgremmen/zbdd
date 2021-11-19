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

import de.sayayi.lib.zbdd.ZbddCache.NoCache;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static de.sayayi.lib.zbdd.ZbddCache.BinaryOperation.DIFF;
import static de.sayayi.lib.zbdd.ZbddCache.BinaryOperation.DIV;
import static de.sayayi.lib.zbdd.ZbddCache.BinaryOperation.INTERSECT;
import static de.sayayi.lib.zbdd.ZbddCache.BinaryOperation.MOD;
import static de.sayayi.lib.zbdd.ZbddCache.BinaryOperation.MUL;
import static de.sayayi.lib.zbdd.ZbddCache.BinaryOperation.UNION;
import static de.sayayi.lib.zbdd.ZbddCache.UnaryOperation.CHANGE;
import static de.sayayi.lib.zbdd.ZbddCache.UnaryOperation.SUBSET0;
import static de.sayayi.lib.zbdd.ZbddCache.UnaryOperation.SUBSET1;
import static java.lang.Integer.MAX_VALUE;
import static java.util.Arrays.copyOf;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.joining;
import static lombok.AccessLevel.PACKAGE;


/**
 * @author Jeroen Gremmen
 */
public class Zbdd
{
  private static final int NODE_MARK = 0x80000000;
  private static final int NODE_WIDTH = 6;

  public static final int MAX_NODES = MAX_VALUE / NODE_WIDTH;

  private static final int _VAR = 0;
  private static final int _P0 = 1;
  private static final int _P1 = 2;
  private static final int _PREV = 3;
  private static final int _NEXT = 4;
  private static final int _REFCOUNT = 5;

  protected static final int ZBDD_EMPTY = 0;
  protected static final int ZBDD_BASE = 1;

  private final @NotNull ZbddNodesAdvisor nodesAdvisor;
  private final @NotNull Statistics statistics;

  private int lastVar;

  private int nodesTableSize;
  private int[] nodes;

  private int firstFreeNode;
  private int freeNodesCount;
  private int deadNodesCount;

  @Getter @Setter
  private @NotNull ZbddNameResolver nameResolver = var -> "v" + var;

  @Getter
  private @NotNull ZbddCache cache = NoCache.INSTANCE;


  public Zbdd() {
    this(NodesAdvisor.INSTANCE);
  }


  public Zbdd(@NotNull ZbddNodesAdvisor nodesAdvisor)
  {
    this.nodesAdvisor = nodesAdvisor;

    nodesTableSize = nodesAdvisor.getInitialNodes();
    nodes = new int[nodesTableSize * NODE_WIDTH];

    initLeafNode(ZBDD_EMPTY);
    initLeafNode(ZBDD_BASE);

    statistics = new Statistics();

    clear();
  }


  private void initLeafNode(int zbdd)
  {
    final int offset = zbdd * NODE_WIDTH;

    nodes[offset + _VAR] = -1;
    nodes[offset + _P0] = zbdd;
    nodes[offset + _P1] = zbdd;
  }


  public void setCache(@NotNull ZbddCache cache) {
    (this.cache = cache).clear();
  }


  @Contract(pure = true)
  public @NotNull ZbddStatistics getStatistics() {
    return statistics;
  }


  @Contract(mutates = "this")
  public void clear()
  {
    lastVar = 0;
    deadNodesCount = 0;
    firstFreeNode = 2;
    freeNodesCount = nodesTableSize - 2;

    for(int i = 2; i < nodesTableSize; i++)
    {
      final int offset = i * NODE_WIDTH;

      nodes[offset + _VAR] = -1;
      nodes[offset + _PREV] = 0;
      nodes[offset + _NEXT] = (i + 1) % nodesTableSize;
    }

    statistics.nodeLookupHitCount = 0;
    statistics.nodeLookups = 0;
    statistics.gcFreedNodes = 0;
    statistics.gcCount = 0;

    cache.clear();
  }


  @Contract(mutates = "this")
  public @Range(from = 1, to = MAX_VALUE) int createVar() {
    return ++lastVar;
  }


  @Contract(pure = true)
  public int empty() {
    return ZBDD_EMPTY;
  }


  @Contract(pure = true)
  public int base() {
    return ZBDD_BASE;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int cube(int @NotNull ... cubeVars)
  {
    final int n = cubeVars.length;
    int r = ZBDD_BASE;

    if (n != 0)
    {
      // singleton -> create immediately
      if (n == 1)
        r = getNode(checkVar(cubeVars[0]), ZBDD_EMPTY, ZBDD_BASE);
      else
      {
        // var count >= 2
        Arrays.sort(cubeVars = copyOf(cubeVars, n));

        for(int var: cubeVars)
          if (checkVar(var) != getVar(r))
          {
            final int p1 = r;

            __incRef(p1);
            r = getNode(var, ZBDD_EMPTY, p1);
            __decRef(p1);
          }
      }
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int universe()
  {
    int r = ZBDD_BASE;

    for(int var = 1; var <= lastVar; var++)
    {
      final int p = r;

      __incRef(p);
      r = getNode(var, p, p);
      __decRef(p);
    }

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int subset0(@Range(from = 0, to = MAX_NODES) int zbdd,
                     @Range(from = 1, to = MAX_VALUE) int var) {
    return __subset0(checkZbdd(zbdd, "zbdd"), checkVar(var));
  }


  @Contract(mutates = "this")
  protected int __subset0(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return zbdd;

    if (top == var)
      return getP0(zbdd);

    return cache.lookupOrPutIfAbsent(this, SUBSET0, zbdd, var, () -> {
      __incRef(zbdd);

      final int p0 = __incRef(__subset0(getP0(zbdd), var));
      final int p1 = __incRef(__subset0(getP1(zbdd), var));
      final int r = getNode(top, p0, p1);

      __decRef(zbdd, p0, p1);

      return r;
    });
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int subset1(@Range(from = 0, to = MAX_NODES) int zbdd,
                     @Range(from = 1, to = MAX_VALUE) int var) {
    return __subset1(checkZbdd(zbdd, "zbdd"), checkVar(var));
  }


  @Contract(mutates = "this")
  protected int __subset1(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return ZBDD_EMPTY;

    if (top == var)
      return getP1(zbdd);

    return cache.lookupOrPutIfAbsent(this, SUBSET1, zbdd, var, () -> {
      __incRef(zbdd);

      final int p0 = __incRef(__subset1(getP0(zbdd), var));
      final int p1 = __incRef(__subset1(getP1(zbdd), var));
      final int r = getNode(top, p0, p1);

      __decRef(zbdd, p0, p1);

      return r;
    });
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int change(@Range(from = 0, to = MAX_NODES) int zbdd,
                    @Range(from = 1, to = MAX_VALUE) int var) {
    return __change(checkZbdd(zbdd, "zbdd"), checkVar(var));
  }


  @Contract(mutates = "this")
  protected int __change(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return getNode(var, ZBDD_EMPTY, zbdd);

    if (top == var)
      return getNode(var, getP1(zbdd), getP0(zbdd));

    return cache.lookupOrPutIfAbsent(this, CHANGE, zbdd, var, () -> {
      __incRef(zbdd);

      final int p0 = __incRef(__change(getP0(zbdd), var));
      final int p1 = __incRef(__change(getP1(zbdd), var));
      final int r = getNode(top, p0, p1);

      __decRef(zbdd, p0, p1);

      return r;
    });
  }


  @Contract(pure = true)
  @Range(from = 0, to = MAX_VALUE)
  public int count(@Range(from = 0, to = MAX_NODES) int zbdd) {
    return __count(checkZbdd(zbdd, "zbdd"));
  }


  @Contract(pure = true)
  protected int __count(int zbdd)
  {
    if (zbdd < 2)
      return zbdd;

    final int offset = zbdd * NODE_WIDTH;

    return __count(nodes[offset + _P0]) + __count(nodes[offset + _P1]);
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int union(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q) {
    return __union(checkZbdd(p, "p"), checkZbdd(q, "q"));
  }


  @Contract(mutates = "this")
  protected int __union(int p, int q)
  {
    if (p == ZBDD_EMPTY)
      return q;
    if (q == ZBDD_EMPTY || q == p)
      return p;

    final int ptop = getVar(p);
    final int qtop = getVar(q);

    if (ptop > qtop)
      return __union(q, p);

    return cache.lookupOrPutIfAbsent(this, UNION, p, q, () -> {
      __incRef(p, q);

      int r;

      if (ptop < qtop)
      {
        final int p0 = __incRef(__union(p, getP0(q)));

        r = getNode(qtop, p0, getP1(q));

        __decRef(p0);
      }
      else
      {
        final int p0 = __incRef(__union(getP0(p), getP0(q)));
        final int p1 = __incRef(__union(getP1(p), getP1(q)));

        r = getNode(ptop, p0, p1);

        __decRef(p0, p1);
      }

      __decRef(p, q);

      return r;
    });
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int intersect(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q) {
    return __intersect(checkZbdd(p, "p"), checkZbdd(q, "q"));
  }


  @Contract(mutates = "this")
  protected int __intersect(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == q)
      return p;

    return cache.lookupOrPutIfAbsent(this, INTERSECT, p, q, () -> {
      __incRef(p, q);

      final int ptop = getVar(p);
      final int qtop = getVar(q);
      final int r;

      if (ptop > qtop)
        r = __intersect(getP0(p), q);
      else if (ptop < qtop)
        r = __intersect(p, getP0(q));
      else
      {
        final int p0 = __incRef(__intersect(getP0(p), getP0(q)));
        final int p1 = __incRef(__intersect(getP1(p), getP1(q)));

        r = getNode(ptop, p0, p1);

        __decRef(p0, p1);
      }

      __decRef(p, q);

      return r;
    });
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int difference(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q) {
    return __difference(checkZbdd(p, "p"), checkZbdd(q, "q"));
  }


  @Contract(mutates = "this")
  protected int __difference(int p, int q)
  {
    if (p == ZBDD_EMPTY || p == q)
      return ZBDD_EMPTY;
    if (q == ZBDD_EMPTY)
      return p;

    return cache.lookupOrPutIfAbsent(this, DIFF, p, q, () -> {
      __incRef(p, q);

      final int ptop = getVar(p);
      final int qtop = getVar(q);
      final int r;

      if (ptop < qtop)
        r = __difference(p, getP0(q));
      else if (ptop > qtop)
      {
        final int p0 = __incRef(__difference(getP0(p), getP0(q)));

        r = getNode(ptop, p0, getP1(p));

        __decRef(p0);
      }
      else
      {
        final int p0 = __incRef(__difference(getP0(p), getP0(q)));
        final int p1 = __incRef(__difference(getP1(p), getP1(q)));

        r = getNode(ptop, p0, p1);

        __decRef(p0, p1);
      }

      __decRef(p, q);

      return r;
    });
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int multiply(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q) {
    return __multiply(checkZbdd(p, "p"), checkZbdd(q, "q"));
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

    return cache.lookupOrPutIfAbsent(this, MUL, p, q, () -> {
      __incRef(p, q);

      // factor P = p0 + v * p1
      final int p0 = __incRef(__subset0(p, ptop));
      final int p1 = __incRef(__subset1(p, ptop));

      // factor Q = q0 + v * q1
      final int q0 = __incRef(__subset0(q, ptop));
      final int q1 = __incRef(__subset1(q, ptop));

      // r = (p0 + v * q1) * (q0 + v * q1) = p0q0 + v * (p0q1 + p1q0 + p1q1)
      final int p0q0 = __incRef(__multiply(p0, q0));
      final int p0q1 = __incRef(__multiply(p0, q1));
      final int p1q0 = __incRef(__multiply(p1, q0));
      final int p1q1 = __incRef(__multiply(p1, q1));

      final int p0q1_p1q0 = __incRef(__union(p0q1, p1q0));
      final int p0p1_p1q0_p1q1 = __incRef(__union(p0q1_p1q0, p1q1));
      final int v_p0q1_p1q0_p1q1 = __incRef(__change(p0p1_p1q0_p1q1, ptop));

      final int r = __union(p0q0, v_p0q1_p1q0_p1q1);

      __decRef(p, q, p0, p1, q0, q1, p0q0, p0q1, p1q0, p1q1, p0q1_p1q0, p0p1_p1q0_p1q1, v_p0q1_p1q0_p1q1);

      return r;
    });
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int divide(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q) {
    return __divide(checkZbdd(p, "p"), checkZbdd(q, "q"));
  }


  @Contract(mutates = "this")
  protected int __divide(int p, int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    if (p < 2)
      return ZBDD_EMPTY;
    if (p == q)
      return ZBDD_BASE;
    if (q == ZBDD_BASE)
      return p;

    return cache.lookupOrPutIfAbsent(this, DIV, p, q, () -> {
      __incRef(p, q);

      final int v = getVar(q);

      // factor P = p0 + v * p1
      final int p0 = __incRef(__subset0(p, v));
      final int p1 = __incRef(__subset1(p, v));

      // factor Q = q0 + v * q1
      final int q0 = __incRef(__subset0(q, v));
      final int q1 = __incRef(__subset1(q, v));

      final int r1 = __divide(p1, q1);
      final int r;

      if (r1 != ZBDD_EMPTY && q0 != ZBDD_EMPTY)
      {
        final int r0 = __incRef(__divide(p0, q0));

        r = __intersect(__incRef(r1), r0);

        __decRef(r0, r1);
      }
      else
        r = r1;

      __decRef(p, q, p0, p1, q0, q1);

      return r;
    });
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int modulo(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q) {
    return __modulo(checkZbdd(p, "p"), checkZbdd(q, "q"));
  }


  @Contract(mutates = "this")
  protected int __modulo(int p, int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return cache.lookupOrPutIfAbsent(this, MOD, p, q, () -> {
      __incRef(p, q);

      final int p_div_q = __incRef(__divide(p, q));
      final int q_mul_p_div_q = __incRef(multiply(q, p_div_q));
      final int r = __difference(p, q_mul_p_div_q);

      __decRef(p, q, p_div_q, q_mul_p_div_q);

      return r;
    });
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  public int atomize(@Range(from = 0, to = MAX_NODES) int zbdd) {
    return __atomize(checkZbdd(zbdd, "zbdd"));
  }


  @Contract(mutates = "this")
  protected int __atomize(int zbdd)
  {
    if (zbdd == ZBDD_EMPTY || zbdd == ZBDD_BASE)
      return ZBDD_EMPTY;

    __incRef(zbdd);

    final int p0 = __incRef(atomize(getP0(zbdd)));
    final int p1 = __incRef(atomize(getP1(zbdd)));
    final int p0_p1 = __incRef(__union(p0, p1));

    final int r = getNode(getVar(zbdd), p0_p1, ZBDD_BASE);

    __decRef(zbdd, p0, p1, p0_p1);

    return r;
  }


  @Contract(mutates = "this")
  @Range(from = 0, to = MAX_NODES)
  protected int getNode(@Range(from = 1, to = MAX_VALUE) int var,
                        @Range(from = 0, to = MAX_NODES) int p0,
                        @Range(from = 0, to = MAX_NODES) int p1)
  {
    statistics.nodeLookups++;

    if (p1 == ZBDD_EMPTY)
    {
      statistics.nodeLookupHitCount++;
      return p0;
    }

    int hash = hash(var, p0, p1);

    // find node in chain...
    for(int r = nodes[hash * NODE_WIDTH + _PREV]; r != 0;)
    {
      final int offset = r * NODE_WIDTH;

      if (nodes[offset + _VAR] == var && nodes[offset + _P0] == p0 && nodes[offset + _P1] == p1)
      {
        statistics.nodeLookupHitCount++;
        return r;
      }

      r = nodes[offset + _NEXT];
    }

    if (freeNodesCount < 2)
    {
      ensureCapacity();
      hash = hash(var, p0, p1);  // may have changed due to grow
    }

    final int r = firstFreeNode;
    final int offset = r * NODE_WIDTH;
    firstFreeNode = nodes[offset + _NEXT];
    freeNodesCount--;

    // set new node
    nodes[offset + _VAR] = var;
    nodes[offset + _P0] = p0;
    nodes[offset + _P1] = p1;
    nodes[offset + _REFCOUNT] = -1;

    chainBeforeHash(r, hash);

    return r;
  }


  @Contract(pure = true)
  protected int getVar(int zbdd) {
    return zbdd < 2 ? -1 : (nodes[zbdd * NODE_WIDTH + _VAR] & ~NODE_MARK);
  }


  @Contract(pure = true)
  @Range(from = 0, to = MAX_NODES)
  protected int getP0(int zbdd) {
    return nodes[zbdd * NODE_WIDTH + _P0];
  }


  @Contract(pure = true)
  @Range(from = 0, to = MAX_NODES)
  protected int getP1(int zbdd) {
    return nodes[zbdd * NODE_WIDTH + _P1];
  }


  @Contract(pure = true)
  @Range(from = 0, to = MAX_NODES)
  protected int hash(int var, int p0, int p1) {
    return ((var * 12582917 + p0 * 4256249 + p1 * 741457) & 0x7fffffff) % nodesTableSize;
  }


  @Range(from = 0, to = MAX_NODES)
  public int gc()
  {
    statistics.gcCount++;

    // mark referenced nodes...
    for(int i = nodesTableSize; i-- > 0;)
    {
      final int offset = i * NODE_WIDTH;

      if (nodes[offset + _VAR] != -1 && nodes[offset + _REFCOUNT] > 0)
        gc_markTree(i);

      nodes[offset + _PREV] = 0;
    }

    final int oldFreeNodesCount = freeNodesCount;
    firstFreeNode = freeNodesCount = 0;

    for(int i = nodesTableSize; i-- > 2;)
    {
      final int offset = i * NODE_WIDTH;

      if ((nodes[offset + _VAR] & NODE_MARK) != 0 && nodes[offset + _VAR] != -1)
      {
        // remove mark and chain valid node
        chainBeforeHash(i,
            hash(nodes[offset + _VAR] &= ~NODE_MARK, nodes[offset + _P0], nodes[offset + _P1]));
      }
      else
      {
        // garbage collect node
        nodes[offset + _VAR] = -1;
        nodes[offset + _NEXT] = firstFreeNode;

        firstFreeNode = i;
        freeNodesCount++;
      }
    }

    deadNodesCount = 0;

    final int gcFreedNodesCount = freeNodesCount - oldFreeNodesCount;
    statistics.gcFreedNodes += gcFreedNodesCount;

    return gcFreedNodesCount;
  }


  private void gc_markTree(int zbdd)
  {
    if (zbdd >= 2)
    {
      final int offset = zbdd * NODE_WIDTH;

      if ((nodes[offset + _VAR] & NODE_MARK) == 0)
      {
        nodes[offset + _VAR] |= NODE_MARK;

        gc_markTree(nodes[offset + _P0]);
        gc_markTree(nodes[offset + _P1]);
      }
    }
  }


  @Contract(mutates = "this")
  protected void ensureCapacity()
  {
    if (deadNodesCount > 0 && nodesAdvisor.isGCRequired(statistics) &&
        gc() >= nodesAdvisor.getMinimumFreeNodes(statistics))
      return;

    final int oldTableSize = nodesTableSize;

    nodesTableSize = Math.min(nodesTableSize + nodesAdvisor.adviseNodesGrowth(statistics), MAX_NODES);
    nodes = copyOf(nodes, nodesTableSize * NODE_WIDTH);

    firstFreeNode = freeNodesCount = 0;

    for(int i = nodesTableSize; i-- > oldTableSize;)
    {
      final int offset = i * NODE_WIDTH;

      nodes[offset + _VAR] = -1;
      nodes[offset + _NEXT] = firstFreeNode;

      firstFreeNode = i;
      freeNodesCount++;
    }

    // unchain old nodes
    for(int i = 0, end = oldTableSize * NODE_WIDTH; i < end; i += NODE_WIDTH)
      nodes[i + _PREV] = 0;

    // re-chain old nodes
    for(int i = oldTableSize; i-- > 2;)
    {
      final int offset = i * NODE_WIDTH;

      if (nodes[offset + _VAR] != -1)
        chainBeforeHash(i, hash(nodes[offset + _VAR], nodes[offset + _P0], nodes[offset + _P1]));
      else
      {
        nodes[offset + _NEXT] = firstFreeNode;
        firstFreeNode = i;
        freeNodesCount++;
      }
    }
  }


  private void chainBeforeHash(int zbdd, int hash)
  {
    final int hashPrevious = hash * NODE_WIDTH + _PREV;

    nodes[zbdd * NODE_WIDTH + _NEXT] = nodes[hashPrevious];
    nodes[hashPrevious] = zbdd;
  }


  private void __incRef(int zbdd1, int zbdd2)
  {
    __incRef(zbdd1);
    __incRef(zbdd2);
  }


  @Contract(value = "_ -> param1", mutates = "this")
  public int incRef(@Range(from = 0, to = MAX_NODES) int zbdd) {
    return __incRef(checkZbdd(zbdd, "zbdd"));
  }


  @Contract(value = "_ -> param1", mutates = "this")
  protected int __incRef(int zbdd)
  {
    final int offset;

    if (zbdd >= 2 && nodes[(offset = zbdd * NODE_WIDTH) + _VAR] != -1)
    {
      final int refCountOffset = offset + _REFCOUNT;
      final int ref = nodes[refCountOffset];

      if (ref == -1)  // new node
        nodes[refCountOffset] = 1;
      else
      {
        if (ref == 0)
          deadNodesCount--;

        nodes[refCountOffset]++;
      }
    }

    return zbdd;
  }


  private void __decRef(int... zbdds)
  {
    for(int zbdd: zbdds)
      __decRef(zbdd);
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

    if (zbdd >= 2 && nodes[(offset = zbdd * NODE_WIDTH) + _VAR] != -1)
    {
      final int refCountOffset = offset + _REFCOUNT;
      final int ref = nodes[refCountOffset];

      if (ref > 0)
      {
        if (ref == 1)
          deadNodesCount++;

        nodes[refCountOffset]--;
      }
    }

    return zbdd;
  }


  @Contract(value = "_, _ -> param1")
  private int checkZbdd(int zbdd, @NotNull String param)
  {
    if (zbdd < 0 || zbdd >= nodesTableSize)
      throw new ZbddException(param + " must be in range 0.." + (nodesTableSize - 1));

    if (zbdd >= 2 && nodes[zbdd * NODE_WIDTH + _VAR] == -1)
      throw new ZbddException("invalid " + param + " node " + zbdd);

    return zbdd;
  }


  @Contract(value = "_ -> param1")
  private int checkVar(int var)
  {
    if (var <= 0 || var > lastVar)
      throw new ZbddException("var must be in range 1.." + var);

    return var;
  }


  @Contract(value = "_ -> new", pure = true)
  public @NotNull String toString(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    return getCubes(zbdd).stream()
        .map(nameResolver::getCube)
        .sorted()
        .collect(joining(", ", "{", "}"));
  }


  @Contract(pure = true)
  @Unmodifiable
  public @NotNull List<int[]> getCubes(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    if (zbdd == ZBDD_EMPTY)
      return emptyList();
    else if (zbdd == ZBDD_BASE)
      return singletonList(new int[0]);

    final List<int[]> cubes = new ArrayList<>(count(zbdd));

    getCubes0(cubes, new IntStack(lastVar), zbdd);

    return unmodifiableList(cubes);
  }


  private void getCubes0(@NotNull List<int[]> set, @NotNull IntStack vars, int zbdd)
  {
    if (zbdd == ZBDD_BASE)
      set.add(vars.getStack());
    else if (zbdd != ZBDD_EMPTY)
    {
      // walk 1-branch
      vars.push(getVar(zbdd));
      getCubes0(set, vars, getP1(zbdd));
      vars.pop();

      // walk 0-branch
      getCubes0(set, vars, getP0(zbdd));
    }
  }


  @Contract(value = "_ -> new", pure = true)
  @Unmodifiable
  public @NotNull Map<Integer,Node> getNodes(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    final Map<Integer,Node> nodeMap = new TreeMap<>((n1,n2) -> n2 - n1);

    getNodes0(nodeMap, zbdd);

    return unmodifiableMap(nodeMap);
  }


  private void getNodes0(@NotNull Map<Integer,Node> nodeMap, int zbdd)
  {
    nodeMap.computeIfAbsent(zbdd, Node::new);

    if (zbdd >= 2)
    {
      getNodes0(nodeMap, getP1(zbdd));
      getNodes0(nodeMap, getP0(zbdd));
    }
  }




  private static class IntStack
  {
    private int stackSize;
    private int[] stack;


    private IntStack(int size) {
      stack = new int[Math.max(size, 4)];
    }


    private void push(int value)
    {
      if (stackSize >= stack.length)
        stack = copyOf(stack, stackSize + 4);

      stack[stackSize++] = value;
    }


    private void pop()
    {
      if (stackSize > 0)
        stackSize--;
    }


    private int[] getStack() {
      return copyOf(stack, stackSize);
    }
  }




  @AllArgsConstructor(access = PACKAGE)
  public final class Node
  {
    private final int zbdd;


    public int getVar() {
      return Zbdd.this.getVar(zbdd);
    }


    public int getP0() {
      return Zbdd.this.getP0(zbdd);
    }


    public int getP1() {
      return Zbdd.this.getP1(zbdd);
    }


    public String toString()
    {
      return zbdd == 0 ? "Empty" : zbdd == 1 ? "Base"
          : ("Node(var=" + nameResolver.getVariable(getVar()) + ", P0=" + getP0() + ", P1=" + getP1() + ")");
    }
  }




  private final class Statistics implements ZbddStatistics
  {
    private int nodeLookups;
    private int nodeLookupHitCount;
    private int gcCount;
    private long gcFreedNodes;


    @Override
    public int getNodeTableSize() {
      return nodesTableSize;
    }


    @Override
    public int getFreeNodes() {
      return freeNodesCount;
    }


    @Override
    public int getDeadNodes() {
      return deadNodesCount;
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
      return lastVar;
    }


    @Override
    public String toString()
    {
      return "Statistics(nodesOccupied=" + getOccupiedNodes() + ", nodesFree=" + getFreeNodes() +
          ", nodesDead=" + getDeadNodes() +
          ", lookupHitRatio=" + Math.round(getNodeLookupHitRatio() * 1000) / 10.0 + "%)";
    }
  }




  private enum NodesAdvisor implements ZbddNodesAdvisor
  {
    INSTANCE;


    @Override
    public @Range(from = 4, to = MAX_NODES) int getInitialNodes() {
      return 128;
    }


    @Override
    public @Range(from = 1, to = MAX_NODES) int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) {
      return statistics.getNodeTableSize() * 2 / 10;  // 20%
    }


    @Override
    public int adviseNodesGrowth(@NotNull ZbddStatistics statistics)
    {
      final int tableSize = statistics.getNodeTableSize();

      // size < 500000 -> increase by 150%
      // size > 500000 -> increase by 30%
      return tableSize < 500000 ? (tableSize / 2) * 3 : (tableSize / 10) * 3;
    }


    @Override
    public boolean isGCRequired(@NotNull ZbddStatistics statistics)
    {
      final int tableSize = statistics.getNodeTableSize();

      // size > 250000
      // dead nodes > 20% of table size
      return tableSize > 250000 || statistics.getDeadNodes() > (tableSize / 5);
    }
  }
}
