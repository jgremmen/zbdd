# Version [0.8.0](https://github.com/jgremmen/zbdd/tree/0.8.0) (2026-11-02)

## Breaking Changes

### `ZbddVisitor` renamed to `CubeZbddVisitor`

The functional interface `Zbdd.ZbddVisitor`, used by `Zbdd#visitCubeZbdds(int, ...)`, has been renamed to
`Zbdd.CubeZbddVisitor` to make its relation to `Zbdd.CubeVisitor` clear. Its single method `visitZbdd(int)` is
unchanged. Code that only passes a lambda continues to compile; code that names the type must be updated:

```java
// Before (0.7.x):
Zbdd.ZbddVisitor visitor = cube -> true;

// After (0.8.0):
Zbdd.CubeZbddVisitor visitor = cube -> true;
```

### `Zbdd.Concurrent#doAtomic(Consumer)` renamed to `runAtomic(Consumer)`

The two overloads `doAtomic(Function)` and `doAtomic(Consumer)` made many lambdas ambiguous, as a lambda such as
`z -> z.incRef(r)` is compatible with both. The `Consumer` variant has been renamed to `runAtomic(Consumer)`, while
`doAtomic(Function)` keeps its name. Both methods now reject a `null` operation with a `NullPointerException`:

```java
// Before (0.7.x):
concurrent.doAtomic((Consumer<Zbdd>)z -> z.decRef(r));

// After (0.8.0):
concurrent.runAtomic(z -> z.decRef(r));
```

### `getVarObject(int)` returns `Object`

`Zbdd#getVarObject(int)` was declared as `<T> T getVarObject(int var)`, which performed an unchecked cast to whatever
type the call site expected. A wrong type only surfaced as a `ClassCastException` at a later, unrelated location. The
method now returns `Object`. Use the new `getVarObject(int, Class)` to obtain the object as a specific type; it fails
immediately with a `ClassCastException` naming both types:

```java
// Before (0.7.x):
String label = zbdd.getVarObject(var);

// After (0.8.0):
String label = zbdd.getVarObject(var, String.class);
```

### Node lookup counters return `long`

`ZbddStatistics#getNodeLookups()` and `ZbddStatistics#getNodeLookupHitCount()` now return `long` instead of `int`.
The `int` counters overflowed after about 2 billion lookups, which long running applications reach easily, and then
produced negative values and meaningless hit ratios. Code assigning the results to an `int` variable must be changed
to use `long`.

### `count()` throws `ArithmeticException` on overflow

The number of cubes in a ZBDD can grow exponentially with the number of variables. `Zbdd#count(int)` silently
overflowed and returned a wrong, possibly negative number if the result exceeded `Integer.MAX_VALUE`. It now throws
an `ArithmeticException` instead. Use the new `countExact(int)` if the number of cubes may exceed the `int` range:

```java
BigInteger cubes = zbdd.countExact(family);
```

### `divide()` and `modulo()` by the empty set throw `ZbddException`

Dividing by the empty set is undefined. `Zbdd#divide(int, int)` and `Zbdd#modulo(int, int)` previously accepted
`Zbdd.EMPTY` as divisor and returned an arbitrary result. Both methods now throw a `ZbddException` if the divisor is
the empty set. Callers that may pass an empty divisor must check for it before invoking the operation:

```java
int quotient = Zbdd.isEmpty(q) ? Zbdd.EMPTY : zbdd.divide(p, q);
```

### `contains()` treats the empty set as contained in every set

`Zbdd#contains(int p, int q)` returned `false` whenever `p` or `q` was the empty set. Mathematically, the empty set is
a subset of every set, including itself. The method now returns `true` if `q` is `Zbdd.EMPTY`, and returns `false`
for `contains(EMPTY, q)` only if `q` is not empty. Code that relied on `contains(p, EMPTY)` being `false` must test for
the empty set explicitly:

```java
boolean nonEmptySubset = !Zbdd.isEmpty(q) && zbdd.contains(p, q);
```

In addition, `contains()` no longer creates zbdd nodes internally. It is now a read-only operation that cannot trigger
a garbage collection or a capacity increase.

### `getNode()` enforces the variable order

`Zbdd#getNode(int var, int p0, int p1)` accepted any combination of variable and branch nodes, so it was possible to
create nodes that violate the ZBDD variable order and break all subsequent operations on them. The method now throws an
`InvalidZbddException` if the variable of `p0` or `p1` is not lower than `var`. The terminal nodes `Zbdd.EMPTY` and
`Zbdd.BASE` remain valid for any variable.

### A `ZbddCache` can no longer be shared between zbdd instances

Cached results are keyed by node numbers, which are only meaningful within a single zbdd instance. Sharing one cache
between instances silently produced wrong results. `ZbddFactory#createCached(...)` and
`Zbdd.WithCache#setZbddCache(ZbddCache)` now throw a `ZbddException` if the cache is already in use by another zbdd
instance. A cache becomes available again once it has been replaced by another cache in its zbdd instance, or once
that instance is no longer reachable. Each zbdd instance requires its own cache:

```java
var zbdd1 = ZbddFactory.createCached(new ZbddFastCache(65536));
var zbdd2 = ZbddFactory.createCached(new ZbddFastCache(65536));
```

Ownership is tracked using `equals()` and `hashCode()`, so custom `ZbddCache` implementations should keep the identity
based implementations of these methods.

### Restricted use of a zbdd instance from callbacks and visitors

Re-entrant use of a zbdd instance is now defined and guarded. A `Zbdd.ZbddCallback` may still use the instance it is
registered with, including the creation of nodes and the registration of further callbacks. However, `gc()`, `clear()`
and `calculateNodeDependency()` now throw a `ZbddException` if invoked from a callback. While a callback is running,
new nodes are provided by increasing the node capacity instead of by garbage collection.

Likewise, `clear()` now throws a `ZbddException` while cubes are visited, i.e. when invoked from a `CubeVisitor`, a
`CubeZbddVisitor` or the `ZbddLiteralResolver` used by `toString(int)`. Previously, the traversal continued on the
discarded nodes. Defer such calls until the callback or visitor has returned.

### Changed node statistics and reference counts

ZBDD operations no longer protect their operands by incrementing and decrementing the reference count. Operands are
now protected by an internal mechanism that leaves the reference count unchanged. Previously, passing a new node (one
whose reference count was never incremented) to any operation turned it into a dead node. Such nodes now remain new,
so `ZbddNodeInfo#isNewNode()` and `ZbddNodeInfo#isDeadNode()` report different results for them.

`ZbddStatistics#getAvailableNodes()` previously returned the sum of free and dead nodes. It now also includes new
nodes and is defined as `getFreeNodes() + getUnreferencedNodes()`. Consequently, `getOccupiedNodes()` returns lower
values. In addition, `getDeadNodes()` now includes dead nodes that survived a garbage collection because they are
reachable from a referenced node; previously it was reset to `0` after every garbage collection. Custom
`ZbddCapacityAdvisor` implementations basing their decisions on these values may need to be reviewed.

### `ZbddFactory` cannot be instantiated

`ZbddFactory` only contains static methods, but its implicit public constructor allowed `new ZbddFactory()`. The
constructor is now private. Use the static factory methods directly.

## New Features

### Predefined capacity advisors

`ZbddCapacityAdvisor` now offers predefined advisors as constants, each making a different trade-off between memory
usage and performance:

| Advisor            | Purpose                                         |
|--------------------|-------------------------------------------------|
| `DEFAULT`          | The advisor used if none is provided            |
| `MINIMAL_MEMORY`   | The smallest node table, at a high cost         |
| `LOW_MEMORY`       | Large memory savings at a moderate cost         |
| `BALANCED`         | Memory savings with hardly any loss of speed    |
| `HIGH_PERFORMANCE` | The fastest, at the cost of a larger node table |

The Javadoc of each constant describes its growth rate, the percentage of nodes left free after a garbage collection
and the garbage collection threshold, as well as the measured effect on table size and run time relative to
`DEFAULT`:

```java
Zbdd zbdd = ZbddFactory.create(ZbddCapacityAdvisor.BALANCED);
```

The new constant `ZbddCapacityAdvisor.MAX_CAPACITY` holds the maximum node capacity of a zbdd instance. Neither the
initial capacity nor a capacity increase can exceed it.

### `ZbddCapacityExhaustedException`

If a new node is required, but no free node is left and the capacity advisor does not increase the capacity (or the
capacity has reached `MAX_CAPACITY`), the operation now fails with the new `ZbddCapacityExhaustedException`. It
extends `ZbddException`, so existing handlers continue to work. The capacity at the time of the failure is available
through `getNodesCapacity()`:

```java
try {
  int r = zbdd.union(p, q);
} catch(ZbddCapacityExhaustedException ex) {
  log.warn("node table full at {} nodes", ex.getNodesCapacity());
}
```

### `subset0()` for multiple variables

The new method `Zbdd#subset0(int zbdd, int... vars)` returns all combinations of `zbdd` that contain none of the given
variables. The result equals applying `subset0(int, int)` for each variable in turn, but the ZBDD is traversed only
once for all variables, which is considerably faster if many variables are filtered out. Duplicate variables are
ignored, and if no variables are given, `zbdd` is returned unchanged:

```java
int r = zbdd.subset0(family, a, b, c);
```

For cached instances, the results are stored in the cache using the new operation
`ZbddCache.Operation2.SUBSET0_CUBE`. Custom `ZbddCache` implementations that switch over all operations must handle
this constant.

### `countExact()`

The new method `Zbdd#countExact(int)` returns the exact number of cubes in a ZBDD as a `BigInteger`, regardless of its
magnitude. It is slower than `count(int)` and its results are not cached, so it should only be used if the number of
cubes may exceed `Integer.MAX_VALUE`:

```java
BigInteger all = zbdd.countExact(family);
```

### Type-checked `getVarObject(int, Class)`

The new default method `Zbdd#getVarObject(int var, Class<T> type)` returns the object associated with a variable,
cast to the given type. A mismatch is reported immediately with a `ClassCastException`:

```java
int var = zbdd.createVar(new Item("apple"));
Item item = zbdd.getVarObject(var, Item.class);
```

### New node statistics

`ZbddStatistics` provides three new methods. `getNewNodes()` returns the number of nodes whose reference count has
never been incremented, such as intermediate results and unprotected operation results. `getUnreferencedNodes()`
returns the sum of new and dead nodes, which is an upper limit of what a garbage collection can free.
`getNodesAllocatedSinceGC()` returns the number of nodes allocated since the last garbage collection, which is a
better estimate of what the next garbage collection will free. Custom capacity advisors can use it to decide whether a
garbage collection is worthwhile:

```java
@Override
public boolean isGCRequired(@NotNull ZbddStatistics statistics)
{
  return statistics.getNodesAllocatedSinceGC() >
      statistics.getNodesCapacity() / 10;
}
```

The `toString()` representation of the statistics now includes the number of new nodes.

### Exception improvements

`ZbddException` has a new constructor `ZbddException(String message, Throwable cause)`. All exception classes now
declare an explicit `serialVersionUID`, so their serialized form remains compatible across future versions.

### Callbacks may register further callbacks

`Zbdd#registerCallback(ZbddCallback)` may now be invoked from within a callback. The newly registered callback is
invoked starting with the next event. Previously, this failed with a `ConcurrentModificationException`.

## Bug Fixes

- `clear()` did not reset the hash chain heads stored in the records of the terminal nodes `EMPTY` and `BASE`. After a
  clear, node lookups in the two affected hash buckets started at node slots that had been discarded, traversing the
  free list and corrupting the hash chains of the node table. Both chain heads are now reset by `clear()`.

- `ZbddFactory#asConcurrent(Zbdd)` created a new wrapper with its own lock on every invocation. Two wrappers obtained
  for the same instance therefore did not exclude each other, and concurrent access through them was not thread-safe.
  In addition, wrapping a cached instance with `asConcurrent(Zbdd)` returned a wrapper without `Zbdd.WithCache`
  support. Every zbdd instance now has exactly one concurrent wrapper, which is returned by all invocations and
  preserves cache support.

- For cached instances, the operation cache was cleared by a callback registered before any user callback. Nodes
  created by a user `beforeGc` or `beforeClear` callback were thus cached and the cache kept referring to them after
  the garbage collection or clear had freed their node numbers, leading to wrong operation results. The cache is now
  cleared after all `beforeGc` and `beforeClear` callbacks have been invoked.

- If a `beforeGc` callback, invoked by a garbage collection triggered while creating a node, created that very node,
  the node was created a second time. Duplicate nodes violate the canonical form of ZBDDs and break equality checks
  on node numbers. The node table is now searched again after such a garbage collection.

- All set operations (`subset0`, `subset1`, `change`, `union`, `intersect`, `difference`, `multiply`, `divide`,
  `modulo`, `atomize` and `removeBase`) were implemented recursively, using one Java stack frame per level. For large or
  deeply nested ZBDDs, they failed with a `StackOverflowError`. All operations now use explicit, heap allocated stacks.

- If an operation failed with an exception, the reference counts incremented internally to protect its operands and
  intermediate results were not decremented again, so these nodes were never garbage collected. Operands are now
  protected by a mechanism that is released reliably, even if the operation fails. Likewise, a failing node capacity
  increase (e.g. due to an `OutOfMemoryError`) or a failing garbage collection now leaves the zbdd instance intact.

- A garbage collection triggered while a callback was running invoked the callbacks recursively. Garbage collection is
  now suspended while callbacks are running, and new nodes are provided by increasing the capacity instead.

- `divide()` checked its trivial cases in the wrong order, so `divide(BASE, BASE)` returned the empty set instead of
  the base set. As a consequence, `modulo(BASE, BASE)` returned the base set instead of the empty set.

- `count()` enumerated every path of the ZBDD, taking exponential time for ZBDDs with many shared nodes. It now
  computes the count of each node only once. For cached instances, the count of every visited node is cached, not only
  that of the root node.

- `hasCubeWithVar()` visited shared nodes repeatedly, taking exponential time in the worst case. For variables far
  apart, the size of its internal stack overflowed and the method failed with a `NegativeArraySizeException`. Each node
  is now visited only once, and the stack grows on demand.

- A capacity advisor returning a negative increment shrank the node table, discarding nodes in use and corrupting the
  instance. Negative increments are now treated as `0`. An initial capacity above the supported maximum overflowed the
  size of the node array; it is now limited to `ZbddCapacityAdvisor.MAX_CAPACITY`, which also respects the maximum
  array size of the JVM.

- The default capacity advisor performed a garbage collection whenever the node table was full and held more than
  250000 nodes, regardless of how many nodes could be freed, and required only 5% of free nodes afterward. This caused
  frequent garbage collections that freed hardly any nodes. It now bases its decision on the number of nodes likely to
  be freed and leaves 25% of the nodes free after a garbage collection.

- The automatic garbage collection was only considered if there was at least one dead node. Unreferenced new nodes,
  such as unprotected operation results, were not taken into account, so the node table could grow without ever
  being garbage collected. New nodes are now considered as well.

- A variable with the number `Integer.MAX_VALUE` could not be distinguished from a free node slot while being marked
  during garbage collection. The highest supported variable number is now `Integer.MAX_VALUE - 1`, and `createVar()`
  throws a `ZbddException` once it has been reached.

- `Zbdd.Concurrent#doAtomic(Function)` passed the raw, unsynchronized zbdd instance to the operation. Retaining that
  reference, for example in a callback or another lambda, bypassed the lock. The operation now receives the concurrent
  wrapper itself.

- `ZbddNodeInfo#getLiteral()` and `ZbddNodeInfo#toString()` passed the variable `-1` to the literal resolver for the
  terminal nodes. `getLiteral()` now returns an empty string for `EMPTY` and the base name of the literal resolver for
  `BASE`.

- `ZbddStatistics#getNodeLookupHitRatio()` and `getNodeLookupMissRatio()` returned `NaN` if no lookups had been
  performed yet. Both now return `0.0`. The hit ratio printed by `ZbddFastCache#toString()` has been fixed likewise.

- `ZbddStatistics#getMemoryUsage()` did not include the memory used for the objects associated with variables.

- `toString(EMPTY)` returned `"{  }"` with two spaces; it now returns `"{ }"`.

- `visitCubes(EMPTY, null)` returned `true` instead of rejecting the `null` visitor with a `NullPointerException`.
