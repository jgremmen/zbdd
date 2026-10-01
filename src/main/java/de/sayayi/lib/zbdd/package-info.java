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
 * Core API for creating, querying and manipulating Zero Suppressed Binary Decision Diagrams (ZBDDs).
 * <p>
 * <a href="https://en.wikipedia.org/wiki/Zero-suppressed_decision_diagram">Zero Suppressed Binary Decision
 * Diagrams</a>, introduced by Shin Ichi Minato, efficiently represent large families of sets (combinations) over a
 * set of variables. A ZBDD is a directed acyclic graph whose nodes represent variables and whose 1 and 0 edges
 * indicate whether a variable is included in or excluded from a combination. Zero suppression removes redundant
 * nodes, which keeps the representation compact, especially for sparse data.
 * <p>
 * Relevant types in this package:
 * <ul>
 *   <li>{@link de.sayayi.lib.zbdd.Zbdd}: the central interface providing variable management, set operations such
 *     as union, intersection, difference, multiplication, division and modulo, traversal and reference counting
 *     based memory management. The nested {@link de.sayayi.lib.zbdd.Zbdd.WithCache Zbdd.WithCache} and
 *     {@link de.sayayi.lib.zbdd.Zbdd.Concurrent Zbdd.Concurrent} interfaces describe cached and thread safe
 *     variants.</li>
 *   <li>{@link de.sayayi.lib.zbdd.ZbddFactory}: creates plain, cached and concurrent ZBDD instances.</li>
 *   <li>{@link de.sayayi.lib.zbdd.ZbddCapacityAdvisor}: controls initial node capacity and growth.</li>
 *   <li>{@link de.sayayi.lib.zbdd.ZbddLiteralResolver}: converts variables into readable literal names.</li>
 *   <li>{@link de.sayayi.lib.zbdd.ZbddStatistics}: exposes statistics about node usage and memory.</li>
 * </ul>
 */
package de.sayayi.lib.zbdd;
