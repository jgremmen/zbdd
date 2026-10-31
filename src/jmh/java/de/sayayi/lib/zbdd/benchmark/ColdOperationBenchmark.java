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
package de.sayayi.lib.zbdd.benchmark;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;

import static java.util.concurrent.TimeUnit.MICROSECONDS;


/**
 * Runs the operations with the full amount of work: before each invocation a garbage collection removes the result of
 * the previous invocation and empties the operation cache. The garbage collection is not part of the score.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
@OutputTimeUnit(MICROSECONDS)
public class ColdOperationBenchmark extends AbstractOperationBenchmark
{
  @Setup(Level.Invocation)
  public void collectGarbage() {
    zbdd.gc();
  }
}
