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
import de.sayayi.lib.zbdd.internal.PresetCapacityAdvisor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static de.sayayi.lib.zbdd.Zbdd.EMPTY;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;


/**
 * Tests the zbdd operations against a set model on random families. Each result must denote the expected family and, as
 * zbdd nodes are canonical, must be the very node built from the expected family.
 * <p>
 * The test runs with each predefined capacity advisor and with the {@code GC_ALWAYS} advisor, which performs a garbage
 * collection whenever a new node is required and the node table is full. With the latter, collections happen in the
 * middle of operations, invalidating the operation cache of a cached instance.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Operations compared with a set model")
class ZbddOperationModelTest
{
  private static final int ITERATIONS = 5000;

  private static final ZbddCapacityAdvisor GC_ALWAYS = new GcAlwaysAdvisor();


  @ParameterizedTest(name = "variant = {0}, advisor = {1}")
  @DisplayName("Random families")
  @MethodSource("variantsAndAdvisors")
  void randomFamilies(String variant, ZbddCapacityAdvisor advisor)
  {
    final var zbdd = create(variant, advisor);
    final var vars = new int[9];

    for(var i = 0; i < vars.length; i++)
      vars[i] = zbdd.createVar();

    final var random = new Random(31337);

    for(var n = 0; n < ITERATIONS; n++)
    {
      final var pModel = randomFamily(random, vars);
      final var qModel = randomFamily(random, vars);
      final var rModel = randomFamily(random, vars);
      final var p = fromModel(zbdd, pModel);
      final var q = fromModel(zbdd, qModel);
      final var r = fromModel(zbdd, rModel);

      final var var = vars[random.nextInt(vars.length)];
      final var subsetVars = randomVars(random, vars);
      final Supplier<String> operands = () -> "P = " + pModel + ", Q = " + qModel + ", R = " + rModel +
          ", var = " + var + ", vars = " + stream(subsetVars).boxed().toList();

      assertResult(zbdd, union(pModel, qModel), zbdd.union(p, q), operands);
      assertResult(zbdd, union(union(pModel, qModel), rModel), zbdd.union(p, q, r), operands);
      assertResult(zbdd, intersect(pModel, qModel), zbdd.intersect(p, q), operands);
      assertResult(zbdd, difference(pModel, qModel), zbdd.difference(p, q), operands);
      assertResult(zbdd, multiply(pModel, qModel), zbdd.multiply(p, q), operands);
      assertResult(zbdd, subset0(pModel, Set.of(var)), zbdd.subset0(p, var), operands);
      assertResult(zbdd, subset0(pModel, stream(subsetVars).boxed().collect(toSet())), zbdd.subset0(p, subsetVars),
          operands);
      assertResult(zbdd, subset1(pModel, var), zbdd.subset1(p, var), operands);
      assertResult(zbdd, change(pModel, var), zbdd.change(p, var), operands);
      assertResult(zbdd, atomize(pModel), zbdd.atomize(p), operands);
      assertResult(zbdd, difference(pModel, Set.of(Set.of())), zbdd.removeBase(p), operands);

      assertEquals(pModel
              .stream()
              .anyMatch(combination -> combination.contains(var)), zbdd.hasCubeWithVar(p, var), operands);
      assertEquals(pModel.size(), zbdd.count(p), operands);
      assertEquals(BigInteger.valueOf(pModel.size()), zbdd.countExact(p), operands);

      zbdd.decRef(p);
      zbdd.decRef(q);
      zbdd.decRef(r);
    }

    if (advisor == GC_ALWAYS)
    {
      assertTrue(
          zbdd.getStatistics().getGCCount() > ITERATIONS,
          "garbage collections expected during operations");
    }
  }


  /**
   * Asserts that {@code result} denotes the {@code expected} family and is the node built from it.
   */
  private static void assertResult(@NotNull Zbdd zbdd, @NotNull Set<Set<Integer>> expected, int result,
                                   @NotNull Supplier<String> operands)
  {
    zbdd.incRef(result);

    assertEquals(expected, toModel(zbdd, result), operands);

    final var expectedZbdd = fromModel(zbdd, expected);
    assertEquals(expectedZbdd, result, () -> "non-canonical result for " + operands.get());

    zbdd.decRef(expectedZbdd);
    zbdd.decRef(result);
  }


  /**
   * Returns all combinations of the instance variants with the predefined capacity advisors and {@link #GC_ALWAYS}.
   */
  private static @NotNull Stream<Arguments> variantsAndAdvisors()
  {
    return Stream.of("plain", "cached").flatMap(variant ->
        Stream.concat(stream(PresetCapacityAdvisor.values()), Stream.of(GC_ALWAYS))
            .map(advisor -> arguments(variant, advisor)));
  }


  private static @NotNull Zbdd create(@NotNull String variant, @NotNull ZbddCapacityAdvisor advisor)
  {
    return variant.equals("cached")
        ? ZbddFactory.createCached(advisor, new ZbddFastCache())
        : ZbddFactory.create(advisor);
  }


  private static @NotNull Set<Set<Integer>> randomFamily(@NotNull Random random, int @NotNull [] vars)
  {
    final var family = new HashSet<Set<Integer>>();

    for(int n = random.nextInt(10); n-- > 0;)
    {
      final var combination = new TreeSet<Integer>();

      for(var var: vars)
        if (random.nextInt(3) == 0)
          combination.add(var);

      family.add(combination);
    }

    return family;
  }


  private static int @NotNull [] randomVars(@NotNull Random random, int @NotNull [] vars) {
    return stream(vars).filter(var -> random.nextInt(3) == 0).toArray();
  }


  private static @NotNull Set<Set<Integer>> union(@NotNull Set<Set<Integer>> p, @NotNull Set<Set<Integer>> q)
  {
    final var union = new HashSet<>(p);
    union.addAll(q);

    return union;
  }


  private static @NotNull Set<Set<Integer>> intersect(@NotNull Set<Set<Integer>> p, @NotNull Set<Set<Integer>> q)
  {
    final var intersection = new HashSet<>(p);
    intersection.retainAll(q);

    return intersection;
  }


  private static @NotNull Set<Set<Integer>> difference(@NotNull Set<Set<Integer>> p, @NotNull Set<Set<Integer>> q)
  {
    final var difference = new HashSet<>(p);
    difference.removeAll(q);

    return difference;
  }


  private static @NotNull Set<Set<Integer>> multiply(@NotNull Set<Set<Integer>> p, @NotNull Set<Set<Integer>> q)
  {
    final var product = new HashSet<Set<Integer>>();

    for(var pCombination: p)
      for(var qCombination: q)
      {
        final var combination = new TreeSet<>(pCombination);
        combination.addAll(qCombination);
        product.add(combination);
      }

    return product;
  }


  private static @NotNull Set<Set<Integer>> subset0(@NotNull Set<Set<Integer>> p, @NotNull Set<Integer> vars)
  {
    final var subset = new HashSet<Set<Integer>>();

    for(var combination: p)
      if (combination.stream().noneMatch(vars::contains))
        subset.add(combination);

    return subset;
  }


  private static @NotNull Set<Set<Integer>> subset1(@NotNull Set<Set<Integer>> p, int var)
  {
    final var subset = new HashSet<Set<Integer>>();

    for(var combination: p)
      if (combination.contains(var))
      {
        final var withoutVar = new TreeSet<>(combination);
        withoutVar.remove(var);
        subset.add(withoutVar);
      }

    return subset;
  }


  private static @NotNull Set<Set<Integer>> change(@NotNull Set<Set<Integer>> p, int var)
  {
    final var changed = new HashSet<Set<Integer>>();

    for(var combination: p)
    {
      final var toggled = new TreeSet<>(combination);

      if (!toggled.remove(var))
        toggled.add(var);

      changed.add(toggled);
    }

    return changed;
  }


  private static @NotNull Set<Set<Integer>> atomize(@NotNull Set<Set<Integer>> p)
  {
    final var atoms = new HashSet<Set<Integer>>();

    for(var combination: p)
      for(var var: combination)
        atoms.add(Set.of(var));

    return atoms;
  }


  /**
   * Builds the zbdd for the given {@code family}. The returned zbdd has an incremented reference count.
   */
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




  /**
   * Capacity advisor with a minimal node table, requiring a garbage collection whenever the table is full.
   */
  private static final class GcAlwaysAdvisor implements ZbddCapacityAdvisor
  {
    @Override
    public int getInitialCapacity() {
      return 8;
    }


    @Override
    public int getMinimumFreeNodes(@NotNull ZbddStatistics statistics) {
      return 1;
    }


    @Override
    public int adviseIncrement(@NotNull ZbddStatistics statistics) {
      return 8;
    }


    @Override
    public boolean isGCRequired(@NotNull ZbddStatistics statistics) {
      return true;
    }


    @Override
    public String toString() {
      return "GC_ALWAYS";
    }
  }
}
