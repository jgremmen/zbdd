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

import de.sayayi.lib.zbdd.cache.ZbddFastCache;
import org.openjdk.jmh.annotations.*;

import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation1.COUNT;
import static de.sayayi.lib.zbdd.cache.ZbddCache.Operation2.UNION;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import static org.openjdk.jmh.annotations.Scope.Thread;


/**
 * The operation cache by itself. It is filled to a quarter of its size, so lookups of the keys stored are mostly hits
 * and lookups of other keys are misses.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 *
 * @see CacheSizeBenchmark
 */
@State(Thread)
@BenchmarkMode(AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CacheBenchmark
{
  @Param({ "1024", "65536", "1048576" })
  public int size;

  private ZbddFastCache cache;
  private int keys;
  private int next;


  @Setup(Level.Trial)
  public void fillCache()
  {
    cache = new ZbddFastCache(size);
    keys = size / 4;

    for(var key = 0; key < keys; key++)
    {
      cache.putResult(COUNT, key, key);
      cache.putResult(UNION, key, key + 1, key);
    }
  }


  private int nextKey() {
    return next = next + 1 == keys ? 0 : next + 1;
  }


  @Benchmark
  public int getResult1Hit() {
    return cache.getResult(COUNT, nextKey());
  }


  @Benchmark
  public int getResult1Miss() {
    return cache.getResult(COUNT, keys + nextKey());
  }


  @Benchmark
  public int getResult2Hit()
  {
    final var key = nextKey();

    return cache.getResult(UNION, key, key + 1);
  }


  @Benchmark
  public int getResult2Miss()
  {
    final var key = nextKey();

    return cache.getResult(UNION, key, key + 2);
  }


  @Benchmark
  public void putResult1()
  {
    final var key = nextKey();

    cache.putResult(COUNT, key, key);
  }


  @Benchmark
  public void putResult2()
  {
    final var key = nextKey();

    cache.putResult(UNION, key, key + 1, key);
  }


  @Benchmark
  public void clear() {
    cache.clear();
  }
}
