/*
 * Copyright 2021 Jeroen Gremmen
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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static de.sayayi.lib.zbdd.Zbdd.*;
import static org.junit.jupiter.api.Assertions.*;


/**
 * @author Jeroen Gremmen
 */
class ZbddTest
{
  @Test
  @SuppressWarnings("ConstantConditions")
  void createVar()
  {
    Zbdd zbdd = new Zbdd();
    int var = zbdd.createVar();
    int r = zbdd.cube(var);

    assertTrue(var > 0);
    assertTrue(r >= 2);
    assertEquals(var, zbdd.getVar(r));
    assertEquals(ZBDD_EMPTY, zbdd.getP0(r));
    assertEquals(ZBDD_BASE, zbdd.getP1(r));

    assertThrows(ZbddException.class, () -> zbdd.cube(var + 1));
    assertThrows(ZbddException.class, () -> zbdd.cube(0));
  }


  @Test
  void change()
  {
    Zbdd zbdd = new Zbdd();
    int var = zbdd.createVar();
    int r = zbdd.cube(var);

    assertEquals(ZBDD_EMPTY, zbdd.change(ZBDD_EMPTY, var));
    assertEquals(r, zbdd.change(ZBDD_BASE, var));
  }


  @Test
  void count()
  {
    Zbdd zbdd = new Zbdd();
    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    int ab = zbdd.cube(a, b);
    int ac = zbdd.cube(a, c);
    int r = zbdd.union(zbdd.union(zbdd.union(zbdd.union(ab, zbdd.cube(b)), zbdd.cube(c)), ac), ZBDD_BASE);

    assertEquals(5, zbdd.count(r));
  }


  @Test
  void subset1Test1()
  {
    Zbdd zbdd = new Zbdd();
    int c = zbdd.createVar();
    int x1 = zbdd.createVar();
    int x2 = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == c ? "container" : var == x1 ? "x1" : "x2");

    int dependency = zbdd.cube(c, x1, x2);
    int containerSubset = zbdd.subset1(dependency, c);

    assertEquals(1, zbdd.count(containerSubset));
    assertEquals(x2, zbdd.getVar(containerSubset));
  }


  @Test
  void subset1Test2()
  {
    Zbdd zbdd = new Zbdd();
    int c = zbdd.createVar();
    int x1 = zbdd.createVar();
    int x2 = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == c ? "container" : var == x1 ? "x1" : "x2");

    int dependency1 = zbdd.cube(c, x1, x2);
    int dependency2 = zbdd.cube(c, x2);
    int union = zbdd.union(zbdd.union(zbdd.union(zbdd.cube(x1), dependency1), dependency2), zbdd.cube(c));

    assertEquals(4, zbdd.count(union));

    int clear = zbdd.subset1(union, c);

    assertEquals(3, zbdd.count(clear));

    int clearWithoutBase = zbdd.difference(clear, ZBDD_BASE);

    assertEquals(2, zbdd.count(clearWithoutBase));
  }


  @Test
  void multiply()
  {
    Zbdd zbdd = new Zbdd();

    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    int ab = zbdd.cube(a, b);
    int p = zbdd.union(zbdd.union(ab, zbdd.cube(b)), zbdd.cube(c));
    int q = zbdd.union(ab, ZBDD_BASE);
    int r = zbdd.multiply(p, q);

    assertEquals(3, zbdd.count(p));
    assertEquals(2, zbdd.count(q));
    assertEquals(4, zbdd.count(r));
    assertEquals(zbdd.union(zbdd.union(zbdd.union(ab, zbdd.cube(a, b, c)), zbdd.cube(b)), zbdd.cube(c)), r);
  }


  @Test
  void removeBase()
  {
    Zbdd zbdd = new Zbdd();
    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    int ab = zbdd.cube(a, b);
    int ac = zbdd.cube(a, c);
    int ab_ac_b_c = zbdd.union(zbdd.union(zbdd.union(ab, zbdd.cube(b)), zbdd.cube(c)), ac);
    int r = zbdd.union(ab_ac_b_c, ZBDD_BASE);

    assertEquals(ab_ac_b_c, zbdd.removeBase(r));
    assertEquals(zbdd.cube(a), zbdd.removeBase(zbdd.subset1(ab_ac_b_c, c)));
    assertTrue(Zbdd.isEmpty(zbdd.removeBase(zbdd.base())));
  }


  @Test
  void contains()
  {
    Zbdd zbdd = new Zbdd();
    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    int ab = zbdd.cube(a, b);
    int ac = zbdd.cube(a, c);
    int ab_ac_b_c = zbdd.union(zbdd.union(zbdd.union(ab, zbdd.cube(b)), zbdd.cube(c)), ac);
    int r = zbdd.union(ab_ac_b_c, ZBDD_BASE);

    assertFalse(zbdd.contains(r, ZBDD_EMPTY));
    assertTrue(zbdd.contains(r, ZBDD_BASE));
    assertTrue(zbdd.contains(r, ab));
    assertTrue(zbdd.contains(r, ac));
    assertTrue(zbdd.contains(r, zbdd.cube(b)));
    assertTrue(zbdd.contains(r, zbdd.union(zbdd.cube(b), zbdd.cube(c))));
    assertFalse(zbdd.contains(r, zbdd.union(ab, zbdd.cube(a))));
  }


  @Test void queens01() { checkSolution(1, 1, 16); }
  @Test void queens02() { checkSolution(2, 0, 16); }
  @Test void queens03() { checkSolution(3, 0, 16); }
  @Test void queens04() { checkSolution(4, 2, 32); }
  @Test void queens05() { checkSolution(5, 10, 128); }
  @Test void queens06() { checkSolution(6, 4, 256); }
  @Test void queens07() { checkSolution(7, 40, 550); }
  @Test void queens08() { checkSolution(8, 92, 1700); }
  @Test void queens09() { checkSolution(9, 352, 5400); }
  @Test void queens10() { checkSolution(10, 724, 20000); }
  @Test void queens11() { checkSolution(11, 2680, 80000); }


  private void checkSolution(int n, int solutionsExpected, int tableSize)
  {
    final Zbdd zbdd = new Zbdd(new SimpleCapacityAdvisor(tableSize));
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
        int tmp0 = zbdd.incRef(tmp);

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
    zbdd.visitCubes(solution, cube -> System.out.println("  " + nameResolver.getCubeName(cube)));
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