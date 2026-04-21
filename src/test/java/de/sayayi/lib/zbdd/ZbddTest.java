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

import de.sayayi.lib.zbdd.exception.InvalidVarException;
import de.sayayi.lib.zbdd.exception.InvalidZbddException;
import de.sayayi.lib.zbdd.internal.DefaultCapacityAdvisor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

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
@TestMethodOrder(MethodOrderer.DisplayName.class)
class ZbddTest
{
  @Test
  @DisplayName("Create variable")
  @SuppressWarnings("ConstantConditions")
  void createVar()
  {
    final var zbdd = ZbddFactory.create();
    final var var = zbdd.createVar();
    final var r = zbdd.cube(var);

    assertTrue(var > 0);
    assertTrue(r >= 2);
    assertEquals(var, zbdd.getVar(r));
    assertEquals(Zbdd.empty(), zbdd.getP0(r));
    assertEquals(Zbdd.base(), zbdd.getP1(r));

    assertThrowsExactly(InvalidVarException.class, () -> zbdd.cube(var + 1));
    assertThrowsExactly(InvalidVarException.class, () -> zbdd.cube(0));
  }


  @Test
  @DisplayName("Operation 'change'")
  void change()
  {
    final var zbdd = ZbddFactory.create();
    final var var = zbdd.createVar();
    final var r = zbdd.cube(var);

    assertEquals(Zbdd.empty(), zbdd.change(Zbdd.empty(), var));
    assertEquals(r, zbdd.change(Zbdd.base(), var));
  }


  @Test
  @DisplayName("Operation 'hasCubeWithVar'")
  void hasCubeWithVar()
  {
    final var zbdd = ZbddFactory.create();
    final var v1 = zbdd.createVar();
    final var v2 = zbdd.createVar();
    final var v3 = zbdd.createVar();
    final var v4 = zbdd.createVar();
    final var v5 = zbdd.createVar();
    final var v6 = zbdd.createVar();
    final var v7 = zbdd.createVar();
    final var v8 = zbdd.createVar();

    // empty and base have no variables
    assertFalse(zbdd.hasCubeWithVar(Zbdd.empty(), v1));
    assertFalse(zbdd.hasCubeWithVar(Zbdd.base(), v1));

    // build a zbdd with 6 cubes: { v1.v3.v5, v2.v4, v3.v6.v7, v1.v8, v4.v5.v6, v2.v3 }
    var r = zbdd.incRef(zbdd.cube(v1, v3, v5));
    r = zbdd.incRef(zbdd.union(r, zbdd.cube(v2, v4)));
    r = zbdd.incRef(zbdd.union(r, zbdd.cube(v3, v6, v7)));
    r = zbdd.incRef(zbdd.union(r, zbdd.cube(v1, v8)));
    r = zbdd.incRef(zbdd.union(r, zbdd.cube(v4, v5, v6)));
    r = zbdd.union(r, zbdd.cube(v2, v3));

    assertEquals(6, zbdd.count(r));

    // v1 appears in cubes: v1.v3.v5, v1.v8
    assertTrue(zbdd.hasCubeWithVar(r, v1));
    // v2 appears in cubes: v2.v4, v2.v3
    assertTrue(zbdd.hasCubeWithVar(r, v2));
    // v3 appears in cubes: v1.v3.v5, v3.v6.v7, v2.v3
    assertTrue(zbdd.hasCubeWithVar(r, v3));
    // v4 appears in cubes: v2.v4, v4.v5.v6
    assertTrue(zbdd.hasCubeWithVar(r, v4));
    // v5 appears in cubes: v1.v3.v5, v4.v5.v6
    assertTrue(zbdd.hasCubeWithVar(r, v5));
    // v6 appears in cubes: v3.v6.v7, v4.v5.v6
    assertTrue(zbdd.hasCubeWithVar(r, v6));
    // v7 appears only in cube: v3.v6.v7
    assertTrue(zbdd.hasCubeWithVar(r, v7));
    // v8 appears only in cube: v1.v8
    assertTrue(zbdd.hasCubeWithVar(r, v8));

    // remove variable v7 from the zbdd
    final var withoutV7 = zbdd.subset0(r, v7);  // removes cubes with v7
    assertFalse(zbdd.hasCubeWithVar(withoutV7, v7));
    assertTrue(zbdd.hasCubeWithVar(withoutV7, v6));  // v6 still in v4.v5.v6

    // remove variable v8 from the zbdd
    final var withoutV8 = zbdd.subset0(r, v8);  // removes cubes with v8
    assertFalse(zbdd.hasCubeWithVar(withoutV8, v8));
    assertTrue(zbdd.hasCubeWithVar(withoutV8, v1));  // v1 still in v1.v3.v5
  }


  @Test
  @DisplayName("Operation 'count'")
  void count()
  {
    final var zbdd = ZbddFactory.create();
    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    final var ab = zbdd.cube(a, b);
    final var ac = zbdd.cube(a, c);
    final var r = zbdd.union(ab, zbdd.cube(b), zbdd.cube(c), ac, Zbdd.base());

    assertEquals(5, zbdd.count(r));
  }


  @Test
  @DisplayName("Operation 'subset0'")
  void subset0()
  {
    final var zbdd = ZbddFactory.create();
    final var v1 = zbdd.createVar();
    final var v2 = zbdd.createVar();
    final var v3 = zbdd.createVar();
    final var v4 = zbdd.createVar();
    final var v5 = zbdd.createVar();
    final var v6 = zbdd.createVar();
    final var v7 = zbdd.createVar();
    final var v8 = zbdd.createVar();

    // trivial cases
    assertEquals(Zbdd.empty(), zbdd.subset0(Zbdd.empty(), v1));
    assertEquals(Zbdd.base(), zbdd.subset0(Zbdd.base(), v1));

    // single cube without the variable returns itself
    final var c_v2v3 = zbdd.cube(v2, v3);
    assertEquals(c_v2v3, zbdd.subset0(c_v2v3, v1));

    // single cube with the variable returns empty
    assertEquals(Zbdd.empty(), zbdd.subset0(c_v2v3, v2));

    // build zbdd r = { v1.v2.v5, v3.v4.v8, v2.v6, v1.v7.v8, v5.v6.v7, v3.v8 }
    final var c1 = zbdd.incRef(zbdd.cube(v1, v2, v5));   // v1.v2.v5
    final var c2 = zbdd.incRef(zbdd.cube(v3, v4, v8));   // v3.v4.v8
    final var c3 = zbdd.incRef(zbdd.cube(v2, v6));        // v2.v6
    final var c4 = zbdd.incRef(zbdd.cube(v1, v7, v8));   // v1.v7.v8
    final var c5 = zbdd.incRef(zbdd.cube(v5, v6, v7));   // v5.v6.v7
    final var c6 = zbdd.incRef(zbdd.cube(v3, v8));        // v3.v8
    final var r = zbdd.incRef(zbdd.union(c1, c2, c3, c4, c5, c6));

    assertEquals(6, zbdd.count(r));

    // subset0(r, v1) -> cubes without v1: { v3.v4.v8, v2.v6, v5.v6.v7, v3.v8 }
    final var r0_v1 = zbdd.incRef(zbdd.subset0(r, v1));
    assertEquals(4, zbdd.count(r0_v1));
    assertTrue(zbdd.contains(r0_v1, c2));
    assertTrue(zbdd.contains(r0_v1, c3));
    assertTrue(zbdd.contains(r0_v1, c5));
    assertTrue(zbdd.contains(r0_v1, c6));
    assertFalse(zbdd.contains(r0_v1, c1));
    assertFalse(zbdd.contains(r0_v1, c4));

    // subset0(r, v8) -> cubes without v8: { v1.v2.v5, v2.v6, v5.v6.v7 }
    final var r0_v8 = zbdd.incRef(zbdd.subset0(r, v8));
    assertEquals(3, zbdd.count(r0_v8));
    assertTrue(zbdd.contains(r0_v8, c1));
    assertTrue(zbdd.contains(r0_v8, c3));
    assertTrue(zbdd.contains(r0_v8, c5));

    // subset0(r, v4) -> removes only v3.v4.v8, leaving 5 cubes
    final var r0_v4 = zbdd.incRef(zbdd.subset0(r, v4));
    assertEquals(5, zbdd.count(r0_v4));
    assertFalse(zbdd.contains(r0_v4, c2));

    // subset0 with a variable not in any cube returns the original set
    assertEquals(r, zbdd.subset0(r, zbdd.createVar()));
  }


  @Test
  @DisplayName("Operation 'subset1'")
  void subset1()
  {
    final var zbdd = ZbddFactory.create();
    final var v1 = zbdd.createVar();
    final var v2 = zbdd.createVar();
    final var v3 = zbdd.createVar();
    final var v4 = zbdd.createVar();
    final var v5 = zbdd.createVar();
    final var v6 = zbdd.createVar();
    final var v7 = zbdd.createVar();
    final var v8 = zbdd.createVar();

    // trivial cases
    assertEquals(Zbdd.empty(), zbdd.subset1(Zbdd.empty(), v1));
    assertEquals(Zbdd.empty(), zbdd.subset1(Zbdd.base(), v1));

    // single cube with the variable returns the cube minus that variable
    final var c_v2v3 = zbdd.cube(v2, v3);
    assertEquals(zbdd.incRef(zbdd.cube(v3)), zbdd.subset1(c_v2v3, v2));

    // single cube without the variable returns empty
    assertEquals(Zbdd.empty(), zbdd.subset1(c_v2v3, v1));

    // build zbdd r = { v1.v2.v5, v3.v4.v8, v2.v6, v1.v7.v8, v5.v6.v7, v3.v8 }
    final var c1 = zbdd.incRef(zbdd.cube(v1, v2, v5));   // v1.v2.v5
    final var c2 = zbdd.incRef(zbdd.cube(v3, v4, v8));   // v3.v4.v8
    final var c3 = zbdd.incRef(zbdd.cube(v2, v6));        // v2.v6
    final var c4 = zbdd.incRef(zbdd.cube(v1, v7, v8));   // v1.v7.v8
    final var c5 = zbdd.incRef(zbdd.cube(v5, v6, v7));   // v5.v6.v7
    final var c6 = zbdd.incRef(zbdd.cube(v3, v8));        // v3.v8
    final var r = zbdd.incRef(zbdd.union(c1, c2, c3, c4, c5, c6));

    assertEquals(6, zbdd.count(r));

    // subset1(r, v1) -> cubes with v1, with v1 removed: { v2.v5, v7.v8 }
    final var r1_v1 = zbdd.incRef(zbdd.subset1(r, v1));
    assertEquals(2, zbdd.count(r1_v1));
    assertTrue(zbdd.contains(r1_v1, zbdd.cube(v2, v5)));
    assertTrue(zbdd.contains(r1_v1, zbdd.cube(v7, v8)));

    // subset1(r, v8) -> cubes with v8, with v8 removed: { v3.v4, v1.v7, v3 }
    final var r1_v8 = zbdd.incRef(zbdd.subset1(r, v8));
    assertEquals(3, zbdd.count(r1_v8));
    assertTrue(zbdd.contains(r1_v8, zbdd.cube(v3, v4)));
    assertTrue(zbdd.contains(r1_v8, zbdd.cube(v1, v7)));
    assertTrue(zbdd.contains(r1_v8, zbdd.cube(v3)));

    // subset1(r, v6) -> cubes with v6, with v6 removed: { v2, v5.v7 }
    final var r1_v6 = zbdd.incRef(zbdd.subset1(r, v6));
    assertEquals(2, zbdd.count(r1_v6));
    assertTrue(zbdd.contains(r1_v6, zbdd.cube(v2)));
    assertTrue(zbdd.contains(r1_v6, zbdd.cube(v5, v7)));

    // subset1(r, v4) -> only v3.v4.v8 contains v4, result: { v3.v8 }
    final var r1_v4 = zbdd.incRef(zbdd.subset1(r, v4));
    assertEquals(1, zbdd.count(r1_v4));
    assertEquals(zbdd.cube(v3, v8), r1_v4);

    // subset1 with a variable not in any cube returns empty
    assertEquals(Zbdd.empty(), zbdd.subset1(r, zbdd.createVar()));
  }


  @Test
  @DisplayName("Operation 'multiply'")
  void multiply()
  {
    final var zbdd = ZbddFactory.create();

    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    final var ab = zbdd.cube(a, b);
    final var p = zbdd.union(ab, zbdd.cube(b), zbdd.cube(c));
    final var q = zbdd.union(ab, Zbdd.base());
    final var r = zbdd.multiply(p, q);

    assertEquals(3, zbdd.count(p));
    assertEquals(2, zbdd.count(q));
    assertEquals(4, zbdd.count(r));
    assertEquals(zbdd.union(ab, zbdd.cube(a, b, c), zbdd.cube(b), zbdd.cube(c)), r);
  }


  @Test
  @DisplayName("Operation 'difference'")
  void difference()
  {
    final var zbdd = ZbddFactory.create();

    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();
    final var d = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : var == c ? "c" : "d");

    // { d, bc, ac, b, a } - { bc, ab, a, ø } = { d, ac, b }
    final var _ac = zbdd.incRef(zbdd.cube(a, c));
    final var _ab = zbdd.incRef(zbdd.cube(a, b));
    final var _bc = zbdd.incRef(zbdd.cube(b, c));
    final var _a = zbdd.incRef(zbdd.cube(a));
    final var _b = zbdd.incRef(zbdd.cube(b));
    final var _d = zbdd.incRef(zbdd.cube(d));

    final var p = zbdd.incRef(zbdd.union(_ac, _bc, _a, _b, _d));
    final var q = zbdd.incRef(zbdd.union(_bc, _ab, _a, Zbdd.base()));
    final var r = zbdd.difference(p, q);

    assertEquals(3, zbdd.count(r));
    assertTrue(zbdd.contains(r, _ac));
    assertTrue(zbdd.contains(r, _b));
    assertTrue(zbdd.contains(r, _d));
  }


  @Test
  @DisplayName("Operation 'removeBase'")
  void removeBase()
  {
    final var zbdd = ZbddFactory.create();
    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    final var ab = zbdd.cube(a, b);
    final var ac = zbdd.cube(a, c);
    final var ab_ac_b_c = zbdd.union(ab, zbdd.cube(b), zbdd.cube(c), ac);
    final var r = zbdd.union(ab_ac_b_c, Zbdd.base());

    assertEquals(ab_ac_b_c, zbdd.removeBase(r));
    assertEquals(zbdd.cube(a), zbdd.removeBase(zbdd.subset1(ab_ac_b_c, c)));
    assertTrue(Zbdd.isEmpty(zbdd.removeBase(Zbdd.base())));
  }


  @Test
  @DisplayName("Operation 'contains'")
  void contains()
  {
    final var zbdd = ZbddFactory.create();
    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    final var ab = zbdd.cube(a, b);
    final var ac = zbdd.cube(a, c);
    final var ab_ac_b_c = zbdd.union(ab, zbdd.cube(b), zbdd.cube(c), ac);
    final var r = zbdd.union(ab_ac_b_c, Zbdd.base());

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
    final var zbdd = ZbddFactory.create(DefaultCapacityAdvisor.INSTANCE);

    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();
    final var d = zbdd.createVar();
    final var e = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : var == c ? "c" : var == d ? "d" : "e");

    var r = zbdd.getNode(a, Zbdd.base(), Zbdd.base());
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
    final var advisor = DefaultCapacityAdvisor.INSTANCE;
    final var zbdd = ZbddFactory.create(advisor);

    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();

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
    final var zbdd = ZbddFactory.create();

    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();

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
    final var zbdd = ZbddFactory.create();

    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();

    zbdd.setLiteralResolver(var -> var == a ? "a" : var == b ? "b" : "c");

    final var _a = zbdd.incRef(zbdd.cube(a));
    final var _ac = zbdd.incRef(zbdd.cube(a, c));
    final var _b = zbdd.incRef(zbdd.cube(b));
    final var _ac_b = zbdd.union(_ac, _b);

    final var zbddNodeInfo = zbdd.getZbddNodeInfo(_ac_b);

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

    assertThrowsExactly(InvalidZbddException.class, zbddNodeInfo::getZbdd);
    assertThrowsExactly(InvalidZbddException.class, zbddNodeInfo::getVar);
    assertThrowsExactly(InvalidZbddException.class, zbddNodeInfo::getP0);
    assertThrowsExactly(InvalidZbddException.class, zbddNodeInfo::getP1);
    assertThrowsExactly(InvalidZbddException.class, zbddNodeInfo::getLiteral);
    assertThrowsExactly(InvalidZbddException.class, zbddNodeInfo::getReferenceCount);
    assertThrowsExactly(InvalidZbddException.class, zbddNodeInfo::isNewNode);
    assertThrowsExactly(InvalidZbddException.class, zbddNodeInfo::isDeadNode);
    assertThrowsExactly(InvalidZbddException.class, zbddNodeInfo::toString);
  }


  @Test
  @DisplayName("Operation 'atomize'")
  void atomize()
  {
    final var zbdd = ZbddFactory.create();
    final var variableToLiteralMap = new TreeMap<Integer,String>();
    final var variables = new int[16];

    for(var n = 0; n < 16; n++)
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

    for(var cycle = 1; cycle <= 500; cycle++)
    {
      var mask = 0;
      var set = random.nextBoolean() ? Zbdd.base() : Zbdd.empty();

      for(int elements = random.nextInt(7) + (random.nextBoolean() ? 1 : 0), e = 0; e < elements; e++)
      {
        int elementMask;
        do {
          elementMask = random.nextInt() & 0xffff;
        } while(bitCount(elementMask) > 6);

        mask |= elementMask;

        final var set0 = zbdd.incRef(set);  // lock set
        set = zbdd.union(set, zbddFromMask(zbdd, variables, elementMask));
        zbdd.decRef(set0);  // release previous set
      }

      final var atomizedSet = zbdd.incRef(zbdd.atomize(zbdd.incRef(set)));  // lock set, atomizedSet

      var expectedSet = Zbdd.empty();
      for(var b = 0; b < 16; b++)
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


  @Test
  @DisplayName("Operation 'asSingleCubeZbdds'")
  void asSingleCubeZbdds()
  {
    final var zbdd = ZbddFactory.create();
    final var variableToLiteralMap = new TreeMap<Integer,String>();
    final var variables = new int[16];

    for(var n = 0; n < 16; n++)
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

    for(var cycle = 1; cycle <= 500; cycle++)
    {
      var set = random.nextBoolean() ? Zbdd.base() : Zbdd.empty();

      for(int elements = random.nextInt(7) + (random.nextBoolean() ? 1 : 0), e = 0; e < elements; e++)
      {
        int elementMask;
        do {
          elementMask = random.nextInt() & 0xffff;
        } while(bitCount(elementMask) > 8);

        final var set0 = zbdd.incRef(set);  // lock set
        set = zbdd.union(set, zbddFromMask(zbdd, variables, elementMask));
        zbdd.decRef(set0);  // release previous set
      }

      final var elementZbdds = zbdd.asSingleCubeZbdds(set);
      assertEquals(zbdd.count(set), elementZbdds.length);

      for(var elementZbdd: zbdd.incRef(elementZbdds))
      {
        assertEquals(1, zbdd.count(elementZbdd));
        set = zbdd.difference(set, elementZbdd);
      }

      assertTrue(Zbdd.isEmpty(set));

      zbdd.decRef(elementZbdds);
    }

    System.out.println(zbdd.getStatistics());
  }


  private int zbddFromMask(@NotNull Zbdd zbdd, int[] variables, int mask)
  {
    final var bits = bitCount(mask);
    final var cubeVars = new int[bits];

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
    final var zbdd = ZbddFactory.create();

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
        assertThrowsExactly(InvalidZbddException.class, zbddNodeInfo::isDeadNode);
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
