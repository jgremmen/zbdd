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
 * Cache implementations for optimizing ZBDD operations through memoization.
 * <p>
 * This package provides caching strategies to improve performance of ZBDD operations by storing and reusing
 * results of previously computed operations. Caching is particularly effective for complex operations that may
 * involve repeated computations on the same operands.
 * <p>
 * Available cache implementations:
 * <ul>
 *   <li>{@link de.sayayi.lib.zbdd.cache.ZbddCache} - Base interface for all
 *       ZBDD cache implementations, defining the contract for storing and
 *       retrieving cached operation results.</li>
 *   <li>{@link de.sayayi.lib.zbdd.cache.ZbddFastCache} - A high-performance
 *       cache implementation optimized for speed with minimal memory overhead.</li>
 * </ul>
 * <p>
 * Caches can be configured when creating ZBDD instances through {@link de.sayayi.lib.zbdd.ZbddFactory}, enabling
 * performance improvements for applications that perform repetitive or complex ZBDD operations.
 */
package de.sayayi.lib.zbdd.cache;
