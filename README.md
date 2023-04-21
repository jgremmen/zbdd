# ZBDDs

## Introduction

Zero-suppressed binary decision diagrams (ZBDDs) are a data structure used to represent and 
manipulate sets of binary vectors, typically used in the field of computer science and 
computational logic. They were developed by Shin-ichi Minato in the early 1990s as a more efficient
alternative to traditional binary decision diagrams (BDDs).

The basic idea behind ZBDDs is to represent sets of binary vectors by encoding the information 
about which vectors are included in the set, rather than explicitly representing each vector. This
is achieved by constructing a directed acyclic graph, where each node represents a decision point 
based on the value of a particular bit in the vector. The edges of the graph then indicate which 
branch to follow depending on the value of the bit being considered.

Unlike BDDs, which can represent any Boolean function but may suffer from exponential blowup in 
size, ZBDDs are specifically designed to efficiently represent sets of vectors that contain a large 
number of zeros. They achieve this by exploiting the fact that a set containing a large number of 
zeros can be represented more compactly by only explicitly encoding the vectors that are not in the
set. This leads to significant space savings, making ZBDDs well-suited for a range of applications 
in computer science and computational logic, including optimization, satisfiability testing, and 
automated reasoning.

Overall, ZBDDs are a powerful and flexible data structure that provides an efficient way to
represent and manipulate sets of binary vectors, making them an important tool in the field of 
computer science and beyond.

## Java Library

This Java library for zero-suppressed binary decision diagram (ZBDD) set manipulation offers a 
comprehensive suite of functions for efficiently representing and manipulating sets of binary 
vectors, including all the functions described in the ACM/IEEE articles by Shin-ichi Minato, as
well as the more complex functions such as product, quotient of division, and remainder of division.

With this library, users can easily create and manipulate ZBDDs to represent large sets of binary 
vectors. The library is designed to be fast, flexible, with options for customization to suit the 
specific needs of each project.
