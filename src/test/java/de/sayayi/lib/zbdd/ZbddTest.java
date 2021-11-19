package de.sayayi.lib.zbdd;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static de.sayayi.lib.zbdd.Zbdd.MAX_NODES;
import static de.sayayi.lib.zbdd.Zbdd.ZBDD_BASE;
import static de.sayayi.lib.zbdd.Zbdd.ZBDD_EMPTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


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

    zbdd.setNameResolver(var -> var == a ? "a" : var == b ? "b" : "c");

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

    zbdd.setNameResolver(var -> var == c ? "container" : var == x1 ? "x1" : "x2");

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

    zbdd.setNameResolver(var -> var == c ? "container" : var == x1 ? "x1" : "x2");

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

    zbdd.setNameResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    int ab = zbdd.cube(a, b);
    int p = zbdd.union(zbdd.union(ab, zbdd.cube(b)), zbdd.cube(c));
    int q = zbdd.union(ab, ZBDD_BASE);
    int r = zbdd.multiply(p, q);

    assertEquals(3, zbdd.count(p));
    assertEquals(2, zbdd.count(q));
    assertEquals(4, zbdd.count(r));
    assertEquals(zbdd.union(zbdd.union(zbdd.union(ab, zbdd.cube(a, b, c)), zbdd.cube(b)), zbdd.cube(c)), r);
  }


  @Test void queens01() { checkSolution(1, 1,16); }
  @Test void queens02() { checkSolution(2, 0, 16); }
  @Test void queens03() { checkSolution(3, 0, 16); }
  @Test void queens04() { checkSolution(4, 2, 32); }
  @Test void queens05() { checkSolution(5, 10, 150); }
  @Test void queens06() { checkSolution(6, 4, 200); }
  @Test void queens07() { checkSolution(7, 40, 600); }
  @Test void queens08() { checkSolution(8, 92, 2000); }
  @Test void queens09() { checkSolution(9, 352, 12500); }
  @Test void queens10() { checkSolution(10, 724, 32000); }
  @Test void queens11() { checkSolution(11, 2680, 200000); }
  @Test void queens12() { checkSolution(12, 14200, 500000); }


  private void checkSolution(int n, int solutionsExpected, int tableSize)
  {
    final Zbdd zbdd = new Zbdd(new SimpleNodesAdvisor(tableSize));
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

    assertEquals(solutionsExpected, zbdd.count(solution));
  }


  private int[][] getVars(Zbdd zbdd, int n)
  {
    final int[][] vars = new int[n][n];
    final Map<Integer,String> varNames = new HashMap<>();
    final String format = n < 10 ? "r%dc%d" : "r%02dc%02d";

    for(int r = 0; r < n; r++)
      for(int c = 0; c < n; c++)
        varNames.put(vars[r][c] = zbdd.createVar(), String.format(format, r + 1, c + 1));

    //noinspection NullableProblems
    zbdd.setNameResolver(varNames::get);

    return vars;
  }




  private static final class SimpleNodesAdvisor implements ZbddNodesAdvisor
  {
    private final int initialSize;


    private SimpleNodesAdvisor(int initialSize) {
      this.initialSize = initialSize;
    }


    @Override
    public @Range(from = 4, to = MAX_NODES) int getInitialNodes() {
      return initialSize;
    }


    @Override
    public @Range(from = 1, to = MAX_NODES) int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) {
      return statistics.getNodeTableSize() / 5;
    }


    @Override
    public @Range(from = 1, to = MAX_NODES) int adviseNodesGrowth(@NotNull ZbddStatistics statistics) {
      return statistics.getNodeTableSize() / 5;  // +20%
    }


    @Override
    public boolean isGCRequired(@NotNull ZbddStatistics statistics) {
      return statistics.getDeadNodes() > (statistics.getNodeTableSize() / 10);
    }
  }
}