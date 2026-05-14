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
 * Internal implementation classes for the ZBDD library.
 * <p>
 * This package contains the core implementation details of the ZBDD data structure and supporting utilities.
 * Classes in this package are not part of the public API and may change without notice.
 * <p>
 * Key components:
 * <ul>
 *   <li>
 *     {@link de.sayayi.lib.zbdd.internal.ZbddImpl} - Core ZBDD implementation providing the fundamental operations
 *     on Zero-suppressed Binary Decision Diagrams.
 *   </li>
 *   <li>
 *     {@link de.sayayi.lib.zbdd.internal.ZbddCachedImpl} - ZBDD implementation enhanced with operation caching for
 *     improved performance.
 *   </li>
 *   <li>
 *     {@link de.sayayi.lib.zbdd.internal.ZbddConcurrent} - Thread-safe ZBDD implementation supporting concurrent
 *     access.
 *   </li>
 *   <li>
 *     {@link de.sayayi.lib.zbdd.internal.DefaultCapacityAdvisor} - Default
 *     {@link de.sayayi.lib.zbdd.ZbddCapacityAdvisor} used when no custom advisor is provided.
 *   </li>
 * </ul>
 */
package de.sayayi.lib.zbdd.internal;
