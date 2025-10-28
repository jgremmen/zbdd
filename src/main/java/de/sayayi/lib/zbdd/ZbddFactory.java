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
package de.sayayi.lib.zbdd;

import de.sayayi.lib.zbdd.cache.ZbddCache;
import de.sayayi.lib.zbdd.impl.DefaultCapacityAdvisor;
import de.sayayi.lib.zbdd.impl.ZbddCachedImpl;
import de.sayayi.lib.zbdd.impl.ZbddImpl;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;


/**
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
public final class ZbddFactory
{
  @Contract(value = "-> new", pure = true)
  public static @NotNull Zbdd create() {
    return new ZbddImpl(DefaultCapacityAdvisor.INSTANCE);
  }


  @Contract(value = "_ -> new", pure = true)
  public static @NotNull Zbdd create(ZbddCapacityAdvisor capacityAdvisor) {
		return new ZbddImpl(capacityAdvisor != null ? capacityAdvisor : DefaultCapacityAdvisor.INSTANCE);
	}


  @Contract(value = "_ -> new", pure = true)
  public static @NotNull Zbdd create(Zbdd.WithCache zbdd) {
    return new ZbddImpl((ZbddImpl)zbdd);
  }


  @Contract(value = "_, _ -> new", pure = true)
  public static @NotNull Zbdd.WithCache createCached(ZbddCapacityAdvisor capacityAdvisor, @NotNull ZbddCache zbddCache) {
    return new ZbddCachedImpl(capacityAdvisor != null ? capacityAdvisor : DefaultCapacityAdvisor.INSTANCE, zbddCache);
  }


  @Contract(value = "_, _ -> new", pure = true)
  public static @NotNull Zbdd.WithCache createCached(Zbdd zbdd, @NotNull ZbddCache zbddCache) {
    return new ZbddCachedImpl((ZbddImpl)zbdd, zbddCache);
  }
}
