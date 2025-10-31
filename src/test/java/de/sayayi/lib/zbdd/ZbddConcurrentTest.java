package de.sayayi.lib.zbdd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.junit.jupiter.api.Assertions.*;


/**
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
@DisplayName("Zbdd concurrency")
public class ZbddConcurrentTest
{
  @Test
  @DisplayName("No concurrency zbdd")
  void noConcurrencyZbdd()
  {
    final var zbdd = ZbddFactory.create();
    final int cube = zbdd.cube(zbdd.createVar(), zbdd.createVar());

    final var state1Complete = new CountDownLatch(1);
    final var state2Complete = new CountDownLatch(1);

    final var executor = newFixedThreadPool(2);
    try {
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

      assertEquals(ZbddException.class,
          assertThrowsExactly(ExecutionException.class, future::get).getCause().getClass());
    } finally {
      executor.shutdown();
    }
  }


  @Test
  @DisplayName("With concurrency zbdd")
  void concurrencyZbdd() throws Exception
  {
    final var zbdd = ZbddFactory.asConcurrent(ZbddFactory.create());
    final int cube = zbdd.cube(zbdd.createVar(), zbdd.createVar());

    final var state1Complete = new CountDownLatch(1);
    final var state2Complete = new CountDownLatch(1);

    final var executor = newFixedThreadPool(2);
    try {
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
        zbdd.doAtomic(z -> {
          System.out.println("perform zbdd operation...");
          zbdd.incRef(cube);

          System.out.println("unlocking gc thread...");
          state1Complete.countDown();  // signal state 1 complete
          // without doAtomic, the gc thread would kick in at this point

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
    } finally {
      executor.shutdown();
    }
  }
}
