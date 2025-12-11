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
 * Exception hierarchy for ZBDD-related errors and exceptional conditions.
 * <p>
 * This package contains all exceptions that can be thrown by ZBDD operations, providing specific error types for
 * different failure scenarios to enable precise error handling in client code.
 * <p>
 * Exception hierarchy:
 * <ul>
 *   <li>{@link de.sayayi.lib.zbdd.exception.ZbddException} - Base runtime
 *       exception for all ZBDD-related errors.</li>
 *   <li>{@link de.sayayi.lib.zbdd.exception.InvalidVarException} - Thrown when
 *       an invalid variable identifier is used in a ZBDD operation.</li>
 *   <li>{@link de.sayayi.lib.zbdd.exception.InvalidZbddException} - Thrown when
 *       an invalid or corrupted ZBDD reference is encountered.</li>
 *   <li>{@link de.sayayi.lib.zbdd.exception.ZbddOutOfRangeException} - Thrown
 *       when an operation would exceed the valid range of ZBDD values or
 *       internal capacity limits.</li>
 * </ul>
 * <p>
 * All exceptions in this package extend {@link java.lang.RuntimeException}, allowing them to be caught and handled
 * as needed without requiring explicit declaration in method signatures.
 */
package de.sayayi.lib.zbdd.exception;

