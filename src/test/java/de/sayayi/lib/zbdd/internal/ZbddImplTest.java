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

import de.sayayi.lib.zbdd.ZbddCapacityAdvisor;
import de.sayayi.lib.zbdd.ZbddStatistics;
import de.sayayi.lib.zbdd.exception.InvalidZbddException;
import de.sayayi.lib.zbdd.exception.ZbddException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.ReflectionUtils;

import java.util.ArrayList;

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
  @DisplayName("Lookup ratios without lookups")
  void lookupRatiosWithoutLookups()
  {
    final var zbdd = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);

    for(var statistics: new ZbddStatistics[] { zbdd.getStatistics(), new ZbddConcurrent(zbdd).getStatistics() })
    {
      assertEquals(0, statistics.getNodeLookups());
      assertEquals(0.0, statistics.getNodeLookupHitRatio());
      assertEquals(0.0, statistics.getNodeLookupMissRatio());
    }

    final var var = zbdd.createVar();
    zbdd.cube(var);  // miss
    zbdd.cube(var);  // hit

    final var statistics = zbdd.getStatistics();

    assertEquals(0.5, statistics.getNodeLookupHitRatio());
    assertEquals(0.5, statistics.getNodeLookupMissRatio());
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


  @Test
  @DisplayName("Instance remains intact after failing to increase the nodes capacity")
  void outOfMemoryOnCapacityIncrease()
  {
    final var advisor = new ZbddCapacityAdvisor() {
      int increment = ZbddImpl.MAX_NODES;  // exceeds any reasonable heap

      @Override public int getInitialCapacity() { return 16; }
      @Override public int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) { return 2; }
      @Override public int adviseIncrement(@NotNull ZbddStatistics statistics) { return increment; }
      @Override public boolean isGCRequired(@NotNull ZbddStatistics statistics) { return false; }
    };
    
    final var zbdd = new ZbddImpl(advisor);
    final var statistics = zbdd.getStatistics();
    final var cubes = new ArrayList<Integer>();

    assertThrowsExactly(OutOfMemoryError.class, () -> {
      //noinspection InfiniteLoopStatement
      while(true)
        cubes.add(zbdd.incRef(zbdd.cube(zbdd.createVar())));
    });

    assertEquals(16, statistics.getNodesCapacity());
    assertEquals(0, statistics.getCapacityIncreaseCount());
    assertEquals(13, cubes.size());

    for(var cube: cubes)
    {
      assertTrue(zbdd.isValidZbdd(cube));
      assertEquals(cube, zbdd.cube(zbdd.getVar(cube)));
    }

    // with a reasonable increment, the instance continues to work
    advisor.increment = 16;

    final var var = zbdd.createVar();
    final var cube = zbdd.cube(var);

    assertEquals(32, statistics.getNodesCapacity());
    assertEquals(1, statistics.getCapacityIncreaseCount());
    assertEquals(var, zbdd.getVar(cube));

    for(var c: cubes)
      assertEquals(c, zbdd.cube(zbdd.getVar(c)));
  }


  @Test
  @DisplayName("Unmatched decRef leaves the reference count unchanged")
  void unmatchedDecRef()
  {
    final var zbdd = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);
    final var cube = zbdd.cube(zbdd.createVar());
    final var nodeInfo = zbdd.getZbddNodeInfo(cube);

    // terminal nodes are not reference counted
    assertEquals(EMPTY, zbdd.decRef(EMPTY));
    assertEquals(BASE, zbdd.decRef(BASE));

    // new node, reference count never incremented
    assertTrue(nodeInfo.isNewNode());
    assertThrowsExactly(AssertionError.class, () -> zbdd.decRef(cube));
    assertTrue(nodeInfo.isNewNode());

    // dead node, all increments have been matched already
    zbdd.decRef(zbdd.incRef(cube));
    assertTrue(nodeInfo.isDeadNode());
    assertThrowsExactly(AssertionError.class, () -> zbdd.decRef(cube));
    assertTrue(nodeInfo.isDeadNode());
    assertEquals(0, zbdd.getStatistics().getNewNodes());
    assertEquals(1, zbdd.getStatistics().getDeadNodes());
  }


  @Test
  @DisplayName("Node info of a freed and a recycled node")
  void nodeInfoOfFreedNode()
  {
    final var zbdd = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);
    final var var1 = zbdd.createVar();
    final var var2 = zbdd.createVar();
    final var cube = zbdd.cube(var1);
    final var nodeInfo = zbdd.getZbddNodeInfo(cube);

    assertEquals(var1, nodeInfo.getVar());

    // the unprotected node is freed
    zbdd.gc();
    assertThrows(InvalidZbddException.class, nodeInfo::getVar);
    assertThrows(InvalidZbddException.class, nodeInfo::toString);

    // the freed node number is reused, so the view describes the new node
    assertEquals(cube, zbdd.cube(var2));
    assertEquals(var2, nodeInfo.getVar());
  }


  @Test
  @DisplayName("Null visitor is rejected for the empty set")
  @SuppressWarnings("DataFlowIssue")
  void nullVisitorForEmptySet()
  {
    final var zbdd = new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);

    assertThrowsExactly(NullPointerException.class, () -> zbdd.visitCubes(EMPTY, null));
    assertThrowsExactly(NullPointerException.class, () -> zbdd.visitCubeZbdds(EMPTY, null));
  }
}
