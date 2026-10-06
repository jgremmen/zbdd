# Version [0.7.0](https://github.com/jgremmen/zbdd/tree/0.7.0) (2026-10-06)

## Breaking Changes

### `union()` with an empty array now throws `IllegalArgumentException`

Calling `Zbdd#union(int...)` without any arguments previously read `p[0]` before validating the array length, so the
call failed with an unchecked `ArrayIndexOutOfBoundsException` and a message that gave no indication of the actual
problem. The method now validates the array up front and throws `IllegalArgumentException` instead. Code that
specifically catches `ArrayIndexOutOfBoundsException` around a call to `union()` must be updated to catch
`IllegalArgumentException`:

```java
// Before (0.6.x):
try {
  zbdd.union(nodes);
} catch(ArrayIndexOutOfBoundsException e) {
  // handle empty input
}

// After (0.7.0):
try {
  zbdd.union(nodes);
} catch(IllegalArgumentException e) {
  // handle empty input
}
```

No alternative is needed beyond catching the new exception type, since the new behavior simply replaces an
accidental, undocumented exception with a deliberate, documented one.

### `getLowBoundZbdd()` on `ZbddOutOfRangeException` now returns `EMPTY` instead of `BASE`

`ZbddOutOfRangeException#getLowBoundZbdd()` reported `Zbdd.BASE` (node reference `1`) as the lowest valid ZBDD node
reference. This was incorrect, since `Zbdd.EMPTY` (node reference `0`) is also a permanently valid node reference.
The method now returns `Zbdd.EMPTY`. Any code that used the previous bound to validate or iterate over the full
range of valid node references excluded `EMPTY` and must be adjusted:

```java
// Before (0.6.x): loop started at BASE (1), skipping EMPTY (0)
// After (0.7.0): loop starts at EMPTY (0), the same source line now covers one more value
for(int ref = ex.getLowBoundZbdd(); ref <= ex.getHighBoundZbdd(); ref++) { ... }
```

Code that hard-coded the expectation `getLowBoundZbdd() == Zbdd.BASE` must instead compare against `Zbdd.EMPTY`, or
better, avoid hard-coding either constant and rely solely on the accessor.

### Terminal node reference count and string representation changed

`ZbddNodeInfo#getReferenceCount()` returned `0` (interpreted as "dead") when invoked on the `EMPTY` or `BASE`
terminal nodes, even though these two nodes are permanently alive and can never be garbage collected. Code that
branched on a `0` result to treat a node as dead now receives `1` instead when the node is `EMPTY` or `BASE`:

```java
// Before (0.6.x): returned 0 for EMPTY/BASE, so this branch was taken
if (zbdd.getZbddNodeInfo(Zbdd.EMPTY).getReferenceCount() == 0) {
  // treated as dead
}

// After (0.7.0): returns 1, this branch is no longer taken for EMPTY/BASE
if (zbdd.getZbddNodeInfo(Zbdd.EMPTY).getReferenceCount() == 0) {
  // not reached for terminal nodes anymore
}
```

Use `Zbdd#isEmpty(int)` or `Zbdd#isBase(int)` to detect terminal nodes explicitly instead of relying on their
reference count. As a related, consistent change, `ZbddNodeInfo#toString()` no longer appends a `refCount=...`
segment for terminal nodes at all (it previously printed `refCount=dead`), since reference count has no meaningful
"dead" state for a node that is never collected. Code parsing the output of `toString()` for the presence of a
`refCount` segment must account for its absence on `EMPTY` and `BASE`.

## New Features

*There are no new features in this version.*

## Bug Fixes

- `Zbdd.Concurrent` did not actually guard several operations with its lock, breaking the thread-safety contract
  documented for this wrapper. `incRef(int[])`, `decRef(int[])`, `getZbddCache()` and `setZbddCache(ZbddCache)` were
  delegated directly to the wrapped instance without acquiring the lock first. In addition, `getStatistics()`
  returned the live `ZbddStatistics` of the wrapped instance directly (with a comment claiming "no lock required"),
  so every statistics getter could be read concurrently with a mutating operation. All of these methods are now
  routed through the lock, and `getStatistics()` returns a dedicated delegate that locks on every access.

- Replacing the cache of a `Zbdd.WithCache` instance via `setZbddCache(ZbddCache)` did not take effect for garbage
  collection callbacks. The `beforeClear` and `beforeGc` callbacks captured the `zbddCache` field value at
  construction time in a lambda, so after installing a new cache, the previously installed cache kept being cleared
  on `clear()` and `gc()` instead of the new one. This left the active cache populated with stale results after a
  clear or garbage collection cycle.

- `gc_markReferencedNodes()` (used by `gc()`) and `visitCubes(int, CubeVisitor)` walked the ZBDD node graph using
  unbounded Java call recursion, one stack frame per node depth. For large or deeply chained ZBDD structures, this
  could exhaust the JVM call stack and throw a `StackOverflowError`, aborting the operation entirely. Both methods
  now use an explicit, heap-allocated stack to traverse nodes iteratively instead of recursing, so they complete
  successfully regardless of structure depth.

- `ensureCapacity()` computed the new node capacity as `nodesCapacity + capacityAdvisor.adviseIncrement(statistics)`
  using `int` arithmetic. For a sufficiently large existing capacity and increment, this addition could overflow and
  wrap around to a negative value before being clamped against `MAX_NODES`, corrupting the capacity calculation. The
  addition is now performed using `long` arithmetic before clamping and narrowing back to `int`.

- The internal validity check performed while resolving a ZBDD node (`checkZbdd`) only treated a node slot as
  invalid when its internal variable field was exactly `-1`. The field can also temporarily carry a negative value
  with the sign bit used internally as a mark during garbage collection, which the previous check did not
  recognize as invalid. The condition now rejects any variable field value less than or equal to `0`, closing this
  gap. Under normal single-threaded use this window is not observable, but the stricter check guards against
  resolving a node reference while its storage slot is in an inconsistent internal state.

- `decRef(int)` silently ignored calls that decremented the reference count of a node below zero, i.e. a `decRef`
  without a matching prior `incRef`. Such a call now triggers an `assert` reporting the underflow and the offending
  node, provided assertions are enabled (`-ea`); production builds running without assertions are unaffected.
  Terminal nodes (`EMPTY`, `BASE`) are exempt, since they are never reference counted.
