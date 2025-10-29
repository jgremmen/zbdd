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
 * Zbdd factory.
 *
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
public final class ZbddFactory
{
  /**
   * Create a new zbdd instance with a default capacity advisor.
   *
   * @return  new zbdd instance, never {@code null}
   */
  @Contract(value = "-> new", pure = true)
  public static @NotNull Zbdd create() {
    return create(null);
  }


  /**
   * Create a new zbdd instance with the given {@code capacityAdvisor}.
   *
   * @param capacityAdvisor  capacity advisor. If this parameter equals {@code null} then a default capacity advisor
   *                         is used.
   *
   * @return  new zbdd instance, never {@code null}
   */
  @Contract(value = "_ -> new", pure = true)
  public static @NotNull Zbdd create(ZbddCapacityAdvisor capacityAdvisor) {
		return new ZbddImpl(capacityAdvisor != null ? capacityAdvisor : DefaultCapacityAdvisor.INSTANCE);
	}


  /**
   * Create a new cached zbdd instance with the given {@code zbddCache} and a default capacity advisor.
   *
   * @param zbddCache  zbdd cache, not {@code null}
   *
   * @return  new cached zbdd instance, never {@code null}
   */
  @Contract(value = "_ -> new", pure = true)
  public static @NotNull Zbdd.WithCache createCached(@NotNull ZbddCache zbddCache) {
    return createCached(null, zbddCache);
  }


  /**
   * Create a new cached zbdd instance with the given {@code capacityAdvisor} and {@code zbddCache}.
   *
   * @param capacityAdvisor  capacity advisor. If this parameter equals {@code null} then a default capacity advisor
   *                         is used.
   * @param zbddCache        zbdd cache, not {@code null}
   *
   * @return  new cached zbdd instance, never {@code null}
   */
  @Contract(value = "_, _ -> new", pure = true)
  public static @NotNull Zbdd.WithCache createCached(ZbddCapacityAdvisor capacityAdvisor, @NotNull ZbddCache zbddCache) {
    return new ZbddCachedImpl(capacityAdvisor != null ? capacityAdvisor : DefaultCapacityAdvisor.INSTANCE, zbddCache);
  }
}
