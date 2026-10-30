# Native ZBDD library

C implementation of the ZBDD core, used by `ZbddFactory.createNative()`. It is a port of `ZbddImpl` with the same
behaviour, including node numbers, statistics and exception messages, and a more compact node layout. Only the node
lookups are lower, as results are cached.

Nothing here is built by hand: `./gradlew build` in the project root compiles the libraries for all targets, runs all
tests and puts the libraries into the jar.

The binding uses JNI. Java 21 is the baseline and `java.lang.foreign` is still a preview there, which would force
`--enable-preview` on every consumer.

## Targets and dependencies

| Target | Library | Imports |
|---|---|---|
| linux-x86_64 | `libzbdd.so` | `malloc`, `realloc`, `free`, `memcpy`, `memset`, without `DT_NEEDED` |
| windows-x86_64 | `zbdd.dll` | `kernel32` only, no C runtime |
| macos-arm64 | `libzbdd.dylib` | `libSystem` only, ad-hoc signed |

The Linux imports resolve from the C library the JVM has already loaded, so there is no glibc version floor. musl should
work too, but hasn't been run. The only function exported is `JNI_OnLoad`. Libraries are built without `-march=native`,
and with the stack protector and sanitizers turned off.

## Source layout

```
native/
  build.gradle            per-target compile and C tests
  include/jni_md.h        own minimal version for all targets, for cross-compiling (jni.h comes from the toolchain JDK)
  include/stdio.h         empty, jni.h includes it without using it and the library is compiled without libc headers
  docker/zig.Dockerfile   compiler image, built by Gradle
  docker/wine.Dockerfile  images for the Windows tests, built by Gradle
  src/
    zb_core.h             context struct, error codes, ops vtable, internal API
    zb_format_small.h     small record layout and inline accessors
    zb_format_large.h     large record layout and inline accessors
    zb_ops.inc            every format dependent algorithm, written once
    zb_small.c            format header + zb_ops.inc, producing zb_ops_small
    zb_large.c            format header + zb_ops.inc, producing zb_ops_large
    zb_ctx.c              lifecycle, stacks, reference count overflow table, int map, 64-bit set, bignum add, sort
    zb_platform_posix.c   malloc family
    zb_platform_win32.c   HeapAlloc family, own memcpy and memset
    zb_jni.c              JNI_OnLoad, RegisterNatives, upcalls (the only file including jni.h)
  test/zb_test.c          C tests without a JVM
```

## Memory formats

Terminal nodes are real records with p0 and p1 pointing to themselves, so reading them needs no branch. Bucket heads are
kept apart from the records, the bucket count is a power of 2 of at least half the capacity, and a lookup uses a
multiplicative hash instead of `% capacity`.

| | Small | Large |
|---|---|---|
| Nodes | up to 32767 | 357,913,941 slots, as `ZbddImpl` |
| Variables | up to 32767 | up to 2³¹−2, as `ZbddImpl` |
| Record | 8 bytes | 16 bytes |
| Bucket head | 2 bytes | 4 bytes |
| Per node, with bucket heads | 9–10 bytes | 18–20 bytes |

The bit layouts are documented in `src/zb_format_small.h` and `src/zb_format_large.h`. `ZbddImpl` uses 24 bytes per
node, so the small format is about 2.5 times smaller and the large format about 20%. A full small instance takes about
288 KB, against 786 KB in Java. The large format saves less as 31-bit variables and 29-bit node numbers need 118 bits.
Packing these into 15 bytes would save about 6% and lose the word alignment, so it stays at 16 bytes.

Node capacity never shrinks, so a stale node number can never index out of bounds. The free list stays in ascending
order, as in `ZbddImpl`, so the same operations and capacity growth produce the same node numbers.

Reference counts are stored in the record up to 5 (small) or 61 (large). Higher counts move to an open-addressing
`uint32 → uint32` table shared by all nodes and allocated on first use. Two things differ from `ZbddImpl`:

- A count reaching 2³¹−1 locks: `incRef` and `decRef` leave it unchanged and throw nothing, so the node stays until
  `clear()`. `ZbddImpl` wraps to a negative count there, which lets a garbage collection free a node that is still
  referenced.
- `incRef` can throw an `OutOfMemoryError`, leaving the count unchanged, if the table can't be allocated or grown.
  `decRef` never allocates.

As in `ZbddImpl`, `decRef` on a dead or new node leaves the count unchanged and only fails an assertion with `-ea`, and
both methods ignore terminal nodes.

A new instance always starts with the small format and changes to the large format when required. The format is an
implementation detail of the C library: Java neither selects it nor can query it. The only exception to starting small
is an initial capacity beyond 32768 nodes, which is only possible with a custom capacity advisor and starts with the
large format. All algorithms are written once in `src/zb_ops.inc`, which is compiled for each format.

## Operation cache

Each `ZbddNative` has an operation cache, which lives entirely in C. It has no connection to `ZbddCache`, so a native
instance is a `Zbdd.Native` and not a `Zbdd.WithCache`.

`ZbddFactory.createNative()` and `createNative(advisor)` request one entry for each node of the initial capacity, so the
cache takes about as much memory as the initial node table. With the default advisor that means 1024 entries and 12 KB.
The cache doesn't grow along with the node table, so `ZbddFactory.createNative(advisor, entries)` requests a size for
larger computations.

The cache is direct mapped: an entry replaces the one at its position. The number of entries is a power of 2 between
1024 and 2³⁰. An entry takes 12 bytes, three 32-bit words:

| Word | Content |
|---|---|
| 0–1 | key: operation in 4 bits, 1st operand in 29 bits, 2nd operand or variable in 31 bits. 0 is an empty entry |
| 2 | result |

| Operation | What is cached |
|---|---|
| `union`, `intersect`, `multiply` | every pair of nodes visited, with ordered operands as they are commutative |
| `difference`, `divide` | every pair of nodes visited |
| `subset0`, `subset1`, `change`, `atomize`, `removeBase` | every node visited |
| `count` | every node, so counts share subgraphs |
| `modulo`, `subset0(vars)`, `contains`, `hasCubeWithVar` | the result of the call |
| `countExact` | every node, in a separate table as the counts don't fit an entry |

The exact counts are limited to the memory of the cache and are dropped when exceeding it. `getMemoryUsage` includes the
cache and the exact counts.

Results refer to node numbers, which are only recycled by a garbage collection or `clear()`. Both empty the cache after
the `beforeGc`/`beforeClear` callbacks and before any node is freed, so a stale result can't be found. Node numbers
don't depend on the format, so the cache survives a format change.

## Changing the format

The change from small to large is automatic and permanent, `clear()` doesn't revert it:

- `zb_create_var` changes the format for variable 32768.
- `ensure_capacity` changes the format when the capacity increase asked for by the advisor exceeds 32768 nodes. The
  capacity is the same as with `ZbddImpl` at all times, so the small format isn't filled up to its last node first. With
  the default advisor this happens going from a capacity of 31250 to 78125.

`zb_migrate` converts the node table and rebuilds the bucket heads. Both tables exist at the same time during the
conversion. If the allocation fails, the operation fails with an `OutOfMemoryError` and the small format stays intact.

Node numbers don't depend on the format and all stacks are format independent, so results of earlier operations stay
valid. A running operation can't continue with the functions of the other format. It returns `ZB_RETRY`, and `ZB_ENTRY`
in `src/zb_ctx.c` runs it again, which is possible as operations are pure functions of the node graph. Nodes created by
the first run are found again. The same happens if user code changes the format during a callback.

An operation running again differs from `ZbddImpl` in one case: if a garbage collection during its first run has freed
intermediate results, they are created again, so later node numbers can differ from those of `ZbddImpl`. Results are
always the same family of sets. Lookup statistics are adjusted for the second run, and are approximate in this case. The
cube iterator advances outside the retry, so it never skips a cube.

## Implementation in C

- **Port of `ZbddImpl`:** this covers the iterative task stacks, protection marks, the mark and sweep of a garbage
  collection, unmarking on failure, growth and the node dependency sequence. There is no recursion, so deep ZBDDs can't
  overflow the C stack of the thread.
- **Formats are separate at compile time:** `ctx->ops` is one vtable dispatched only when entering from JNI, so inner
  loops have no format branch.
- **Almost no allocation per operation:** tasks, results and protected nodes live in growable `int32_t` stacks in the
  context, and each operation works from a saved mark. This also makes nested operations and re-entry from callbacks
  safe. Only the maps and sets of `count`, `contains`, `hasCubeWithVar` and `subset0(vars)` are allocated per call.
- **Errors are negative return codes,** checked at every call, without `setjmp`. The context records the details: kind,
  value, limit and parameter. Node pointers are read again after anything that can grow the table.
- **Memory safety:** each `zbdd` or `var` arriving from Java is checked in C before it is used.

## Java side

`ZbddNative` holds only what C can't: the callback list, the literal resolver, the variable objects and the views for
statistics and node info. Everything else lives in C, including the variable counter.

- **Natives are instance methods taking the handle,** apart from those creating and releasing a context. JNI keeps the
  receiver reachable during the call, so the `Cleaner` can't free the context while an operation is running. Static
  natives would allow that use-after-free.
- **Failures** map to the same exception types and messages as `ZbddImpl`. C returns an error code, then `check()` reads
  the details and throws. The assertion on a reference count underflow stays in Java, driven by a return flag.
- **Visitors** use an iterator: Java asks for the next cube in a loop and closes the iterator in `finally`. No C frames
  sit below user code, so a throwing visitor can't leak. `toString` builds on this, while `asSingleCubeZbdds` runs
  entirely in C.
- **`countExact`** runs in C with multi-limb counts and returns a `byte[]` for `new BigInteger(1, bytes)`.
- **Upcalls** for callbacks and the capacity advisor go through a `JNIEnv` and receiver scoped to the current call, and
  only happen if there are callbacks or a custom advisor. The default advisor is ported to C, so normal use never calls
  back. A pending Java exception becomes an error code, unwinds, and is rethrown when the native call returns.

### Releasing memory

A static `Cleaner` runs a state object holding only the handle, never the instance. C never keeps a JNI global reference
to the instance, as that would make it a GC root and leak it permanently.

`close()` frees the memory through the same `Cleanable`, so this happens exactly once, and later calls throw instead of
reaching C with a dead handle. It fails with a `ZbddException` while a visitor, callback or capacity advisor method is
running, as C frames or an open iterator still use the context then. The wrapper returned by `asConcurrent` is a
`Zbdd.Native` as well: its `close()` takes the lock, so it waits for the operation of another thread to finish, and
later operations of all threads throw.

### Loading the library

`NativeLoader` maps `os.name` and `os.arch` to `META-INF/native/<os>-<arch>/`. An unsupported platform results in a
`ZbddException` naming it.

- **Linux and macOS:** the library is copied to an owner-only temporary file, loaded, and the file is deleted right
  away.
- **Windows:** a loaded DLL can't be deleted, so each load writes a file of its own to `zbdd-native` in
  `%LOCALAPPDATA%`, or in the temporary directory if that isn't available. Sharing no file means several JVMs and class
  loaders can't interfere with each other, and no file written by someone else is loaded. A directory belonging to
  another user is refused, and later loads delete files older than an hour unless they are still loaded.

The first failure is stored, and each later call reports it with a new exception having it as its cause. `ZbddFactory.isNativeAvailable()` returns the outcome instead
of throwing, and attempts the actual load, as the platform alone doesn't show that the library can be used.
`ZbddFactory.create()` never touches this class.

## Build

zig 0.17.0 only ever runs in a container, so building requires Docker and nothing else besides the JDK. The CI job's
image doesn't need zig or Wine. `docker/zig.Dockerfile` builds the compiler image, selecting the zig build for the
architecture of the Docker daemon and verifying its SHA-256 before extracting it. Gradle doesn't depend on the
architecture it runs on.

Sources are copied into each container and results are copied out, so the daemon doesn't need access to the paths of the
build. This also works for a CI job running in a container, as long as it has a Docker CLI and can reach a daemon
(`DOCKER_HOST` or a mounted socket). A named volume keeps the zig cache between builds. The daemon needs to reach the
zig download URL and the Debian package mirrors, but only until Docker has built the images.

`native/` is a Gradle subproject rather than Make or CMake, so it shares the wrapper and up-to-date checks. Tasks reach
both projects, it publishes nothing of its own, and its libraries are exposed to the root project by the `nativeLibs`
configuration:

```
:native:buildZigImage → :native:compileNative{LinuxX64,WindowsX64,MacosArm64} → nativeLibs ┐
                                                                                           ├→ :processResources → :jar
                                                                              :compileJava ┘
```

Each `compileNative<Target>` task declares the C sources, Dockerfile, zig version and flags as inputs, so unchanged C
isn't rebuilt, and writes to `.build/native/libs/<os>-<arch>/`. All three libraries are always built, whatever the host,
and a failed compile fails the build. The native test tasks are in `gradle/native-test.gradle`, and `jar` depends on
them, so a jar is only built after they pass. The main jar contains the libraries and the sources jar contains
`native/`. No change to `publish.gradle` is needed for publishing these.

| Task | Runs in `build` | Purpose |
|---|---|---|
| `:native:compileNative<Target>` | yes | compiles one library |
| `:native:testNativeC` | yes | C tests for the host target |
| `:native:testNativeCWindows` | if the Docker daemon can run `linux/amd64` | C tests for Windows under Wine |
| `testNative` | yes | Java test suite, larger tests cross the format change |
| `testNativeWindows` | if `ZBDD_WINE_JAVA` is set | Java test suite with a Windows JVM under Wine |
| `verifyNativeJar` | yes | all three libraries are in the jar |
| `:native:testNativeSanitized` | no, needs a host clang | C tests with ASan and UBSan |

Native tests are skipped on a host which isn't a target, the jar still contains all libraries.

Wine also only runs in a container, started by Gradle from the images in `docker/wine.Dockerfile`, which are built for
`linux/amd64`, so CI needs no Windows runner. The image for the C tests takes about 2 GB, and Docker reuses it
afterwards. The C tests run under Wine 8 and don't load `zbdd.dll` or use `zb_jni.c`, also through Rosetta on a Mac. The
Java suite uses an image with Wine 10 and a verified Windows JDK, and runs through JUnit's console launcher, as Gradle's
`Test` task passes Unix paths to the JVM it forks. It stays disabled because the Windows JVM crashes under Wine 8 when
JUnit starts, independent of this library, and Wine 10 can't run under Rosetta, so it is untried.

## Tests

- **Existing suite:** `ZbddTestSupport.create()` selects the implementation by system property `zbdd.test.impl`, and
  `testNative` runs the shared test classes against native. Instances start small, so larger tests cross the format
  change. Tests of Java internals stay Java-only, such as `ZbddImplTest` and the `getMemoryUsage` assertions in
  `ZbddTest` assuming 6 ints per node.
- **`ZbddNativeDifferentialTest`:** random operation sequences against `ZbddImpl`, starting small or after exceeding
  32767 variables, comparing node numbers, structure, reference counts, statistics, exception types and messages. Only
  lookups missing an existing node are compared, as the cache avoids the others.
- **Format change:** crossing by variable, by node growth, from a callback and from a visitor, with allocation failures,
  keeping reference counts, and differential runs across the boundary.
- **Reference counts:** the move to the overflow table and back in both formats, and `decRef` at 0 and on new nodes.
  `zb_test.c` seeds a count of 2³¹−1 directly, as reaching it takes 2 billion calls, and checks that it stays locked,
  and that a failed table allocation leaves the count unchanged.
- **Leaks:** a test-only counter of live contexts in C covers thousands of dropped instances, `close()`, a double
  `close()`, use after close, a throwing visitor and a throwing callback.
- **C tests:** `zb_test.c` runs all tests in both formats and with two cache sizes. Allocation accounting asserts that
  no blocks remain after destroying a context, as LeakSanitizer isn't available with Apple clang. For Linux it is a
  static musl build.

## Known limits

- **Each library is tested in a different place:** the x86_64 CI server runs the Linux library and the Windows C core
  under Wine. It can't run the macOS library, which is only run by a local `./gradlew build` on an arm64 Mac.
- **Wine is not Windows:** Wine implements `kernel32` and the file system behaviour itself, so a pass doesn't prove the
  DLL works on real Windows. The Windows path of `NativeLoader` is the most exposed.
- **Single calls are slower:** bulk operations are faster, but each call from Java crosses JNI, which takes about 5 ns
  on an Apple M3 Pro against 0.3–1.5 ns for `incRef` or `getVar` in Java. Code walking nodes one at a time from Java is
  therefore slower than with `ZbddImpl`.
- **Thread misuse can crash the JVM:** unsynchronized use of `ZbddImpl` corrupts data, but in C a racing `realloc` can
  crash the whole process. `asConcurrent` is required for shared use.
- **The JVM doesn't see native memory:** a large instance causes no garbage collection by itself, so release it with
  `close()` where that matters.
- **JDK 24 and later print a native access warning** unless `--enable-native-access` is set. A future JDK may require
  the flag.

## Before a release

- Run `./gradlew clean build` from a fresh checkout with an empty Gradle user home, on an arm64 Mac and in CI. A second
  build must report the `:native:compileNative*` tasks as up to date.
- Run `createNative()` from the built jar outside the project, on the class path and on the module path.
- Run the Java suite on a real Windows machine, and try `testNativeWindows` with Wine 10 on the x86_64 CI server.

## Known zig issue

The zig 0.17.0 Mach-O linker crashes on a library without exported symbols. This doesn't affect the library, which
exports `JNI_OnLoad`, but it does affect experiments with `-fvisibility=hidden`.
