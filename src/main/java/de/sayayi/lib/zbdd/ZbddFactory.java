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
import de.sayayi.lib.zbdd.internal.DefaultCapacityAdvisor;
import de.sayayi.lib.zbdd.internal.ZbddCachedImpl;
import de.sayayi.lib.zbdd.internal.ZbddConcurrent;
import de.sayayi.lib.zbdd.internal.ZbddImpl;
import de.sayayi.lib.zbdd.internal.ZbddNative;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;


/**
 * Factory for creating {@link Zbdd} instances with various configurations, including support for operation caching
 * and thread-safe (concurrent) access.
 * <p>
 * This class provides static factory methods only and cannot be instantiated.
 *
 * @author Jeroen Gremmen
 * @since 0.5.0
 */
public final class ZbddFactory
{
  private ZbddFactory() {
  }


  /**
   * Creates a new ZBDD instance with a default capacity advisor. The returned instance does no
   * operation caching and is not thread-safe.
   *
   * @return  new ZBDD instance, never {@code null}
   */
  @Contract(value = "-> new", pure = true)
  public static @NotNull Zbdd create() {
    return create(null);
  }


  /**
   * Creates a new ZBDD instance with the given {@code capacityAdvisor}. The returned instance does no
   * operation caching and is not thread-safe.
   *
   * @param capacityAdvisor  capacity advisor. If this parameter equals {@code null} then a default capacity advisor
   *                         is used.
   *
   * @return  new ZBDD instance, never {@code null}
   */
  @Contract(value = "_ -> new", pure = true)
  public static @NotNull Zbdd create(ZbddCapacityAdvisor capacityAdvisor) {
    return new ZbddImpl(capacityAdvisor != null ? capacityAdvisor : DefaultCapacityAdvisor.INSTANCE);
  }


  /**
   * Tells whether the native library can be used on the current platform, so {@link #createNative()} and its variants
   * can create instances. Native libraries are available for Linux and Windows on x86_64, and for macOS on arm64.
   * <p>
   * This method loads the library from the jar if this hasn't been attempted yet, as only that shows whether it can
   * be used. The outcome is remembered, so later invocations are cheap and return the same result.
   *
   * @return  {@code true} if native instances can be created, {@code false} if the platform is not supported or the
   *          library cannot be loaded
   *
   * @see #createNative()
   *
   * @since 0.8.0
   */
  public static boolean isNativeAvailable() {
    return ZbddNative.isAvailable();
  }


  /**
   * Creates a new ZBDD instance backed by a native library, which is loaded from the jar on first use. It behaves like
   * the instance returned by {@link #create()}, keeping its nodes in a more compact form outside the Java heap. Native
   * libraries are available for Linux and Windows on x86_64, and for macOS on arm64.
   * <p>
   * The instance starts with a memory format using 8 bytes per node, limited to 32767 nodes and variables. It changes
   * to a format using 16 bytes per node automatically as soon as these limits are exceeded, with the limits of
   * {@link #create()}. Existing node numbers stay valid. The change is permanent, it isn't reverted by
   * {@link Zbdd#clear()}.
   * <p>
   * Results of {@code subset0}, {@code subset1}, {@code change}, {@code union}, {@code intersect}, {@code difference},
   * {@code multiply}, {@code divide}, {@code modulo}, {@code atomize}, {@code removeBase}, {@code count},
   * {@code countExact}, {@code contains} and {@code hasCubeWithVar} are cached in native memory. This instance has a
   * small cache of 1024 entries, taking 12 KB. Use {@link #createNative(ZbddCapacityAdvisor, int)} for larger
   * computations, as the cache doesn't grow along with the nodes. The cache is emptied by each garbage collection and
   * by {@link Zbdd#clear()}.
   * <p>
   * The cache is part of the native library and cannot be configured from the outside, so the returned instance is not
   * a {@link Zbdd.WithCache}.
   * <p>
   * The returned instance is not thread-safe, and unsynchronized concurrent access can crash the JVM. Use
   * {@link #asConcurrent(Zbdd)} if the instance is shared between threads.
   * <p>
   * The native memory is released when the instance becomes unreachable. As the JVM is not aware of that memory, an
   * unreachable instance doesn't cause a garbage collection by itself. Use {@link Zbdd.Native#close() close} to release
   * the memory right away. The same applies to the wrapper returned by {@link #asConcurrent(Zbdd.Native)}.
   * <p>
   * Starting with JDK 24, the JVM prints a warning for loading a native library unless native access is enabled with
   * {@code --enable-native-access=de.sayayi.lib.zbdd}, or {@code --enable-native-access=ALL-UNNAMED} on the class path.
   *
   * @return  new native ZBDD instance, never {@code null}
   *
   * @throws de.sayayi.lib.zbdd.exception.ZbddException  if no native library is available for the current platform or
   *                                                     the library cannot be loaded
   *
   * @see #isNativeAvailable()
   *
   * @since 0.8.0
   */
  @Contract("-> new")
  public static @NotNull Zbdd.Native createNative() {
    return createNative(null);
  }


  /**
   * Creates a new ZBDD instance like {@link #createNative()} with the given {@code capacityAdvisor}. The operation cache
   * has one entry for each node of the initial capacity, with a minimum of 1024 entries, and takes about as much memory
   * as the initial node table.
   *
   * @param capacityAdvisor  capacity advisor. If this parameter equals {@code null} then a default capacity advisor is
   *                         used.
   *
   * @return  new native ZBDD instance, never {@code null}
   *
   * @throws de.sayayi.lib.zbdd.exception.ZbddException  if no native library is available for the current platform or
   *                                                     the library cannot be loaded
   *
   * @see #createNative()
   *
   * @since 0.8.0
   */
  @Contract("_ -> new")
  public static @NotNull Zbdd.Native createNative(ZbddCapacityAdvisor capacityAdvisor) {
    return new ZbddNative(capacityAdvisor != null ? capacityAdvisor : DefaultCapacityAdvisor.INSTANCE);
  }


  /**
   * Creates a new ZBDD instance like {@link #createNative()} with the given {@code capacityAdvisor} and size of the
   * operation cache.
   *
   * @param capacityAdvisor  capacity advisor. If this parameter equals {@code null} then a default capacity advisor is
   *                         used.
   * @param cacheEntries     requested number of cache entries, rounded up to the next power of 2 between 1024 and 2^30.
   *                         An entry occupies 12 bytes and holds a single result. An entry is replaced when another
   *                         result maps to the same position. Results of {@code countExact} take additional memory of
   *                         at most about the same size.
   *
   * @return  new native ZBDD instance, never {@code null}
   *
   * @throws de.sayayi.lib.zbdd.exception.ZbddException  if no native library is available for the current platform or
   *                                                     the library cannot be loaded
   *
   * @see #createNative()
   *
   * @since 0.8.0
   */
  @Contract("_, _ -> new")
  public static @NotNull Zbdd.Native createNative(ZbddCapacityAdvisor capacityAdvisor, int cacheEntries) {
    return new ZbddNative(capacityAdvisor != null ? capacityAdvisor : DefaultCapacityAdvisor.INSTANCE, cacheEntries);
  }


  /**
   * Creates a new cached ZBDD instance with the given {@code zbddCache} and a default capacity advisor.
   * The returned instance is not thread-safe.
   * <p>
   * The cache is cleared and bound to the returned instance. It must not be used by any other ZBDD instance, as cached
   * results are keyed by node numbers of the returned instance.
   *
   * @param zbddCache  ZBDD operation cache, not {@code null}
   *
   * @return  new cached ZBDD instance, never {@code null}
   *
   * @throws de.sayayi.lib.zbdd.exception.ZbddException  if {@code zbddCache} is in use by another ZBDD instance
   */
  @Contract(value = "_ -> new", mutates = "param1")
  public static @NotNull Zbdd.WithCache createCached(@NotNull ZbddCache zbddCache) {
    return createCached(null, zbddCache);
  }


  /**
   * Creates a new cached ZBDD instance with the given {@code capacityAdvisor} and {@code zbddCache}.
   * The returned instance is not thread-safe.
   * <p>
   * The cache is cleared and bound to the returned instance. It must not be used by any other ZBDD instance, as cached
   * results are keyed by node numbers of the returned instance.
   *
   * @param capacityAdvisor  capacity advisor. If this parameter equals {@code null} then a default capacity advisor is
   *                         used.
   * @param zbddCache        ZBDD operation cache, not {@code null}
   *
   * @return  new cached ZBDD instance, never {@code null}
   *
   * @throws de.sayayi.lib.zbdd.exception.ZbddException  if {@code zbddCache} is in use by another ZBDD instance
   */
  @Contract(value = "_, _ -> new", mutates = "param2")
  public static @NotNull Zbdd.WithCache createCached(ZbddCapacityAdvisor capacityAdvisor,
                                                     @NotNull ZbddCache zbddCache) {
    return new ZbddCachedImpl(capacityAdvisor != null ? capacityAdvisor : DefaultCapacityAdvisor.INSTANCE, zbddCache);
  }


  /**
   * Wraps the given {@code zbdd} instance to make it thread-safe. If the instance is already concurrent, it is returned
   * as-is. If the instance is {@link Zbdd.WithCache cached}, the returned wrapper preserves cache support and
   * implements {@link Zbdd.WithCache} as well, regardless of the static type of {@code zbdd}. The same applies to a
   * {@link Zbdd.Native native} instance, with the returned wrapper implementing {@link Zbdd.Native}. After wrapping,
   * access the underlying instance only through the returned wrapper; direct access through another reference bypasses
   * synchronization.
   *
   * @param zbdd  ZBDD instance to wrap, not {@code null}
   *
   * @return  thread-safe ZBDD instance, never {@code null}
   *
   * @see #asConcurrent(Zbdd.WithCache)
   * @see #asConcurrent(Zbdd.Native)
   */
  @Contract(pure = true)
  public static @NotNull Zbdd.Concurrent asConcurrent(@NotNull Zbdd zbdd)
  {
    return switch(zbdd) {
      case Zbdd.Concurrent concurrent -> concurrent;
      case Zbdd.WithCache withCache -> new ZbddConcurrent.WithCache(withCache);
      case Zbdd.Native nativeZbdd -> new ZbddConcurrent.Native(nativeZbdd);
      default -> new ZbddConcurrent(zbdd);
    };
  }


  /**
   * Wraps the given cached {@code zbdd} instance to make it thread-safe while preserving cache support. If the
   * instance is already concurrent, it is returned as-is. After wrapping, access the underlying instance only through
   * the returned wrapper; direct access through another reference bypasses synchronization.
   *
   * @param zbdd  cached ZBDD instance to wrap, not {@code null}
   *
   * @param <T>   return type that is both {@link Zbdd.Concurrent concurrent} and {@link Zbdd.WithCache cached}
   *
   * @return  thread-safe cached ZBDD instance, never {@code null}
   */
  @Contract(pure = true)
  @SuppressWarnings("unchecked")
  public static <T extends Zbdd.Concurrent & Zbdd.WithCache> @NotNull T asConcurrent(@NotNull Zbdd.WithCache zbdd) {
    return (T)(zbdd instanceof Zbdd.Concurrent ? zbdd : new ZbddConcurrent.WithCache(zbdd));
  }


  /**
   * Wraps the given native {@code zbdd} instance to make it thread-safe, while it can still be
   * {@linkplain Zbdd.Native#close() closed}. If the instance is already concurrent, it is returned as-is. After wrapping,
   * access the underlying instance only through the returned wrapper; direct access through another reference bypasses
   * synchronization.
   *
   * @param zbdd  native ZBDD instance to wrap, not {@code null}
   *
   * @param <T>   return type that is both {@link Zbdd.Concurrent concurrent} and {@link Zbdd.Native native}
   *
   * @return  thread-safe native ZBDD instance, never {@code null}
   *
   * @since 0.8.0
   */
  @Contract(pure = true)
  @SuppressWarnings("unchecked")
  public static <T extends Zbdd.Concurrent & Zbdd.Native> @NotNull T asConcurrent(@NotNull Zbdd.Native zbdd) {
    return (T)(zbdd instanceof Zbdd.Concurrent ? zbdd : new ZbddConcurrent.Native(zbdd));
  }
}
