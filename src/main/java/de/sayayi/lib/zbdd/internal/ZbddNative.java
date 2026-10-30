/*
 * Copyright 2026 Jeroen Gremmen
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

import java.lang.ref.Cleaner;
import java.math.BigInteger;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.lang.Math.round;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;


/**
 * {@link Zbdd} implementation backed by a native library, which holds and processes all nodes in its own memory. It
 * behaves like {@link ZbddImpl}; node numbers are identical for the same sequence of operations. Node lookups are lower,
 * as operation results are cached.
 * <p>
 * The operation cache is implemented by the native library and occupies native memory. As it isn't a
 * {@link de.sayayi.lib.zbdd.cache.ZbddCache ZbddCache}, it cannot be retrieved or replaced.
 * <p>
 * This class is not thread-safe. Unsynchronized concurrent access can crash the JVM, as opposed to corrupting the
 * instance only. Use {@link de.sayayi.lib.zbdd.ZbddFactory#asConcurrent(Zbdd)} to obtain a thread-safe wrapper.
 * <p>
 * The native memory is released when the instance becomes unreachable, or earlier by invoking {@link #close()}. Natives
 * are instance methods on purpose: JNI keeps the receiver reachable during the call, so the {@link Cleaner} cannot
 * release the memory while an operation is running.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@ApiStatus.Internal
public final class ZbddNative implements Zbdd.Native
{
  // error codes, see zb_core.h
  private static final int ERR_RANGE = -1;
  private static final int ERR_INVALID = -2;
  private static final int ERR_VAR = -3;
  private static final int ERR_VAR_ORDER = -4;
  private static final int ERR_DIV_EMPTY = -5;
  private static final int ERR_CAPACITY = -6;
  private static final int ERR_VAR_COUNT = -7;
  private static final int ERR_NOMEM = -9;
  private static final int ERR_COUNT = -10;
  private static final int ERR_CB_GC = -12;
  private static final int ERR_CB_CLEAR = -13;
  private static final int ITER_END = -100;

  // parameter names reported with an error, see zb_core.h
  private static final String[] PARAM_NAMES = { "zbdd", "p", "q", "p0", "p1" };

  // callback events, see zb_core.h
  private static final int EVENT_BEFORE_CLEAR = 0;
  private static final int EVENT_AFTER_CLEAR = 1;
  private static final int EVENT_BEFORE_GC = 2;
  private static final int EVENT_AFTER_GC = 3;

  // phases of clear, see zb_core.h
  private static final int CLEAR_BEFORE = 0;
  private static final int CLEAR_RESET = 1;
  private static final int CLEAR_AFTER = 2;

  // statistics, see zb_core.h
  private static final int STAT_CAPACITY = 0;
  private static final int STAT_FREE = 1;
  private static final int STAT_DEAD = 2;
  private static final int STAT_NEW = 3;
  private static final int STAT_LOOKUPS = 4;
  private static final int STAT_HITS = 5;
  private static final int STAT_GC_COUNT = 6;
  private static final int STAT_GC_FREED = 7;
  private static final int STAT_CAPACITY_INCREASES = 8;
  private static final int STAT_MEMORY = 9;
  private static final int STAT_VARS = 10;
  private static final int STAT_CACHE_ENTRIES = 11;
  private static final int STAT_CACHE_LOOKUPS = 12;
  private static final int STAT_CACHE_HITS = 13;

  private static final Cleaner CLEANER = Cleaner.create();

  private final @NotNull ZbddCapacityAdvisor capacityAdvisor;
  private final @NotNull Statistics statistics;
  private final @NotNull IntObjectMap varObjectMap;
  private final @NotNull List<ZbddCallback> callbacks;
  private final @NotNull Cleaner.Cleanable cleanable;

  private long handle;

  // number of visitors, callbacks and capacity advisor methods currently running, which prevent closing this instance
  private int userCodeDepth;

  private @NotNull ZbddLiteralResolver literalResolver = var -> "v" + var;


  /**
   * Creates a new native ZBDD instance with a small operation cache: one entry for each node of the initial capacity,
   * so the cache takes about as much memory as the initial node table, and at least 1024 entries. The cache doesn't
   * grow along with the node table.
   *
   * @param capacityAdvisor  capacity advisor, not {@code null}
   *
   * @throws ZbddException  if the native library is not available
   */
  public ZbddNative(@NotNull ZbddCapacityAdvisor capacityAdvisor) {
    this(capacityAdvisor, capacityAdvisor.getInitialCapacity());
  }


  /**
   * Creates a new native ZBDD instance with the given size of the operation cache.
   *
   * @param capacityAdvisor  capacity advisor, not {@code null}
   * @param cacheEntries     requested number of cache entries, rounded up to the next power of 2 between 1024 and 2^30
   *
   * @throws ZbddException  if the native library is not available
   */
  public ZbddNative(@NotNull ZbddCapacityAdvisor capacityAdvisor, int cacheEntries)
  {
    NativeLoader.ensureLoaded();

    this.capacityAdvisor = capacityAdvisor;

    //noinspection ConstantConditions
    handle = nCreate(capacityAdvisor.getInitialCapacity(), cacheEntries,
        capacityAdvisor != DefaultCapacityAdvisor.INSTANCE);
    if (handle == 0)
      throw new OutOfMemoryError("native zbdd allocation failed");

    // the state must not reference this instance, or it would never become unreachable
    cleanable = CLEANER.register(this, new Release(handle));

    statistics = new Statistics();
    varObjectMap = new IntObjectMap();
    callbacks = new CopyOnWriteArrayList<>();
  }


  /**
   * {@inheritDoc}
   */
  @Override
  @Contract(mutates = "this")
  public void close()
  {
    if (userCodeDepth != 0)
      throw new ZbddException("close must not be invoked from a visitor, callback or capacity advisor");

    handle = 0;
    cleanable.clean();
  }


  /**
   * Tells whether the native library can be used, loading it if this hasn't been attempted yet.
   *
   * @return  {@code true} if native instances can be created, {@code false} if the platform is not supported or the
   *          library cannot be loaded
   */
  public static boolean isAvailable() {
    return NativeLoader.isAvailable();
  }


  /**
   * Returns the number of native contexts which have not been released, across all instances.
   *
   * @return  number of native contexts &ge; 0
   */
  static long liveContexts()
  {
    NativeLoader.ensureLoaded();
    return nLiveContexts();
  }


  /**
   * Returns the number of entries of the operation cache.
   *
   * @return  number of cache entries
   */
  @Contract(pure = true)
  long cacheEntries() {
    return nStat(handle(), STAT_CACHE_ENTRIES);
  }


  /**
   * Returns the number of lookups in the operation cache, since creating or clearing this instance.
   *
   * @return  number of cache lookups
   */
  @Contract(pure = true)
  long cacheLookups() {
    return nStat(handle(), STAT_CACHE_LOOKUPS);
  }


  /**
   * Returns the number of lookups in the operation cache finding a result, since creating or clearing this instance.
   *
   * @return  number of cache hits
   */
  @Contract(pure = true)
  long cacheHits() {
    return nStat(handle(), STAT_CACHE_HITS);
  }


  @Contract(pure = true)
  private long handle()
  {
    final var h = handle;
    if (h == 0)
      throw new ZbddException("zbdd has been closed");

    return h;
  }


  @Contract(value = "_ -> param1", pure = true)
  private int check(int result) {
    return result >= 0 ? result : throwError(result);
  }


  /**
   * Throws the exception for the given error code, with the same type and message as {@link ZbddImpl}.
   */
  private int throwError(int code)
  {
    if (code == ERR_NOMEM)
      throw new OutOfMemoryError("native zbdd allocation failed");

    final var error = new int[5];

    nLastError(handle, error);

    final var value = error[0];
    final var limit = error[1];
    final var param = paramName(error[2]);

    throw switch(code) {
      case ERR_RANGE -> new ZbddOutOfRangeException(value, limit, param + " must be in range 0.." + limit);
      case ERR_INVALID -> new InvalidZbddException(value, "invalid " + param + " node " + value);
      case ERR_VAR -> new InvalidVarException(value, limit, "var must be in range 1.." + limit);
      case ERR_VAR_ORDER -> new InvalidZbddException(value, param + " node " + value + " has var " + limit +
          ", which must be lower than var " + error[3]);
      case ERR_DIV_EMPTY -> new ZbddException("division by empty set");
      case ERR_CAPACITY -> new ZbddException("nodes capacity exhausted");
      case ERR_VAR_COUNT -> new ZbddException("variable count exceeded");
      case ERR_COUNT -> new ArithmeticException("number of cubes exceeds " + Integer.MAX_VALUE +
          ", use countExact instead");
      case ERR_CB_GC -> new ZbddException("gc must not be invoked from a zbdd callback");
      case ERR_CB_CLEAR -> new ZbddException("clear must not be invoked from a zbdd callback");
      default -> new IllegalStateException("unexpected native zbdd error " + code);
    };
  }


  /**
   * Returns the error code of a native method which reported a failure by returning {@code null} or {@code 0}, in case
   * no exception is pending.
   */
  private int lastErrorCode()
  {
    final var error = new int[5];

    nLastError(handle, error);

    return error[4];
  }


  @Contract(pure = true)
  private static @NotNull String paramName(int param) {
    return param < PARAM_NAMES.length ? PARAM_NAMES[param] : "p[" + (param - PARAM_NAMES.length) + ']';
  }


  /** Invoked by the native library before or after garbage collection. */
  @SuppressWarnings("unused")
  private void upcallCallbacks(int event)
  {
    userCodeDepth++;
    try {
      for(final var callback: callbacks)
      {
        switch(event) {
          case EVENT_BEFORE_CLEAR -> callback.beforeClear();
          case EVENT_AFTER_CLEAR -> callback.afterClear();
          case EVENT_BEFORE_GC -> callback.beforeGc();
          case EVENT_AFTER_GC -> callback.afterGc();
          default -> throw new IllegalStateException("unexpected native zbdd event " + event);
        }
      }
    } finally {
      userCodeDepth--;
    }
  }


  /** Invoked by the native library, if a custom capacity advisor is used. */
  @SuppressWarnings("unused")
  private boolean upcallIsGcRequired()
  {
    userCodeDepth++;
    try {
      return capacityAdvisor.isGCRequired(statistics);
    } finally {
      userCodeDepth--;
    }
  }


  /** Invoked by the native library, if a custom capacity advisor is used. */
  @SuppressWarnings("unused")
  private int upcallMinFreeNodes()
  {
    userCodeDepth++;
    try {
      return capacityAdvisor.getMinimumFreeNodes(statistics);
    } finally {
      userCodeDepth--;
    }
  }


  /** Invoked by the native library, if a custom capacity advisor is used. */
  @SuppressWarnings("unused")
  private int upcallAdviseIncrement()
  {
    userCodeDepth++;
    try {
      return capacityAdvisor.adviseIncrement(statistics);
    } finally {
      userCodeDepth--;
    }
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public void registerCallback(@NotNull ZbddCallback callback)
  {
    final var h = handle();

    callbacks.add(requireNonNull(callback));
    nSetHasCallbacks(h);
  }


  /** {@inheritDoc} */
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


  /** {@inheritDoc} */
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
    final var h = handle();

    check(nClear(h, CLEAR_BEFORE));
    check(nClear(h, CLEAR_RESET));
    varObjectMap.clear();
    check(nClear(h, CLEAR_AFTER));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int createVar() {
    return check(nCreateVar(handle()));
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
    return (T)varObjectMap.get(check(nCheckVar(handle(), var)));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int cube(int var) {
    return check(nCube1(handle(), var));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int cube(int @NotNull ... cubeVars)
  {
    final var h = handle();

    return cubeVars.length == 0 ? BASE : check(nCubeN(h, cubeVars));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public boolean hasCubeWithVar(int zbdd, int var) {
    return check(nHasCubeWithVar(handle(), zbdd, var)) != 0;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int subset0(int zbdd, int var) {
    return check(nSubset0(handle(), zbdd, var));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int subset0(int zbdd, int @NotNull ... vars)
  {
    // JNI would crash the JVM on a null array
    requireNonNull(vars, "vars must not be null");

    return check(nSubset0N(handle(), zbdd, vars));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int subset1(int zbdd, int var) {
    return check(nSubset1(handle(), zbdd, var));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int change(int zbdd, int var) {
    return check(nChange(handle(), zbdd, var));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public int count(int zbdd) {
    return check(nCount(handle(), zbdd));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public @NotNull BigInteger countExact(int zbdd)
  {
    final var magnitude = nCountExact(handle(), zbdd);
    if (magnitude == null)
      throwError(lastErrorCode());

    return new BigInteger(1, magnitude);
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int union(int... p)
  {
    if (p.length == 0)
      throw new IllegalArgumentException("at least one ZBDD node must be provided");

    return check(nUnionN(handle(), p));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int union(int p, int q) {
    return check(nUnion(handle(), p, q));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int intersect(int p, int q) {
    return check(nIntersect(handle(), p, q));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int difference(int p, int q) {
    return check(nDifference(handle(), p, q));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int multiply(int p, int q) {
    return check(nMultiply(handle(), p, q));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int divide(int p, int q) {
    return check(nDivide(handle(), p, q));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int modulo(int p, int q) {
    return check(nModulo(handle(), p, q));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int atomize(int zbdd) {
    return check(nAtomize(handle(), zbdd));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int removeBase(int zbdd) {
    return check(nRemoveBase(handle(), zbdd));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public boolean contains(int p, int q) {
    return check(nContains(handle(), p, q)) != 0;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  public int getNode(int var, int p0, int p1) {
    return check(nGetNode(handle(), var, p0, p1));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public int getVar(int zbdd)
  {
    // terminal nodes are reported as 0 by the native library, as negative results are errors
    final var var = check(nGetVar(handle(), zbdd));

    return var == 0 ? -1 : var;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public int getP0(int zbdd) {
    return check(nGetP0(handle(), zbdd));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public int getP1(int zbdd) {
    return check(nGetP1(handle(), zbdd));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(mutates = "this")
  @SuppressWarnings("UnusedReturnValue")
  public int gc() {
    return check(nGc(handle()));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(value = "_ -> param1", mutates = "this")
  public int incRef(int zbdd) {
    return check(nIncRef(handle(), zbdd));
  }


  /** {@inheritDoc} */
  @Override
  @Contract(value = "_ -> param1", mutates = "this")
  @SuppressWarnings("UnusedReturnValue")
  public int decRef(int zbdd)
  {
    final var underflow = check(nDecRef(handle(), zbdd));

    // underflow checked only if assertions are enabled (-ea)
    assert underflow == 0 :
        "refcount underflow on zbdd node " + zbdd + " (decRef without matching incRef)";

    return zbdd;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public boolean isValidZbdd(int zbdd) {
    return nIsValidZbdd(handle(), zbdd) != 0;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public boolean isValidVar(int var) {
    return nIsValidVar(handle(), var) != 0;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(pure = true)
  public @NotNull ZbddNodeInfo getZbddNodeInfo(int zbdd)
  {
    check(nGetVar(handle(), zbdd));

    return new ZbddNodeInfoDelegate(zbdd);
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
  public boolean visitCubeZbdds(int zbdd, @NotNull CubeZbddVisitor visitor)
  {
    requireNonNull(visitor, "visitor must not be null");

    final var h = handle();

    if (zbdd == EMPTY)
      return true;

    final var iterator = openIterator(h, zbdd);

    userCodeDepth++;
    try {
      for(int cubeZbdd; (cubeZbdd = nIterNextZbdd(h, iterator)) != ITER_END;)
        if (!visitor.visitZbdd(check(cubeZbdd)))
          return false;

      return true;
    } finally {
      userCodeDepth--;
      nIterClose(h, iterator);
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean visitCubes(int zbdd, @NotNull CubeVisitor visitor)
  {
    requireNonNull(visitor, "visitor must not be null");

    final var h = handle();

    if (zbdd == EMPTY)
      return true;

    final var iterator = openIterator(h, zbdd);

    userCodeDepth++;
    try {
      for(;;)
      {
        final var cube = nIterNextCube(h, iterator);

        if (cube == null)
        {
          final var code = lastErrorCode();
          if (code == ITER_END)
            return true;

          throwError(code);
        }

        if (!visitor.visitCube(cube))
          return false;
      }
    } finally {
      userCodeDepth--;
      nIterClose(h, iterator);
    }
  }


  private long openIterator(long h, int zbdd)
  {
    final var iterator = nIterOpen(h, zbdd);
    if (iterator == 0)
      throwError(lastErrorCode());

    return iterator;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(value = "-> new", mutates = "this")
  public int @NotNull [] calculateNodeDependency()
  {
    final var sequence = nDependency(handle());
    if (sequence == null)
      throwError(lastErrorCode());

    return sequence;
  }


  /** {@inheritDoc} */
  @Override
  @Contract(value = "_ -> new", mutates = "this")
  public int @NotNull [] asSingleCubeZbdds(int zbdd)
  {
    final var cubes = nAsSingleCubes(handle(), zbdd);
    if (cubes == null)
      throwError(lastErrorCode());

    return cubes;
  }


  private static native long nCreate(int capacity, int cacheEntries, boolean customAdvisor);
  private static native void nDestroy(long handle);
  private static native long nLiveContexts();

  private native long nStat(long handle, int stat);
  private native void nSetHasCallbacks(long handle);
  private native void nLastError(long handle, int[] error);
  private native int nCreateVar(long handle);
  private native int nIsValidVar(long handle, int var);
  private native int nCheckVar(long handle, int var);
  private native int nGetVar(long handle, int zbdd);
  private native int nGetP0(long handle, int zbdd);
  private native int nGetP1(long handle, int zbdd);
  private native int nGetNode(long handle, int var, int p0, int p1);
  private native int nCube1(long handle, int var);
  private native int nCubeN(long handle, int[] vars);
  private native int nHasCubeWithVar(long handle, int zbdd, int var);
  private native int nSubset0(long handle, int zbdd, int var);
  private native int nSubset0N(long handle, int zbdd, int[] vars);
  private native int nSubset1(long handle, int zbdd, int var);
  private native int nChange(long handle, int zbdd, int var);
  private native int nCount(long handle, int zbdd);
  private native byte[] nCountExact(long handle, int zbdd);
  private native int nUnion(long handle, int p, int q);
  private native int nUnionN(long handle, int[] p);
  private native int nIntersect(long handle, int p, int q);
  private native int nDifference(long handle, int p, int q);
  private native int nMultiply(long handle, int p, int q);
  private native int nDivide(long handle, int p, int q);
  private native int nModulo(long handle, int p, int q);
  private native int nAtomize(long handle, int zbdd);
  private native int nRemoveBase(long handle, int zbdd);
  private native int nContains(long handle, int p, int q);
  private native int nGc(long handle);
  private native int nClear(long handle, int phase);
  private native int nIncRef(long handle, int zbdd);
  private native int nDecRef(long handle, int zbdd);
  private native int nGetRef(long handle, int zbdd);
  private native int nIsValidZbdd(long handle, int zbdd);
  private native int[] nDependency(long handle);
  private native int[] nAsSingleCubes(long handle, int zbdd);
  private native long nIterOpen(long handle, int zbdd);
  private native int[] nIterNextCube(long handle, long iterator);
  private native int nIterNextZbdd(long handle, long iterator);
  private native void nIterClose(long handle, long iterator);




  /**
   * Releases the native memory. This state is held by the {@link Cleaner} and therefore must not refer to the instance.
   */
  private record Release(long handle) implements Runnable
  {
    @Override
    public void run() {
      nDestroy(handle);
    }
  }




  /**
   * Live statistics view for this ZBDD instance, implementing {@link ZbddStatistics}.
   */
  private final class Statistics implements ZbddStatistics
  {
    private Statistics() {
    }


    @Override
    public int getNodesCapacity() {
      return (int)nStat(handle(), STAT_CAPACITY);
    }


    @Override
    public int getFreeNodes() {
      return (int)nStat(handle(), STAT_FREE);
    }


    @Override
    public int getDeadNodes() {
      return (int)nStat(handle(), STAT_DEAD);
    }


    @Override
    public int getNewNodes() {
      return (int)nStat(handle(), STAT_NEW);
    }


    @Override
    public long getNodeLookups() {
      return nStat(handle(), STAT_LOOKUPS);
    }


    @Override
    public long getNodeLookupHitCount() {
      return nStat(handle(), STAT_HITS);
    }


    @Override
    public int getGCCount() {
      return (int)nStat(handle(), STAT_GC_COUNT);
    }


    @Override
    public long getGCFreedNodes() {
      return nStat(handle(), STAT_GC_FREED);
    }


    @Override
    public int getCapacityIncreaseCount() {
      return (int)nStat(handle(), STAT_CAPACITY_INCREASES);
    }


    @Override
    public long getMemoryUsage() {
      return nStat(handle(), STAT_MEMORY) + varObjectMap.getMemoryUsage();
    }


    @Override
    public int getRegisteredVars() {
      return (int)nStat(handle(), STAT_VARS);
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
    public int getZbdd()
    {
      check(nGetVar(handle(), zbdd));
      return zbdd;
    }


    @Override
    @Contract(pure = true)
    public int getVar() {
      return ZbddNative.this.getVar(zbdd);
    }


    @Override
    @Contract(pure = true)
    public int getP0() {
      return ZbddNative.this.getP0(zbdd);
    }


    @Override
    @Contract(pure = true)
    public int getP1() {
      return ZbddNative.this.getP1(zbdd);
    }


    @Override
    @Contract(pure = true)
    public int getReferenceCount() {
      // -1 denotes a new node, not an error, as the node has been validated
      return nGetRef(handle(), getZbdd());
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

      final var p0 = ZbddNative.this.getP0(zbdd);
      s.append(",P0=").append(p0);
      final var p0var = ZbddNative.this.getVar(p0);
      if (p0var != -1)
      {
        final var p0literal = literalResolver.getLiteralName(p0var);
        if (!p0literal.isBlank())
          s.append(':').append(p0literal);
      }

      final var p1 = ZbddNative.this.getP1(zbdd);
      s.append(",P1=").append(p1);
      final var p1var = ZbddNative.this.getVar(p1);
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
