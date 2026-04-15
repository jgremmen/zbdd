# Version [0.4.0](https://github.com/jgremmen/zbdd/releases/tag/0.4.0) (2025-07-15)

## Breaking Changes

- **Cache functionality moved from `Zbdd` to `ZbddCached`.**
  The methods `setZbddCache(ZbddCache)` and `getZbddCache()` have been removed from the `Zbdd` class. All
  cache-related ZBDD operations now reside in the new `ZbddCached` class (in the `de.sayayi.lib.zbdd.cache` package),
  which extends `Zbdd`. Code that previously called `zbdd.setZbddCache(...)` on a `Zbdd` instance must now create a
  `ZbddCached` instance instead:

  ```java
  // Before (0.3.0):
  Zbdd zbdd = new Zbdd();
  zbdd.setZbddCache(new ZbddFastCache(8192));

  // After (0.4.0):
  ZbddCached zbdd = new ZbddCached();
  zbdd.setZbddCache(new ZbddFastCache(8192));
  ```

  If your code does not use caching, you can continue to use the `Zbdd` class as before. The `Zbdd` class no longer
  has any internal references to the cache, and all protected cache-related methods (`__union_cache`, `__intersect_cache`,
  etc.) have been moved to `ZbddCached`.

- **`ZbddFastCache` deprecated for removal.**
  The `ZbddFastCache` class has been marked `@Deprecated(forRemoval = true)`. It remains functional but should be
  replaced with an alternative cache implementation in future versions.

## New Features

- **`hasCubeWithVar(int zbdd, int var)` method.**
  A new method `hasCubeWithVar` has been added to `Zbdd`. It checks whether the given zbdd set contains at least one
  cube that includes the specified variable. This is a pure (non-mutating) operation:

  ```java
  int v1 = zbdd.createVar();
  int v2 = zbdd.createVar();
  int c = zbdd.cube(v1, v2);
  boolean has = zbdd.hasCubeWithVar(c, v1);  // true
  ```

- **`calculateNodeDependency()` method.**
  A new method `calculateNodeDependency` has been added to `Zbdd`. It returns an array describing the generation
  sequence for all zbdd nodes, ordered so that each node only references nodes with a lower index in the array.
  This is useful for serialization or analysis of the ZBDD structure. Note: this method always performs a garbage
  collection.

## Bug Fixes

- Fixed the `difference` operation for the case where `p_var > q_var`, which could produce incorrect results.

