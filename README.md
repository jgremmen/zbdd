## Introduction to the ZBDD Java Library

### Introduction
[Zero-Suppressed Binary Decision Diagrams (ZBDDs)][1], cooked up by Shin Ichi Minato, are a slick way to store and 
mess with huge piles of sets or combos picked from a bunch of variables. Think of each ZBDD as a directed graph without 
loops: nodes stand for variables, edges say whether a variable is in (1-edge) or out (0-edge) of a combo, and 
zero-suppression kicks out useless nodes to keep things lean, perfect for sparse data. This setup makes it fast and 
light on memory for stuff like listing subsets, doing set-family math, or cracking combo problems in optimization and 
data crunching.

### ZBDD Library
This Java library gives you a solid ZBDD setup with all the key moves for building and poking at these structures. The 
basics let you whip up an empty ZBDD, make a base one (just the empty set), flip variable assignments, pull subsets 
where a variable is missing (subset0) or there (subset1), figure diffs, unions, intersections, and count combos.

On top of that, you get fancier algebra: multiply to smash two ZBDD sets together into a product, divide for the 
quotient, and modulo for the leftover. There’s removeBase to ditch the base element from a set, and atomize to spit out
a fresh ZBDD holding only the single-variable bits from the original.

The library comes with a factory to spin up regular or cached ZBDDs, speeding things up with memoization when it helps. 
Plus, the factory can wrap any ZBDD to make it thread-safe, so you can run it in parallel without things falling apart.

### Test Suite
The library’s beefy test suite shows it in action on real combo headaches, like solving n-Queens on boards from 1×1 to
13×13, plus Sudoku puzzles from easy breezy to downright brutal.

<img src="doc/image/test-suite-elapsed-time.png" title="n-Queens and Sudoku timing" width="450px">

[1]: <https://dl.acm.org/doi/pdf/10.1145/157485.164890> "Zero-Suppressed BDDs for Set Manipulation in Combinatorial Problems"
