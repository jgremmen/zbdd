package de.sayayi.lib.zbdd;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

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


  @Test
  void queens1() {
    checkSolution(1, 1);
  }


  @Test
  void queens2() {
    checkSolution(2, 0);
  }


  @Test
  void queens3() {
    checkSolution(3, 0);
  }


  @Test
  void queens4() {
    checkSolution(4, 2);
  }


  @Test
  void queens5() {
    checkSolution(5, 10);
  }


  @Test
  void queens6() {
    checkSolution(6, 4);
  }


  @Test
  void queens7() {
    checkSolution(7, 40);
  }


  @Test
  void queens8() {
    checkSolution(8, 92);
  }


  @Test
  void queens9() {
    checkSolution(9, 352);
  }


  @Test
  void queens10() {
    checkSolution(10, 724);
  }


  private void checkSolution(int n, int solutionsExpected)
  {
    final Zbdd zbdd = new Zbdd();
    final int[][] vars = getVars(zbdd, n);

    int solution = ZBDD_BASE;
    int ct;

    for(int s = 0; s < n; s++)
    {
      int tmp = ZBDD_EMPTY;

      for(int c = 0; c < n; c++)
      {
        int sc = solution;

        for(int r = 0; r < s; r++)
        {
          sc = zbdd.incRef(zbdd.subset0(sc, vars[r][c]));

          if ((ct = c - (s - r)) >= 0)
            sc = zbdd.incRef(zbdd.subset0(sc, vars[r][ct]));

          if ((ct = c + (s - r)) < n)
            sc = zbdd.incRef(zbdd.subset0(sc, vars[r][ct]));
        }

        tmp = zbdd.incRef(zbdd.union(tmp, zbdd.incRef(zbdd.change(sc, vars[s][c]))));
      }

      solution = tmp;
    }

    assertEquals(solutionsExpected, zbdd.count(solution));
  }


  private int[][] getVars(Zbdd zbdd, int n)
  {
    final int[][] vars = new int[n][n];
    final Map<Integer,String> varNames = new HashMap<>();
    final String format = n < 10 ? "Qr%dc%d" : "Qr%02dc%02d";

    for(int r = 0; r < n; r++)
      for(int c = 0; c < n; c++)
        varNames.put(vars[r][c] = zbdd.createVar(), String.format(format, r + 1, c + 1));

    //noinspection NullableProblems
    zbdd.setNameResolver(varNames::get);

    return vars;
  }
}
