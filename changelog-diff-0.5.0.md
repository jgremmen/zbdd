# Version [0.5.0](https://github.com/jgremmen/zbdd/releases/tag/0.5.0) (2025-11-01)

## Breaking Changes

### `Zbdd` refactored from class to interface

The `Zbdd` class has been redesigned as an interface. All implementation details have been moved to non-exported
internal classes. Code that previously instantiated `Zbdd` directly must now use `ZbddFactory`:

```java
// Before (0.4.x):
Zbdd zbdd = new Zbdd();
Zbdd zbdd = new Zbdd(capacityAdvisor);

// After (0.5.0):
Zbdd zbdd = ZbddFactory.create();
Zbdd zbdd = ZbddFactory.create(capacityAdvisor);
```

As a consequence, the following changes apply:

#### `empty()` and `base()` are now static

Both methods are now static on the `Zbdd` interface. Replace `zbdd.empty()` with `Zbdd.empty()` and `zbdd.base()`
with `Zbdd.base()`.

#### Interface constants

`ZBDD_EMPTY` and `ZBDD_BASE`, previously `protected static final` fields, are now public interface constants
(`Zbdd.ZBDD_EMPTY` and `Zbdd.ZBDD_BASE`).

#### Node access methods are now public

`getVar(int)`, `getP0(int)`, `getP1(int)`, `getNode(int, int, int)`, `incRef(int)` and `decRef(int)` are now part
of the public `Zbdd` interface. These were previously `protected final` methods on the `Zbdd` class.

### Clone support removed

The `clone()` method has been removed. The `Zbdd` interface does not extend `Cloneable`.

If you relied on cloning a `Zbdd` instance, create a new instance via `ZbddFactory` and reconstruct the required
state using `calculateNodeDependency()` to obtain the node generation order.

### `ZbddCached` replaced by `Zbdd.WithCache`

The concrete class `ZbddCached` (in `de.sayayi.lib.zbdd.cache`) has been removed and replaced by the
`Zbdd.WithCache` interface. Use `ZbddFactory.createCached(...)` to obtain a cached instance:

```java
// Before (0.4.x):
ZbddCached zbdd = new ZbddCached();
zbdd.setZbddCache(new ZbddFastCache(8192));

// After (0.5.0):
Zbdd.WithCache zbdd = ZbddFactory.createCached(new ZbddFastCache(8192));
```

The `Zbdd.WithCache` interface exposes `getZbddCache()` and `setZbddCache(ZbddCache)`.

### `ZbddException` moved to `exception` package

`ZbddException` has been moved from `de.sayayi.lib.zbdd` to `de.sayayi.lib.zbdd.exception`. Update your imports
accordingly. The `de.sayayi.lib.zbdd.exception` package is now exported in `module-info.java`.

Operations that previously threw a generic `ZbddException` for invalid arguments now throw more specific exceptions:

- `InvalidVarException` - thrown when an invalid variable identifier is used.
- `InvalidZbddException` - thrown when an invalid zbdd node reference is encountered.

### `ZbddFastCache` deprecation removed

`ZbddFastCache` is no longer marked `@Deprecated(forRemoval = true)`. The deprecation introduced in 0.4.0 has been
reverted.


## New Features

### `ZbddFactory`

A new factory class `ZbddFactory` provides static methods for creating `Zbdd` instances with different
configurations:

```java
// Basic instance (no caching, not thread-safe):
Zbdd zbdd = ZbddFactory.create();

// With custom capacity advisor:
Zbdd zbdd = ZbddFactory.create(myAdvisor);

// With operation caching:
Zbdd.WithCache zbdd = ZbddFactory.createCached(new ZbddFastCache(8192));

// Thread-safe wrapper:
Zbdd.Concurrent safe = ZbddFactory.asConcurrent(zbdd);
```

### Thread-safe ZBDD via `Zbdd.Concurrent`

The new `Zbdd.Concurrent` interface marks a thread-safe `Zbdd` instance. Concurrent instances are created using
`ZbddFactory.asConcurrent(...)`. The `Concurrent` interface adds an `atomic(...)` method that allows performing
multiple operations within a single locked context:

```java
Zbdd.Concurrent zbdd = ZbddFactory.asConcurrent(ZbddFactory.create());

int result = zbdd.atomic(z -> {
  int v1 = z.createVar();
  int v2 = z.createVar();
  return z.union(z.cube(v1), z.cube(v2));
});
```

A variant accepting a `Consumer` is also available for operations that do not return a value.

For cached instances, use `ZbddFactory.asConcurrent(Zbdd.WithCache)` to obtain a wrapper that is both thread-safe
and cached.

### Variable-associated objects

Variables can now carry an associated object. Use `createVar(Object)` to create a variable with an object and
`getVarObject(int)` to retrieve it:

```java
Zbdd zbdd = ZbddFactory.create();
int row3 = zbdd.createVar("Row 3");
String name = zbdd.getVarObject(row3);  // "Row 3"
```

Variables created with `createVar()` (without an object) return `null` from `getVarObject`.

### Callback mechanism

`registerCallback(ZbddCallback)` allows registering callback instances that are notified before and after
`clear()` and garbage collection:

```java
zbdd.registerCallback(new Zbdd.ZbddCallback() {
  @Override
  public void beforeGc() {
    System.out.println("GC starting");
  }

  @Override
  public void afterGc() {
    System.out.println("GC finished");
  }
});
```

All callback methods have default (empty) implementations.

### Validation methods

Two new methods allow checking whether a zbdd node or variable is valid without throwing an exception:

- `isValidZbdd(int)` - returns `true` if the zbdd node identifier is valid.
- `isValidVar(int)` - returns `true` if the variable identifier is valid.

### Node inspection via `getZbddNodeInfo`

`getZbddNodeInfo(int)` returns a `ZbddNodeInfo` object that provides a live view of a zbdd node, including its
variable, 0-branch, 1-branch, reference count and literal name.

### Capacity increase count in statistics

`ZbddStatistics.getCapacityIncreaseCount()` returns the number of times the internal node array capacity has been
increased since the last `clear()`.


## Bug Fixes

- Fixed an internal issue where `getNode(...)` did not use the optimized lookup path when `var`, `p0` and `p1` were
  already validated.
- Fixed the cube visitor stack to grow on demand instead of using a fixed initial size.

