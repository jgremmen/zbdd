/*
 * Copyright 2025 Jeroen Gremmen
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
import de.sayayi.lib.zbdd.internal.DefaultCapacityAdvisor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static de.sayayi.lib.zbdd.ZbddFactory.createCached;
import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
@DisplayName("Solve Sudoku")
public class SudokuTest
{
  private Zbdd zbdd;
  private Map<GridCellValue,Integer> gridToVar;


  @BeforeEach
  void init()
  {
    zbdd = createCached(DefaultCapacityAdvisor.INSTANCE, new ZbddFastCache(65536));
    gridToVar = new HashMap<>();

    for(int row = 1; row <= 9; row++)
      for(int col = 1; col <= 9; col++)
        for(int value = 1; value <= 9; value++)
        {
          final var cellValue = new GridCellValue(col, row, value);
          gridToVar.put(cellValue, zbdd.createVar(cellValue));
        }

    zbdd.setLiteralResolver(new SudokuLiteralResolver());
  }


  private static Stream<Arguments> sudokuParameters()
  {
    // Source: Sudoku.com
    return Stream.of(
        Arguments.of("Easy", new int[] {
            0, 0, 1, 0, 0, 2, 4, 0, 6,
            6, 0, 0, 0, 0, 5, 0, 1, 8,
            0, 8, 2, 9, 1, 6, 0, 0, 0,
            0, 0, 0, 8, 0, 4, 5, 0, 0,
            1, 0, 8, 0, 0, 0, 6, 0, 9,
            0, 0, 0, 0, 0, 0, 8, 2, 7,
            5, 1, 4, 7, 3, 0, 0, 6, 2,
            3, 0, 7, 2, 4, 0, 0, 0, 0,
            0, 2, 0, 0, 0, 1, 3, 7, 0 }),
        Arguments.of("Medium", new int[] {
            7, 8, 2, 6, 1, 0, 9, 0, 5,
            5, 6, 0, 9, 4, 2, 8, 0, 3,
            0, 0, 0, 0, 0, 7, 2, 0, 1,
            0, 7, 6, 4, 0, 5, 0, 0, 0,
            3, 0, 0, 0, 0, 0, 0, 2, 0,
            0, 0, 0, 3, 6, 0, 5, 0, 0,
            6, 1, 0, 0, 0, 0, 7, 9, 0,
            0, 2, 3, 0, 9, 0, 0, 5, 0,
            0, 0, 0, 0, 0, 4, 6, 0, 0 }),
        Arguments.of("Hard", new int[] {
            4, 0, 0, 6, 0, 0, 5, 0, 0,
            3, 0, 0, 5, 0, 9, 0, 2, 0,
            7, 0, 2, 8, 3, 0, 0, 0, 9,
            0, 0, 8, 0, 0, 7, 0, 0, 0,
            0, 4, 0, 3, 9, 6, 0, 0, 8,
            6, 0, 0, 2, 0, 4, 0, 0, 0,
            0, 7, 0, 0, 0, 0, 9, 0, 0,
            0, 2, 0, 7, 0, 0, 1, 0, 0,
            1, 0, 5, 0, 0, 0, 3, 0, 0 }),
        Arguments.of("Expert", new int[] {
            0, 7, 5, 0, 2, 0, 0, 8, 0,
            0, 0, 4, 0, 0, 0, 2, 0, 0,
            6, 2, 0, 9, 0, 5, 3, 0, 0,
            0, 4, 9, 1, 5, 0, 0, 0, 0,
            0, 0, 0, 0, 8, 9, 5, 0, 0,
            5, 0, 1, 0, 0, 0, 9, 7, 0,
            0, 0, 0, 2, 0, 0, 0, 0, 0,
            0, 0, 0, 8, 0, 0, 0, 2, 3,
            8, 3, 2, 5, 7, 1, 0, 0, 0 }),
        Arguments.of("Master", new int[] {
            0, 8, 4, 6, 0, 0, 0, 0, 1,
            2, 5, 7, 0, 0, 1, 6, 0, 0,
            0, 0, 3, 5, 4, 0, 0, 0, 0,
            1, 0, 0, 0, 0, 4, 0, 5, 0,
            7, 0, 0, 3, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 7, 4, 9, 0,
            0, 0, 0, 0, 1, 0, 0, 0, 2,
            5, 0, 0, 0, 2, 8, 0, 7, 0,
            0, 2, 0, 0, 0, 0, 1, 0, 0 }),
        Arguments.of("Extreme", new int[] {
            0, 0, 0, 1, 0, 6, 2, 0, 4,
            0, 4, 0, 0, 0, 0, 0, 5, 0,
            1, 0, 0, 7, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 5, 0, 0, 3, 0,
            0, 0, 0, 0, 0, 3, 0, 0, 0,
            0, 0, 1, 0, 0, 0, 6, 7, 2,
            6, 9, 2, 0, 0, 0, 0, 0, 5,
            0, 0, 0, 4, 0, 0, 0, 0, 0,
            0, 3, 0, 0, 6, 0, 0, 0, 8 }),
        /* http://www.aisudoku.com/en/AIsudoku_Top10s1_en.pdf */
        Arguments.of("AI escargot", new int[] {
            1, 0, 0, 0, 0, 7, 0, 9, 0,
            0, 3, 0, 0, 2, 0, 0, 0, 8,
            0, 0, 9, 6, 0, 0, 5, 0, 0,
            0, 0, 5, 3, 0, 0, 9, 0, 0,
            0, 1, 0, 0, 8, 0, 0, 0, 2,
            6, 0, 0, 0, 0, 4, 0, 0, 0,
            3, 0, 0, 0, 0, 0, 0, 1, 0,
            0, 4, 0, 0, 0, 0, 0, 0, 7,
            0, 0, 7, 0, 0, 0, 3, 0, 0 }),
        Arguments.of("AI killer application", new int[] {
            0, 0, 0, 0, 0, 0, 0, 7, 0,
            0, 6, 0, 0, 1, 0, 0, 0, 4,
            0, 0, 3, 4, 0, 0, 2, 0, 0,
            8, 0, 0, 0, 0, 3, 0, 5, 0,
            0, 0, 2, 9, 0, 0, 7, 0, 0,
            0, 4, 0, 0, 8, 0, 0, 0, 9,
            0, 2, 0, 0, 6, 0, 0, 0, 7,
            0, 0, 0, 1, 0, 0, 9, 0, 0,
            7, 0, 0, 0, 0, 8, 0, 6, 0 }),
        Arguments.of("AI lucky diamond", new int[] {
            1, 0, 0, 5, 0, 0, 4, 0, 0,
            0, 0, 9, 0, 3, 0, 0, 0, 0,
            0, 7, 0, 0, 0, 8, 0, 0, 5,
            0, 0, 1, 0, 0, 0, 0, 3, 0,
            8, 0, 0, 6, 0, 0, 5, 0, 0,
            0, 9, 0, 0, 0, 7, 0, 0, 8,
            0, 0, 4, 0, 2, 0, 0, 1, 0,
            2, 0, 0, 8, 0, 0, 6, 0, 0,
            0, 0, 0, 0, 0, 1, 0, 0, 2 }),
        Arguments.of("AI worm hole", new int[] {
            0, 8, 0, 0, 0, 0, 0, 0, 1,
            0, 0, 7, 0, 0, 4, 0, 2, 0,
            6, 0, 0, 3, 0, 0, 7, 0, 0,
            0, 0, 2, 0, 0, 9, 0, 0, 0,
            1, 0, 0, 0, 6, 0, 0, 0, 8,
            0, 3, 0, 4, 0, 0, 0, 0, 0,
            0, 0, 1, 7, 0, 0, 6, 0, 0,
            0, 9, 0, 0, 0, 8, 0, 0, 5,
            0, 0, 0, 0, 0, 0, 0, 4, 0 })
    );
  }

  @DisplayName("Sudoku problem solving")
  @ParameterizedTest(name = "{0} Sudoku")
  @MethodSource("sudokuParameters")
  void sudoku(@SuppressWarnings("unused") String level, int[] grid)
  {
    int solution = Zbdd.base();

    for(int row = 1; row <= 9; row++)
      for(int col = 1; col <= 9; col++)
      {
        final int boxCol1 = 3 * ((col - 1) / 3) + 1;
        final int boxRow1 = 3 * ((row - 1) / 3) + 1;

        final int solution0 = zbdd.incRef(solution);
        final int value = grid[(row - 1) * 9 + col - 1];

        solution = value > 0
            ? getIncrementalSolutionForValue(row, col, boxRow1, boxCol1, value, solution)
            : getIncrementalSolution(row, col, boxRow1, boxCol1, grid, solution);

        zbdd.decRef(solution0);
      }

    assertEquals(1, zbdd.count(solution));

    System.out.println("Solution for '" + level + "' Sudoko:");

    zbdd.visitCubes(solution, vars -> {
      final var sudoku = new char[9][9];

      for(int var: vars)
      {
        final var cell = zbdd.<GridCellValue>getVarObject(var);
        sudoku[cell.row - 1][cell.col - 1] = (char)('0' + cell.value);
      }

      for(int y = 0; y < 9; y++)
      {
        if (y % 3 == 0)
          System.out.println();

        System.out.print(" ");

        for(int x = 0; x < 9; x++)
        {
          if (x % 3 == 0)
            System.out.print(" ");

          System.out.print(sudoku[y][x]);
        }

        System.out.println();
      }

      System.out.println();
      return false;
    });

    zbdd.gc();
    System.out.println("  " + zbdd.getStatistics() + '\n');
  }


  private int getIncrementalSolutionForValue(int row, int col, int boxRow1, int boxCol1, int value, int prunedSolution)
  {
    // same row
    for(int c = 1; c < col; c++)
      prunedSolution = zbdd.subset0(prunedSolution, var(c, row, value));

    // same column
    for(int r = 1; r < row; r++)
      prunedSolution = zbdd.subset0(prunedSolution, var(col, r, value));

    // same box
    for(int r = boxRow1; r <= row; r++)
      for(int c = boxCol1; c < col || (r < row && c < boxCol1 + 3); c++)
        prunedSolution = zbdd.subset0(prunedSolution, var(c, r, value));

    return zbdd.change(prunedSolution, var(col, row, value));
  }


  private int getIncrementalSolution(int row, int col, int boxRow1, int boxCol1, int[] grid, int solution)
  {
    int tmpSolution = Zbdd.empty();

    for(int value = 1; value <= 9; value++)
      if (!isExcludedValue(row, col, boxRow1, boxCol1, grid, value))
      {
        final int tmpSolution0 = zbdd.incRef(tmpSolution);

        tmpSolution = zbdd.union(tmpSolution,
            getIncrementalSolutionForValue(row, col, boxRow1, boxCol1, value, solution));

        zbdd.decRef(tmpSolution0);
      }

    return tmpSolution;
  }


  @Contract(pure = true)
  private boolean isExcludedValue(int row, int col, int boxRow1, int boxCol1, int[] grid, int value)
  {
    // check current 3x3 box
    for(int r = boxRow1; r < boxRow1 + 3; r++)
      for(int c = boxCol1; c < boxCol1 + 3; c++)
        if (gridValue(r, c, grid) == value)
          return true;

    // check same row beyond 3x3 box
    for(int c = boxCol1 + 3; c <= 9; c++)
      if (gridValue(row, c, grid) == value)
        return true;

    // check same column beyond 3x3 box
    for(int r = boxRow1 + 3; r <= 9; r++)
      if (gridValue(r, col, grid) == value)
        return true;

    return false;
  }


  @Contract(pure = true)
  private int gridValue(int row, int col, int[] grid) {
    return grid[(row - 1) * 9 + col - 1];
  }


  @Contract(pure = true)
  private int var(int col, int row, int value) {
    return gridToVar.get(new GridCellValue(col, row, value));
  }




  private static class GridCellValue
  {
    final int col;
    final int row;
    final int value;


    GridCellValue(int col, int row, int value) {
      this.col = col;
      this.row = row;
      this.value = value;
    }


    @Override
    public boolean equals(Object o)
    {
      if (!(o instanceof GridCellValue))
        return false;

      final var that = (GridCellValue)o;

      return col == that.col && row == that.row && value == that.value;
    }


    @Override
    public int hashCode() {
      return (col * 31 + row) * 31 + value;
    }


    @Override
    public String toString() {
      return "(" + col + "," + row + ") = " + value;
    }
  }




  private class SudokuLiteralResolver implements ZbddLiteralResolver
  {
    @Override
    public @NotNull String getLiteralName(int var) {
      return zbdd.getVarObject(var).toString();
    }


    @Override
    public @NotNull String getCubeName(int @NotNull [] cubeVars)
    {
      final var sudoku = (
          "\n" +
          "?????????\n" +
          "?????????\n" +
          "?????????\n" +
          "?????????\n" +
          "?????????\n" +
          "?????????\n" +
          "?????????\n" +
          "?????????\n" +
          "?????????\n").toCharArray();

      for(int var: cubeVars)
      {
        final var cell = zbdd.<GridCellValue>getVarObject(var);
        sudoku[10 * (cell.row - 1) + cell.col] = (char)('0' + cell.value);
      }

      return "\n" + new String(sudoku);
    }
  }
}
