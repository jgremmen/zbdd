# Version [0.4.1](https://github.com/jgremmen/zbdd/releases/tag/0.4.1) (2025-10-27)

## Bug Fixes

- Fixed the `difference` operation producing incorrect results when the top variable of `p` is greater than the top
  variable of `q`. The 0-branch of `p` was incorrectly differenced against the 0-branch of `q` instead of the full `q`.

