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
package de.sayayi.lib.zbdd.benchmark;

import de.sayayi.lib.zbdd.Zbdd;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

import static java.util.Arrays.copyOf;


/**
 * Deterministic zbdd sets used by the benchmarks. All sets returned have an increased reference count.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
final class Workloads
{
  private Workloads() {
  }


  static int @NotNull [] createVars(@NotNull Zbdd zbdd, int count)
  {
    final var vars = new int[count];

    for(var i = 0; i < count; i++)
      vars[i] = zbdd.createVar();

    return vars;
  }


  /**
   * Creates a set of random cubes with few shared nodes. Each cube has between {@code minVars} and {@code maxVars}
   * variables, taken from {@code vars[from]} up to and excluding {@code vars[to]}.
   */
  static int randomSet(@NotNull Zbdd zbdd, int @NotNull [] vars, int from, int to, @NotNull Random random, int cubes,
                       int minVars, int maxVars)
  {
    var set = Zbdd.EMPTY;

    for(var n = 0; n < cubes; n++)
    {
      final var cubeVars = new int[minVars + random.nextInt(maxVars - minVars + 1)];

      for(var i = 0; i < cubeVars.length; i++)
        cubeVars[i] = vars[from + random.nextInt(to - from)];

      final var union = zbdd.incRef(zbdd.union(set, zbdd.cube(cubeVars)));

      zbdd.decRef(set);
      set = union;
    }

    return set;
  }


  /**
   * Creates the set of all combinations of {@code vars}, having {@code vars.length} nodes and
   * 2<sup>vars.length</sup> cubes. As each node is reachable through many paths, this is the worst case for operations
   * which don't remember the nodes visited.
   */
  static int powerSet(@NotNull Zbdd zbdd, int @NotNull [] vars)
  {
    var powerSet = Zbdd.BASE;

    for(var var: vars)
      powerSet = zbdd.getNode(var, powerSet, powerSet);

    return zbdd.incRef(powerSet);
  }


  /**
   * Keeps creating referenced cubes until at least {@code liveNodes} nodes are in use.
   */
  static void fillNodes(@NotNull Zbdd zbdd, int @NotNull [] vars, @NotNull Random random, int liveNodes)
  {
    final var statistics = zbdd.getStatistics();

    do {
      for(var n = 0; n < 20_000; n++)
      {
        zbdd.incRef(zbdd.cube(vars[random.nextInt(vars.length)], vars[random.nextInt(vars.length)],
            vars[random.nextInt(vars.length)], vars[random.nextInt(vars.length)]));
      }

      zbdd.gc();
    } while(statistics.getNodesCapacity() - statistics.getFreeNodes() < liveNodes);
  }


  /**
   * Solves the n-queens problem, placing {@code n} queens on an {@code n}x{@code n} board so that no two of them attack
   * each other. See {@code QueensTest} for an explanation.
   *
   * @return  number of solutions
   */
  static int queens(@NotNull Zbdd zbdd, int n)
  {
    final var vars = new int[n][n];

    for(int r = n; r-- > 0;)
      for(int c = n; c-- > 0;)
        vars[r][c] = zbdd.createVar();

    final var attackingVars = new int[3 * n];
    var solution = Zbdd.BASE;
    int ct;

    for(var s = n; s-- > 0;)
    {
      var tmp = Zbdd.EMPTY;

      zbdd.incRef(solution);

      for(var c = 0; c < n; c++)
      {
        final var tmp0 = zbdd.incRef(tmp);
        var attackingCount = 0;

        for(var r = s + 1; r < n; r++)
        {
          attackingVars[attackingCount++] = vars[r][c];

          if ((ct = c - (r - s)) >= 0)
            attackingVars[attackingCount++] = vars[r][ct];

          if ((ct = c + (r - s)) < n)
            attackingVars[attackingCount++] = vars[r][ct];
        }

        final var sc = zbdd.subset0(solution, copyOf(attackingVars, attackingCount));

        tmp = zbdd.union(tmp0, zbdd.change(sc, vars[s][c]));
        zbdd.decRef(tmp0);
      }

      zbdd.decRef(solution);
      solution = tmp;
    }

    return zbdd.count(solution);
  }
}
