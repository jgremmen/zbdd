# Version [0.4.1](https://github.com/jgremmen/zbdd/tree/0.4.1) (2025-10-27)

## Breaking Changes

There are no breaking changes in this version.

## New Features

There are no new features in this version.

## Bug Fixes

- Fixed the `difference` operation in `Zbdd` which produced incorrect results when the top variable of the
  first operand was greater than the top variable of the second operand. The recursive decomposition
  incorrectly descended into the low branch of `q` instead of passing `q` unchanged, leading to wrong
  set subtractions.

