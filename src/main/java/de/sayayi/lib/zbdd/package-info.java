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

/**
 * Core API for working with Zero-Suppressed Binary Decision Diagrams (ZBDDs).
 * <p>
 * <a href="https://en.wikipedia.org/wiki/Zero-suppressed_decision_diagram">
 *   Zero-Suppressed Binary Decision Diagrams (ZBDDs)
 * </a>, developed by Shin Ichi Minato, provide an efficient way to represent
 * and manipulate large collections of sets or combinations selected from a set
 * of variables. Each ZBDD is a directed acyclic graph where nodes represent
 * variables, edges indicate whether a variable is included (1-edge) or excluded
 * (0-edge) from a combination, and zero-suppression eliminates redundant nodes
 * to maintain a compact representation, particularly beneficial for sparse data.
 * <p>
 * This package provides the fundamental interfaces and classes for creating and
 * manipulating ZBDDs:
 * <ul>
 *   <li>{@link de.sayayi.lib.zbdd.Zbdd} - The main interface representing a
 *       Zero-Suppressed Binary Decision Diagram with operations such as union,
 *       intersection, difference, and set manipulation.</li>
 *   <li>{@link de.sayayi.lib.zbdd.ZbddFactory} - Factory for creating ZBDD
 *       instances, including support for caching and thread-safe wrappers.</li>
 *   <li>{@link de.sayayi.lib.zbdd.ZbddCapacityAdvisor} - Advisor interface for
 *       optimizing internal capacity allocation.</li>
 *   <li>{@link de.sayayi.lib.zbdd.ZbddLiteralResolver} - Interface for resolving
 *       variable literals to human-readable representations.</li>
 *   <li>{@link de.sayayi.lib.zbdd.ZbddStatistics} - Provides statistical
 *       information about ZBDD structure and operations.</li>
 * </ul>
 * <p>
 * The library supports advanced algebraic operations including multiplication (to combine two ZBDD sets into a
 * product), division (for quotient calculation), and modulo operations. Additional operations include
 * {@code removeBase} to remove the base element from a set, and {@code atomize} to create a ZBDD containing only
 * single-variable elements from the original.
 */
package de.sayayi.lib.zbdd;
