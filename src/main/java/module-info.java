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
 * A Java library for creating and manipulating
 * <a href="https://en.wikipedia.org/wiki/Zero-suppressed_decision_diagram">Zero-suppressed Binary Decision
 * Diagrams (ZBDDs)</a>.
 * <p>
 * ZBDDs are a compact data structure for efficiently representing and operating on families of sets, especially
 * when most variables are absent from most sets (sparse data). They are well-suited for combinatorial problems such
 * as constraint solving, covering problems, and enumeration tasks.
 *
 * <h2>Getting Started</h2>
 * Use {@link de.sayayi.lib.zbdd.ZbddFactory} to create a {@link de.sayayi.lib.zbdd.Zbdd} instance. Variables are
 * created with {@link de.sayayi.lib.zbdd.Zbdd#createVar()}, and combinations (cubes) are built from those variables.
 * The library provides set operations such as union, intersection, difference, product, division, and modulo on
 * families of sets.
 * <p>
 * Example:
 * <pre>{@code
 *   Zbdd zbdd = ZbddFactory.create();
 *   int a = zbdd.createVar();
 *   int b = zbdd.createVar();
 *   int cubeA = zbdd.cube(a);
 *   int cubeB = zbdd.cube(b);
 *   int result = zbdd.union(cubeA, cubeB);  // { a, b }
 * }</pre>
 *
 * <h2>Caching</h2>
 * For performance-critical applications, operation caching can be enabled by providing a
 * {@link de.sayayi.lib.zbdd.cache.ZbddCache} implementation (e.g.
 * {@link de.sayayi.lib.zbdd.cache.ZbddFastCache}) via
 * {@link de.sayayi.lib.zbdd.ZbddFactory#createCached(de.sayayi.lib.zbdd.cache.ZbddCache)}.
 *
 * <h2>Thread Safety</h2>
 * By default, ZBDD instances are not thread-safe. Thread-safe access can be enabled by wrapping an instance with
 * {@link de.sayayi.lib.zbdd.ZbddFactory#asConcurrent(de.sayayi.lib.zbdd.Zbdd)}.
 *
 * <h2>Memory Management</h2>
 * ZBDD nodes are managed through reference counting. Nodes that need to be preserved across operations should have
 * their reference count incremented via {@link de.sayayi.lib.zbdd.Zbdd#incRef(int)}. Unreferenced nodes can be
 * reclaimed by invoking {@link de.sayayi.lib.zbdd.Zbdd#gc()}.
 */
module de.sayayi.lib.zbdd {

  requires static org.jetbrains.annotations;

  exports de.sayayi.lib.zbdd;
  exports de.sayayi.lib.zbdd.cache;
  exports de.sayayi.lib.zbdd.exception;

}