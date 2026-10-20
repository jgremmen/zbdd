package de.sayayi.lib.zbdd;

import de.sayayi.lib.zbdd.cache.ZbddFastCache;
import de.sayayi.lib.zbdd.exception.InvalidZbddException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static de.sayayi.lib.zbdd.ZbddFactory.asConcurrent;
import static de.sayayi.lib.zbdd.ZbddFactory.createCached;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.junit.jupiter.api.Assertions.*;


/**
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
@DisplayName("Zbdd concurrency")
@SuppressWarnings("JavaPrintToLogpoint")
class ZbddConcurrentTest
{
  @Test
  @DisplayName("No concurrency zbdd")
  void noConcurrencyZbdd()
  {
    final var zbdd = ZbddFactory.create();
    final int cube = zbdd.cube(zbdd.createVar(), zbdd.createVar());

    final var state1Complete = new CountDownLatch(1);
    final var state2Complete = new CountDownLatch(1);

    try(final var executor = newFixedThreadPool(2)) {
      final var future = executor.submit(() -> {
        state1Complete.countDown();  // unlocks 2nd thread
        state2Complete.await();  // wait for it to finish

        return zbdd.incRef(cube);
      });

      // 2nd thread destroys "ab" the 1st one is relying on
      executor.submit(() -> {
        state1Complete.await();  // wait for 1st thread to be ready
        zbdd.gc();
        state2Complete.countDown();  // unlocks 1st thread

        return null;
      });

      assertEquals(InvalidZbddException.class,
          assertThrowsExactly(ExecutionException.class, future::get).getCause().getClass());
    }
  }


  @Test
  @DisplayName("With concurrency zbdd")
  void concurrencyZbdd() throws Exception
  {
    final var zbdd = asConcurrent(ZbddFactory.create());
    final int cube = zbdd.cube(zbdd.createVar(), zbdd.createVar());

    final var state1Complete = new CountDownLatch(1);
    final var state2Complete = new CountDownLatch(1);

    try(final var executor = newFixedThreadPool(2)) {
      executor.submit(() -> {
        System.out.println("start gc thread");
        System.out.println("wait for zbdd operation thread to be on stand-by...");
        state1Complete.await();  // wait for signal to continue (state 1)

        System.out.println("performing gc...");
        zbdd.decRef(cube);
        zbdd.gc();
        System.out.println("gc finished");
        state2Complete.countDown();  // signal state 2 complete

        System.out.println("gc thread done");
        return null;
      });

      final var future = executor.submit(() -> {
        System.out.println("start zbdd operation thread");
        zbdd.runAtomic(z -> {
          System.out.println("perform zbdd operation...");
          zbdd.incRef(cube);

          System.out.println("unlocking gc thread...");
          state1Complete.countDown();  // signal state 1 complete
          // without runAtomic, the gc thread would kick in at this point

          zbdd.incRef(cube);
          System.out.println("zbdd operation finished");
        });

        System.out.println("waiting for gc to finish...");
        state2Complete.await();  // wait for signal to continue (state 2)

        System.out.println("zbdd operation thread done");

        return zbdd.getZbddNodeInfo(cube).getReferenceCount();
      });

      assertTrue(zbdd.isValidZbdd(cube));
      assertEquals(1, future.get());
    }
  }


  @Test
  @DisplayName("Concurrent count with cache")
  void concurrentCountWithCache() throws Exception {
    assertConcurrentCounts(asConcurrent(createCached(new ZbddFastCache())));
  }


  @Test
  @DisplayName("Concurrent count with cache wrapped through plain Zbdd type")
  void concurrentCountWithCacheAsPlainZbdd() throws Exception
  {
    // don't change Zbdd -> var!
    final Zbdd cachedZbdd = createCached(new ZbddFastCache());
    final var zbdd = asConcurrent(cachedZbdd);

    assertInstanceOf(Zbdd.WithCache.class, zbdd);
    assertConcurrentCounts(zbdd);
  }


  private static void assertConcurrentCounts(Zbdd.Concurrent zbdd) throws Exception
  {
    final var vars = new int[14];

    for(var i = 0; i < vars.length; i++)
      vars[i] = zbdd.createVar();

    final var random = new Random(1);
    final var sets = new int[300];
    final var expectedCounts = new long[sets.length];

    for(var s = 0; s < sets.length; s++)
    {
      var set = Zbdd.empty();

      for(var n = 0; n < 25; n++)
      {
        final var cubeVars = new int[1 + random.nextInt(4)];

        for(var i = 0; i < cubeVars.length; i++)
          cubeVars[i] = vars[random.nextInt(vars.length)];

        final var union = zbdd.incRef(zbdd.union(set, zbdd.cube(cubeVars)));

        zbdd.decRef(set);
        set = union;
      }

      sets[s] = set;
      expectedCounts[s] = zbdd.count(set);
    }

    final var wrongCounts = new AtomicInteger();

    try(final var executor = newFixedThreadPool(8)) {
      final var futures = new ArrayList<Future<?>>();

      for(var t = 0; t < 8; t++)
      {
        final var seed = t;

        futures.add(executor.submit(() -> {
          final var threadRandom = new Random(seed);

          for(var n = 0; n < 200_000; n++)
          {
            final var s = threadRandom.nextInt(sets.length);

            if (zbdd.count(sets[s]) != expectedCounts[s])
              wrongCounts.incrementAndGet();
          }
        }));
      }

      for(var future: futures)
        future.get();
    }

    assertEquals(0, wrongCounts.get());
  }
}
