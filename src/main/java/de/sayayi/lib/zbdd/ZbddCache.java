/*
 * Copyright 2021 Jeroen Gremmen
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
package de.sayayi.lib.zbdd;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import java.util.function.IntSupplier;


/**
 * @author Jeroen Gremmen
 */
public interface ZbddCache
{
  @Range(from = 0, to = Zbdd.MAX_NODES)
  int lookupOrPutIfAbsent(@NotNull Zbdd zbdd,
                          @NotNull UnaryOperation operation,
                          @Range(from = 0, to = Zbdd.MAX_NODES) int p,
                          @Range(from = 1, to = Integer.MAX_VALUE) int var,
                          @NotNull IntSupplier resultSupplier);


  @Range(from = 0, to = Zbdd.MAX_NODES)
  int lookupOrPutIfAbsent(@NotNull Zbdd zbdd,
                          @NotNull BinaryOperation operation,
                          @Range(from = 0, to = Zbdd.MAX_NODES) int p,
                          @Range(from = 0, to = Zbdd.MAX_NODES) int q,
                          @NotNull IntSupplier resultSupplier);


  void clear();




  enum UnaryOperation {
    SUBSET0, SUBSET1, CHANGE
  }




  enum BinaryOperation {
    UNION, INTERSECT, DIFF, MUL, DIV, MOD
  }




  enum NoCache implements ZbddCache
  {
    INSTANCE;


    @Override
    public int lookupOrPutIfAbsent(@NotNull Zbdd zbdd, @NotNull UnaryOperation operation, int p, int var,
                                   @NotNull IntSupplier resultSupplier) {
      return resultSupplier.getAsInt();
    }


    @Override
    public int lookupOrPutIfAbsent(@NotNull Zbdd zbdd, @NotNull BinaryOperation operation, int p, int q,
                                   @NotNull IntSupplier resultSupplier) {
      return resultSupplier.getAsInt();
    }


    @Override
    public void clear() {
    }
  }
}
