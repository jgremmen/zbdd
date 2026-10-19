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
package de.sayayi.lib.zbdd;

import de.sayayi.lib.zbdd.cache.ZbddFastCache;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static de.sayayi.lib.zbdd.Zbdd.BASE;
import static de.sayayi.lib.zbdd.Zbdd.EMPTY;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * Tests for the zbdd operations {@code divide} and {@code modulo}, as defined by Minato:
 * <ul>
 *   <li>
 *     {@code P / Q} is the largest family {@code R} such that {@code R * Q} is contained in {@code P} and every
 *     combination of {@code R} is disjoint with every combination of {@code Q}
 *   </li>
 *   <li>
 *     {@code P % Q = P - Q * (P / Q)}
 *   </li>
 * </ul>
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Operations 'divide' and 'modulo'")
class ZbddDivideTest
{
  @ParameterizedTest(name = "cached = {0}")
  @DisplayName("Terminal and trivial cases")
  @ValueSource(booleans = { false, true })
  void trivialCases(boolean cached)
  {
    final var zbdd = create(cached);
    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var p = zbdd.incRef(zbdd.union(zbdd.cube(a, b), zbdd.cube(b)));  // { a.b, b }
    final var cubeA = zbdd.incRef(zbdd.cube(a));

    // {} / Q = {}
    assertEquals(EMPTY, zbdd.divide(EMPTY, BASE));
    assertEquals(EMPTY, zbdd.divide(EMPTY, cubeA));
    assertEquals(EMPTY, zbdd.modulo(EMPTY, cubeA));

    // P / { {} } = P
    assertEquals(BASE, zbdd.divide(BASE, BASE));
    assertEquals(p, zbdd.divide(p, BASE));
    assertEquals(EMPTY, zbdd.modulo(BASE, BASE));
    assertEquals(EMPTY, zbdd.modulo(p, BASE));

    // P / P = { {} }
    assertEquals(BASE, zbdd.divide(p, p));
    assertEquals(EMPTY, zbdd.modulo(p, p));

    // { {} } / Q = {} for Q != { {} }
    assertEquals(EMPTY, zbdd.divide(BASE, cubeA));
    assertEquals(BASE, zbdd.modulo(BASE, cubeA));
  }


  @ParameterizedTest(name = "cached = {0}")
  @DisplayName("Dividend with base as 1-cofactor")
  @ValueSource(booleans = { false, true })
  void baseCofactor(boolean cached)
  {
    final var zbdd = create(cached);
    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var p = zbdd.incRef(zbdd.union(zbdd.cube(a), zbdd.cube(b)));  // { a, b }
    final var cubeA = zbdd.incRef(zbdd.cube(a));

    assertEquals(Set.of(Set.of()), toModel(zbdd, zbdd.divide(p, cubeA)));
    assertEquals(Set.of(Set.of(b)), toModel(zbdd, zbdd.modulo(p, cubeA)));
  }


  @ParameterizedTest(name = "cached = {0}")
  @DisplayName("Textbook example")
  @ValueSource(booleans = { false, true })
  void textbookExample(boolean cached)
  {
    final var zbdd = create(cached);
    final var a = zbdd.createVar();
    final var b = zbdd.createVar();
    final var c = zbdd.createVar();
    final var d = zbdd.createVar();
    final var e = zbdd.createVar();

    // P = { a.b.c, a.b.d, a.c.d, e }, Q = { b, c }
    final var p = fromModel(zbdd, Set.of(Set.of(a, b, c), Set.of(a, b, d), Set.of(a, c, d), Set.of(e)));
    final var q = fromModel(zbdd, Set.of(Set.of(b), Set.of(c)));

    // P / Q = { a.d }, P % Q = { a.b.c, e }
    assertEquals(Set.of(Set.of(a, d)), toModel(zbdd, zbdd.divide(p, q)));
    assertEquals(Set.of(Set.of(a, b, c), Set.of(e)), toModel(zbdd, zbdd.modulo(p, q)));
  }


  @ParameterizedTest(name = "cached = {0}")
  @DisplayName("Random families compared with set model")
  @ValueSource(booleans = { false, true })
  void randomFamilies(boolean cached)
  {
    final var zbdd = create(cached);
    final var vars = new int[6];

    for(int i = 0; i < vars.length; i++)
      vars[i] = zbdd.createVar();

    final var random = new Random(4711);

    for(int n = 0; n < 500; n++)
    {
      final var pModel = randomFamily(random, vars, 1 + random.nextInt(12));
      final var qModel = randomFamily(random, vars, 1 + random.nextInt(3));
      final var p = fromModel(zbdd, pModel);
      final var q = fromModel(zbdd, qModel);
      final var expectedQuotient = divide(pModel, qModel);

      assertEquals(expectedQuotient, toModel(zbdd, zbdd.divide(p, q)), () -> pModel + " / " + qModel);
      assertEquals(modulo(pModel, qModel, expectedQuotient), toModel(zbdd, zbdd.modulo(p, q)),
          () -> pModel + " % " + qModel);

      zbdd.decRef(p);
      zbdd.decRef(q);
    }
  }


  private static @NotNull Zbdd create(boolean cached) {
    return cached ? ZbddFactory.createCached(new ZbddFastCache()) : ZbddFactory.create();
  }


  private static @NotNull Set<Set<Integer>> randomFamily(@NotNull Random random, int @NotNull [] vars, int size)
  {
    final var family = new HashSet<Set<Integer>>();

    for(int n = 0; n < size; n++)
    {
      final var combination = new TreeSet<Integer>();

      for(var var: vars)
        if (random.nextInt(3) == 0)
          combination.add(var);

      family.add(combination);
    }

    return family;
  }


  /**
   * Weak division by definition: the combinations {@code r}, disjoint with every {@code q} in {@code Q}, for which
   * {@code r + q} is contained in {@code P} for every {@code q} in {@code Q}.
   */
  private static @NotNull Set<Set<Integer>> divide(@NotNull Set<Set<Integer>> p, @NotNull Set<Set<Integer>> q)
  {
    final var anyQ = q.iterator().next();
    final var quotient = new HashSet<Set<Integer>>();

    candidates:
    for(var pCombination: p)
    {
      if (!pCombination.containsAll(anyQ))
        continue;

      final var r = new TreeSet<>(pCombination);
      r.removeAll(anyQ);

      for(var qCombination: q)
      {
        final var union = new TreeSet<>(r);
        union.addAll(qCombination);

        if (union.size() != r.size() + qCombination.size() || !p.contains(union))
          continue candidates;
      }

      quotient.add(r);
    }

    return quotient;
  }


  private static @NotNull Set<Set<Integer>> modulo(@NotNull Set<Set<Integer>> p, @NotNull Set<Set<Integer>> q,
                                                   @NotNull Set<Set<Integer>> quotient)
  {
    final var remainder = new HashSet<>(p);

    for(var qCombination: q)
      for(var rCombination: quotient)
      {
        final var product = new TreeSet<>(qCombination);
        product.addAll(rCombination);
        remainder.remove(product);
      }

    return remainder;
  }


  private static int fromModel(@NotNull Zbdd zbdd, @NotNull Set<Set<Integer>> family)
  {
    var r = EMPTY;

    for(var combination: family)
    {
      final var cube = zbdd.cube(combination.stream().mapToInt(Integer::intValue).toArray());
      final var union = zbdd.incRef(zbdd.union(r, cube));

      zbdd.decRef(r);
      r = union;
    }

    return r;
  }


  private static @NotNull Set<Set<Integer>> toModel(@NotNull Zbdd zbdd, int family)
  {
    final var model = new HashSet<Set<Integer>>();

    zbdd.visitCubes(family, vars -> model.add(stream(vars).boxed().collect(toSet())));

    return model;
  }
}
