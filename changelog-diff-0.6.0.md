# Version [0.6.0](https://github.com/jgremmen/zbdd/tree/0.6.0) (2026-04-21)

## Breaking Changes

### Minimum Java version raised to 21

The minimum required Java version has been raised from 11 to 21. Projects using this library must update their
toolchain accordingly. This change was made to take advantage of sealed classes (used for the `Zbdd` interface
hierarchy). Attempting to use this library with a JDK older than 21 will result in a compilation or runtime error.

### `Zbdd` interfaces are now sealed

The interfaces `Zbdd`, `Zbdd.WithCache`, and `Zbdd.Concurrent` are now sealed. External code can no longer provide
custom implementations of these interfaces. All ZBDD instances must be obtained through `ZbddFactory`. If you
previously created a custom `Zbdd` implementation (e.g. for testing or wrapping), consider using
`ZbddFactory.create()` and delegating to the returned instance instead.

### Constants `ZBDD_EMPTY` and `ZBDD_BASE` renamed

The constants `Zbdd.ZBDD_EMPTY` and `Zbdd.ZBDD_BASE` have been renamed to `Zbdd.EMPTY` and `Zbdd.BASE` respectively.
Replace all references to the old constant names with their new counterparts:

```java
// Before (0.5.x):
if (result == Zbdd.ZBDD_EMPTY) { ... }
int base = Zbdd.ZBDD_BASE;

// After (0.6.0):
if (result == Zbdd.EMPTY) { ... }
int base = Zbdd.BASE;
```

As an alternative, use the static methods `Zbdd.isEmpty(int)`, `Zbdd.isBase(int)`, `Zbdd.empty()` and `Zbdd.base()`:

```java
if (Zbdd.isEmpty(result)) { ... }
int base = Zbdd.base();
```

### `visitCubes` now returns a boolean

The method `visitCubes(int, CubeVisitor)` now returns `boolean` instead of `void`. The return value is `false` if
the visitor aborted traversal, `true` otherwise.

The `CubeVisitor.visitCube(int[])` method likewise now returns `boolean`. Return `true` to continue visiting,
`false` to abort.

```java
// Before (0.5.x):
zbdd.visitCubes(node, vars -> {
  System.out.println(Arrays.toString(vars));
});

// After (0.6.0):
zbdd.visitCubes(node, vars -> {
  System.out.println(Arrays.toString(vars));
  return true; // continue visiting
});
```

### Exception API changes

The exception hierarchy has been restructured to provide more detailed error information.

`InvalidVarException` now requires a `highBoundVar` parameter in its constructor, and the accessor method has been
renamed from `getInvalidVar()` to `getVar()`. Two new methods `getLowBoundVar()` and `getHighBoundVar()` allow
callers to inspect the valid variable range:

```java
try {
  zbdd.subset0(node, var);
} catch(InvalidVarException ex) {
  int invalidVar = ex.getVar();     // was: e.getInvalidVar()
  int low = ex.getLowBoundVar();    // new: always returns 1
  int high = ex.getHighBoundVar();  // new: returns highest registered var
}
```

`InvalidZbddException` is no longer `final`. It now serves as the base class for `ZbddOutOfRangeException`
(see New Features). The accessor `getInvalidZbdd()` has been renamed to `getZbdd()`:

```java
try {
  zbdd.count(node);
} catch(InvalidZbddException ex) {
  int zbddNode = ex.getZbdd();  // was: e.getInvalidZbdd()
}
```

### Internal package renamed from `impl` to `internal`

The `de.sayayi.lib.zbdd.impl` package has been renamed to `de.sayayi.lib.zbdd.internal`. This package was never
exported by the module descriptor and is not part of the public API. Code that relied on implementation classes from
the `impl` package via classpath access (without the module system) will need to update the package reference.

### Dependency changes

| Dependency | Type | 0.5.0 | 0.6.0 |
|---|---|---|---|
| org.jetbrains:annotations | compile | [24.0,26.1) | [24.0,26.2) |

## New Features

### `visitCubeZbdds(int, ZbddVisitor)`

A new method `visitCubeZbdds` visits all elements in a ZBDD set, passing each element as a single-cube ZBDD node
to the visitor. This is useful when you need to work with individual cubes as ZBDD nodes rather than as variable
arrays.

```java
zbdd.visitCubeZbdds(node, cubeZbdd -> {
  // cubeZbdd represents a single cube as a zbdd node
  System.out.println(zbdd.toString(cubeZbdd));
  return true;  // continue visiting
});
```

The `ZbddVisitor` is a new functional interface with a single method `visitZbdd(int)` that returns `boolean`
to control continuation.

### `asSingleCubeZbdds(int)`

Returns an array of ZBDD nodes, where each node represents a single cube from the given ZBDD set. For an empty
ZBDD, the returned array is empty.

```java
int[] cubes = zbdd.asSingleCubeZbdds(node);
for(int cube: cubes)
  System.out.println(zbdd.toString(cube));
```

### `incRef(int[])` and `decRef(int[])`

Convenience methods for incrementing and decrementing reference counts on multiple ZBDD nodes at once.

```java
int[] nodes = new int[] { cubeA, cubeB, cubeC };
zbdd.incRef(nodes);
// ... perform operations ...
zbdd.decRef(nodes);
```

### `ZbddOutOfRangeException`

A new exception class `ZbddOutOfRangeException` (extending `InvalidZbddException`) is thrown when a ZBDD node
identifier is outside the valid range. It provides `getLowBoundZbdd()` and `getHighBoundZbdd()` to inspect the
valid range.

## Bug Fixes

- Fixed incorrect javadoc for `getP0(int)` which stated it returned the 1-branch instead of the 0-branch.

