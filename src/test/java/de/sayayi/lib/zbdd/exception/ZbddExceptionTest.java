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
package de.sayayi.lib.zbdd.exception;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;


/**
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@DisplayName("Zbdd exceptions")
class ZbddExceptionTest
{
  @Test
  @DisplayName("Exception with cause")
  void withCause()
  {
    final var cause = new IllegalStateException("cause");
    final var ex = new ZbddException("message", cause);

    assertEquals("message", ex.getMessage());
    assertSame(cause, ex.getCause());
  }


  @Test
  @DisplayName("Exceptions survive serialization")
  void serialization() throws Exception
  {
    final var zbddException = roundTrip(new ZbddException("zbdd", new IllegalStateException("cause")));
    assertEquals("zbdd", zbddException.getMessage());
    assertEquals("cause", zbddException.getCause().getMessage());

    final var invalidVarException = roundTrip(new InvalidVarException(7, 5, "var"));
    assertEquals("var", invalidVarException.getMessage());
    assertEquals(7, invalidVarException.getVar());
    assertEquals(5, invalidVarException.getHighBoundVar());

    final var invalidZbddException = roundTrip(new InvalidZbddException(3, "zbdd"));
    assertEquals("zbdd", invalidZbddException.getMessage());
    assertEquals(3, invalidZbddException.getZbdd());

    final var capacityExhaustedException = roundTrip(new ZbddCapacityExhaustedException(128, "capacity"));
    assertEquals("capacity", capacityExhaustedException.getMessage());
    assertEquals(128, capacityExhaustedException.getNodesCapacity());

    final var outOfRangeException = roundTrip(new ZbddOutOfRangeException(42, 15, "range"));
    assertEquals("range", outOfRangeException.getMessage());
    assertEquals(42, outOfRangeException.getZbdd());
    assertEquals(15, outOfRangeException.getHighBoundZbdd());
  }


  @SuppressWarnings("unchecked")
  private static <T extends Serializable> @NotNull T roundTrip(@NotNull T object) throws Exception
  {
    final var bytes = new ByteArrayOutputStream();

    try(var out = new ObjectOutputStream(bytes)) {
      out.writeObject(object);
    }

    try(var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      return (T)in.readObject();
    }
  }
}
