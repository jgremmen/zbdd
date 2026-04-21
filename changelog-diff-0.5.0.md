# Version [0.5.0](https://github.com/jgremmen/zbdd/tree/0.5.0) (2025-11-01)

## Breaking Changes

### `Zbdd` refactored from class to interface

The `Zbdd` class has been converted to an interface. All implementation details (node storage, hash chains, reference
counting internals, etc.) have been moved to internal implementation classes that are not part of the public API.

Code that previously instantiated `Zbdd` directly must now use `ZbddFactory`:

```java
// Before (0.4.x):
Zbdd zbdd = new Zbdd();
Zbdd zbdd = new Zbdd(capacityAdvisor);

// After (0.5.0):
Zbdd zbdd = ZbddFactory.create();
Zbdd zbdd = ZbddFactory.create(capacityAdvisor);
```

Similarly, `ZbddCached` (which was a class extending `Zbdd`) has been replaced by the `Zbdd.WithCache` interface.
The `ZbddCached` class in `de.sayayi.lib.zbdd.cache` no longer exists:

```java
// Before (0.4.x):
ZbddCached zbdd = new ZbddCached();
zbdd.setZbddCache(new ZbddFastCache(8192));

// After (0.5.0):
Zbdd.WithCache zbdd = ZbddFactory.createCached(new ZbddFastCache(8192));
```

The `Zbdd.WithCache` interface provides `getZbddCache()` and `setZbddCache(ZbddCache)` for changing the cache at
runtime.

### `Zbdd.clone()` removed

Clone support has been dropped entirely. The `Zbdd` class (now interface) no longer implements `Cloneable`, and
the `clone()` method and the protected copy constructor are no longer available. If you need to replicate a ZBDD
state, use `calculateNodeDependency()` in combination with the node accessor methods to reconstruct it.

### `empty()` and `base()` are now static methods

The `empty()` and `base()` methods on `Zbdd` are now static interface methods. Since `Zbdd` is an interface, they
can no longer be called on an instance:

```java
// Before (0.4.x):
int e = zbdd.empty();
int b = zbdd.base();

// After (0.5.0):
int e = Zbdd.empty();
int b = Zbdd.base();
```

The constants `ZBDD_EMPTY` and `ZBDD_BASE` remain available as interface constants (`Zbdd.ZBDD_EMPTY`,
`Zbdd.ZBDD_BASE`).

### `MAX_NODES` constant removed

The `Zbdd.MAX_NODES` constant has been removed. The `@Range` annotations on method parameters and return values
have been removed as well.

### `ZbddException` moved to `de.sayayi.lib.zbdd.exception`

The `ZbddException` class has been relocated from package `de.sayayi.lib.zbdd` to `de.sayayi.lib.zbdd.exception`.
Update your import statements accordingly:

```java
// Before (0.4.x):
import de.sayayi.lib.zbdd.ZbddException;

// After (0.5.0):
import de.sayayi.lib.zbdd.exception.ZbddException;
```

The new `exception` package is exported in `module-info.java`.

### Exception hierarchy for invalid zbdd/var parameters

Methods that previously threw a generic `ZbddException` for invalid zbdd node or variable parameters now throw
specific exception subclasses:

- `InvalidZbddException` (extends `ZbddException`) - thrown when an invalid zbdd node is passed as parameter.
  Provides `getInvalidZbdd()` to retrieve the offending value.
- `InvalidVarException` (extends `ZbddException`) - thrown when an invalid variable number is passed as parameter.
  Provides `getInvalidVar()` to retrieve the offending value.

Existing catch blocks for `ZbddException` will continue to work, but you can now catch these subtypes for more
specific error handling.

### Protected methods no longer accessible

Since `Zbdd` is now an interface, the previously protected methods (prefixed with `__`) such as `__union`, `__intersect`,
`__subset0`, `__subset1`, `__change`, `__count`, `__difference`, `__multiply`, `__divide`, `__modulo`, `__atomize`,
`__removeBase`, `__contains`, `__incRef`, `__decRef`, `__hasCubeWithVar`, `checkZbdd`, `checkVar`, `getNode`, `getVar`,
`getP0`, `getP1`, and `hash` are no longer part of the public API. The methods `getVar`, `getP0`, `getP1`, and
`getNode` are exposed on the `Zbdd` interface as public methods.

### `DefaultCapacityAdvisor` moved to internal package

The `DefaultCapacityAdvisor` enum, previously a nested type inside `Zbdd`, has been moved to
`de.sayayi.lib.zbdd.internal`. This package is not exported and therefore not accessible. If you were referencing
`Zbdd.DefaultCapacityAdvisor.INSTANCE`, pass `null` as the capacity advisor to `ZbddFactory.create(null)` to use
the default.


## New Features

### `ZbddFactory` for creating zbdd instances

The new `ZbddFactory` class provides static factory methods for all zbdd creation scenarios:

- `ZbddFactory.create()` - creates a basic zbdd instance with default capacity advisor
- `ZbddFactory.create(ZbddCapacityAdvisor)` - creates a basic zbdd instance with custom capacity advisor
- `ZbddFactory.createCached(ZbddCache)` - creates a caching zbdd instance
- `ZbddFactory.createCached(ZbddCapacityAdvisor, ZbddCache)` - creates a caching zbdd instance with custom advisor
- `ZbddFactory.asConcurrent(Zbdd)` - wraps an existing zbdd instance to make it thread-safe
- `ZbddFactory.asConcurrent(Zbdd.WithCache)` - wraps an existing cached zbdd instance to make it thread-safe

### Thread-safe concurrent zbdd via `Zbdd.Concurrent`

The `Zbdd.Concurrent` interface extends `Zbdd` and adds thread safety. A concurrent instance can be created by
wrapping any existing `Zbdd` or `Zbdd.WithCache` instance through the factory:

```java
Zbdd.Concurrent concurrentZbdd = ZbddFactory.asConcurrent(ZbddFactory.create());
```

To perform multiple zbdd operations atomically (without another thread interleaving), use `doAtomic`:

```java
int result = concurrentZbdd.doAtomic(zbdd -> {
  int v1 = zbdd.createVar();
  int v2 = zbdd.createVar();
  return zbdd.cube(v1, v2);
});
```

### `registerCallback(ZbddCallback)` method

A new `registerCallback` method allows registering a `Zbdd.ZbddCallback` instance. The callback interface provides
hooks that are invoked before and after `clear()` and `gc()` operations:

```java
zbdd.registerCallback(new Zbdd.ZbddCallback() {
  @Override
  public void beforeGc() {
    // invoked before garbage collection
  }

  @Override
  public void afterGc() {
    // invoked after garbage collection
  }
});
```

### Variable-associated objects via `createVar(Object)` and `getVarObject(int)`

Variables can now carry an associated object. Use `createVar(Object)` to create a variable with an associated object,
and `getVarObject(int)` to retrieve it:

```java
int var = zbdd.createVar("temperature");
String obj = zbdd.getVarObject(var);  // "temperature"
```

Variables created with the no-argument `createVar()` return `null` from `getVarObject`.

### `isValidZbdd(int)` and `isValidVar(int)` methods

Two new validation methods allow checking whether a zbdd node or variable number is valid without throwing an
exception:

```java
boolean validNode = zbdd.isValidZbdd(someZbdd);
boolean validVar = zbdd.isValidVar(someVar);
```

### `getZbddNodeInfo(int)` method

The new `getZbddNodeInfo` method returns a `Zbdd.ZbddNodeInfo` object that provides a live view of a zbdd node.
It exposes the node's var, p0, p1, reference count, literal, and whether the node is new or dead:

```java
Zbdd.ZbddNodeInfo info = zbdd.getZbddNodeInfo(node);
int var = info.getVar();
int refCount = info.getReferenceCount();
boolean dead = info.isDeadNode();
String literal = info.getLiteral();
```

If the node has been garbage collected, methods on the `ZbddNodeInfo` instance throw an exception.

### `getCapacityIncreaseCount()` in `ZbddStatistics`

The `ZbddStatistics` interface now includes a `getCapacityIncreaseCount()` method that returns the number of times
the internal node capacity has been increased.


## Bug Fixes

- Fixed cube visitor stack size to grow on demand instead of using a fixed size based on the variable count, which
  could cause an `ArrayIndexOutOfBoundsException` for large zbdd structures.
- Improved initial cube visitor stack size estimation based on the zbdd structure, reducing unnecessary allocations.
- Fixed internal node creation to use the unchecked `__getNode(...)` when var, p0 and p1 are already known to be
  valid, eliminating redundant validation overhead.

