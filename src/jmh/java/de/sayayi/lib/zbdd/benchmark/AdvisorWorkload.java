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
import java.util.function.Supplier;

import static de.sayayi.lib.zbdd.benchmark.Workloads.*;
import static java.util.Arrays.copyOf;


/**
 * Complete workloads from scratch, with different ratios between live nodes and garbage. Together they cover the
 * situations a capacity advisor has to deal with.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 *
 * @see CapacityAdvisorBenchmark
 */
public enum AdvisorWorkload
{
  /** Referenced cubes only. Without any garbage, the costs are those of growing the table. */
  BULK_LOAD {
    @Override
    int run(@NotNull Supplier<Zbdd> instances)
    {
      final var zbdd = instances.get();
      final var vars = createVars(zbdd, 3000);
      final var random = new Random(1);

      for(var n = 0; n < 1_000_000; n++)
      {
        zbdd.incRef(zbdd.cube(vars[random.nextInt(3000)], vars[random.nextInt(3000)], vars[random.nextInt(3000)],
            vars[random.nextInt(3000)]));
      }

      return zbdd.getStatistics().getOccupiedNodes();
    }
  },


  /** A single growing set: nearly all nodes created are garbage after the next union. */
  BUILD_SET {
    @Override
    int run(@NotNull Supplier<Zbdd> instances)
    {
      final var zbdd = instances.get();

      return zbdd.count(randomSet(zbdd, createVars(zbdd, 64), 0, 64, new Random(1), 40_000, 3, 12));
    }
  },


  /** Intermediate results of filtered unions, of which only a small part survives each step. */
  QUEENS {
    @Override
    int run(@NotNull Supplier<Zbdd> instances) {
      return queens(instances.get(), 12);
    }
  },


  /** Solving a hard sudoku, with intermediate results far larger than the solution. */
  SUDOKU {
    // "Beyond Nightmare", see SudokuTest
    private static final String GRID =
        "120300000400000300003050000004200500000080009060005070001500200000090060000007008";


    @Override
    int run(@NotNull Supplier<Zbdd> instances) {
      return sudoku(instances.get(), GRID);
    }
  },


  /** Products with their quotient and remainder: large and short-lived intermediate results. */
  MULTIPLY_DIVIDE {
    @Override
    int run(@NotNull Supplier<Zbdd> instances)
    {
      final var zbdd = instances.get();
      final var vars = createVars(zbdd, 48);
      final var random = new Random(1);
      var checksum = 0;

      for(var n = 0; n < 12; n++)
      {
        final var f = randomSet(zbdd, vars, 0, 48, random, 200, 1, 4);
        final var g = randomSet(zbdd, vars, 0, 48, random, 200, 1, 4);
        final var product = zbdd.incRef(zbdd.multiply(f, g));
        final var quotient = zbdd.incRef(zbdd.divide(product, f));

        checksum += zbdd.count(product) + zbdd.count(quotient) + zbdd.count(zbdd.modulo(product, g));

        zbdd.decRef(quotient);
        zbdd.decRef(product);
        zbdd.decRef(g);
        zbdd.decRef(f);
      }

      return checksum;
    }
  },


  /** Dynamic programming over the subsets of items with a given weight: many live sets sharing their nodes. */
  SUBSET_SUM {
    @Override
    int run(@NotNull Supplier<Zbdd> instances)
    {
      final var zbdd = instances.get();
      final var vars = createVars(zbdd, 160);
      final var random = new Random(1);
      final var maxWeight = 7000;
      final var sets = new int[maxWeight + 1];

      sets[0] = zbdd.incRef(Zbdd.BASE);

      for(var var: vars)
      {
        final var weight = 1 + random.nextInt(100);

        for(var w = maxWeight; w >= weight; w--)
        {
          final var set = zbdd.incRef(zbdd.union(sets[w], zbdd.change(sets[w - weight], var)));

          zbdd.decRef(sets[w]);
          sets[w] = set;
        }
      }

      return zbdd.countExact(sets[maxWeight]).intValue();
    }
  },


  /** A long-running application: a constant number of live sets, continuously being replaced by new ones. */
  STEADY_CHURN {
    @Override
    int run(@NotNull Supplier<Zbdd> instances)
    {
      final var zbdd = instances.get();
      final var vars = createVars(zbdd, 256);
      final var random = new Random(1);
      final var sets = new int[32];

      for(var n = 0; n < sets.length; n++)
        sets[n] = randomSet(zbdd, vars, 0, 256, random, 300, 3, 12);

      var checksum = 0;

      for(var n = 0; n < 400; n++)
      {
        final var p = sets[random.nextInt(sets.length)];
        final var q = sets[random.nextInt(sets.length)];
        final var slot = random.nextInt(sets.length);
        final var add = randomSet(zbdd, vars, 0, 256, random, 150, 3, 12);
        final var replacement = zbdd.count(p) > 600
            ? zbdd.incRef(add)
            : zbdd.incRef(zbdd.union(zbdd.difference(p, q), add));

        zbdd.decRef(add);
        zbdd.decRef(sets[slot]);
        sets[slot] = replacement;

        checksum += zbdd.count(replacement);
      }

      return checksum;
    }
  },


  /** A little garbage per operation on a table with a million live nodes, making each garbage collection costly. */
  LARGE_TABLE {
    @Override
    int run(@NotNull Supplier<Zbdd> instances)
    {
      final var zbdd = instances.get();
      final var vars = createVars(zbdd, 3000);
      final var random = new Random(1);
      final var set = randomSet(zbdd, vars, 0, 200, random, 500, 4, 4);

      for(var n = 0; n < 250_000; n++)
      {
        zbdd.incRef(zbdd.cube(vars[random.nextInt(3000)], vars[random.nextInt(3000)], vars[random.nextInt(3000)],
            vars[random.nextInt(3000)]));
      }

      for(var n = 0; n < 700_000; n++)
      {
        zbdd.subset0(set, vars[random.nextInt(50)]);
        zbdd.cube(vars[200 + random.nextInt(2800)], vars[200 + random.nextInt(2800)], vars[200 + random.nextInt(2800)]);
      }

      return zbdd.count(set);
    }
  },


  /** Many instances having a short life and only a few nodes, for which the initial capacity is what counts. */
  SMALL_INSTANCES {
    @Override
    int run(@NotNull Supplier<Zbdd> instances)
    {
      var checksum = 0;

      for(var n = 0; n < 5000; n++)
      {
        final var zbdd = instances.get();

        checksum += zbdd.count(randomSet(zbdd, createVars(zbdd, 16), 0, 16, new Random(n), 8, 2, 5));
      }

      return checksum;
    }


    @Override
    int cacheSize() {
      return 1024;
    }
  };


  /**
   * Runs this workload.
   *
   * @param instances  supplier for the zbdd instance(s) to use, not {@code null}
   *
   * @return  value depending on the result of this workload only, not on how the nodes are managed
   */
  abstract int run(@NotNull Supplier<Zbdd> instances);


  /**
   * Returns the cache size to use for a cached instance.
   *
   * @return  cache size
   */
  int cacheSize() {
    return 1 << 18;
  }


  private static int sudoku(@NotNull Zbdd zbdd, @NotNull String grid)
  {
    final var vars = new int[81][10];

    for(var cell = 0; cell < 81; cell++)
      for(var value = 1; value <= 9; value++)
        vars[cell][value] = zbdd.createVar();

    var solution = Zbdd.BASE;

    for(var cell = 0; cell < 81; cell++)
    {
      final var given = grid.charAt(cell) - '0';
      final var solution0 = zbdd.incRef(solution);
      var next = Zbdd.EMPTY;

      for(var value = 1; value <= 9; value++)
        if (given == 0 ? isPossible(grid, cell, value) : given == value)
        {
          final var next0 = zbdd.incRef(next);

          next = zbdd.union(next0,
              zbdd.change(zbdd.subset0(solution0, conflicts(vars, cell, value)), vars[cell][value]));
          zbdd.decRef(next0);
        }

      solution = next;
      zbdd.decRef(solution0);
    }

    return zbdd.count(solution);
  }


  private static boolean isPossible(@NotNull String grid, int cell, int value)
  {
    for(var other = 0; other < 81; other++)
      if (grid.charAt(other) - '0' == value && isConflicting(cell, other))
        return false;

    return true;
  }


  private static int @NotNull [] conflicts(int @NotNull [] @NotNull [] vars, int cell, int value)
  {
    final var conflicts = new int[20];
    var count = 0;

    for(var other = 0; other < cell; other++)
      if (isConflicting(cell, other))
        conflicts[count++] = vars[other][value];

    return copyOf(conflicts, count);
  }


  private static boolean isConflicting(int cell1, int cell2)
  {
    final int row1 = cell1 / 9, col1 = cell1 % 9, row2 = cell2 / 9, col2 = cell2 % 9;

    return row1 == row2 || col1 == col2 || (row1 / 3 == row2 / 3 && col1 / 3 == col2 / 3);
  }
}
