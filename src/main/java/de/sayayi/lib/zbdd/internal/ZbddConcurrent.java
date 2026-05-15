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
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;


/**
 * Thread-safe wrapper around a {@link Zbdd} instance. All operations are synchronized using a {@link ReentrantLock}
 * to ensure safe concurrent access from multiple threads.
 * <p>
 * Instances are created through {@link de.sayayi.lib.zbdd.ZbddFactory#asConcurrent(Zbdd) ZbddFactory.asConcurrent}.
 *
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
public sealed class ZbddConcurrent implements Zbdd.Concurrent permits ZbddConcurrent.WithCache
{
  protected final Zbdd zbdd;
  protected final Lock lock;

  private final ZbddStatistics statistics;


  /**
   * Creates a new concurrent wrapper around the given ZBDD instance.
   *
   * @param zbdd  ZBDD instance to wrap, not {@code null}
   */
  public ZbddConcurrent(@NotNull Zbdd zbdd)
  {
    this.zbdd = zbdd;

    lock = new ReentrantLock();
    statistics = new Statistics(zbdd.getStatistics());
  }


  /** {@inheritDoc} */
  @Override
  public <T> T doAtomic(@NotNull Function<Zbdd,T> operation)
  {
    lock.lock();
    try {
      return operation.apply(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public void doAtomic(@NotNull Consumer<Zbdd> operation)
  {
    lock.lock();
    try {
      operation.accept(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public void registerCallback(@NotNull ZbddCallback callback)
  {
    lock.lock();
    try {
      zbdd.registerCallback(callback);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public @NotNull ZbddLiteralResolver getLiteralResolver()
  {
    lock.lock();
    try {
      return zbdd.getLiteralResolver();
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public void setLiteralResolver(@NotNull ZbddLiteralResolver literalResolver)
  {
    lock.lock();
    try {
      zbdd.setLiteralResolver(literalResolver);
    } finally {
      lock.unlock();
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
    lock.lock();
    try {
      zbdd.clear();
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int createVar()
  {
    lock.lock();
    try {
      return zbdd.createVar();
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int createVar(@NotNull Object varObject)
  {
    lock.lock();
    try {
      return zbdd.createVar(varObject);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public <T> T getVarObject(int var)
  {
    lock.lock();
    try {
      return zbdd.getVarObject(var);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int cube(int var)
  {
    lock.lock();
    try {
      return zbdd.cube(var);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int cube(int @NotNull ... cubeVars)
  {
    lock.lock();
    try {
      return zbdd.cube(cubeVars);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean hasCubeWithVar(int zbdd, int var)
  {
    lock.lock();
    try {
      return this.zbdd.hasCubeWithVar(zbdd, var);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int subset0(int zbdd, int var)
  {
    lock.lock();
    try {
      return this.zbdd.subset0(zbdd, var);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int subset1(int zbdd, int var)
  {
    lock.lock();
    try {
      return this.zbdd.subset1(zbdd, var);
    } finally {
      lock.unlock();
    }
  }

  /** {@inheritDoc} */
  @Override
  public int change(int zbdd, int var)
  {
    lock.lock();
    try {
      return this.zbdd.change(zbdd, var);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int count(int zbdd)
  {
    lock.lock();
    try {
      return this.zbdd.count(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int union(int... p)
  {
    lock.lock();
    try {
      return zbdd.union(p);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int union(int p, int q)
  {
    lock.lock();
    try {
      return zbdd.union(p, q);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int intersect(int p, int q)
  {
    lock.lock();
    try {
      return zbdd.intersect(p, q);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int difference(int p, int q)
  {
    lock.lock();
    try {
      return zbdd.difference(p, q);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int multiply(int p, int q)
  {
    lock.lock();
    try {
      return zbdd.multiply(p, q);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int divide(int p, int q)
  {
    lock.lock();
    try {
      return zbdd.divide(p, q);
    } finally {
      lock.unlock();
    }
  }

  /** {@inheritDoc} */
  @Override
  public int modulo(int p, int q)
  {
    lock.lock();
    try {
      return zbdd.modulo(p, q);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int atomize(int zbdd)
  {
    lock.lock();
    try {
      return this.zbdd.atomize(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int removeBase(int zbdd)
  {
    lock.lock();
    try {
      return this.zbdd.removeBase(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean contains(int p, int q)
  {
    lock.lock();
    try {
      return zbdd.contains(p, q);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean isValidZbdd(int zbdd)
  {
    lock.lock();
    try {
      return this.zbdd.isValidZbdd(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean isValidVar(int var)
  {
    lock.lock();
    try {
      return zbdd.isValidVar(var);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int getVar(int zbdd)
  {
    lock.lock();
    try {
      return this.zbdd.getVar(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int getP0(int zbdd)
  {
    lock.lock();
    try {
      return this.zbdd.getP0(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int getP1(int zbdd)
  {
    lock.lock();
    try {
      return this.zbdd.getP1(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int getNode(int var, int p0, int p1)
  {
    lock.lock();
    try {
      return zbdd.getNode(var, p0, p1);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int gc()
  {
    lock.lock();
    try {
      return zbdd.gc();
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int incRef(int zbdd)
  {
    lock.lock();
    try {
      return this.zbdd.incRef(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  @SuppressWarnings("MethodDoesntCallSuperMethod")
  public int @NotNull [] incRef(int @NotNull [] zbdds)
  {
    lock.lock();
    try {
      return this.zbdd.incRef(zbdds);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int decRef(int zbdd)
  {
    lock.lock();
    try {
      return this.zbdd.decRef(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  @SuppressWarnings("MethodDoesntCallSuperMethod")
  public int @NotNull [] decRef(int @NotNull [] zbdds)
  {
    lock.lock();
    try {
      return this.zbdd.decRef(zbdds);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public @NotNull ZbddNodeInfo getZbddNodeInfo(int zbdd)
  {
    lock.lock();
    try {
      return new ZbddNodeInfoDelegate(this.zbdd.getZbddNodeInfo(zbdd));
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public @NotNull String toString(int zbdd)
  {
    lock.lock();
    try {
      return this.zbdd.toString(zbdd);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean visitCubes(int zbdd, @NotNull CubeVisitor visitor)
  {
    lock.lock();
    try {
      return this.zbdd.visitCubes(zbdd, visitor);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public boolean visitCubeZbdds(int zbdd, @NotNull ZbddVisitor visitor)
  {
    lock.lock();
    try {
      return this.zbdd.visitCubeZbdds(zbdd, visitor);
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int @NotNull [] calculateNodeDependency()
  {
    lock.lock();
    try {
      return zbdd.calculateNodeDependency();
    } finally {
      lock.unlock();
    }
  }


  /** {@inheritDoc} */
  @Override
  public int @NotNull [] asSingleCubeZbdds(int zbdd)
  {
    lock.lock();
    try {
      return this.zbdd.asSingleCubeZbdds(zbdd);
    } finally {
      lock.unlock();
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
      lock.lock();
      try {
        return nodeInfo.getZbdd();
      } finally {
        lock.unlock();
      }
    }


    @Override
    public int getVar()
    {
      lock.lock();
      try {
        return nodeInfo.getVar();
      } finally {
        lock.unlock();
      }
    }


    @Override
    public int getP0()
    {
      lock.lock();
      try {
        return nodeInfo.getP0();
      } finally {
        lock.unlock();
      }
    }


    @Override
    public int getP1()
    {
      lock.lock();
      try {
        return nodeInfo.getP1();
      } finally {
        lock.unlock();
      }
    }


    @Override
    public int getReferenceCount()
    {
      lock.lock();
      try {
        return nodeInfo.getReferenceCount();
      } finally {
        lock.unlock();
      }
    }


    @Override
    public @NotNull String getLiteral()
    {
      lock.lock();
      try {
        return nodeInfo.getLiteral();
      } finally {
        lock.unlock();
      }
    }


    @Override
    public String toString()
    {
      lock.lock();
      try {
        return nodeInfo.toString();
      } finally {
        lock.unlock();
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


    /** {@inheritDoc} */
    @Contract(pure = true)
    public @NotNull ZbddCache getZbddCache()
    {
      lock.lock();
      try {
        return ((Zbdd.WithCache)zbdd).getZbddCache();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public void setZbddCache(@NotNull ZbddCache zbddCache)
    {
      lock.lock();
      try {
        ((Zbdd.WithCache)zbdd).setZbddCache(zbddCache);
      } finally {
        lock.unlock();
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
      lock.lock();
      try {
        return statistics.getNodesCapacity();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getFreeNodes()
    {
      lock.lock();
      try {
        return statistics.getFreeNodes();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getDeadNodes()
    {
      lock.lock();
      try {
        return statistics.getDeadNodes();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getNodeLookups()
    {
      lock.lock();
      try {
        return statistics.getNodeLookups();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getNodeLookupHitCount()
    {
      lock.lock();
      try {
        return statistics.getNodeLookupHitCount();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getGCCount()
    {
      lock.lock();
      try {
        return statistics.getGCCount();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public long getGCFreedNodes()
    {
      lock.lock();
      try {
        return statistics.getGCFreedNodes();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getCapacityIncreaseCount()
    {
      lock.lock();
      try {
        return statistics.getCapacityIncreaseCount();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public long getMemoryUsage()
    {
      lock.lock();
      try {
        return statistics.getMemoryUsage();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getRegisteredVars()
    {
      lock.lock();
      try {
        return statistics.getRegisteredVars();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getAvailableNodes()
    {
      lock.lock();
      try {
        return statistics.getFreeNodes() + statistics.getDeadNodes();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public int getOccupiedNodes()
    {
      lock.lock();
      try {
        return statistics.getNodesCapacity() - statistics.getAvailableNodes();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public double getNodeLookupHitRatio()
    {
      lock.lock();
      try {
        return statistics.getNodeLookupHitCount() / (double)statistics.getNodeLookups();
      } finally {
        lock.unlock();
      }
    }


    /** {@inheritDoc} */
    @Override
    public double getNodeLookupMissRatio()
    {
      lock.lock();
      try {
        return 1.0 - statistics.getNodeLookupHitRatio();
      } finally {
        lock.unlock();
      }
    }


    @Override
    public String toString()
    {
      lock.lock();
      try {
        return statistics.toString();
      } finally {
        lock.unlock();
      }
    }
  }
}
