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
package de.sayayi.lib.zbdd.internal;

import de.sayayi.lib.zbdd.Zbdd;
import de.sayayi.lib.zbdd.ZbddLiteralResolver;
import de.sayayi.lib.zbdd.ZbddStatistics;
import de.sayayi.lib.zbdd.cache.ZbddCache;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;


/**
 * Thread-safe wrapper around a {@link Zbdd} instance. Read-only operations use a read lock and mutating operations
 * use a write lock from a {@link ReentrantReadWriteLock} to allow concurrent reads while serializing writes.
 * <p>
 * Because the write lock is reentrant, a callback invoked synchronously during a locked operation (e.g.
 * {@link de.sayayi.lib.zbdd.Zbdd.ZbddCallback#beforeGc() ZbddCallback.beforeGc()} or a user-supplied
 * {@link de.sayayi.lib.zbdd.Zbdd.CubeVisitor CubeVisitor}/{@link de.sayayi.lib.zbdd.Zbdd.ZbddVisitor ZbddVisitor})
 * can safely re-enter other {@code Zbdd} methods on the same thread without deadlocking.
 * <p>
 * Instances are created through {@link de.sayayi.lib.zbdd.ZbddFactory#asConcurrent(Zbdd) ZbddFactory.asConcurrent}.
 *
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
@ApiStatus.Internal
public sealed class ZbddConcurrent implements Zbdd.Concurrent permits ZbddConcurrent.WithCache
{
  protected final Zbdd zbdd;
  protected final Lock readLock;
  protected final Lock writeLock;

  private final ZbddStatistics statistics;


  /**
   * Creates a new concurrent wrapper around the given ZBDD instance.
   *
   * @param zbdd  ZBDD instance to wrap, not {@code null}
   */
  public ZbddConcurrent(@NotNull Zbdd zbdd)
  {
    this.zbdd = zbdd;

    final var readWriteLock = new ReentrantReadWriteLock();

    this.readLock = readWriteLock.readLock();
    this.writeLock = readWriteLock.writeLock();

    statistics = new Statistics(zbdd.getStatistics());
  }


  /** {@inheritDoc} */
  @Override
  public <T> T doAtomic(@NotNull Function<Zbdd,T> operation)
  {
    writeLock.lock();
    try {
      return operation.apply(this);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public void doAtomic(@NotNull Consumer<Zbdd> operation)
  {
    writeLock.lock();
    try {
      operation.accept(this);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public void registerCallback(@NotNull ZbddCallback callback)
  {
    writeLock.lock();
    try {
      zbdd.registerCallback(callback);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public @NotNull ZbddLiteralResolver getLiteralResolver()
  {
    readLock.lock();
    try {
      return zbdd.getLiteralResolver();
    } finally {
      readLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public void setLiteralResolver(@NotNull ZbddLiteralResolver literalResolver)
  {
    writeLock.lock();
    try {
      zbdd.setLiteralResolver(literalResolver);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public @NotNull ZbddStatistics getStatistics() {
    return statistics;
  }


  /** {@inheritDoc} */
  @Override
  public void clear()
  {
    writeLock.lock();
    try {
      zbdd.clear();
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int createVar()
  {
    writeLock.lock();
    try {
      return zbdd.createVar();
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int createVar(@NotNull Object varObject)
  {
    writeLock.lock();
    try {
      return zbdd.createVar(varObject);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public <T> T getVarObject(int var)
  {
    readLock.lock();
    try {
      return zbdd.getVarObject(var);
    } finally {
      readLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int cube(int var)
  {
    writeLock.lock();
    try {
      return zbdd.cube(var);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int cube(int @NotNull ... cubeVars)
  {
    writeLock.lock();
    try {
      return zbdd.cube(cubeVars);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean hasCubeWithVar(int zbdd, int var)
  {
    readLock.lock();
    try {
      return this.zbdd.hasCubeWithVar(zbdd, var);
    } finally {
      readLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int subset0(int zbdd, int var)
  {
    writeLock.lock();
    try {
      return this.zbdd.subset0(zbdd, var);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int subset1(int zbdd, int var)
  {
    writeLock.lock();
    try {
      return this.zbdd.subset1(zbdd, var);
    } finally {
      writeLock.unlock();
    }
  }

  /** {@inheritDoc} */
  @Override
  public int change(int zbdd, int var)
  {
    writeLock.lock();
    try {
      return this.zbdd.change(zbdd, var);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public long count(int zbdd)
  {
    readLock.lock();
    try {
      return this.zbdd.count(zbdd);
    } finally {
      readLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int union(int... p)
  {
    writeLock.lock();
    try {
      return zbdd.union(p);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int union(int p, int q)
  {
    writeLock.lock();
    try {
      return zbdd.union(p, q);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int intersect(int p, int q)
  {
    writeLock.lock();
    try {
      return zbdd.intersect(p, q);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int difference(int p, int q)
  {
    writeLock.lock();
    try {
      return zbdd.difference(p, q);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int multiply(int p, int q)
  {
    writeLock.lock();
    try {
      return zbdd.multiply(p, q);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int divide(int p, int q)
  {
    writeLock.lock();
    try {
      return zbdd.divide(p, q);
    } finally {
      writeLock.unlock();
    }
  }

  /** {@inheritDoc} */
  @Override
  public int modulo(int p, int q)
  {
    writeLock.lock();
    try {
      return zbdd.modulo(p, q);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int atomize(int zbdd)
  {
    writeLock.lock();
    try {
      return this.zbdd.atomize(zbdd);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int removeBase(int zbdd)
  {
    writeLock.lock();
    try {
      return this.zbdd.removeBase(zbdd);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean contains(int p, int q)
  {
    writeLock.lock();
    try {
      return zbdd.contains(p, q);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean isValidZbdd(int zbdd)
  {
    readLock.lock();
    try {
      return this.zbdd.isValidZbdd(zbdd);
    } finally {
      readLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean isValidVar(int var)
  {
    readLock.lock();
    try {
      return zbdd.isValidVar(var);
    } finally {
      readLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int getVar(int zbdd)
  {
    readLock.lock();
    try {
      return this.zbdd.getVar(zbdd);
    } finally {
      readLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int getP0(int zbdd)
  {
    readLock.lock();
    try {
      return this.zbdd.getP0(zbdd);
    } finally {
      readLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int getP1(int zbdd)
  {
    readLock.lock();
    try {
      return this.zbdd.getP1(zbdd);
    } finally {
      readLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int getNode(int var, int p0, int p1)
  {
    writeLock.lock();
    try {
      return zbdd.getNode(var, p0, p1);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int gc()
  {
    writeLock.lock();
    try {
      return zbdd.gc();
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int incRef(int zbdd)
  {
    writeLock.lock();
    try {
      return this.zbdd.incRef(zbdd);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  @SuppressWarnings("MethodDoesntCallSuperMethod")
  public int @NotNull [] incRef(int @NotNull [] zbdds)
  {
    writeLock.lock();
    try {
      return this.zbdd.incRef(zbdds);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int decRef(int zbdd)
  {
    writeLock.lock();
    try {
      return this.zbdd.decRef(zbdd);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  @SuppressWarnings("MethodDoesntCallSuperMethod")
  public int @NotNull [] decRef(int @NotNull [] zbdds)
  {
    writeLock.lock();
    try {
      return this.zbdd.decRef(zbdds);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public @NotNull ZbddNodeInfo getZbddNodeInfo(int zbdd)
  {
    readLock.lock();
    try {
      return new ZbddNodeInfoDelegate(this.zbdd.getZbddNodeInfo(zbdd));
    } finally {
      readLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public @NotNull String toString(int zbdd)
  {
    writeLock.lock();
    try {
      return this.zbdd.toString(zbdd);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean visitCubes(int zbdd, @NotNull CubeVisitor visitor)
  {
    writeLock.lock();
    try {
      return this.zbdd.visitCubes(zbdd, visitor);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean visitCubeZbdds(int zbdd, @NotNull ZbddVisitor visitor)
  {
    writeLock.lock();
    try {
      return this.zbdd.visitCubeZbdds(zbdd, visitor);
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int @NotNull [] calculateNodeDependency()
  {
    writeLock.lock();
    try {
      return zbdd.calculateNodeDependency();
    } finally {
      writeLock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int @NotNull [] asSingleCubeZbdds(int zbdd)
  {
    writeLock.lock();
    try {
      return this.zbdd.asSingleCubeZbdds(zbdd);
    } finally {
      writeLock.unlock();
    }
  }




  /**
   * Thread-safe delegate for {@link ZbddNodeInfo} that acquires the lock for every access.
   */
  private final class ZbddNodeInfoDelegate implements ZbddNodeInfo
  {
    private final ZbddNodeInfo nodeInfo;


    private ZbddNodeInfoDelegate(@NotNull ZbddNodeInfo nodeInfo) {
      this.nodeInfo = nodeInfo;
    }


    @Override
    public int getZbdd()
    {
      readLock.lock();
      try {
        return nodeInfo.getZbdd();
      } finally {
        readLock.unlock();
      }
    }


    @Override
    public int getVar()
    {
      readLock.lock();
      try {
        return nodeInfo.getVar();
      } finally {
        readLock.unlock();
      }
    }


    @Override
    public int getP0()
    {
      readLock.lock();
      try {
        return nodeInfo.getP0();
      } finally {
        readLock.unlock();
      }
    }


    @Override
    public int getP1()
    {
      readLock.lock();
      try {
        return nodeInfo.getP1();
      } finally {
        readLock.unlock();
      }
    }


    @Override
    public int getReferenceCount()
    {
      readLock.lock();
      try {
        return nodeInfo.getReferenceCount();
      } finally {
        readLock.unlock();
      }
    }


    @Override
    public @NotNull String getLiteral()
    {
      readLock.lock();
      try {
        return nodeInfo.getLiteral();
      } finally {
        readLock.unlock();
      }
    }


    @Override
    public String toString()
    {
      readLock.lock();
      try {
        return nodeInfo.toString();
      } finally {
        readLock.unlock();
      }
    }
  }




  /**
   * Thread-safe wrapper for a cached ZBDD instance, combining {@link Zbdd.Concurrent} and {@link Zbdd.WithCache}
   * capabilities.
   * <p>
   * Instances are created through
   * {@link de.sayayi.lib.zbdd.ZbddFactory#asConcurrent(Zbdd.WithCache) ZbddFactory.asConcurrent}.
   */
  @ApiStatus.Internal
  public static final class WithCache extends ZbddConcurrent implements Zbdd.WithCache
  {
    /**
     * Creates a new concurrent wrapper around the given cached ZBDD instance.
     *
     * @param zbdd  cached ZBDD instance to wrap, not {@code null}
     */
    public WithCache(@NotNull Zbdd.WithCache zbdd) {
      super(zbdd);
    }


    /**
     * {@inheritDoc}
     * <p>
     * Counting reads and writes the ZBDD cache, which is not thread-safe. Therefore, this implementation acquires the
     * write lock instead of the read lock.
     */
    @Override
    public long count(int zbdd)
    {
      writeLock.lock();
      try {
        return this.zbdd.count(zbdd);
      } finally {
        writeLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    @Contract(pure = true)
    public @NotNull ZbddCache getZbddCache()
    {
      readLock.lock();
      try {
        return ((Zbdd.WithCache)zbdd).getZbddCache();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public void setZbddCache(@NotNull ZbddCache zbddCache)
    {
      writeLock.lock();
      try {
        ((Zbdd.WithCache)zbdd).setZbddCache(zbddCache);
      } finally {
        writeLock.unlock();
      }
    }
  }




  /**
   * Thread-safe {@link ZbddStatistics} delegate that acquires the lock for every access.
   *
   * @since 0.6.1
   */
  private final class Statistics implements ZbddStatistics
  {
    private final ZbddStatistics statistics;


    private Statistics(@NotNull ZbddStatistics statistics) {
      this.statistics = statistics;
    }


    /** {@inheritDoc} */
    @Override
    public int getNodesCapacity()
    {
      readLock.lock();
      try {
        return statistics.getNodesCapacity();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getFreeNodes()
    {
      readLock.lock();
      try {
        return statistics.getFreeNodes();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getDeadNodes()
    {
      readLock.lock();
      try {
        return statistics.getDeadNodes();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getNodeLookups()
    {
      readLock.lock();
      try {
        return statistics.getNodeLookups();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getNodeLookupHitCount()
    {
      readLock.lock();
      try {
        return statistics.getNodeLookupHitCount();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getGCCount()
    {
      readLock.lock();
      try {
        return statistics.getGCCount();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public long getGCFreedNodes()
    {
      readLock.lock();
      try {
        return statistics.getGCFreedNodes();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getCapacityIncreaseCount()
    {
      readLock.lock();
      try {
        return statistics.getCapacityIncreaseCount();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public long getMemoryUsage()
    {
      readLock.lock();
      try {
        return statistics.getMemoryUsage();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getRegisteredVars()
    {
      readLock.lock();
      try {
        return statistics.getRegisteredVars();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getAvailableNodes()
    {
      readLock.lock();
      try {
        return statistics.getFreeNodes() + statistics.getDeadNodes();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getOccupiedNodes()
    {
      readLock.lock();
      try {
        return statistics.getNodesCapacity() - statistics.getAvailableNodes();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public double getNodeLookupHitRatio()
    {
      readLock.lock();
      try {
        return statistics.getNodeLookupHitCount() / (double)statistics.getNodeLookups();
      } finally {
        readLock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public double getNodeLookupMissRatio()
    {
      readLock.lock();
      try {
        return 1.0 - statistics.getNodeLookupHitRatio();
      } finally {
        readLock.unlock();
      }
    }


    @Override
    public String toString()
    {
      readLock.lock();
      try {
        return statistics.toString();
      } finally {
        readLock.unlock();
      }
    }
  }
}
