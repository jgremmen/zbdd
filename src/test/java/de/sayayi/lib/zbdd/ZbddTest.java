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

import de.sayayi.lib.zbdd.impl.DefaultCapacityAdvisor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.TreeMap;

import static java.lang.Integer.bitCount;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
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
    Zbdd zbdd = ZbddFactory.create();
    int var = zbdd.createVar();
    int r = zbdd.cube(var);

    assertTrue(var > 0);
    assertTrue(r >= 2);
    assertEquals(var, zbdd.getVar(r));
    assertEquals(Zbdd.empty(), zbdd.getP0(r));
    assertEquals(Zbdd.base(), zbdd.getP1(r));

    assertThrows(ZbddException.class, () -> zbdd.cube(var + 1));
    assertThrows(ZbddException.class, () -> zbdd.cube(0));
  }


  @Test
  @DisplayName("Operation 'change'")
  void change()
  {
    Zbdd zbdd = ZbddFactory.create();
    int var = zbdd.createVar();
    int r = zbdd.cube(var);

    assertEquals(Zbdd.empty(), zbdd.change(Zbdd.empty(), var));
    assertEquals(r, zbdd.change(Zbdd.base(), var));
  }


  @Test
  @DisplayName("Operation 'count'")
  void count()
  {
    Zbdd zbdd = ZbddFactory.create();
    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    int ab = zbdd.cube(a, b);
    int ac = zbdd.cube(a, c);
    int r = zbdd.union(ab, zbdd.cube(b), zbdd.cube(c), ac, Zbdd.base());

    assertEquals(5, zbdd.count(r));
  }


  @Test
  void subset1Test1()
  {
    Zbdd zbdd = ZbddFactory.create();
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
    Zbdd zbdd = ZbddFactory.create();
    int c = zbdd.createVar();
    int x1 = zbdd.createVar();
    int x2 = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == c ? "container" : var == x1 ? "x1" : "x2");

    int dependency1 = zbdd.cube(c, x1, x2);
    int dependency2 = zbdd.cube(c, x2);
    int union = zbdd.union(zbdd.cube(x1), dependency1, dependency2, zbdd.cube(c));

    assertEquals(4, zbdd.count(union));

    int clear = zbdd.subset1(union, c);

    assertEquals(3, zbdd.count(clear));

    int clearWithoutBase = zbdd.difference(clear, Zbdd.base());

    assertEquals(2, zbdd.count(clearWithoutBase));
  }


  @Test
  @DisplayName("Operation 'multiply'")
  void multiply()
  {
    Zbdd zbdd = ZbddFactory.create();

    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    int ab = zbdd.cube(a, b);
    int p = zbdd.union(ab, zbdd.cube(b), zbdd.cube(c));
    int q = zbdd.union(ab, Zbdd.base());
    int r = zbdd.multiply(p, q);

    assertEquals(3, zbdd.count(p));
    assertEquals(2, zbdd.count(q));
    assertEquals(4, zbdd.count(r));
    assertEquals(zbdd.union(ab, zbdd.cube(a, b, c), zbdd.cube(b), zbdd.cube(c)), r);
  }


  @Test
  @DisplayName("Operation 'difference'")
  void difference()
  {
    Zbdd zbdd = ZbddFactory.create();

    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();
    int d = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : var == c ? "c" : "d");

    // { d, bc, ac, b, a } - { bc, ab, a, ø } = { d, ac, b }
    int _ac = zbdd.incRef(zbdd.cube(a, c));
    int _ab = zbdd.incRef(zbdd.cube(a, b));
    int _bc = zbdd.incRef(zbdd.cube(b, c));
    int _a = zbdd.incRef(zbdd.cube(a));
    int _b = zbdd.incRef(zbdd.cube(b));
    int _d = zbdd.incRef(zbdd.cube(d));

    int p = zbdd.incRef(zbdd.union(_ac, _bc, _a, _b, _d));
    int q = zbdd.incRef(zbdd.union(_bc, _ab, _a, Zbdd.base()));
    int r = zbdd.difference(p, q);

    assertEquals(3, zbdd.count(r));
    assertTrue(zbdd.contains(r, _ac));
    assertTrue(zbdd.contains(r, _b));
    assertTrue(zbdd.contains(r, _d));
  }


  @Test
  @DisplayName("Operation 'removeBase'")
  void removeBase()
  {
    Zbdd zbdd = ZbddFactory.create();
    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    int ab = zbdd.cube(a, b);
    int ac = zbdd.cube(a, c);
    int ab_ac_b_c = zbdd.union(ab, zbdd.cube(b), zbdd.cube(c), ac);
    int r = zbdd.union(ab_ac_b_c, Zbdd.base());

    assertEquals(ab_ac_b_c, zbdd.removeBase(r));
    assertEquals(zbdd.cube(a), zbdd.removeBase(zbdd.subset1(ab_ac_b_c, c)));
    assertTrue(Zbdd.isEmpty(zbdd.removeBase(Zbdd.base())));
  }


  @Test
  @DisplayName("Operation 'contains'")
  void contains()
  {
    Zbdd zbdd = ZbddFactory.create();
    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    int ab = zbdd.cube(a, b);
    int ac = zbdd.cube(a, c);
    int ab_ac_b_c = zbdd.union(ab, zbdd.cube(b), zbdd.cube(c), ac);
    int r = zbdd.union(ab_ac_b_c, Zbdd.base());

    assertFalse(zbdd.contains(r, Zbdd.empty()));
    assertTrue(zbdd.contains(r, Zbdd.base()));
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
    Zbdd zbdd = ZbddFactory.create(DefaultCapacityAdvisor.INSTANCE);

    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();
    int d = zbdd.createVar();
    int e = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : var == c ? "c" : var == d ? "d" : "e");

    int r = zbdd.getNode(a, Zbdd.base(), Zbdd.base());
    r = zbdd.getNode(b, r, r);
    r = zbdd.getNode(c, r, r);
    r = zbdd.getNode(d, r, r);
    r = zbdd.getNode(e, r, r);

    assertEquals(32, zbdd.count(r));
  }


  @Test
  @DisplayName("Valid zbdd")
  void isValidZbdd()
  {
    ZbddCapacityAdvisor advisor = DefaultCapacityAdvisor.INSTANCE;
    Zbdd zbdd = ZbddFactory.create(advisor);

    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    assertTrue(zbdd.isValidZbdd(Zbdd.empty()));
    assertTrue(zbdd.isValidZbdd(Zbdd.base()));
    assertTrue(zbdd.isValidZbdd(zbdd.cube(a, b)));
    assertTrue(zbdd.isValidZbdd(zbdd.cube(c)));

    assertFalse(zbdd.isValidZbdd(-1));
    assertFalse(zbdd.isValidZbdd(zbdd.cube(a, b, c) + 5));
    assertFalse(zbdd.isValidZbdd(advisor.getInitialCapacity() + 1));
  }


  @Test
  @DisplayName("Valid var")
  void isValidVar()
  {
    Zbdd zbdd = ZbddFactory.create();

    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    assertTrue(zbdd.isValidVar(a));
    assertTrue(zbdd.isValidVar(c));

    assertFalse(zbdd.isValidVar(0));
    assertFalse(zbdd.isValidVar(c + 1));
  }


  @Test
  @DisplayName("Zbdd node info")
  void getZbddNodeInfo()
  {
    Zbdd zbdd = ZbddFactory.create();

    int a = zbdd.createVar();
    int b = zbdd.createVar();
    int c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    int _a = zbdd.incRef(zbdd.cube(a));
    int _ac = zbdd.incRef(zbdd.cube(a, c));
    int _b = zbdd.incRef(zbdd.cube(b));
    int _ac_b = zbdd.union(_ac, _b);

    var zbddNodeInfo = zbdd.getZbddNodeInfo(_ac_b);

    //noinspection ResultOfMethodCallIgnored
    zbddNodeInfo.toString();

    assertEquals(_ac_b, zbddNodeInfo.getZbdd());
    assertEquals(c, zbddNodeInfo.getVar());
    assertEquals(_b, zbddNodeInfo.getP0());
    assertEquals(_a, zbddNodeInfo.getP1());
    assertEquals(-1, zbddNodeInfo.getReferenceCount());
    assertEquals("c", zbddNodeInfo.getLiteral());
    assertTrue(zbddNodeInfo.isNewNode());

    zbdd.incRef(_ac_b);
    assertEquals(1, zbddNodeInfo.getReferenceCount());
    assertFalse(zbddNodeInfo.isNewNode());
    assertFalse(zbddNodeInfo.isDeadNode());

    zbdd.decRef(_ac_b);
    assertTrue(zbddNodeInfo.isDeadNode());
    zbdd.gc();

    assertThrowsExactly(ZbddException.class, zbddNodeInfo::getZbdd);
    assertThrowsExactly(ZbddException.class, zbddNodeInfo::getVar);
    assertThrowsExactly(ZbddException.class, zbddNodeInfo::getP0);
    assertThrowsExactly(ZbddException.class, zbddNodeInfo::getP1);
    assertThrowsExactly(ZbddException.class, zbddNodeInfo::getLiteral);
    assertThrowsExactly(ZbddException.class, zbddNodeInfo::getReferenceCount);
    assertThrowsExactly(ZbddException.class, zbddNodeInfo::isNewNode);
    assertThrowsExactly(ZbddException.class, zbddNodeInfo::isDeadNode);
    assertThrowsExactly(ZbddException.class, zbddNodeInfo::toString);
  }


  @Test
  @DisplayName("Operation 'atomize'")
  void atomize()
  {
    final var zbdd = ZbddFactory.create();
    final var variableToLiteralMap = new TreeMap<Integer,String>();
    final var variables = new int[16];

    for(int n = 0; n < 16; n++)
      variableToLiteralMap.put(variables[n] = zbdd.createVar(), Character.toString((char)('a' + 15 - n)));

    zbdd.setLiteralResolver(new ZbddLiteralResolver() {
      @Override
      public @NotNull String getLiteralName(int var) {
        return variableToLiteralMap.get(var);
      }

      @Override
      public @NotNull String getCubeName(int @NotNull [] cubeVars) {
        return cubeVars.length == 0 ? "*" : stream(cubeVars).boxed().sorted((a,b) -> b - a).map(this::getLiteralName).collect(joining());
      }
    });

    final var random = new Random();

    for(int cycle = 1; cycle <= 500; cycle++)
    {
      var mask = 0;
      var set = random.nextBoolean() ? Zbdd.base() : Zbdd.empty();

      for(int elements = random.nextInt(7) + (random.nextBoolean() ? 1 : 0), e = 0; e < elements; e++)
      {
        int elementMask, bits;
        do { bits = bitCount(elementMask = random.nextInt() & 0xffff); } while(bits > 6);

        mask |= elementMask;

        final var set0 = zbdd.incRef(set);  // lock set
        set = zbdd.union(set, zbddFromMask(zbdd, variables, elementMask));
        zbdd.decRef(set0);  // release previous set
      }

      final var atomizedSet = zbdd.incRef(zbdd.atomize(zbdd.incRef(set)));  // lock set, atomizedSet

      var expectedSet = Zbdd.empty();
      for(int b = 0; b < 16; b++)
        if ((mask & (1 << b)) != 0)
        {
          final var expectedSet0 = zbdd.incRef(expectedSet);  // lock expectedSet
          expectedSet = zbdd.union(expectedSet, zbdd.cube(variables[b]));
          zbdd.decRef(expectedSet0);  // release previous expectedSet
        }

      zbdd.decRef(set);  // release set
      zbdd.decRef(atomizedSet);  // release atomizedSet

      System.out.println("atomize " + zbdd.toString(set) + " -> " + zbdd.toString(atomizedSet));

      assertEquals(bitCount(mask), zbdd.count(atomizedSet));

      final var _expectedSet = expectedSet;
      assertEquals(expectedSet, atomizedSet, () -> "expected result = " + zbdd.toString(_expectedSet));
    }

    System.out.println(zbdd.getStatistics());
  }


  private int zbddFromMask(@NotNull Zbdd zbdd, int[] variables, int mask)
  {
    var bits = bitCount(mask);
    var cubeVars = new int[bits];

    for(int b = 0, i = 0; b < 16 && i < bits; b++)
      if ((mask & (1 << b)) != 0)
        cubeVars[i++] = variables[b];

    return zbdd.cube(cubeVars);
  }


  @Test
  @DisplayName("Zbdd callback")
  @SuppressWarnings({"ConstantValue", "DataFlowIssue"})
  void zbddCallback()
  {
    Zbdd zbdd = ZbddFactory.create();

    final int a = zbdd.createVar();
    final int b = zbdd.createVar();
    zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    final int _a = zbdd.incRef(zbdd.cube(a));
    final int _b = zbdd.incRef(zbdd.cube(b));
    zbdd.union(_a, _b);

    final boolean[] callbackResult = new boolean[4];
    final var zbddNodeInfo = zbdd.getZbddNodeInfo(_a);

    zbdd.registerCallback(new Zbdd.ZbddCallback() {
      @Override public void beforeClear() { callbackResult[0] = true; }
      @Override public void afterClear() { callbackResult[1] = true; }
      @Override public void beforeGc() {
        callbackResult[2] = true;
        assertTrue(zbddNodeInfo.isDeadNode());
      }
      @Override public void afterGc() {
        callbackResult[3] = true;
        assertThrowsExactly(ZbddException.class, zbddNodeInfo::isDeadNode);
      }
    });

    zbdd.decRef(_a);
    zbdd.gc();

    assertFalse(callbackResult[0]);
    assertFalse(callbackResult[1]);
    assertTrue(callbackResult[2]);
    assertTrue(callbackResult[3]);

    zbdd.clear();

    assertTrue(callbackResult[0]);
    assertTrue(callbackResult[1]);
  }
}
