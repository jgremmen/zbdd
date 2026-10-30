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
import de.sayayi.lib.zbdd.cache.ZbddFastCache;
import de.sayayi.lib.zbdd.exception.ZbddException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.ProcessBuilder.Redirect.DISCARD;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * Behavior specific to the native implementation: its limits and the release of its memory.
 *
 * @author Jeroen Gremmen
 */
@DisplayName("Native zbdd")
class ZbddNativeTest
{
  private static final int SMALL_LIMIT = 32767;


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


  /**
   * Waits for the release of unreachable instances left behind by earlier tests. It happens at any time otherwise, so a
   * test comparing the number of live contexts with an earlier value would see that number drop unexpectedly.
   */
  @BeforeEach
  void releaseUnreachableInstances() throws InterruptedException
  {
    var live = ZbddNative.liveContexts();

    for(var stable = 0; stable < 5; stable++)
    {
      System.gc();
      Thread.sleep(20);

      final var current = ZbddNative.liveContexts();
      if (current != live)
      {
        live = current;
        stable = -1;
      }
    }
  }


  private static ZbddNative create() {
    return new ZbddNative(DefaultCapacityAdvisor.INSTANCE);
  }


  /**
   * Makes the given new instance change its memory model by exceeding the variables of the small one. The first
   * variables are taken, but no nodes are created and the capacity stays the same.
   */
  static ZbddNative exceedSmallLimit(ZbddNative zbdd)
  {
    for(var i = 0; i <= SMALL_LIMIT; i++)
      zbdd.createVar();

    return zbdd;
  }


  /** A capacity beyond the small memory model is only possible with the 16 byte records of the large one. */
  private static void assertLargeNodes(Zbdd zbdd)
  {
    final var statistics = zbdd.getStatistics();

    assertTrue(statistics.getNodesCapacity() > SMALL_LIMIT + 1);
    assertTrue(statistics.getMemoryUsage() >= statistics.getNodesCapacity() * 16L);
  }


  @Test
  @DisplayName("Native implementation is available on a supported platform")
  void nativeAvailable() {
    assertTrue(ZbddFactory.isNativeAvailable());
  }


  @Test
  @DisplayName("Native implementation is not available on an unsupported platform, without failing")
  void nativeNotAvailable() throws Exception
  {
    // a failure is remembered per JVM and the library is already loaded in this one
    final var process = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dos.arch=unsupported",
        "-cp", System.getProperty("java.class.path"),
        AvailabilityProbe.class.getName())
        .redirectError(DISCARD)  // the JVM may print warnings, under Wine for example
        .start();
    final var output = new String(process.getInputStream().readAllBytes(), UTF_8).lines().toList();

    assertEquals(0, process.waitFor(), () -> String.join("\n", output));
    assertEquals("false", output.get(0));
    assertEquals("false", output.get(1));
    assertTrue(output.get(2).startsWith("native zbdd library is not available for "), output.get(2));
  }


  /** Started in a JVM of its own by {@link #nativeNotAvailable()}. */
  static final class AvailabilityProbe
  {
    public static void main(String[] args)
    {
      System.out.println(ZbddFactory.isNativeAvailable());
      System.out.println(ZbddFactory.isNativeAvailable());

      try {
        ZbddFactory.createNative();
      } catch(ZbddException ex) {
        System.out.println(ex.getMessage());
      }
    }
  }


  @Test
  @DisplayName("Factory creates a working native instance")
  void createNative()
  {
    final var zbdd = ZbddFactory.createNative();

    assertInstanceOf(ZbddNative.class, zbdd);
    assertFalse((Object)zbdd instanceof Zbdd.WithCache);

    final var a = zbdd.createVar();
    final var b = zbdd.createVar();

    assertEquals("{ v2, v1 }", zbdd.toString(zbdd.union(zbdd.cube(a), zbdd.cube(b))));

    zbdd.close();
  }


  @Test
  @DisplayName("Variable 32768 changes the format and keeps existing nodes")
  void changeFormatOnVariable()
  {
    try(var zbdd = create()) {
      for(var i = 0; i < SMALL_LIMIT; i++)
        assertEquals(i + 1, zbdd.createVar());

      final var cube = zbdd.incRef(zbdd.cube(SMALL_LIMIT));
      final var memory = zbdd.getStatistics().getMemoryUsage();
      final var var = zbdd.createVar();

      assertEquals(SMALL_LIMIT + 1, var);
      assertTrue(zbdd.getStatistics().getMemoryUsage() > memory);

      assertEquals(cube, zbdd.cube(SMALL_LIMIT));
      assertEquals(SMALL_LIMIT, zbdd.getVar(cube));
      assertEquals(1, zbdd.getZbddNodeInfo(cube).getReferenceCount());
      assertEquals(2, zbdd.count(zbdd.union(cube, zbdd.cube(var))));
    }
  }


  @Test
  @DisplayName("Growing beyond 32768 nodes changes the format during a running operation")
  void changeFormatOnNodes()
  {
    try(var zbdd = create()) {
      final var vars = 20000;

      for(var i = 0; i < vars; i++)
        zbdd.createVar();

      var all = Zbdd.empty();

      // each step adds a cube and its union node
      for(var var = 1; var <= vars; var++)
      {
        final var next = zbdd.incRef(zbdd.union(all, zbdd.incRef(zbdd.cube(var))));

        assertEquals(var, zbdd.count(next));

        all = next;
      }

      assertLargeNodes(zbdd);
      assertEquals(vars, zbdd.count(all));

      for(var var = 1; var <= vars; var += 997)
        assertTrue(zbdd.hasCubeWithVar(all, var));
    }
  }


  @Test
  @DisplayName("A capacity advisor declining to grow fails like the java implementation, keeping the capacity")
  void noIncreaseKeepsCapacity()
  {
    final var advisor = new ZbddCapacityAdvisor() {
      @Override public int getInitialCapacity() { return 16; }
      @Override public int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) { return 1; }
      @Override public int adviseIncrement(@NotNull ZbddStatistics statistics) { return 0; }
      @Override public boolean isGCRequired(@NotNull ZbddStatistics statistics) { return false; }
    };

    try(var zbdd = new ZbddNative(advisor)) {
      final var exception = assertThrowsExactly(ZbddException.class, () -> {
        for(var i = 0; i < 20; i++)
          zbdd.cube(zbdd.createVar());
      });

      assertEquals("nodes capacity exhausted", exception.getMessage());
      assertEquals(16, zbdd.getStatistics().getNodesCapacity());
    }
  }


  @Test
  @DisplayName("Initial capacity beyond the small memory model is honoured")
  void largeInitialCapacity()
  {
    final var advisor = new ZbddCapacityAdvisor() {
      @Override public int getInitialCapacity() { return 40000; }
      @Override public int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) { return 1; }
      @Override public int adviseIncrement(@NotNull ZbddStatistics statistics) { return 100; }
      @Override public boolean isGCRequired(@NotNull ZbddStatistics statistics) { return false; }
    };

    try(var zbdd = new ZbddNative(advisor)) {
      assertLargeNodes(zbdd);
      assertEquals(40000, zbdd.getStatistics().getNodesCapacity());
    }
  }


  @Test
  @DisplayName("A callback changing the format doesn't disturb the running operation")
  void changeFormatFromCallback()
  {
    try(var zbdd = create()) {
      final var vars = 2000;

      for(var i = 0; i < vars; i++)
        zbdd.createVar();

      zbdd.registerCallback(new Zbdd.ZbddCallback() {
        @Override
        public void beforeGc()
        {
          while(zbdd.getStatistics().getRegisteredVars() <= SMALL_LIMIT)
            zbdd.createVar();
        }
      });

      var all = Zbdd.empty();

      for(var var = 1; var <= vars; var++)
      {
        final var next = zbdd.incRef(zbdd.union(all, zbdd.cube(var)));

        zbdd.decRef(all);
        all = next;
      }

      assertTrue(zbdd.getStatistics().getRegisteredVars() > SMALL_LIMIT);
      assertEquals(vars, zbdd.count(all));
    }
  }


  @Test
  @DisplayName("A visitor changing the format neither skips nor repeats cubes")
  void changeFormatFromVisitor()
  {
    try(var zbdd = create()) {
      var set = Zbdd.empty();

      for(var i = 0; i < 16; i++)
        set = zbdd.union(set, zbdd.cube(zbdd.createVar()));

      final var cubes = new ArrayList<Integer>();

      zbdd.visitCubeZbdds(set, cube -> {
        cubes.add(zbdd.getVar(cube));

        if (cubes.size() == 4)
          while(zbdd.getStatistics().getRegisteredVars() <= SMALL_LIMIT)
            zbdd.createVar();

        return true;
      });

      assertTrue(zbdd.getStatistics().getRegisteredVars() > SMALL_LIMIT);
      assertEquals(16, cubes.size());
      assertEquals(16, new HashSet<>(cubes).size());
    }
  }


  @Test
  @DisplayName("Variables and nodes go past the limits of the small memory model")
  void pastSmallLimit()
  {
    try(var zbdd = create()) {
      for(var i = 0; i < 70000; i++)
        zbdd.incRef(zbdd.cube(zbdd.createVar()));

      assertEquals(70000, zbdd.getStatistics().getRegisteredVars());
      assertTrue(zbdd.isValidZbdd(70001));
    }
  }


  @ParameterizedTest(name = "past small limit: {0}")
  @ValueSource(booleans = { false, true })
  @DisplayName("Reference counts move through the overflow table")
  void referenceCounts(boolean large)
  {
    try(var zbdd = large ? exceedSmallLimit(create()) : create()) {
      final var node = zbdd.cube(zbdd.createVar());
      final var info = zbdd.getZbddNodeInfo(node);

      assertEquals(-1, info.getReferenceCount());

      for(var n = 1; n <= 200; n++)
      {
        zbdd.incRef(node);
        assertEquals(n, info.getReferenceCount());
      }

      for(var n = 199; n >= 0; n--)
      {
        zbdd.decRef(node);
        assertEquals(n, info.getReferenceCount());
      }

      assertEquals(1, zbdd.getStatistics().getDeadNodes());
    }
  }


  @ParameterizedTest(name = "past small limit: {0}")
  @ValueSource(booleans = { false, true })
  @DisplayName("Native memory is smaller than the java node table")
  void memoryUsage(boolean large)
  {
    try(var zbdd = large ? exceedSmallLimit(create()) : create()) {
      final var statistics = zbdd.getStatistics();
      final var nodeBytes = large ? 16 : 8;
      final var headBytes = large ? 4 : 2;
      final var capacity = (long)statistics.getNodesCapacity();

      // nodes and hash buckets for a capacity of 128 (64 buckets), excluding the fixed stacks and the context
      final var tables = capacity * nodeBytes + capacity / 2 * headBytes;

      assertTrue(statistics.getMemoryUsage() >= tables);
      assertTrue(tables < capacity * 24);
    }
  }


  @Test
  @DisplayName("Closing releases the native memory exactly once and rejects further calls")
  void close()
  {
    final var before = ZbddNative.liveContexts();
    final var zbdd = create();

    assertEquals(before + 1, ZbddNative.liveContexts());

    zbdd.close();
    assertEquals(before, ZbddNative.liveContexts());

    zbdd.close();
    assertEquals(before, ZbddNative.liveContexts());

    assertThrowsExactly(ZbddException.class, zbdd::createVar);
    assertThrowsExactly(ZbddException.class, zbdd::gc);
    assertThrowsExactly(ZbddException.class, () -> zbdd.getStatistics().getNodesCapacity());
  }


  @Test
  @DisplayName("Unreachable instances are released by the garbage collector")
  void releasedWhenUnreachable() throws InterruptedException
  {
    final var before = ZbddNative.liveContexts();
    final var kept = create();
    final var created = 5000;

    for(var i = 0; i < created; i++)
    {
      final var zbdd = i % 500 == 0 ? exceedSmallLimit(create()) : create();

      zbdd.cube(zbdd.createVar());
    }

    final var deadline = System.nanoTime() + 30_000_000_000L;

    while(ZbddNative.liveContexts() > before + 1 && System.nanoTime() < deadline)
    {
      System.gc();
      Thread.sleep(20);
    }

    assertEquals(before + 1, ZbddNative.liveContexts());
    assertEquals(1, kept.createVar());

    kept.close();
  }


  @Test
  @DisplayName("Instances with registered callbacks and statistics views are released as well")
  void releasedWithCallbacks() throws InterruptedException
  {
    final var before = ZbddNative.liveContexts();
    final var calls = new AtomicInteger();

    for(var i = 0; i < 200; i++)
    {
      final var zbdd = create();

      zbdd.registerCallback(new Zbdd.ZbddCallback() {
        @Override
        public void beforeGc() {
          calls.incrementAndGet();
        }
      });
      zbdd.gc();
    }

    final var deadline = System.nanoTime() + 30_000_000_000L;

    while(ZbddNative.liveContexts() > before && System.nanoTime() < deadline)
    {
      System.gc();
      Thread.sleep(20);
    }

    assertEquals(200, calls.get());
    assertEquals(before, ZbddNative.liveContexts());
  }


  @Test
  @DisplayName("A null array is rejected before reaching the native library")
  @SuppressWarnings("DataFlowIssue")
  void nullArray()
  {
    try(var zbdd = create()) {
      assertThrowsExactly(NullPointerException.class, () -> zbdd.cube((int[])null));
      assertThrowsExactly(NullPointerException.class, () -> zbdd.subset0(Zbdd.BASE, (int[])null));
      assertThrowsExactly(NullPointerException.class, () -> zbdd.union((int[])null));
    }
  }


  @Test
  @DisplayName("Closing is rejected while a visitor is running and possible again afterwards")
  void closeFromVisitor()
  {
    final var before = ZbddNative.liveContexts();
    final var zbdd = create();
    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var set = zbdd.incRef(zbdd.union(zbdd.cube(a), zbdd.cube(b)));
    final var cubes = new AtomicInteger();

    assertTrue(zbdd.visitCubes(set, cube -> {
      assertThrowsExactly(ZbddException.class, zbdd::close);
      cubes.incrementAndGet();
      return true;
    }));
    assertEquals(2, cubes.get());

    assertTrue(zbdd.visitCubeZbdds(set, cube -> {
      assertThrowsExactly(ZbddException.class, zbdd::close);
      cubes.incrementAndGet();
      return true;
    }));
    assertEquals(4, cubes.get());

    zbdd.setLiteralResolver(var -> {
      assertThrowsExactly(ZbddException.class, zbdd::close);
      return "v" + var;
    });
    assertEquals("{ v2, v1 }", zbdd.toString(set));

    // a visitor which has ended no longer prevents closing, not even by throwing an exception
    assertThrowsExactly(IllegalStateException.class, () -> zbdd.visitCubes(set, cube -> {
      throw new IllegalStateException("visitor");
    }));

    zbdd.close();
    assertEquals(before, ZbddNative.liveContexts());
  }


  @Test
  @DisplayName("Closing is rejected while a callback is running and possible again afterwards")
  void closeFromCallback()
  {
    final var before = ZbddNative.liveContexts();
    final var zbdd = create();
    final var rejected = new AtomicInteger();
    final Runnable close = () -> {
      assertThrowsExactly(ZbddException.class, zbdd::close);
      rejected.incrementAndGet();
    };

    zbdd.registerCallback(new Zbdd.ZbddCallback() {
      @Override public void beforeGc() { close.run(); }
      @Override public void afterGc() { close.run(); }
      @Override public void beforeClear() { close.run(); }
      @Override public void afterClear() { close.run(); }
    });

    zbdd.gc();
    zbdd.clear();
    assertEquals(4, rejected.get());

    zbdd.close();
    assertEquals(before, ZbddNative.liveContexts());
  }


  @Test
  @DisplayName("Closing is rejected while the capacity advisor is running")
  void closeFromCapacityAdvisor()
  {
    final var zbddRef = new ZbddNative[1];
    final var rejected = new AtomicInteger();
    final Runnable close = () -> {
      assertThrowsExactly(ZbddException.class, zbddRef[0]::close);
      rejected.incrementAndGet();
    };

    try(var zbdd = new ZbddNative(new ZbddCapacityAdvisor() {
      @Override
      public int getInitialCapacity() {
        return 8;
      }

      @Override
      public int getMinimumFreeNodes(@NotNull ZbddStatistics statistics)
      {
        close.run();
        return Integer.MAX_VALUE;
      }

      @Override
      public int adviseIncrement(@NotNull ZbddStatistics statistics)
      {
        close.run();
        return 8;
      }

      @Override
      public boolean isGCRequired(@NotNull ZbddStatistics statistics)
      {
        close.run();
        return true;
      }
    })) {
      zbddRef[0] = zbdd;

      for(var i = 0; i < 10; i++)
        zbdd.cube(zbdd.createVar());

      assertTrue(rejected.get() >= 3);
    }
  }


  @Test
  @DisplayName("Operations on a closed instance fail, even if the result does not depend on the native library")
  void useAfterClose()
  {
    final var zbdd = create();

    zbdd.close();

    assertThrowsExactly(ZbddException.class, () -> zbdd.cube(new int[0]));
    assertThrowsExactly(ZbddException.class, () -> zbdd.visitCubes(Zbdd.EMPTY, cube -> true));
    assertThrowsExactly(ZbddException.class, () -> zbdd.visitCubeZbdds(Zbdd.EMPTY, cube -> true));
  }


  @Test
  @DisplayName("Concurrent wrapper closes the wrapped instance, regardless of the static type used for wrapping")
  void closeConcurrentWrapper()
  {
    final var before = ZbddNative.liveContexts();
    final Zbdd plain = create();
    final var wrappers = new Object[] { ZbddFactory.asConcurrent(plain), ZbddFactory.asConcurrent(create()) };

    assertEquals(before + 2, ZbddNative.liveContexts());

    for(var i = 0; i < wrappers.length; i++)
    {
      final var zbdd = assertInstanceOf(Zbdd.Native.class, wrappers[i]);

      assertInstanceOf(Zbdd.Concurrent.class, zbdd);

      zbdd.close();
      assertEquals(before + 1 - i, ZbddNative.liveContexts());

      zbdd.close();
      assertEquals(before + 1 - i, ZbddNative.liveContexts());

      assertThrowsExactly(ZbddException.class, zbdd::createVar);
      assertThrowsExactly(ZbddException.class, () -> zbdd.getStatistics().getNodesCapacity());
    }
  }


  @Test
  @DisplayName("Concurrent wrappers of java instances can't be closed")
  void concurrentWrapperOfJavaInstance()
  {
    assertFalse((Object)ZbddFactory.asConcurrent(ZbddFactory.create()) instanceof AutoCloseable);
    assertFalse((Object)ZbddFactory.asConcurrent(ZbddFactory.createCached(new ZbddFastCache())) instanceof AutoCloseable);
  }


  @Test
  @DisplayName("Closing a concurrent wrapper is rejected from a visitor and waits for the operation of another thread")
  void closeConcurrentWrapperDuringVisitor() throws Exception
  {
    final var before = ZbddNative.liveContexts();
    final var zbdd = ZbddFactory.asConcurrent(create());
    final var set = zbdd.incRef(zbdd.cube(zbdd.createVar()));
    final var visiting = new CountDownLatch(1);
    final var closerStarted = new CountDownLatch(1);

    try(final var executor = Executors.newSingleThreadExecutor()) {
      final var visitor = executor.submit(() -> zbdd.visitCubes(set, cube -> {
        assertThrowsExactly(ZbddException.class, zbdd::close);
        visiting.countDown();

        try {
          assertTrue(closerStarted.await(10, SECONDS));

          // give the closing thread time to reach the lock, if it wrongly didn't wait it has released the instance
          Thread.sleep(100);
        } catch(InterruptedException ex) {
          throw new IllegalStateException(ex);
        }

        assertEquals(before + 1, ZbddNative.liveContexts());

        return true;
      }));

      assertTrue(visiting.await(10, SECONDS));
      closerStarted.countDown();
      zbdd.close();

      assertTrue(visitor.get(10, SECONDS));
    }

    assertEquals(before, ZbddNative.liveContexts());
    assertThrowsExactly(ZbddException.class, zbdd::createVar);
  }


  @Test
  @DisplayName("Throwing visitor releases protection and iterator")
  void throwingVisitor()
  {
    try(var zbdd = create()) {
      final var a = zbdd.createVar();
      final var b = zbdd.createVar();
      final var set = zbdd.union(zbdd.cube(a), zbdd.cube(b));

      assertThrowsExactly(IllegalStateException.class, () -> zbdd.visitCubes(set, cube -> {
        throw new IllegalStateException("visitor");
      }));

      // no longer protected, nothing refers to the nodes
      assertEquals(3, zbdd.gc());
      assertFalse(zbdd.isValidZbdd(set));
    }
  }


  @Test
  @DisplayName("Throwing callback fails the operation and keeps the instance intact")
  void throwingCallback()
  {
    try(var zbdd = create()) {
      final var kept = zbdd.incRef(zbdd.cube(zbdd.createVar()));
      final var fail = new boolean[] { true };

      zbdd.registerCallback(new Zbdd.ZbddCallback() {
        @Override
        public void beforeGc()
        {
          if (fail[0])
            throw new IllegalStateException("callback");
        }
      });

      assertThrowsExactly(IllegalStateException.class, zbdd::gc);
      assertTrue(zbdd.isValidZbdd(kept));

      fail[0] = false;

      assertEquals(0, zbdd.gc());
      assertEquals(1, zbdd.count(kept));
    }
  }


  @Test
  @DisplayName("Callbacks may create nodes, nested in a running operation")
  void callbacksCreatingNodes()
  {
    try(var zbdd = exceedSmallLimit(create())) {
      final var vars = new ArrayList<Integer>();

      for(var i = 0; i < 2000; i++)
        vars.add(zbdd.createVar());

      zbdd.registerCallback(new Zbdd.ZbddCallback() {
        @Override
        public void beforeGc() {
          zbdd.incRef(zbdd.cube(vars.get(0), vars.get(1)));
        }
      });

      var all = Zbdd.empty();

      for(final var var: vars)
      {
        final var next = zbdd.incRef(zbdd.union(all, zbdd.cube(var)));

        zbdd.decRef(all);
        all = next;
      }

      assertEquals(2000, zbdd.count(all));
      assertTrue(zbdd.getStatistics().getGCCount() > 0);
    }
  }
}
