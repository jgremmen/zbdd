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
package de.sayayi.lib.zbdd;

import de.sayayi.lib.zbdd.Zbdd.ZbddCallback;
import de.sayayi.lib.zbdd.cache.ZbddFastCache;
import de.sayayi.lib.zbdd.exception.ZbddException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Tests for re-entrant use of a zbdd instance from {@link ZbddCallback callbacks}.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Callbacks")
class ZbddCallbackTest
{
  @ParameterizedTest(name = "cached = {0}")
  @DisplayName("Register callback from within a callback")
  @ValueSource(booleans = { false, true })
  void registerFromCallback(boolean cached)
  {
    final var zbdd = cached ? ZbddFactory.createCached(new ZbddFastCache(1024)) : ZbddFactory.create();
    final var events = new ArrayList<String>();

    zbdd.registerCallback(new ZbddCallback() {
      boolean registered;

      @Override
      public void beforeGc()
      {
        events.add("first");

        if (!registered)
        {
          registered = true;

          zbdd.registerCallback(new ZbddCallback() {
            @Override public void beforeGc() {
              events.add("second");
            }
          });
        }
      }
    });

    zbdd.gc();
    assertEquals(List.of("first"), events);

    zbdd.gc();
    assertEquals(List.of("first", "first", "second"), events);
  }


  @ParameterizedTest(name = "concurrent = {0}")
  @DisplayName("gc, clear and calculateNodeDependency are rejected from within a callback")
  @ValueSource(booleans = { false, true })
  void rejectedFromCallback(boolean concurrent)
  {
    final Zbdd zbdd = concurrent ? ZbddFactory.asConcurrent(ZbddFactory.create()) : ZbddFactory.create();
    final var cube = zbdd.incRef(zbdd.cube(zbdd.createVar()));
    final var failures = new ArrayList<String>();
    final Function<Executable,String> rejection =
        executable -> assertThrowsExactly(ZbddException.class, executable).getMessage();

    zbdd.registerCallback(new ZbddCallback() {
      @Override public void beforeClear() { failures.add(rejection.apply(zbdd::gc)); }
      @Override public void afterClear() { failures.add(rejection.apply(zbdd::clear)); }
      @Override public void beforeGc() { failures.add(rejection.apply(zbdd::calculateNodeDependency)); }
      @Override public void afterGc() { failures.add(rejection.apply(zbdd::gc)); }
    });

    zbdd.gc();
    assertTrue(zbdd.isValidZbdd(cube));

    zbdd.clear();

    assertEquals(List.of(
        "gc must not be invoked from a zbdd callback",
        "gc must not be invoked from a zbdd callback",
        "gc must not be invoked from a zbdd callback",
        "clear must not be invoked from a zbdd callback"), failures);

    // outside of callbacks, the methods work as before
    assertEquals(0, zbdd.gc());
  }


  @Test
  @DisplayName("Node creation in beforeGc grows the nodes capacity without nested garbage collection")
  void createNodesInBeforeGc()
  {
    final var zbdd = ZbddFactory.create(new SmallCapacityAdvisor());
    final var statistics = zbdd.getStatistics();
    final var vars = new int[64];

    for(var i = 0; i < vars.length; i++)
      vars[i] = zbdd.createVar();

    final var referenced = zbdd.incRef(zbdd.cube(vars[0], vars[1]));

    zbdd.gc();
    final var occupiedNodes = statistics.getOccupiedNodes();
    final var createdInCallback = new ArrayList<Integer>();

    zbdd.registerCallback(new ZbddCallback() {
      @Override
      public void beforeGc()
      {
        // more nodes than the initial capacity provides
        for(var var: vars)
          createdInCallback.add(zbdd.cube(var));
      }
    });

    final var gcCount = statistics.getGCCount();

    // a nested gc would invoke beforeGc again and never return
    assertTimeoutPreemptively(Duration.ofSeconds(10), zbdd::gc);

    assertEquals(gcCount + 1, statistics.getGCCount());
    assertEquals(vars.length, createdInCallback.size());
    assertTrue(statistics.getCapacityIncreaseCount() > 0);

    // unreferenced nodes created in beforeGc are freed by the gc in progress
    assertEquals(occupiedNodes, statistics.getOccupiedNodes());
    assertTrue(zbdd.isValidZbdd(referenced));
    assertEquals(referenced, zbdd.cube(vars[0], vars[1]));
  }




  private static final class SmallCapacityAdvisor implements ZbddCapacityAdvisor
  {
    @Override public int getInitialCapacity() { return 16; }
    @Override public int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) { return 2; }
    @Override public int adviseIncrement(@NotNull ZbddStatistics statistics) { return 16; }
    @Override public boolean isGCRequired(@NotNull ZbddStatistics statistics) { return true; }
  }
}
