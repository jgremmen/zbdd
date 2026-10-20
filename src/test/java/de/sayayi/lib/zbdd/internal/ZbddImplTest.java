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

import de.sayayi.lib.zbdd.exception.ZbddException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.ReflectionUtils;

import static de.sayayi.lib.zbdd.Zbdd.BASE;
import static de.sayayi.lib.zbdd.Zbdd.EMPTY;
import static de.sayayi.lib.zbdd.internal.ZbddImpl.MAX_VAR;
import static java.lang.Integer.MAX_VALUE;
import static org.junit.jupiter.api.Assertions.*;


/**
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("ZbddImpl")
class ZbddImplTest
{
  @Test
  @DisplayName("Node lookup in buckets 0 and 1 after clear")
  void lookupAfterClear()
  {
    final var zbdd = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);

    // populate the hash table, including the chains for buckets 0 and 1
    var vars = new int[60];

    for(var i = 0; i < vars.length; i++)
      vars[i] = zbdd.createVar();

    for(var i = 0; i < vars.length; i++)
      for(var j = i + 1; j < vars.length; j++)
        zbdd.incRef(zbdd.cube(vars[i], vars[j]));

    zbdd.clear();

    final var statistics = zbdd.getStatistics();
    assertEquals(0, statistics.getRegisteredVars());
    assertEquals(statistics.getNodesCapacity() - 2, statistics.getFreeNodes());

    // the first lookups after clear must hit buckets 0 and 1, before stale nodes get reallocated
    final var foundBucket = new boolean[2];

    for(var i = 0; i < 10000 && !(foundBucket[0] && foundBucket[1]); i++)
    {
      final var var = zbdd.createVar();
      final var bucket = zbdd.hash(var, EMPTY, BASE);

      if (bucket < 2 && !foundBucket[bucket])
      {
        foundBucket[bucket] = true;

        final var cube = zbdd.cube(var);

        assertEquals(var, zbdd.getVar(cube));
        assertEquals(cube, zbdd.cube(var));
      }
    }

    assertTrue(foundBucket[0] && foundBucket[1]);
  }


  @Test
  @DisplayName("Lookup counters beyond int range")
  void lookupCountersBeyondIntRange() throws Exception
  {
    final var zbdd = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);
    final var statistics = zbdd.getStatistics();

    // simulate 2^31 - 1 node lookups, all of them hits
    for(var counterFieldName: new String[] { "nodeLookups", "nodeLookupHitCount" })
    {
      ReflectionUtils
          .makeAccessible(statistics.getClass().getDeclaredField(counterFieldName))
          .setLong(statistics, MAX_VALUE);
    }

    final var var = zbdd.createVar();
    zbdd.cube(var);  // miss
    zbdd.cube(var);  // hit

    assertEquals(MAX_VALUE + 2L, statistics.getNodeLookups());
    assertEquals(MAX_VALUE + 1L, statistics.getNodeLookupHitCount());
    assertTrue(statistics.getNodeLookupHitRatio() > 0.99);
  }


  @Test
  @DisplayName("Highest variable survives garbage collection")
  void highestVarSurvivesGc() throws Exception
  {
    final var zbdd = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);
    final var lowVar = zbdd.createVar();

    // simulate MAX_VAR - 1 created variables
    ReflectionUtils
        .makeAccessible(ZbddImpl.class.getDeclaredField("lastVarNumber"))
        .setInt(zbdd, MAX_VAR - 1);

    final var highVar = zbdd.createVar();
    assertEquals(MAX_VAR, highVar);
    assertThrowsExactly(ZbddException.class, zbdd::createVar);

    final var cube = zbdd.incRef(zbdd.cube(lowVar, highVar));

    zbdd.gc();

    // the top node of the cube must not have been mistaken for a free node
    assertTrue(zbdd.isValidZbdd(cube));
    assertEquals(highVar, zbdd.getVar(cube));
    assertEquals(lowVar, zbdd.getVar(zbdd.getP1(cube)));
    assertEquals(1, zbdd.count(cube));
    assertEquals(cube, zbdd.cube(lowVar, highVar));
  }


  @Test
  @DisplayName("Cube with variable for variables far apart")
  void hasCubeWithVarForDistantVars() throws Exception
  {
    final var zbdd = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);
    final var lowVar = zbdd.createVar();
    final var midVar = zbdd.createVar();

    // simulate MAX_VAR - 1 created variables
    ReflectionUtils
        .makeAccessible(ZbddImpl.class.getDeclaredField("lastVarNumber"))
        .setInt(zbdd, MAX_VAR - 1);

    final var highVar = zbdd.createVar();
    final var set = zbdd.union(zbdd.cube(highVar), zbdd.cube(midVar, highVar));

    assertTrue(zbdd.hasCubeWithVar(set, highVar));
    assertTrue(zbdd.hasCubeWithVar(set, midVar));
    assertFalse(zbdd.hasCubeWithVar(set, lowVar));
  }
}
