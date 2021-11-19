package de.sayayi.lib.zbdd;

import de.sayayi.lib.zbdd.ZbddCache.NoCache;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

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
import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.joining;


public class Zbdd
{
  private static final int NODE_MARK = 0x80000000;
  private static final int NODE_WIDTH = 6;

  public static final int MAX_NODES = MAX_VALUE / NODE_WIDTH;

  private static final int IDX_VAR = 0;
  private static final int IDX_P0 = 1;
  private static final int IDX_P1 = 2;
  private static final int IDX_PREV = 3;
  private static final int IDX_NEXT = 4;
  private static final int IDX_REFCOUNT = 5;

  public static final int ZBDD_EMPTY = 0;
  public static final int ZBDD_BASE = 1;

  private final ZbddNodesAdvisor nodesAdvisor;
  private final Statistics statistics;

  private int lastVar;

  private int nodesTableSize;
  private int[] nodes;

  private int firstFreeNode;
  private int freeNodesCount;
  private int deadNodesCount;

  private ZbddNameResolver nameResolver = var -> "v" + var;
  private ZbddCache cache = NoCache.INSTANCE;


  public Zbdd() {
    this(NodesAdvisor.INSTANCE);
  }


  public Zbdd(@NotNull ZbddNodesAdvisor nodesAdvisor)
  {
    this.nodesAdvisor = nodesAdvisor;

    nodesTableSize = nodesAdvisor.getInitialNodes();
    nodes = new int[nodesTableSize * NODE_WIDTH];

    deadNodesCount = 0;
    firstFreeNode = 2;
    freeNodesCount = nodesTableSize - 2;

    for(int i = 2; i < nodesTableSize; i++)
    {
      nodes[i * NODE_WIDTH + IDX_VAR] = -1;
      nodes[i * NODE_WIDTH + IDX_NEXT] = (i + 1) % nodesTableSize;
    }

    initFixedNode(ZBDD_EMPTY);
    initFixedNode(ZBDD_BASE);

    statistics = new Statistics();
  }


  private void initFixedNode(int zbdd)
  {
    final int offset = zbdd * NODE_WIDTH;

    nodes[offset + IDX_VAR] = -1;
    nodes[offset + IDX_P0] = zbdd;
    nodes[offset + IDX_P1] = zbdd;
  }


  public void setCache(@NotNull ZbddCache cache) {
    (this.cache = cache).clear();
  }


  public void setNameResolver(@NotNull ZbddNameResolver nameResolver) {
    this.nameResolver = nameResolver;
  }


  @Contract(pure = true)
  public @NotNull ZbddStatistics getStatistics() {
    return statistics;
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
  public int cube(int... cubeVars)
  {
    final int n = cubeVars.length;
    int r = ZBDD_BASE;

    if (n != 0)
    {
      // singleton -> create immediately
      if (n == 1)
      {
        int var = cubeVars[0];
        checkVar(var);

        r = getNode(var, ZBDD_EMPTY, ZBDD_BASE);
      }
      else
      {
        // var count >= 2
        Arrays.sort(cubeVars = copyOf(cubeVars, n));

        for(int var: cubeVars)
        {
          checkVar(var);

          if (var != getVar(r))
          {
            final int p1 = r;

            __incRef(p1);
            r = getNode(var, ZBDD_EMPTY, p1);
            __decRef(p1);
          }
        }
      }
    }

    return r;
  }


  @Contract(mutates = "this")
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
  public int subset0(@Range(from = 0, to = MAX_NODES) int zbdd,
                     @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd, "zbdd");
    checkVar(var);

    return __subset0(zbdd, var);
  }


  @Contract(mutates = "this")
  protected int __subset0(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return zbdd;

    if (top == var)
      return getP0(zbdd);

    return cache.lookupOrPutIfAbsent(SUBSET0, zbdd, var, () -> {
      __incRef(zbdd);

      final int p0 = __incRef(__subset0(getP0(zbdd), var));
      final int p1 = __incRef(__subset0(getP1(zbdd), var));
      final int r = getNode(top, p0, p1);

      __decRef(zbdd, p0, p1);

      return r;
    });
  }


  @Contract(mutates = "this")
  public int subset1(@Range(from = 0, to = MAX_NODES) int zbdd,
                     @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd, "zbdd");
    checkVar(var);

    return __subset1(zbdd, var);
  }


  @Contract(mutates = "this")
  protected int __subset1(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return ZBDD_EMPTY;

    if (top == var)
      return getP1(zbdd);

    return cache.lookupOrPutIfAbsent(SUBSET1, zbdd, var, () -> {
      __incRef(zbdd);

      final int p0 = __incRef(__subset1(getP0(zbdd), var));
      final int p1 = __incRef(__subset1(getP1(zbdd), var));
      final int r = getNode(top, p0, p1);

      __decRef(zbdd, p0, p1);

      return r;
    });
  }


  @Contract(mutates = "this")
  public int change(@Range(from = 0, to = MAX_NODES) int zbdd,
                    @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd, "zbdd");
    checkVar(var);

    return __change(zbdd, var);
  }


  @Contract(mutates = "this")
  protected int __change(int zbdd, int var)
  {
    final int top = getVar(zbdd);

    if (top < var)
      return getNode(var, ZBDD_EMPTY, zbdd);

    if (top == var)
      return getNode(var, getP1(zbdd), getP0(zbdd));

    return cache.lookupOrPutIfAbsent(CHANGE, zbdd, var, () -> {
      __incRef(zbdd);

      final int p0 = __incRef(__change(getP0(zbdd), var));
      final int p1 = __incRef(__change(getP1(zbdd), var));
      final int r = getNode(top, p0, p1);

      __decRef(zbdd, p0, p1);

      return r;
    });
  }


  @Contract(pure = true)
  public int count(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    checkZbdd(zbdd, "zbdd");

    return __count(zbdd);
  }


  @Contract(pure = true)
  protected int __count(int zbdd)
  {
    if (zbdd < 2)
      return zbdd;

    final int offset = zbdd * NODE_WIDTH;

    return __count(nodes[offset + IDX_P0]) + __count(nodes[offset + IDX_P1]);
  }


  @Contract(mutates = "this")
  public int union(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return __union(p, q);
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

    return cache.lookupOrPutIfAbsent(UNION, p, q, () -> {
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
  public int intersect(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return __intersect(p, q);
  }


  @Contract(mutates = "this")
  protected int __intersect(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == q)
      return p;

    return cache.lookupOrPutIfAbsent(INTERSECT, p, q, () -> {
      __incRef(p);
      __incRef(q);

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
  public int difference(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return __difference(p, q);
  }


  @Contract(mutates = "this")
  protected int __difference(int p, int q)
  {
    if (p == ZBDD_EMPTY || p == q)
      return ZBDD_EMPTY;
    if (q == ZBDD_EMPTY)
      return p;

    return cache.lookupOrPutIfAbsent(DIFF, p, q, () -> {
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
  public int multiply(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return __multiply(p, q);
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

    return cache.lookupOrPutIfAbsent(MUL, p, q, () -> {
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
      final int v_p0p1_p1q0_p1q1 = __incRef(__change(p0p1_p1q0_p1q1, ptop));

      final int r = __union(p0q0, v_p0p1_p1q0_p1q1);

      __decRef(p, q, p0, p1, q0, q1, p0q0, p0q1, p1q0, p1q1, p0q1_p1q0, p0p1_p1q0_p1q1, v_p0p1_p1q0_p1q1);

      return r;
    });
  }


  @Contract(mutates = "this")
  public int divide(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return __divide(p, q);
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

    return cache.lookupOrPutIfAbsent(DIV, p, q, () -> {
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
  public int modulo(@Range(from = 0, to = MAX_NODES) int p, @Range(from = 0, to = MAX_NODES) int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return __modulo(p, q);
  }


  @Contract(mutates = "this")
  protected int __modulo(int p, int q)
  {
    checkZbdd(p, "p");
    checkZbdd(q, "q");

    return cache.lookupOrPutIfAbsent(MOD, p, q, () -> {
      __incRef(p, q);

      final int p_div_q = __incRef(__divide(p, q));
      final int q_mul_p_div_q = __incRef(multiply(q, p_div_q));
      final int r = __difference(p, q_mul_p_div_q);

      __decRef(p, q, p_div_q, q_mul_p_div_q);

      return r;
    });
  }


  @Contract(mutates = "this")
  public int atomize(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    checkZbdd(zbdd, "zbdd");

    return __atomize(zbdd);
  }


  @Contract(mutates = "this")
  protected int __atomize(int zbdd)
  {
    checkZbdd(zbdd, "zbdd");

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


  protected int getNode(@Range(from = 1, to = MAX_VALUE) int var,
                        @Range(from = 0, to = MAX_NODES) int low,
                        @Range(from = 0, to = MAX_NODES) int high)
  {
    statistics.nodeLookups++;

    if (high == ZBDD_EMPTY)
    {
      statistics.nodeLookupHitCount++;
      return low;
    }

    int hash = hash(var, low, high);

    // find node in chain...
    for(int r = nodes[hash * NODE_WIDTH + IDX_PREV]; r != 0;)
    {
      final int offset = r * NODE_WIDTH;

      if (nodes[offset + IDX_VAR] == var && nodes[offset + IDX_P0] == low && nodes[offset + IDX_P1] == high)
      {
        statistics.nodeLookupHitCount++;
        return r;
      }

      r = nodes[offset + IDX_NEXT];
    }

    if (freeNodesCount < 2)
    {
      ensureCapacity();
      hash = hash(var, low, high);  // may have changed due to grow
    }

    final int r = firstFreeNode;
    final int offset = r * NODE_WIDTH;
    firstFreeNode = nodes[offset + IDX_NEXT];
    freeNodesCount--;

    // set new node
    nodes[offset + IDX_VAR] = var;
    nodes[offset + IDX_P0] = low;
    nodes[offset + IDX_P1] = high;
    nodes[offset + IDX_REFCOUNT] = -1;

    chainBeforeHash(r, hash);

    return r;
  }


  protected int getVar(int zbdd) {
    return zbdd < 2 ? -1 : (nodes[zbdd * NODE_WIDTH + IDX_VAR] & ~NODE_MARK);
  }


  protected int getP0(int zbdd) {
    return nodes[zbdd * NODE_WIDTH + IDX_P0];
  }


  protected int getP1(int zbdd) {
    return nodes[zbdd * NODE_WIDTH + IDX_P1];
  }


  @Contract(pure = true)
  protected int hash(int var, int low, int high) {
    return ((var * 12582917 + low * 4256249 + high * 741457) & 0x7fffffff) % nodesTableSize;
  }


  public int gc()
  {
    statistics.gcCount++;

    // mark referenced nodes...
    for(int i = nodesTableSize; i-- > 0;)
    {
      final int offset = i * NODE_WIDTH;

      if (nodes[offset + IDX_VAR] != -1 && nodes[offset + IDX_REFCOUNT] > 0)
        gc_markTree(i);

      nodes[offset + IDX_PREV] = 0;
    }

    final int oldFreeNodesCount = freeNodesCount;
    firstFreeNode = freeNodesCount = 0;

    for(int i = nodesTableSize; i-- > 2;)
    {
      final int offset = i * NODE_WIDTH;

      if ((nodes[offset + IDX_VAR] & NODE_MARK) != 0 && nodes[offset + IDX_VAR] != -1)
      {
        // remove mark and chain valid node
        chainBeforeHash(i,
            hash(nodes[offset + IDX_VAR] &= ~NODE_MARK, nodes[offset + IDX_P0], nodes[offset + IDX_P1]));
      }
      else
      {
        // garbage collect node
        nodes[offset + IDX_VAR] = -1;
        nodes[offset + IDX_NEXT] = firstFreeNode;

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

      if ((nodes[offset + IDX_VAR] & NODE_MARK) == 0)
      {
        nodes[offset + IDX_VAR] |= NODE_MARK;

        gc_markTree(nodes[offset + IDX_P0]);
        gc_markTree(nodes[offset + IDX_P1]);
      }
    }
  }


  protected void ensureCapacity()
  {
    if (nodesAdvisor.isGCRequired(statistics) && gc() >= nodesAdvisor.getMinimumFreeNodes(statistics))
      return;

    final int oldTableSize = nodesTableSize;

    nodesTableSize = Math.min(nodesTableSize + nodesAdvisor.adviseNodesGrowth(statistics), MAX_NODES);
    nodes = copyOf(nodes, nodesTableSize * NODE_WIDTH);

    firstFreeNode = freeNodesCount = 0;

    for(int i = nodesTableSize; i-- > oldTableSize;)
    {
      final int offset = i * NODE_WIDTH;

      nodes[offset + IDX_VAR] = -1;
      nodes[offset + IDX_NEXT] = firstFreeNode;

      firstFreeNode = i;
      freeNodesCount++;
    }

    // unchain old nodes
    for(int i = 0, end = oldTableSize * NODE_WIDTH; i < end; i += NODE_WIDTH)
      nodes[i + IDX_PREV] = 0;

    // re-chain old nodes
    for(int i = oldTableSize; i-- > 2;)
    {
      final int offset = i * NODE_WIDTH;

      if (nodes[offset + IDX_VAR] != -1)
        chainBeforeHash(i, hash(nodes[offset + IDX_VAR], nodes[offset + IDX_P0], nodes[offset + IDX_P1]));
      else
      {
        nodes[offset + IDX_NEXT] = firstFreeNode;
        firstFreeNode = i;
        freeNodesCount++;
      }
    }
  }


  protected void chainBeforeHash(int zbdd, int hash)
  {
    final int hashPrevious = hash * NODE_WIDTH + IDX_PREV;

    nodes[zbdd * NODE_WIDTH + IDX_NEXT] = nodes[hashPrevious];
    nodes[hashPrevious] = zbdd;
  }


  private void __incRef(int zbdd1, int zbdd2)
  {
    __incRef(zbdd1);
    __incRef(zbdd2);
  }


  @Contract(mutates = "this")
  public int incRef(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    checkZbdd(zbdd, "zbdd");

    return __incRef(zbdd);
  }


  protected int __incRef(int zbdd)
  {
    if (zbdd >= 2)
    {
      final int refCountOffset = zbdd * NODE_WIDTH + IDX_REFCOUNT;
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


  @SuppressWarnings("UnusedReturnValue")
  public int decRef(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    checkZbdd(zbdd, "zbdd");

    return __decRef(zbdd);
  }


  protected int __decRef(int zbdd)
  {
    if (zbdd >= 2)
    {
      final int refCountOffset = zbdd * NODE_WIDTH + IDX_REFCOUNT;
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


  private void checkZbdd(int zbdd, String param)
  {
    if (zbdd < 0 || zbdd >= nodesTableSize)
      throw new ZbddException(param + " must be in range 0.." + (nodesTableSize - 1));

    if (zbdd >= 2 && nodes[zbdd * NODE_WIDTH + IDX_VAR] == -1)
      throw new ZbddException("invalid " + param + " node " + zbdd);
  }


  private void checkVar(int var)
  {
    if (var <= 0 || var > lastVar)
      throw new ZbddException("var must be in range 1.." + var);
  }


  public @NotNull String toString(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    return getCubes(zbdd).stream()
        .map(nameResolver::getCube)
        .sorted()
        .collect(joining(", ", "{", "}"));
  }


  public @NotNull List<int[]> getCubes(@Range(from = 0, to = MAX_NODES) int zbdd)
  {
    if (zbdd == ZBDD_EMPTY)
      return emptyList();
    else if (zbdd == ZBDD_BASE)
      return singletonList(new int[0]);

    final List<int[]> cubes = new ArrayList<>(count(zbdd));

    getCubes0(cubes, new IntStack(lastVar), zbdd);

    return cubes;
  }


  private void getCubes0(List<int[]> set, IntStack vars, int zbdd)
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




  public final class Node
  {
    private final int zbdd;


    Node(int zbdd) {
      this.zbdd = zbdd;
    }


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
      // dead nodes > 40% of table size
      return tableSize > 250000 || statistics.getDeadNodes() > ((tableSize / 5) * 2);
    }
  };
}
