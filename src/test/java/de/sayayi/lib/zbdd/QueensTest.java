/*
 * Copyright 2024 Jeroen Gremmen
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static de.sayayi.lib.zbdd.Zbdd.*;
import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * @author Jeroen Gremmen
 * @since 0.2.2
 */
@DisplayName("Solve n-Queens problem")
class QueensTest
{
  private static Stream<Arguments> queensParameters()
  {
    return Stream.of(
        Arguments.of(1, 1, 16),
        Arguments.of(2, 0, 16),
        Arguments.of(3, 0, 16),
        Arguments.of(4, 2, 32),
        Arguments.of(5, 10, 128),
        Arguments.of(6, 4, 256),
        Arguments.of(7, 40, 550),
        Arguments.of(8, 92, 1_700),
        Arguments.of(9, 352, 5_400),
        Arguments.of(10, 724, 20_000),
        Arguments.of(11, 2_680, 80_000),
        Arguments.of(12, 14_200, 350_000),
        Arguments.of(13, 73_712, 256)  // force a large number of capacity changes
    );
  }


  @DisplayName("n-Queens problem solving")
  @ParameterizedTest(name = "{0}-Queens problem has {1} solutions")
  @MethodSource("queensParameters")
  void queens(int n, int solutionsExpected, int tableSize)
  {
    final Zbdd zbdd = new Zbdd(new SimpleCapacityAdvisor(tableSize));
    zbdd.setZbddCache(new ZbddFastCache(65536));

    final int[][] vars = getVars(zbdd, n);

    int solution = ZBDD_BASE;
    int ct;

    for(int s = 0; s < n; s++)
    {
      int tmp = ZBDD_EMPTY;

      zbdd.incRef(solution);

      for(int c = 0; c < n; c++)
      {
        int sc = solution;
        final int tmp0 = zbdd.incRef(tmp);

        for(int r = 0; r < s; r++)
        {
          sc = zbdd.subset0(sc, vars[r][c]);

          if ((ct = c - (s - r)) >= 0)
            sc = zbdd.subset0(sc, vars[r][ct]);

          if ((ct = c + (s - r)) < n)
            sc = zbdd.subset0(sc, vars[r][ct]);
        }

        tmp = zbdd.union(tmp0, zbdd.change(sc, vars[s][c]));
        zbdd.decRef(tmp0);
      }

      zbdd.decRef(solution);
      solution = tmp;
    }

    int solutions = zbdd.count(zbdd.incRef(solution));
    assertEquals(solutionsExpected, solutions);

    ZbddLiteralResolver nameResolver = zbdd.getLiteralResolver();
    System.out.println("Queens " + n + "x" + n + "  (" + solutions + ")");
    System.out.println("  " + zbdd.getStatistics());

    if (n < 9)
      zbdd.visitCubes(solution, cube -> System.out.println("  " + nameResolver.getCubeName(cube)));

    System.out.println(zbdd.getZbddCache());
    System.out.println();
  }


  private int[][] getVars(Zbdd zbdd, int n)
  {
    final int[][] vars = new int[n][n];
    final Map<Integer,String> varNames = new HashMap<>();
    final String format = n < 10 ? "%c%d" : "%c%02d";

    for(int r = n; r-- > 0;)
      for(int c = n; c-- > 0;)
        varNames.put(vars[r][c] = zbdd.createVar(), String.format(format, 'a' + c, n - r));

    zbdd.setLiteralResolver(varNames::get);

    return vars;
  }




  private static final class SimpleCapacityAdvisor implements ZbddCapacityAdvisor
  {
    private final int initialSize;


    private SimpleCapacityAdvisor(int initialSize) {
      this.initialSize = initialSize;
    }


    @Override
    public int getInitialCapacity() {
      return initialSize;
    }


    @Override
    public @Range(from = 1, to = MAX_NODES) int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) {
      return statistics.getNodesCapacity() / 20;
    }


    @Override
    public @Range(from = 1, to = MAX_NODES) int adviseIncrement(@NotNull ZbddStatistics statistics) {
      return statistics.getNodesCapacity() / 5;  // +20%
    }


    @Override
    public boolean isGCRequired(@NotNull ZbddStatistics statistics) {
      return statistics.getDeadNodes() > (statistics.getNodesCapacity() / 10);
    }
  }
}
