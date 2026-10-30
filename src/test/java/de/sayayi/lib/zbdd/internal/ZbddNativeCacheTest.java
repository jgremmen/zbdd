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
import de.sayayi.lib.zbdd.ZbddFactory;
import de.sayayi.lib.zbdd.ZbddStatistics;
import de.sayayi.lib.zbdd.exception.ZbddException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * The operation cache of the native implementation.
 *
 * @author Jeroen Gremmen
 */
@DisplayName("Native zbdd with cache")
class ZbddNativeCacheTest
{
  @BeforeAll
  static void nativeLibraryAvailable()
  {
    var available = true;

    try {
      ZbddNative.liveContexts();
    } catch(ZbddException ex) {
      if (ex.getMessage().contains("not available"))
        available = false;
      else
        throw ex;
    }

    assumeTrue(available);
  }


  private static ZbddNative create(int entries) {
    return new ZbddNative(DefaultCapacityAdvisor.INSTANCE, entries);
  }


  private static ZbddCapacityAdvisor advisor(int initialCapacity)
  {
    return new ZbddCapacityAdvisor() {
      @Override public int getInitialCapacity() { return initialCapacity; }
      @Override public int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) { return 1; }
      @Override public int adviseIncrement(@NotNull ZbddStatistics statistics) { return 100; }
      @Override public boolean isGCRequired(@NotNull ZbddStatistics statistics) { return false; }
    };
  }


  @ParameterizedTest(name = "initial capacity {0}")
  @CsvSource({ "16, 1024", "1024, 1024", "2000, 2048", "40000, 65536" })
  @DisplayName("Factory with a capacity advisor derives the cache size from the initial capacity")
  void createNativeWithAdvisor(int initialCapacity, int entries)
  {
    try(var zbdd = (ZbddNative)ZbddFactory.createNative(advisor(initialCapacity))) {
      assertEquals(initialCapacity, zbdd.getStatistics().getNodesCapacity());
      assertEquals(entries, zbdd.cacheEntries());
    }
  }


  @Test
  @DisplayName("Factory with a capacity advisor and cache entries uses both")
  void createNativeWithAdvisorAndCacheEntries()
  {
    try(var zbdd = (ZbddNative)ZbddFactory.createNative(advisor(2000), 4096)) {
      assertEquals(2000, zbdd.getStatistics().getNodesCapacity());
      assertEquals(4096, zbdd.cacheEntries());
    }
  }


  @Test
  @DisplayName("Factory without a capacity advisor creates a small cache")
  void createNativeDefault()
  {
    try(var zbdd = (ZbddNative)ZbddFactory.createNative(); var noAdvisor = (ZbddNative)ZbddFactory.createNative(null)) {
      assertEquals(1024, zbdd.cacheEntries());
      assertEquals(1024, noAdvisor.cacheEntries());
      assertEquals(128, noAdvisor.getStatistics().getNodesCapacity());
    }
  }


  @Test
  @DisplayName("Factory creates an instance with the requested cache size")
  void createNativeWithCacheEntries()
  {
    final var zbdd = ZbddFactory.createNative(null, 4096);
    final var nativeZbdd = assertInstanceOf(ZbddNative.class, zbdd);

    assertEquals(4096, nativeZbdd.cacheEntries());

    final var a = zbdd.createVar();
    final var b = zbdd.createVar();

    assertEquals("{ v2, v1 }", zbdd.toString(zbdd.union(zbdd.cube(a), zbdd.cube(b))));

    zbdd.close();
  }


  @ParameterizedTest(name = "{0} entries requested")
  @CsvSource({ "-5, 1024", "0, 1024", "1000, 1024", "1024, 1024", "1025, 2048", "70000, 131072" })
  @DisplayName("Cache size is rounded up to a power of 2, 12 bytes per entry")
  void cacheSize(int requested, int entries)
  {
    try(var cached = create(requested); var smallest = create(1024)) {
      assertEquals(entries, cached.cacheEntries());
      assertEquals((entries - 1024) * 12L,
          cached.getStatistics().getMemoryUsage() - smallest.getStatistics().getMemoryUsage());
    }
  }


  @Test
  @DisplayName("Concurrent wrapper keeps the native interface")
  void concurrentWrapper()
  {
    final var zbdd = ZbddFactory.asConcurrent(ZbddFactory.createNative(null, 1024));

    assertInstanceOf(Zbdd.Native.class, zbdd);
    assertInstanceOf(Zbdd.Concurrent.class, zbdd);
    assertFalse((Object)zbdd instanceof Zbdd.WithCache);
    assertEquals(1, zbdd.count(zbdd.cube(zbdd.createVar())));

    zbdd.close();
  }


  @ParameterizedTest(name = "past small limit: {0}")
  @ValueSource(booleans = { false, true })
  @DisplayName("A repeated operation is answered by the cache")
  void repeatedOperation(boolean large)
  {
    try(var zbdd = large ? ZbddNativeTest.exceedSmallLimit(create(4096)) : create(4096)) {
      final var a = zbdd.incRef(zbdd.cube(zbdd.createVar()));
      final var b = zbdd.incRef(zbdd.cube(zbdd.createVar()));
      final var r = zbdd.union(a, b);
      final var statistics = zbdd.getStatistics();
      final var lookups = statistics.getNodeLookups();
      final var hits = zbdd.cacheHits();

      assertEquals(r, zbdd.union(a, b));
      assertEquals(r, zbdd.union(b, a));
      assertEquals(lookups, statistics.getNodeLookups());
      assertEquals(hits + 2, zbdd.cacheHits());
      assertTrue(zbdd.cacheLookups() >= zbdd.cacheHits());
    }
  }


  @Test
  @DisplayName("Caching reduces the node lookups of a computation with shared subproblems")
  void reducesWork()
  {
    final var java = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);

    try(var cached = create(1 << 16)) {
      assertEquals(powerSetUnion(java), powerSetUnion(cached));
      assertTrue(cached.cacheHits() > 0);
      assertTrue(cached.getStatistics().getNodeLookups() < java.getStatistics().getNodeLookups());
    }
  }


  /** A graph with few nodes and exponentially many paths, which takes long without any cache. */
  private static long powerSetUnion(Zbdd zbdd)
  {
    var set = Zbdd.base();

    for(var i = 0; i < 16; i++)
      set = zbdd.incRef(zbdd.getNode(zbdd.createVar(), set, set));

    return zbdd.countExact(zbdd.union(set, zbdd.incRef(zbdd.change(set, 3)))).longValueExact();
  }


  @ParameterizedTest(name = "seed {0}")
  @CsvSource({ "1", "2", "3", "4", "5", "6", "7", "8" })
  @DisplayName("Results equal the java implementation, also with a tiny cache, garbage collection and the format change")
  void sameResultsAsJava(long seed)
  {
    final var java = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);

    try(var nativeZbdd = create(seed % 2 == 0 ? 1024 : 1 << 18)) {
      final var random = new Random(seed);
      final var vars = 30;

      for(var i = 0; i < vars; i++)
      {
        java.createVar();
        nativeZbdd.createVar();
      }

      final var javaNodes = new ArrayList<Integer>(List.of(Zbdd.base()));
      final var nativeNodes = new ArrayList<Integer>(List.of(Zbdd.base()));

      for(var step = 0; step < 60000 && (nativeZbdd.getStatistics().getNodesCapacity() <= 32768 || step % 200 != 0); step++)
      {
        final var i = random.nextInt(javaNodes.size());
        final var j = random.nextInt(javaNodes.size());
        final var var = 1 + random.nextInt(vars);
        final var operation = random.nextInt(9);
        final var cube = random.ints(5, 1, vars + 1).toArray();
        final var collect = random.nextInt(40) == 0;

        // unreferenced results are freed, repeated operations must not find them in the cache
        final var javaTemp = apply(java, operation, javaNodes.get(i), javaNodes.get(j), var, cube);
        final var nativeTemp = apply(nativeZbdd, operation, nativeNodes.get(i), nativeNodes.get(j), var, cube);

        assertEquals(java.countExact(javaTemp), nativeZbdd.countExact(nativeTemp), "step " + step);
        assertEquals(java.count(javaTemp) > 0 && java.contains(javaNodes.get(i), javaTemp),
            nativeZbdd.count(nativeTemp) > 0 && nativeZbdd.contains(nativeNodes.get(i), nativeTemp), "step " + step);
        assertEquals(java.hasCubeWithVar(javaTemp, var), nativeZbdd.hasCubeWithVar(nativeTemp, var), "step " + step);

        if (java.count(javaTemp) < 500)
          assertEquals(java.toString(javaTemp), nativeZbdd.toString(nativeTemp), "step " + step);

        if (random.nextBoolean())
        {
          javaNodes.add(java.incRef(javaTemp));
          nativeNodes.add(nativeZbdd.incRef(nativeTemp));
        }

        if (collect)
        {
          java.gc();
          nativeZbdd.gc();
        }
      }

      assertTrue(nativeZbdd.getStatistics().getNodesCapacity() > 32768);
      assertTrue(nativeZbdd.cacheHits() > 0);
    }
  }


  /** Intermediate results stay referenced, as creating the next one can trigger a garbage collection. */
  private static int apply(Zbdd zbdd, int operation, int p, int q, int var, int[] vars)
  {
    return switch(operation) {
      case 0 -> zbdd.union(p, zbdd.incRef(zbdd.change(q, var)));
      case 1 -> zbdd.intersect(p, zbdd.incRef(zbdd.subset0(q, var)));
      case 2 -> zbdd.difference(p, zbdd.incRef(zbdd.subset1(q, var)));
      case 3 -> {
        final var subset = zbdd.incRef(zbdd.subset1(q, var));
        final var cube = zbdd.incRef(zbdd.cube(var, 1 + var % 29));

        yield zbdd.union(p, zbdd.incRef(zbdd.multiply(subset, cube)));
      }
      case 4 -> zbdd.union(zbdd.incRef(zbdd.change(p, var)), zbdd.incRef(zbdd.atomize(q)));
      case 5 -> zbdd.union(p, zbdd.incRef(zbdd.divide(q, zbdd.incRef(zbdd.cube(var)))));
      case 6 -> zbdd.union(zbdd.incRef(zbdd.modulo(p, zbdd.incRef(zbdd.cube(var)))), zbdd.incRef(zbdd.removeBase(q)));
      case 7 -> zbdd.union(p, zbdd.incRef(zbdd.subset0(q, vars)));
      default -> zbdd.union(p, zbdd.incRef(zbdd.cube(vars)));
    };
  }
}
