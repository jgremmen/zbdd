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

import de.sayayi.lib.zbdd.cache.ZbddFastCache;
import de.sayayi.lib.zbdd.internal.PresetCapacityAdvisor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Random;

import static de.sayayi.lib.zbdd.internal.ZbddImpl.MAX_NODES;
import static java.lang.reflect.Proxy.newProxyInstance;
import static org.junit.jupiter.api.Assertions.*;


/**
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Predefined capacity advisors")
class ZbddCapacityAdvisorTest
{
  @Test
  @DisplayName("Constants refer to the implementation of the same name")
  void constants()
  {
    assertSame(PresetCapacityAdvisor.DEFAULT, ZbddCapacityAdvisor.DEFAULT);
    assertSame(PresetCapacityAdvisor.MINIMAL_MEMORY, ZbddCapacityAdvisor.MINIMAL_MEMORY);
    assertSame(PresetCapacityAdvisor.LOW_MEMORY, ZbddCapacityAdvisor.LOW_MEMORY);
    assertSame(PresetCapacityAdvisor.BALANCED, ZbddCapacityAdvisor.BALANCED);
    assertSame(PresetCapacityAdvisor.HIGH_PERFORMANCE, ZbddCapacityAdvisor.HIGH_PERFORMANCE);
  }


  @ParameterizedTest(name = "{0}: capacity {1}")
  @DisplayName("Capacity increment")
  @CsvSource({
      "DEFAULT,          499999,    749998",
      "DEFAULT,          500000,    150000",
      "MINIMAL_MEMORY,   64,        8",
      "MINIMAL_MEMORY,   1000000,   120000",
      "LOW_MEMORY,       8,         8",
      "LOW_MEMORY,       1000000,   250000",
      "BALANCED,         1000000,   500000",
      "BALANCED,         4000000,   2000000",
      "HIGH_PERFORMANCE, 3999999,   5999998",
      "HIGH_PERFORMANCE, 4000000,   2000000",
      "HIGH_PERFORMANCE, 300000000, 150000000"
  })
  void adviseIncrement(PresetCapacityAdvisor advisor, int capacity, int expectedIncrement) {
    assertEquals(expectedIncrement, advisor.adviseIncrement(statistics(capacity, 0, 0, 0)));
  }


  @ParameterizedTest(name = "{0}: capacity {1}")
  @DisplayName("Minimum number of free nodes")
  @CsvSource({
      "DEFAULT,          1000,      250",
      "MINIMAL_MEMORY,   8,         1",
      "MINIMAL_MEMORY,   1000,      100",
      "LOW_MEMORY,       1000,      150",
      "BALANCED,         1000,      250",
      "HIGH_PERFORMANCE, 1000,      500",
      "HIGH_PERFORMANCE, 300000000, 150000000"
  })
  void minimumFreeNodes(PresetCapacityAdvisor advisor, int capacity, int expectedNodes) {
    assertEquals(expectedNodes, advisor.getMinimumFreeNodes(statistics(capacity, 0, 0, 0)));
  }


  @ParameterizedTest(name = "{0}: dead={1}, new={2}, allocated={3}")
  @DisplayName("Garbage collection depends on the nodes likely to be freed")
  @CsvSource({
      "DEFAULT,          100, 0,   0,   false",
      "DEFAULT,          101, 0,   0,   true",
      "BALANCED,         100, 0,   0,   false",
      "BALANCED,         101, 0,   0,   true",
      "BALANCED,         0,   200, 100, false",
      "BALANCED,         0,   101, 200, true",
      "MINIMAL_MEMORY,   101, 0,   0,   true",
      "LOW_MEMORY,       101, 0,   0,   true",
      "HIGH_PERFORMANCE, 250, 0,   0,   false",
      "HIGH_PERFORMANCE, 200, 51,  51,  true"
  })
  void isGCRequired(PresetCapacityAdvisor advisor, int dead, int newNodes, int allocated, boolean expected) {
    assertEquals(expected, advisor.isGCRequired(statistics(1000, dead, newNodes, allocated)));
  }


  @ParameterizedTest
  @EnumSource(PresetCapacityAdvisor.class)
  @DisplayName("Garbage collection is always required at the maximum capacity")
  void isGCRequiredAtMaximumCapacity(PresetCapacityAdvisor advisor) {
    assertTrue(advisor.isGCRequired(statistics(MAX_NODES, 1, 0, 0)));
  }


  @ParameterizedTest
  @EnumSource(PresetCapacityAdvisor.class)
  @DisplayName("Results don't depend on the advisor, with or without cache")
  void results(PresetCapacityAdvisor advisor)
  {
    assertEquals(4000, workload(ZbddFactory.create(advisor)));
    assertEquals(4000, workload(ZbddFactory.createCached(advisor, new ZbddFastCache())));
  }


  @Test
  @DisplayName("A stricter advisor ends up with a smaller table")
  void tableSizes()
  {
    final var minimal = ZbddFactory.create(ZbddCapacityAdvisor.MINIMAL_MEMORY);
    final var performance = ZbddFactory.create(ZbddCapacityAdvisor.HIGH_PERFORMANCE);

    workload(minimal);
    workload(performance);

    assertTrue(minimal.getStatistics().getNodesCapacity() < performance.getStatistics().getNodesCapacity());
    assertTrue(minimal.getStatistics().getGCCount() > performance.getStatistics().getGCCount());
  }


  private static int workload(@NotNull Zbdd zbdd)
  {
    final var vars = new int[2000];

    for(var i = 0; i < vars.length; i++)
      vars[i] = zbdd.createVar();

    final var random = new Random(1);
    final var live = new ArrayList<Integer>();
    var set = Zbdd.EMPTY;

    // the set of single var cubes grows with each union, leaving garbage behind; the other cubes stay referenced
    for(var var: vars)
    {
      final var union = zbdd.incRef(zbdd.union(set, zbdd.cube(var)));

      zbdd.decRef(set);
      set = union;

      live.add(zbdd.incRef(zbdd.cube(vars[random.nextInt(vars.length)], var)));
    }

    return zbdd.count(set) + zbdd.count(zbdd.union(live.stream().mapToInt(Integer::intValue).toArray()));
  }


  private static @NotNull ZbddStatistics statistics(int capacity, int dead, int newNodes, int allocated)
  {
    return (ZbddStatistics)newProxyInstance(ZbddStatistics.class.getClassLoader(),
        new Class<?>[] { ZbddStatistics.class }, (proxy, method, args) -> switch(method.getName()) {
          case "getNodesCapacity" -> capacity;
          case "getDeadNodes" -> dead;
          case "getNewNodes" -> newNodes;
          case "getNodesAllocatedSinceGC" -> allocated;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
