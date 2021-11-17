package de.sayayi.lib.zbdd;

import de.sayayi.lib.zbdd.ZbddCache.NoCache;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

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


public class Zbdd
{
  private static final int NODE_MARK = 0x80000000;

  public static final int MAX_NODES = MAX_VALUE / 5;

  private static final int IDX_VAR = 0;
  private static final int IDX_LOW = 1;
  private static final int IDX_HIGH = 2;
  private static final int IDX_PREV = 3;
  private static final int IDX_NEXT = 4;

  public static final int ZBDD_EMPTY = 0;
  public static final int ZBDD_BASE = 1;

  private int lastVar;

  private int nodesTableSize = 128;
  private short[] referenceCount;

  /**
   * 5 * zbdd + 0 = var
   * 5 * zbdd + 1 = low
   * 5 * zbdd + 2 = high
   * 5 * zbdd + 3 = previous
   * 5 * zbdd + 4 = next
   */
  private int[] nodes;

  private int firstFreeNode;
  private int freeNodesCount;
  private int deadNodesCount;
  private int nodesMinFree;

  private final IntStack stack;

  private ZbddNameResolver nameResolver = var -> "v" + var;
  private ZbddCache cache = NoCache.INSTANCE;


  public Zbdd()
  {
    stack = new IntStack(16);
    referenceCount = new short[nodesTableSize];
    nodes = new int[nodesTableSize * 5];

    deadNodesCount = 0;
    firstFreeNode = 2;
    freeNodesCount = nodesTableSize - 2;

    for(int i = 2; i < nodesTableSize; i++)
    {
      nodes[i * 5 + IDX_VAR] = -1;
      nodes[i * 5 + IDX_NEXT] = (i + 1) % nodesTableSize;
    }

    initFixedNode(ZBDD_EMPTY);
    initFixedNode(ZBDD_BASE);

    updateGrowParameters();
  }


  private void initFixedNode(int zbdd)
  {
    final int offset = zbdd * 5;

    nodes[offset + IDX_VAR] = -1;
    nodes[offset + IDX_LOW] = zbdd;
    nodes[offset + IDX_HIGH] = zbdd;

    referenceCount[zbdd] = Short.MAX_VALUE;
  }


  public void setCache(@NotNull ZbddCache cache) {
    this.cache = cache;
  }


  public void setNameResolver(@NotNull ZbddNameResolver nameResolver) {
    this.nameResolver = nameResolver;
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
  public int single(@Range(from = 1, to = MAX_VALUE) int var) {
    return mk(var, ZBDD_EMPTY, ZBDD_BASE);
  }


  @Contract(mutates = "this")
  public int cube(int @NotNull [] cubeVars)
  {
    Arrays.sort(cubeVars = Arrays.copyOf(cubeVars, cubeVars.length));

    int previousVar = -1;
    int r = ZBDD_BASE;

    for(int i = cubeVars.length; i-- > 0;)
    {
      final int var = cubeVars[i];
      if (var < 1 || var > lastVar)
        throw new ZbddException("unknown var " + var);

      if (var != previousVar)
        r = stackPop(mk(previousVar = var, ZBDD_EMPTY, stackPush(r)), 1);
    }

    return r;
  }


  @Contract(mutates = "this")
  public int universe()
  {
    int r = ZBDD_BASE;

    for(int var = 1; var <= lastVar; var++)
    {
      stackPush(r);
      r = stackPop(mk(var, r, r), 1);
    }

    return r;
  }


  @Contract(mutates = "this")
  public int subset0(int zbdd, @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd);
    checkVar(var);

    final int zbddVar = getVar(zbdd);

    if (zbddVar < var)
      return zbdd;
    if (zbddVar == var)
      return getLow(zbdd);

    return cache.lookupOrPutIfAbsent(SUBSET0, zbdd, var, () ->
        stackPop(mk(zbddVar, stackPush(subset0(getLow(zbdd), var)), stackPush(subset0(getHigh(zbdd), var))), 2)
    );
  }


  @Contract(mutates = "this")
  public int subset1(int zbdd, @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd);
    checkVar(var);

    final int zbddVar = getVar(zbdd);

    if (zbddVar < var)
      return ZBDD_EMPTY;
    if (zbddVar == var)
      return getHigh(zbdd);

    return cache.lookupOrPutIfAbsent(SUBSET1, zbdd, var, () ->
        stackPop(mk(zbddVar, stackPush(subset1(getLow(zbdd), var)), stackPush(subset1(getHigh(zbdd), var))), 2)
    );
  }


  @Contract(mutates = "this")
  public int change(int zbdd, @Range(from = 1, to = MAX_VALUE) int var)
  {
    checkZbdd(zbdd);
    checkVar(var);

    final int zbddVar = getVar(zbdd);

    if (zbddVar < var)
      return mk(var, ZBDD_EMPTY, zbdd);
    if (zbddVar == var)
      return mk(var, getHigh(zbdd), getLow(zbdd));

    return cache.lookupOrPutIfAbsent(CHANGE, zbdd, var, () ->
        stackPop(mk(zbddVar, stackPush(change(getLow(zbdd), var)), stackPush(change(getHigh(zbdd), var))), 2)
    );
  }


  @Contract(pure = true)
  public int count(int zbdd)
  {
    checkZbdd(zbdd);

    return zbdd < 2 ? zbdd : count(getLow(zbdd)) + count(getHigh(zbdd));
  }


  @Contract(mutates = "this")
  public int union(int p, int q)
  {
    if (p == ZBDD_EMPTY)
      return q;
    if (q == ZBDD_EMPTY || q == p)
      return p;

    final int pvar = getVar(p);
    final int qvar = getVar(q);

    if (pvar > qvar)
      return union(q, p);

    return cache.lookupOrPutIfAbsent(UNION, p, q, () -> pvar < qvar
        ? stackPop(mk(qvar, stackPush(union(p, getLow(q))), getHigh(q)), 1)
        : stackPop(mk(pvar, stackPush(union(getLow(p), getLow(q))), stackPush(union(getHigh(p), getHigh(q)))), 2)
    );
  }


  @Contract(mutates = "this")
  public int intersect(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == q)
      return p;

    return cache.lookupOrPutIfAbsent(INTERSECT, p, q, () -> {
      final int pvar = getVar(p);
      final int qvar = getVar(q);
      final int r;

      if (pvar > qvar)
        r = intersect(getLow(p), q);
      else if (pvar < qvar)
        r = intersect(p, getLow(q));
      else
      {
        r = stackPop(mk(pvar,
            stackPush(intersect(getLow(p), getLow(q))),
            stackPush(intersect(getHigh(p), getHigh(q)))), 2);
      }

      return r;
    });
  }


  @Contract(mutates = "this")
  public int diff(int p, int q)
  {
    if (p == ZBDD_EMPTY || p == q)
      return ZBDD_EMPTY;
    if (q == ZBDD_EMPTY)
      return p;

    return cache.lookupOrPutIfAbsent(DIFF, p, q, () -> {
      final int pvar = getVar(p);
      final int qvar = getVar(q);
      final int r;

      if (pvar < qvar)
        r = diff(p, getLow(q));
      else if (pvar > qvar)
        r = stackPop(mk(pvar, stackPush(diff(getLow(p), getLow(q))), getHigh(p)), 1);
      else
        r = stackPop(mk(pvar, stackPush(diff(getLow(p), getLow(q))), stackPush(diff(getHigh(p), getHigh(q)))), 2);

      return r;
    });
  }


  @Contract(mutates = "this")
  public int mul(int p, int q)
  {
    if (p == ZBDD_EMPTY || q == ZBDD_EMPTY)
      return ZBDD_EMPTY;
    if (p == ZBDD_BASE)
      return q;
    if (q == ZBDD_BASE)
      return p;

    final int pvar = getVar(p);
    final int qvar = getVar(q);

    if (pvar > qvar)
      return mul(q, p);

    return cache.lookupOrPutIfAbsent(MUL, p, q, () -> {
      final int r;

      if (pvar < qvar)
        r = stackPop(mk(qvar, stackPush(mul(p, getLow(q))), stackPush(mul(p, getHigh(q)))), 2);
      else
      {
        int tmp = stackPush(mul(getHigh(p), getHigh(q)));
        final int r0 = stackPop(union(tmp, stackPush(mul(getHigh(p), getLow(q)))), 2);

        stackPush(tmp);
        tmp = stackPop(union(r0, stackPush(mul(getLow(p), getHigh(q)))), 2);

        stackPush(tmp);
        r = stackPop(mk(pvar, stackPush(mul(getLow(p), getLow(q))), tmp), 2);
      }

      return r;
    });
  }


  @Contract(mutates = "this")
  public int div(int p, int q)
  {
    if (p < 2)
      return ZBDD_EMPTY;
    if (p == q)
      return ZBDD_BASE;
    if (q == ZBDD_BASE)
      return p;

    return cache.lookupOrPutIfAbsent(DIV, p, q, () -> {
      final int pvar = getVar(p);
      final int qvar = getVar(q);
      final int r;

      if (pvar > qvar)
        r = stackPop(mk(pvar, stackPush(div(getLow(p), q)), stackPush(div(getHigh(p), q))), 2);
      else
      {
        int r0 = div(getHigh(p), getHigh(q));
        int tmp = getLow(p);

        if (tmp != ZBDD_EMPTY && r0 != ZBDD_EMPTY)
        {
          stackPush(r0);
          r0 = stackPop(intersect(stackPush(div(getLow(p), tmp)), r0), 2);
        }

        r = r0;
      }

      return r;
    });
  }


  @Contract(mutates = "this")
  public int mod(int p, int q)
  {
    return cache.lookupOrPutIfAbsent(MOD, p, q, () ->
        stackPop(diff(p, stackPush(mul(q, stackPush(div(p, q))))), 2)
    );
  }


  protected int mk(@Range(from = 1, to = MAX_VALUE) int var, int low, int high)
  {
    if (high == ZBDD_EMPTY)
      return low;

    int hash = hash(var, low, high);
    int r = nodes[hash * 5 + IDX_PREV];

    // find node in chain...
    while(r != 0)
    {
      final int offset = r * 5;

      if (nodes[offset + IDX_VAR] == var && nodes[offset + IDX_LOW] == low && nodes[offset + IDX_HIGH] == high)
        return r;

      r = nodes[offset + IDX_NEXT];
    }

    if (freeNodesCount < 2)
    {
      ensureMinFreeCapacity();
      hash = hash(var, low, high);  // may have changed due to grow
    }

    r = firstFreeNode;
    final int offset = r * 5;
    firstFreeNode = nodes[offset + IDX_NEXT];
    freeNodesCount--;

    // set new node
    nodes[offset + IDX_VAR] = var;
    nodes[offset + IDX_LOW] = low;
    nodes[offset + IDX_HIGH] = high;
    referenceCount[r] = -1;

    chainBeforeHash(r, hash);

    return r;
  }


  protected void updateGrowParameters() {
    nodesMinFree = Math.min(nodesTableSize * 20 / 100, 99999);
  }


  protected int getVar(int zbdd) {
    return zbdd < 2 ? -1 : (nodes[zbdd * 5 + IDX_VAR] & ~NODE_MARK);
  }


  protected int getLow(int zbdd) {
    return nodes[zbdd * 5 + IDX_LOW];
  }


  protected int getHigh(int zbdd) {
    return nodes[zbdd * 5 + IDX_HIGH];
  }


  @Contract(pure = true)
  protected int hash(int var, int low, int high) {
    return ((var * 12582917 + low * 4256249 + high * 741457) & 0x7fffffff) % nodesTableSize;
  }


  protected int gc()
  {
    // mark stack nodes...
    stack.forEach(this::gc_markTree);

    // mark referenced nodes...
    for(int i = nodesTableSize; i-- > 0;)
    {
      final int offset = i * 5;

      if (nodes[offset + IDX_VAR] != -1 && referenceCount[i] > 0)
        gc_markTree(i);

      nodes[offset + IDX_PREV] = 0;
    }

    final int oldFree = freeNodesCount;
    firstFreeNode = freeNodesCount = 0;

    for(int i = nodesTableSize; i-- > 2;)
    {
      final int offset = i * 5;

      if ((nodes[offset + IDX_VAR] & NODE_MARK) != 0 && nodes[offset + IDX_VAR] != -1)
      {
        // remove mark and chain valid node
        chainBeforeHash(i,
            hash(nodes[offset + IDX_VAR] &= ~NODE_MARK, nodes[offset + IDX_LOW], nodes[offset + IDX_HIGH]));
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

    return freeNodesCount - oldFree;
  }


  private void gc_markTree(int zbdd)
  {
    if (zbdd >= 2)
    {
      final int offset = zbdd * 5;

      if ((nodes[offset + IDX_VAR] & NODE_MARK) == 0)
      {
        nodes[offset + IDX_VAR] |= NODE_MARK;

        gc_markTree(nodes[offset + IDX_LOW]);
        gc_markTree(nodes[offset + IDX_HIGH]);
      }
    }
  }


  protected void ensureMinFreeCapacity()
  {
    if (deadNodesCount > 0 || nodesTableSize > 20000)
    {
      if (gc() >= nodesMinFree)
        return;
    }

    final int oldTableSize = nodesTableSize;

    nodesTableSize += computeIncreaseLimit(nodesMinFree);

    nodes = Arrays.copyOf(nodes, nodesTableSize * 5);
    referenceCount = Arrays.copyOf(referenceCount, nodesTableSize);

    firstFreeNode = freeNodesCount = 0;

    for(int i = nodesTableSize; i-- > oldTableSize;)
    {
      final int offset = i * 5;

      nodes[offset + IDX_VAR] = -1;
      nodes[offset + IDX_NEXT] = firstFreeNode;

      firstFreeNode = i;
      freeNodesCount++;
    }

    // unchain old nodes
    for(int i = 0, end = oldTableSize * 5; i < end; i += 5)
      nodes[i + IDX_PREV] = 0;

    // re-chain old nodes
    for(int i = oldTableSize; i-- > 2;)
    {
      final int offset = i * 5;

      if (nodes[offset + IDX_VAR] != -1)
        chainBeforeHash(i, hash(nodes[offset + IDX_VAR], nodes[offset + IDX_LOW], nodes[offset + IDX_HIGH]));
      else
      {
        nodes[offset + IDX_NEXT] = firstFreeNode;
        firstFreeNode = i;
        freeNodesCount++;
      }
    }

    updateGrowParameters();
  }


  protected int computeIncreaseLimit(int currentSize)
  {
    if (currentSize <= 200000)
      return 300000;
    if (currentSize >= 4000000)
      return 50000;

    return 300000 - ((currentSize - 200000) * (300000 - 50000)) / (4000000 - 200000);
  }


  protected void chainBeforeHash(int zbdd, int hash)
  {
    final int hashPrevious = hash * 5 + IDX_PREV;

    nodes[zbdd * 5 + IDX_NEXT] = nodes[hashPrevious];
    nodes[hashPrevious] = zbdd;
  }


  public int incReference(int zbdd)
  {
    checkZbdd(zbdd);

    short ref = referenceCount[zbdd];
    if (ref == -1)
      ref = 1;
    else if (ref == 0)
    {
      ref = 1;
      deadNodesCount--;
    }
    else if (ref < Short.MAX_VALUE)
      ref++;

    referenceCount[zbdd] = ref;

    return zbdd;
  }


  public int decReference(int zbdd)
  {
    checkZbdd(zbdd);

    short ref = referenceCount[zbdd];
    if (ref == 1)
    {
      ref = 0;
      deadNodesCount++;
    }
    else if (ref > 0 && ref < Short.MAX_VALUE)
      ref--;

    referenceCount[zbdd] = ref;

    return zbdd;
  }


  public int getReferenceCount(int zbdd)
  {
    checkZbdd(zbdd);

    final short ref = referenceCount[zbdd];
    return ref == -1 ? 0 : ref;
  }


  private int stackPush(int zbdd)
  {
    stack.push(zbdd);
    return zbdd;
  }


  private int stackPop(int result, int n)
  {
    stack.drop(n);
    return result;
  }


  private void checkZbdd(int zbdd)
  {
    if (zbdd < 0 || zbdd >= nodesTableSize)
      throw new IllegalArgumentException("zbdd must be in range 0.." + (nodesTableSize - 1));
  }


  private void checkVar(int var)
  {
    if (var <= 0 || var > lastVar)
      throw new IllegalArgumentException("var must be in range 1.." + var);
  }


  public String printSet(int zbdd)
  {
    if (zbdd == ZBDD_EMPTY)
      return nameResolver.getEmptyName();
    else if (zbdd == ZBDD_BASE)
      return nameResolver.getBaseName();

    Set<String> set = new LinkedHashSet<>();

    printSet_cubes(set, new IntStack(lastVar), zbdd);

    return set.toString();
  }


  private void printSet_cubes(Set<String> set, IntStack vars, int zbdd)
  {
    if (zbdd == ZBDD_BASE)
      set.add(nameResolver.getCube(vars.getStack()));
    else if (zbdd != ZBDD_EMPTY)
    {
      vars.push(getVar(zbdd));
      printSet_cubes(set, vars, getHigh(zbdd));
      vars.pop();
      printSet_cubes(set, vars, getLow(zbdd));
    }
  }
}
