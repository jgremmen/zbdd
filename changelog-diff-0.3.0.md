# Version [0.3.0](https://github.com/jgremmen/zbdd/releases/tag/0.3.0) (2025-03-05)

## Breaking Changes

- **Minimum Java version raised from 8 to 11.**
  All users must now compile and run with Java 11 or later. Projects still targeting Java 8 will no longer be
  compatible with this library.

- **Java module system support added.**
  A `module-info.java` has been added to the library, declaring the module `de.sayayi.lib.zbdd`. Only the packages
  `de.sayayi.lib.zbdd` and `de.sayayi.lib.zbdd.cache` are exported. Code that previously accessed internal classes
  via reflection or from non-exported packages will no longer work in a modular environment.

## New Features

- **Varargs `union` operation.**
  The `union` method now accepts a variable number of zbdd arguments, allowing multiple sets to be combined in a
  single call:

  ```java
  int result = zbdd.union(a, b, c, d);
  ```

- **`ZbddLiteralResolver.getBaseName()` method.**
  A new default method `getBaseName()` has been added to `ZbddLiteralResolver`. It returns the string representation
  for a cube representing the base (default: `"{}"`). Custom literal resolvers can override this method to provide a
  different name for the base element:

  ```java
  zbdd.setLiteralResolver(new ZbddLiteralResolver() {
    @Override
    public String getLiteralName(int var) { return "x" + var; }

    @Override
    public String getBaseName() { return "1"; }
  });
  ```

## Bug Fixes

- Fixed `createVar()` to throw a `ZbddException` when the maximum variable count is exceeded, instead of silently
  overflowing.
- Fixed `visitCubes` to validate the zbdd parameter before traversing.
- Fixed node capacity growth to correctly track the number of free nodes during expansion.
- Fixed reference count increment/decrement to use explicit arithmetic instead of post-increment operators, preventing
  potential overflow issues.
