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

import de.sayayi.lib.zbdd.Zbdd;
import de.sayayi.lib.zbdd.ZbddCapacityAdvisor;
import de.sayayi.lib.zbdd.ZbddFactory;
import de.sayayi.lib.zbdd.cache.ZbddFastCache;
import org.jetbrains.annotations.NotNull;


/**
 * The zbdd configurations offered by {@link ZbddFactory}.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
public enum ZbddVariant
{
  PLAIN {
    @Override
    @NotNull Zbdd create(ZbddCapacityAdvisor advisor, int cacheSize) {
      return ZbddFactory.create(advisor);
    }
  },

  CACHED {
    @Override
    @NotNull Zbdd create(ZbddCapacityAdvisor advisor, int cacheSize) {
      return ZbddFactory.createCached(advisor, new ZbddFastCache(cacheSize));
    }
  },

  CONCURRENT {
    @Override
    @NotNull Zbdd create(ZbddCapacityAdvisor advisor, int cacheSize) {
      return ZbddFactory.asConcurrent(ZbddFactory.create(advisor));
    }
  },

  CACHED_CONCURRENT {
    @Override
    @NotNull Zbdd create(ZbddCapacityAdvisor advisor, int cacheSize) {
      return ZbddFactory.asConcurrent((Zbdd)ZbddFactory.createCached(advisor, new ZbddFastCache(cacheSize)));
    }
  };


  /** Cache size used, unless a benchmark is about the cache size itself. */
  static final int DEFAULT_CACHE_SIZE = 1 << 20;


  /**
   * Creates a new instance with a default capacity advisor.
   *
   * @return  new zbdd instance, never {@code null}
   */
  @NotNull Zbdd create() {
    return create(null, DEFAULT_CACHE_SIZE);
  }


  /**
   * Creates a new instance.
   *
   * @param advisor    capacity advisor or {@code null} for the default advisor
   * @param cacheSize  cache size; ignored if this variant has no cache
   *
   * @return  new zbdd instance, never {@code null}
   */
  abstract @NotNull Zbdd create(ZbddCapacityAdvisor advisor, int cacheSize);
}
