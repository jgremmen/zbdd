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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static de.sayayi.lib.zbdd.Zbdd.ZBDD_BASE;
import static de.sayayi.lib.zbdd.Zbdd.ZBDD_EMPTY;
import static org.junit.jupiter.api.Assertions.*;


/**
 * @author Jeroen Gremmen
 */
@DisplayName("Basic zbdd operations")
class ZbddTest
{
  @Test
  @DisplayName("Create variable")
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
  @DisplayName("Operation 'change'")
  void change()
  {
    Zbdd zbdd = new Zbdd();
    int var = zbdd.createVar();
    int r = zbdd.cube(var);

    assertEquals(ZBDD_EMPTY, zbdd.change(ZBDD_EMPTY, var));
    assertEquals(r, zbdd.change(ZBDD_BASE, var));
  }


  @Test
  @DisplayName("Operation 'count'")
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
  @DisplayName("Operation 'multiply'")
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
  @DisplayName("Operation 'removeBase'")
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
  @DisplayName("Operation 'contains'")
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


  @Test
  @DisplayName("Cartesian product")
  void cartesianProduct()
  {
    Zbdd zbdd = new Zbdd();
    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();
    int d = zbdd.createVar();
    int e = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : var == c ? "c" : var ==d ? "d" : "e");

    int r = zbdd.getNode(a, zbdd.base(), zbdd.base());
    r = zbdd.getNode(b, r, r);
    r = zbdd.getNode(c, r, r);
    r = zbdd.getNode(d, r, r);
    r = zbdd.getNode(e, r, r);

    assertEquals(32, zbdd.count(r));
  }
}
