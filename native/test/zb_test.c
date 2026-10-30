/*
 * Copyright 2026 Jeroen Gremmen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* Tests of the C core without a JVM. Both formats run the same tests, each ends with all memory released. */
#include <stdio.h>
#include <stdlib.h>

#include "zb_core.h"

static int failures;
static int checks;

#define CHECK(cond)                                              \
  do {                                                           \
    checks++;                                                    \
    if (!(cond)) {                                               \
      printf("FAILED %s:%d: %s\n", __FILE__, __LINE__, #cond);   \
      failures++;                                                \
    }                                                            \
  } while (0)

#define CHECK_EQ(actual, expected)                                                                      \
  do {                                                                                                  \
    const long long a_ = (long long)(actual);                                                           \
    const long long e_ = (long long)(expected);                                                         \
    checks++;                                                                                           \
    if (a_ != e_) {                                                                                     \
      printf("FAILED %s:%d: %s = %lld, expected %lld\n", __FILE__, __LINE__, #actual, a_, e_);          \
      failures++;                                                                                       \
    }                                                                                                   \
  } while (0)

/* All tests run with the smallest operation cache, where results get replaced all the time, and with a larger one. */
static int32_t cache_entries = 1024;

/* A context starts with the small format, so the tests of the large format change it before doing anything else. */
static zb_ctx *create_ctx(zb_format format, int32_t capacity, int32_t entries, const zb_host *host) {
  zb_ctx *c = zb_create(capacity, entries, host);

  if (c != NULL && format == ZB_FORMAT_LARGE && zb_migrate(c, (uint32_t)capacity) != 0) {
    zb_destroy(c);
    c = NULL;
  }

  return c;
}

static zb_ctx *new_ctx_with(zb_format format, int32_t capacity, const zb_host *host) {
  zb_ctx *c = create_ctx(format, capacity, cache_entries, host);

  if (c == NULL) {
    printf("zb_create failed\n");
    exit(1);
  }

  return c;
}

static zb_ctx *new_ctx(zb_format format) {
  return new_ctx_with(format, 128, NULL);
}

static void test_initial_format(void) {
  const struct { int32_t capacity; zb_format format; } expected[] = {
    { 128, ZB_FORMAT_SMALL }, { ZB_SMALL_MAX_NODES, ZB_FORMAT_SMALL }, { ZB_SMALL_MAX_NODES + 1, ZB_FORMAT_LARGE }
  };

  for (size_t i = 0; i < sizeof(expected) / sizeof(expected[0]); i++) {
    zb_ctx *c = zb_create(expected[i].capacity, cache_entries, NULL);

    CHECK_EQ(zb_get_format(c), expected[i].format);
    CHECK_EQ(zb_stat(c, ZB_STAT_CAPACITY), expected[i].capacity);

    zb_destroy(c);
  }
}

static void test_terminals(zb_format format) {
  zb_ctx *c = new_ctx(format);

  CHECK_EQ(zb_get_var(c, ZB_EMPTY), 0);
  CHECK_EQ(zb_get_p0(c, ZB_BASE), ZB_BASE);
  CHECK_EQ(zb_get_p1(c, ZB_EMPTY), ZB_EMPTY);
  CHECK_EQ(zb_get_ref(c, ZB_BASE), 1);
  CHECK_EQ(zb_count(c, ZB_EMPTY), 0);
  CHECK_EQ(zb_count(c, ZB_BASE), 1);
  CHECK(zb_is_valid_zbdd(c, ZB_EMPTY));
  CHECK(!zb_is_valid_zbdd(c, -1));
  CHECK(!zb_is_valid_zbdd(c, 2));
  CHECK(!zb_is_valid_zbdd(c, 1000000));

  zb_destroy(c);
}

static void test_validation(zb_format format) {
  zb_ctx *c = new_ctx(format);

  CHECK_EQ(zb_get_var(c, -1), ZB_ERR_RANGE);
  CHECK_EQ(c->err_value, -1);
  CHECK_EQ(c->err_limit, 127);
  CHECK_EQ(zb_get_var(c, 128), ZB_ERR_RANGE);
  CHECK_EQ(zb_get_var(c, 2), ZB_ERR_INVALID);
  CHECK_EQ(zb_union(c, ZB_EMPTY, 5), ZB_ERR_INVALID);
  CHECK_EQ(c->err_param, ZB_PARAM_Q);
  CHECK_EQ(zb_cube1(c, 1), ZB_ERR_VAR);
  CHECK_EQ(c->err_limit, 0);

  const int32_t v1 = zb_create_var(c);
  const int32_t v2 = zb_create_var(c);
  const int32_t cube2 = zb_cube1(c, v2);

  CHECK_EQ(zb_get_node(c, v1, cube2, ZB_BASE), ZB_ERR_VAR_ORDER);
  CHECK_EQ(c->err_value, cube2);
  CHECK_EQ(c->err_limit, v2);
  CHECK_EQ(c->err_extra, v1);
  CHECK_EQ(c->err_param, ZB_PARAM_P0);
  CHECK_EQ(zb_divide(c, cube2, ZB_EMPTY), ZB_ERR_DIV_EMPTY);

  int32_t invalid[] = { cube2, 77 };

  CHECK_EQ(zb_union_n(c, invalid, 2), ZB_ERR_INVALID);
  CHECK_EQ(c->err_param, ZB_PARAM_ARRAY + 1);

  zb_destroy(c);
}

static void test_set_operations(zb_format format) {
  zb_ctx *c = new_ctx(format);
  const int32_t a = zb_create_var(c);
  const int32_t b = zb_create_var(c);
  const int32_t d = zb_create_var(c);

  const int32_t ca = zb_cube1(c, a);
  const int32_t cb = zb_cube1(c, b);
  const int32_t cd = zb_cube1(c, d);
  const int32_t ab = zb_union(c, ca, cb);

  CHECK_EQ(zb_count(c, ab), 2);
  CHECK_EQ(zb_union(c, ab, ca), ab);
  CHECK_EQ(zb_intersect(c, ab, ca), ca);
  CHECK_EQ(zb_difference(c, ab, ca), cb);
  CHECK_EQ(zb_difference(c, ab, ab), ZB_EMPTY);

  /* (a + b) * d = ad + bd */
  const int32_t product = zb_multiply(c, ab, cd);

  CHECK_EQ(zb_count(c, product), 2);
  CHECK_EQ(zb_divide(c, product, cd), ab);
  CHECK_EQ(zb_divide(c, product, ab), cd);
  CHECK_EQ(zb_modulo(c, product, cd), ZB_EMPTY);
  CHECK_EQ(zb_modulo(c, zb_union(c, product, ca), cd), ca);

  CHECK_EQ(zb_subset0(c, product, d), ZB_EMPTY);
  CHECK_EQ(zb_subset1(c, product, d), ab);
  CHECK_EQ(zb_subset1(c, ab, a), ZB_BASE);
  CHECK_EQ(zb_subset0(c, ab, a), cb);
  CHECK_EQ(zb_change(c, ZB_BASE, a), ca);
  CHECK_EQ(zb_change(c, ca, a), ZB_BASE);

  CHECK_EQ(zb_has_cube_with_var(c, product, a), 1);
  CHECK_EQ(zb_has_cube_with_var(c, cb, a), 0);
  CHECK_EQ(zb_contains(c, product, zb_multiply(c, ca, cd)), 1);
  CHECK_EQ(zb_contains(c, ab, cd), 0);
  CHECK_EQ(zb_contains(c, ab, ZB_EMPTY), 1);

  CHECK_EQ(zb_remove_base(c, zb_union(c, ab, ZB_BASE)), ab);
  CHECK_EQ(zb_atomize(c, product), zb_union(c, ab, cd));

  int32_t vars[] = { d, a, d, b };
  const int32_t abd = zb_cube_n(c, vars, 4);

  CHECK_EQ(zb_count(c, abd), 1);
  CHECK_EQ(zb_get_var(c, abd), d);

  int32_t drop[] = { b, d };

  CHECK_EQ(zb_subset0_n(c, zb_union(c, ca, abd), drop, 2), ca);

  int32_t none[] = { 0 };

  CHECK_EQ(zb_cube_n(c, none, 0), ZB_BASE);

  int32_t operands[] = { ca, cb, cd };

  CHECK_EQ(zb_count(c, zb_union_n(c, operands, 3)), 3);

  zb_destroy(c);
}

static void test_iterator(zb_format format) {
  zb_ctx *c = new_ctx(format);
  const int32_t a = zb_create_var(c);
  const int32_t b = zb_create_var(c);
  const int32_t set = zb_union(c, zb_cube1(c, a), zb_union(c, zb_cube1(c, b), ZB_BASE));
  zb_iter *it = NULL;
  const uint32_t prot = c->prot.size;
  int cubes = 0;

  CHECK_EQ(zb_iter_open(c, set, &it), 0);

  for (int32_t n; (n = zb_iter_next_cube(c, it)) != ZB_ITER_END; cubes++)
    CHECK(n == 0 || n == 1);

  zb_iter_close(c, it);

  CHECK_EQ(cubes, 3);
  CHECK_EQ(c->prot.size, prot);

  int32_t *singles = NULL;

  CHECK_EQ(zb_as_single_cubes(c, set, &singles), 3);
  zb_free(singles);

  CHECK_EQ(zb_as_single_cubes(c, ZB_EMPTY, &singles), 0);
  zb_free(singles);

  CHECK_EQ(c->prot.size, prot);

  zb_destroy(c);
}

/* Two to the n cubes built with n variables, a graph with few nodes and a large count. */
static int32_t power_set(zb_ctx *c, int n) {
  int32_t r = ZB_BASE;

  for (int i = 0; i < n; i++)
    r = zb_get_node(c, zb_create_var(c), r, r);

  return r;
}

static void test_count(zb_format format) {
  zb_ctx *c = new_ctx(format);
  const int32_t z = power_set(c, 100);
  uint8_t *bytes = NULL;
  size_t len = 0;

  CHECK_EQ(zb_count(c, z), ZB_ERR_COUNT);
  CHECK_EQ(zb_count_exact(c, z, &bytes, &len), 0);

  /* 2^100 = 0x10_00000000_00000000_00000000 */
  size_t first = 0;

  while (first < len && bytes[first] == 0)
    first++;

  CHECK_EQ(len - first, 13);
  CHECK_EQ(bytes[first], 0x10);

  for (size_t i = first + 1; i < len; i++)
    CHECK_EQ(bytes[i], 0);

  zb_free(bytes);
  zb_destroy(c);
}

static void test_ref_counts(zb_format format) {
  const int32_t inline_max = format == ZB_FORMAT_SMALL ? 5 : 61;
  zb_ctx *c = new_ctx(format);
  const int32_t v = zb_create_var(c);
  const int32_t z = zb_cube1(c, v);

  CHECK_EQ(zb_get_ref(c, z), -1);
  CHECK_EQ(zb_stat(c, ZB_STAT_NEW), 1);

  /* a new node can't be released */
  CHECK_EQ(zb_dec_ref(c, z), 1);
  CHECK_EQ(zb_get_ref(c, z), -1);

  CHECK_EQ(zb_inc_ref(c, z), z);
  CHECK_EQ(zb_get_ref(c, z), 1);
  CHECK_EQ(zb_stat(c, ZB_STAT_NEW), 0);

  /* into the overflow table and back */
  for (int32_t n = 2; n <= inline_max + 3; n++) {
    CHECK_EQ(zb_inc_ref(c, z), z);
    CHECK_EQ(zb_get_ref(c, z), n);
  }

  CHECK_EQ(c->ovf_size, 1);

  for (int32_t n = inline_max + 2; n >= 1; n--) {
    CHECK_EQ(zb_dec_ref(c, z), 0);
    CHECK_EQ(zb_get_ref(c, z), n);
  }

  CHECK_EQ(c->ovf_size, 0);

  CHECK_EQ(zb_dec_ref(c, z), 0);
  CHECK_EQ(zb_get_ref(c, z), 0);
  CHECK_EQ(zb_stat(c, ZB_STAT_DEAD), 1);

  /* a dead node can't be released */
  CHECK_EQ(zb_dec_ref(c, z), 1);
  CHECK_EQ(zb_get_ref(c, z), 0);

  CHECK_EQ(zb_inc_ref(c, z), z);
  CHECK_EQ(zb_stat(c, ZB_STAT_DEAD), 0);

  /* terminals are ignored */
  CHECK_EQ(zb_inc_ref(c, ZB_BASE), ZB_BASE);
  CHECK_EQ(zb_dec_ref(c, ZB_BASE), 0);
  CHECK_EQ(zb_get_ref(c, ZB_BASE), 1);

  zb_destroy(c);
}

static void test_ref_count_locked(zb_format format) {
  zb_ctx *c = new_ctx(format);
  const int32_t v = zb_create_var(c);
  const int32_t z = zb_cube1(c, v);

  CHECK_EQ(zb_test_set_ref(c, z, ZB_REF_LOCKED - 1), 0);
  CHECK_EQ(zb_get_ref(c, z), ZB_REF_LOCKED - 1);

  /* reaching the highest count locks it */
  CHECK_EQ(zb_inc_ref(c, z), z);
  CHECK_EQ(zb_get_ref(c, z), ZB_REF_LOCKED);
  CHECK_EQ(zb_inc_ref(c, z), z);
  CHECK_EQ(zb_get_ref(c, z), ZB_REF_LOCKED);
  CHECK_EQ(zb_dec_ref(c, z), 0);
  CHECK_EQ(zb_get_ref(c, z), ZB_REF_LOCKED);

  CHECK_EQ(zb_gc(c), 0);
  CHECK(zb_is_valid_zbdd(c, z));
  CHECK_EQ(zb_get_ref(c, z), ZB_REF_LOCKED);

  zb_destroy(c);
}

static void test_ref_table_allocation_failure(zb_format format) {
  const int32_t inline_max = format == ZB_FORMAT_SMALL ? 5 : 61;
  zb_ctx *c = new_ctx(format);
  const int32_t v = zb_create_var(c);
  const int32_t z = zb_cube1(c, v);

  for (int32_t n = 0; n < inline_max; n++)
    zb_inc_ref(c, z);

  zb_test_fail_alloc(1);

  CHECK_EQ(zb_inc_ref(c, z), ZB_ERR_NOMEM);
  CHECK_EQ(zb_get_ref(c, z), inline_max);

  zb_test_fail_alloc(0);

  CHECK_EQ(zb_inc_ref(c, z), z);
  CHECK_EQ(zb_get_ref(c, z), inline_max + 1);

  /* releasing a count in the table allocates nothing */
  zb_inc_ref(c, z);
  zb_test_fail_alloc(1);

  CHECK_EQ(zb_dec_ref(c, z), 0);
  CHECK_EQ(zb_dec_ref(c, z), 0);
  CHECK_EQ(zb_get_ref(c, z), inline_max);

  zb_test_fail_alloc(0);
  zb_destroy(c);
}

static void test_gc(zb_format format) {
  zb_ctx *c = new_ctx(format);
  const int32_t a = zb_create_var(c);
  const int32_t b = zb_create_var(c);
  const int32_t kept = zb_inc_ref(c, zb_cube1(c, a));
  const int32_t gone = zb_cube1(c, b);

  CHECK_EQ(zb_stat(c, ZB_STAT_NEW), 1);
  CHECK_EQ(zb_gc(c), 1);
  CHECK(zb_is_valid_zbdd(c, kept));
  CHECK(!zb_is_valid_zbdd(c, gone));
  CHECK_EQ(zb_stat(c, ZB_STAT_NEW), 0);
  CHECK_EQ(zb_stat(c, ZB_STAT_GC_COUNT), 1);
  CHECK_EQ(zb_stat(c, ZB_STAT_GC_FREED), 1);

  /* the freed number is reused and the unique table still finds surviving nodes */
  CHECK_EQ(zb_cube1(c, b), gone);
  CHECK_EQ(zb_cube1(c, a), kept);

  int32_t *sequence = NULL;
  const int32_t kept_again = zb_inc_ref(c, zb_cube1(c, b));
  const int32_t n = zb_dependency(c, &sequence);

  CHECK_EQ(n, 4);
  CHECK_EQ(sequence[0], ZB_EMPTY);
  CHECK_EQ(sequence[1], ZB_BASE);
  CHECK(sequence[2] == kept || sequence[3] == kept);
  CHECK(sequence[2] == kept_again || sequence[3] == kept_again);

  zb_free(sequence);
  zb_destroy(c);
}

static void test_gc_mark_failure(zb_format format) {
  zb_ctx *c = new_ctx(format);
  const int32_t depth = 2000;
  int32_t z = ZB_BASE;

  for (int32_t i = 0; i < depth; i++)
    zb_create_var(c);

  /* the 1-branch is followed first, leaving each 0-branch on the mark stack, which exceeds its initial 64 slots */
  for (int32_t v = 2; v <= depth; v++)
    z = zb_get_node(c, v, zb_cube1(c, v - 1), z);

  zb_inc_ref(c, z);

  const int64_t occupied = zb_stat(c, ZB_STAT_CAPACITY) - zb_stat(c, ZB_STAT_FREE);
  const int64_t gc_count = zb_stat(c, ZB_STAT_GC_COUNT);

  zb_test_fail_alloc(1);
  CHECK_EQ(zb_gc(c), ZB_ERR_NOMEM);
  zb_test_fail_alloc(0);

  CHECK_EQ(zb_stat(c, ZB_STAT_GC_COUNT), gc_count);
  CHECK_EQ(zb_stat(c, ZB_STAT_CAPACITY) - zb_stat(c, ZB_STAT_FREE), occupied);
  CHECK_EQ(c->tasks.size, 0);

  /* left over marks would make this gc skip the branches of marked nodes and free reachable nodes */
  CHECK(zb_gc(c) >= 0);

  int32_t node = z;

  for (int32_t v = depth; v >= 2; v--) {
    CHECK_EQ(zb_get_var(c, node), v);
    CHECK(zb_is_valid_zbdd(c, zb_get_p0(c, node)));
    node = zb_get_p1(c, node);
  }

  CHECK_EQ(node, ZB_BASE);

  zb_destroy(c);
}

/* Growth and collection in the middle of operations, with the values checked afterwards. */
static void test_growth_and_gc_during_operations(zb_format format) {
  zb_ctx *c = new_ctx(format);
  const int32_t vars = 3000;
  int32_t all = ZB_EMPTY;

  for (int32_t v = 1; v <= vars; v++)
    zb_create_var(c);

  for (int32_t v = 1; v <= vars; v++) {
    const int32_t next = zb_inc_ref(c, zb_union(c, all, zb_cube1(c, v)));

    if (all >= 2)
      zb_dec_ref(c, all);

    all = next;

    if (v % 500 == 0)
      CHECK(zb_gc(c) >= 0);
  }

  uint8_t *bytes = NULL;
  size_t len = 0;

  CHECK_EQ(zb_count(c, all), vars);
  CHECK_EQ(zb_count_exact(c, all, &bytes, &len), 0);
  zb_free(bytes);

  CHECK(zb_stat(c, ZB_STAT_CAPACITY_INCREASES) > 0);
  CHECK(zb_stat(c, ZB_STAT_GC_FREED) > 0);

  for (int32_t v = 1; v <= vars; v++)
    CHECK_EQ(zb_has_cube_with_var(c, all, v), 1);

  zb_destroy(c);
}

/* Node numbers are identical in both formats, as they share the free list order and growth. */
static void test_formats_agree(void) {
  zb_ctx *s = new_ctx(ZB_FORMAT_SMALL);
  zb_ctx *l = new_ctx(ZB_FORMAT_LARGE);

  for (int i = 0; i < 32; i++) {
    zb_create_var(s);
    zb_create_var(l);
  }

  int32_t zs = ZB_BASE, zl = ZB_BASE;
  uint32_t seed = 12345;

  for (int i = 0; i < 2000; i++) {
    seed = seed * 1664525u + 1013904223u;

    const int32_t v = (int32_t)((seed >> 16) % 32) + 1;

    switch ((seed >> 8) % 5) {
      case 0: zs = zb_change(s, zs, v); zl = zb_change(l, zl, v); break;
      case 1: zs = zb_union(s, zs, zb_cube1(s, v)); zl = zb_union(l, zl, zb_cube1(l, v)); break;
      case 2: zs = zb_subset0(s, zs, v); zl = zb_subset0(l, zl, v); break;
      case 3: zs = zb_union(s, zs, zb_change(s, zs, v)); zl = zb_union(l, zl, zb_change(l, zl, v)); break;
      default: zs = zb_remove_base(s, zs); zl = zb_remove_base(l, zl); break;
    }

    if (zs == ZB_EMPTY) {
      zs = ZB_BASE;
      zl = ZB_BASE;
    }

    if (zs < 0 || zl < 0 || zs != zl) {
      CHECK_EQ(zs, zl);
      break;
    }

    if (zb_stat(s, ZB_STAT_CAPACITY) > 20000)
      break;
  }

  CHECK_EQ(zb_stat(s, ZB_STAT_FREE), zb_stat(l, ZB_STAT_FREE));
  CHECK_EQ(zb_stat(s, ZB_STAT_LOOKUPS), zb_stat(l, ZB_STAT_LOOKUPS));
  CHECK_EQ(zb_stat(s, ZB_STAT_HITS), zb_stat(l, ZB_STAT_HITS));

  zb_destroy(s);
  zb_destroy(l);
}

static void test_migration_on_var(void) {
  zb_ctx *c = new_ctx(ZB_FORMAT_SMALL);

  for (int32_t i = 0; i < ZB_SMALL_MAX_VAR; i++)
    CHECK_EQ(zb_create_var(c), i + 1);

  const int32_t cube = zb_inc_ref(c, zb_cube1(c, ZB_SMALL_MAX_VAR));
  const int64_t capacity = zb_stat(c, ZB_STAT_CAPACITY);

  CHECK_EQ(zb_get_format(c), ZB_FORMAT_SMALL);

  /* a failing allocation leaves the small format intact */
  zb_test_fail_alloc(1);
  CHECK_EQ(zb_create_var(c), ZB_ERR_NOMEM);
  zb_test_fail_alloc(0);

  CHECK_EQ(zb_get_format(c), ZB_FORMAT_SMALL);
  CHECK_EQ(zb_stat(c, ZB_STAT_VARS), ZB_SMALL_MAX_VAR);
  CHECK_EQ(zb_get_var(c, cube), ZB_SMALL_MAX_VAR);

  CHECK_EQ(zb_create_var(c), ZB_SMALL_MAX_VAR + 1);
  CHECK_EQ(zb_get_format(c), ZB_FORMAT_LARGE);
  CHECK_EQ(zb_stat(c, ZB_STAT_CAPACITY), capacity);

  /* existing nodes keep their numbers and the unique table still finds them */
  CHECK_EQ(zb_get_var(c, cube), ZB_SMALL_MAX_VAR);
  CHECK_EQ(zb_get_ref(c, cube), 1);
  CHECK_EQ(zb_cube1(c, ZB_SMALL_MAX_VAR), cube);

  const int32_t beyond = zb_cube1(c, ZB_SMALL_MAX_VAR + 1);

  CHECK_EQ(zb_get_var(c, beyond), ZB_SMALL_MAX_VAR + 1);
  CHECK_EQ(zb_count(c, zb_union(c, cube, beyond)), 2);

  zb_destroy(c);
}

static void test_migration_on_nodes(void) {
  zb_ctx *c = new_ctx(ZB_FORMAT_SMALL);
  zb_ctx *l = new_ctx(ZB_FORMAT_LARGE);
  const int32_t vars = 30000;

  for (int32_t i = 0; i < vars; i++) {
    zb_create_var(c);
    zb_create_var(l);
  }

  /* referenced cubes and a growing union, so the operation creating the 32768th node is a running union */
  int32_t all = ZB_EMPTY, all_l = ZB_EMPTY;
  int migrated_at = 0;

  for (int32_t v = 1; v <= vars; v++) {
    const int32_t next = zb_inc_ref(c, zb_union(c, all, zb_inc_ref(c, zb_cube1(c, v))));
    const int32_t next_l = zb_inc_ref(l, zb_union(l, all_l, zb_inc_ref(l, zb_cube1(l, v))));

    if (next != next_l) {
      CHECK_EQ(next, next_l);
      break;
    }

    all = next;
    all_l = next_l;

    if (migrated_at == 0 && zb_get_format(c) == ZB_FORMAT_LARGE)
      migrated_at = v;

    if (migrated_at != 0 && v > migrated_at + 100)
      break;
  }

  CHECK(migrated_at != 0);
  CHECK_EQ(zb_get_format(c), ZB_FORMAT_LARGE);
  CHECK_EQ(c->prot.size, 0);
  CHECK_EQ(c->tasks.size, 0);
  CHECK_EQ(c->results.size, 0);
  CHECK_EQ(zb_stat(c, ZB_STAT_CAPACITY), zb_stat(l, ZB_STAT_CAPACITY));
  CHECK_EQ(zb_stat(c, ZB_STAT_FREE), zb_stat(l, ZB_STAT_FREE));
  CHECK_EQ(zb_stat(c, ZB_STAT_NEW), zb_stat(l, ZB_STAT_NEW));
  CHECK_EQ(zb_stat(c, ZB_STAT_DEAD), zb_stat(l, ZB_STAT_DEAD));
  CHECK_EQ(zb_stat(c, ZB_STAT_LOOKUPS), zb_stat(l, ZB_STAT_LOOKUPS));
  CHECK_EQ(zb_stat(c, ZB_STAT_HITS), zb_stat(l, ZB_STAT_HITS));
  CHECK_EQ(zb_stat(c, ZB_STAT_GC_COUNT), zb_stat(l, ZB_STAT_GC_COUNT));
  CHECK_EQ(zb_stat(c, ZB_STAT_CAPACITY_INCREASES), zb_stat(l, ZB_STAT_CAPACITY_INCREASES));
  CHECK_EQ(zb_count(c, all), zb_count(l, all_l));
  CHECK_EQ(zb_gc(c), zb_gc(l));

  zb_destroy(c);
  zb_destroy(l);
}

static void test_migration_keeps_ref_counts(void) {
  zb_ctx *c = new_ctx(ZB_FORMAT_SMALL);

  for (int32_t i = 0; i < 4; i++)
    zb_create_var(c);

  const int32_t fresh = zb_cube1(c, 1);
  const int32_t dead = zb_inc_ref(c, zb_cube1(c, 2));
  const int32_t in_range = zb_cube1(c, 3);
  const int32_t in_table = zb_cube1(c, 4);
  const int32_t locked = zb_get_node(c, 2, fresh, ZB_BASE);

  zb_dec_ref(c, dead);

  for (int i = 0; i < 40; i++)
    zb_inc_ref(c, in_range);

  CHECK_EQ(zb_test_set_ref(c, in_table, 1000), 0);
  CHECK_EQ(zb_test_set_ref(c, locked, ZB_REF_LOCKED), 0);
  CHECK_EQ(c->ovf_size, 3);

  for (int32_t i = 4; i < ZB_SMALL_MAX_VAR; i++)
    zb_create_var(c);

  CHECK_EQ(zb_create_var(c), ZB_SMALL_MAX_VAR + 1);
  CHECK_EQ(zb_get_format(c), ZB_FORMAT_LARGE);

  CHECK_EQ(zb_get_ref(c, fresh), -1);
  CHECK_EQ(zb_get_ref(c, dead), 0);
  CHECK_EQ(zb_get_ref(c, in_range), 40);
  CHECK_EQ(zb_get_ref(c, in_table), 1000);
  CHECK_EQ(zb_get_ref(c, locked), ZB_REF_LOCKED);
  CHECK_EQ(c->ovf_size, 2);

  zb_inc_ref(c, in_range);
  CHECK_EQ(zb_get_ref(c, in_range), 41);
  zb_dec_ref(c, in_table);
  CHECK_EQ(zb_get_ref(c, in_table), 999);
  zb_dec_ref(c, locked);
  CHECK_EQ(zb_get_ref(c, locked), ZB_REF_LOCKED);

  /* the new node and the dead node stay reachable from the locked node only as far as it refers to them */
  CHECK(zb_gc(c) >= 0);
  CHECK(zb_is_valid_zbdd(c, fresh));
  CHECK(!zb_is_valid_zbdd(c, dead));
  CHECK(zb_is_valid_zbdd(c, in_range));
  CHECK_EQ(zb_get_ref(c, in_table), 999);

  zb_destroy(c);
}

/* A callback changing the format below a running operation. */
static int32_t callback_migrates(zb_ctx *c, int32_t event) {
  if (event == ZB_EVENT_BEFORE_GC)
    while (zb_get_format(c) == ZB_FORMAT_SMALL)
      zb_create_var(c);

  return 0;
}

static const zb_host migrating_host = { callback_migrates, NULL, NULL, NULL };

static void test_migration_from_callback(void) {
  zb_ctx *c = new_ctx_with(ZB_FORMAT_SMALL, 128, &migrating_host);
  const int32_t vars = 2000;

  c->has_callbacks = 1;

  for (int32_t i = 0; i < vars; i++)
    zb_create_var(c);

  int32_t all = ZB_EMPTY;

  for (int32_t v = 1; v <= vars; v++) {
    const int32_t next = zb_inc_ref(c, zb_union(c, all, zb_cube1(c, v)));

    CHECK(next >= 0);

    if (all >= 2)
      zb_dec_ref(c, all);

    all = next;
  }

  CHECK_EQ(zb_get_format(c), ZB_FORMAT_LARGE);
  CHECK_EQ(zb_count(c, all), vars);
  CHECK(zb_stat(c, ZB_STAT_GC_COUNT) > 0);
  CHECK_EQ(c->prot.size, 0);

  zb_destroy(c);
}

/* The format changing while an iterator is open: no cube is skipped or visited twice. */
static void test_migration_with_open_iterator(void) {
  zb_ctx *c = new_ctx(ZB_FORMAT_SMALL);

  for (int32_t i = 0; i < 8; i++)
    zb_create_var(c);

  int32_t set = ZB_EMPTY;

  for (int32_t v = 1; v <= 8; v++)
    set = zb_union(c, set, zb_cube1(c, v));

  zb_iter *it = NULL;
  int cubes = 0;

  CHECK_EQ(zb_iter_open(c, set, &it), 0);

  for (int32_t z; (z = zb_iter_next_zbdd(c, it)) != ZB_ITER_END; cubes++) {
    CHECK(z >= 2);

    if (cubes == 3)
      while (zb_get_format(c) == ZB_FORMAT_SMALL)
        zb_create_var(c);
  }

  zb_iter_close(c, it);

  CHECK_EQ(cubes, 8);
  CHECK_EQ(zb_get_format(c), ZB_FORMAT_LARGE);

  zb_destroy(c);
}

/* Growing past the small format with an allocation failure: the operation fails and can be repeated. */
static void test_migration_allocation_failure(void) {
  zb_ctx *c = new_ctx(ZB_FORMAT_SMALL);

  for (int32_t i = 0; i < 30000; i++)
    zb_create_var(c);

  int failed = 0;

  for (int32_t v = 1; v <= 30000 && zb_get_format(c) == ZB_FORMAT_SMALL; v++) {
    /* the capacity increase past 31250 is the one leaving the small format */
    if (zb_stat(c, ZB_STAT_CAPACITY) == 31250 && zb_stat(c, ZB_STAT_FREE) < 2 && !failed) {
      zb_test_fail_alloc(1);

      const int32_t rc = zb_cube1(c, v);

      zb_test_fail_alloc(0);

      CHECK_EQ(rc, ZB_ERR_NOMEM);
      CHECK_EQ(zb_get_format(c), ZB_FORMAT_SMALL);
      CHECK_EQ(c->prot.size, 0);

      failed = 1;
    }

    const int32_t rc = zb_cube1(c, v);

    CHECK(rc >= 0);
    zb_inc_ref(c, rc);

    if (v < 3000) {
      const int32_t other = zb_get_node(c, v + 1, rc, ZB_BASE);

      CHECK(other >= 0);
      zb_inc_ref(c, other);
    }
  }

  CHECK_EQ(failed, 1);
  CHECK_EQ(zb_get_format(c), ZB_FORMAT_LARGE);

  zb_destroy(c);
}

static void test_deep_chain(zb_format format) {
  zb_ctx *c = new_ctx(format);
  const int32_t depth = format == ZB_FORMAT_SMALL ? 10000 : 200000;
  int32_t chain = ZB_BASE;

  for (int32_t i = 0; i < depth; i++)
    chain = zb_get_node(c, zb_create_var(c), ZB_EMPTY, chain);

  CHECK_EQ(zb_count(c, chain), 1);
  CHECK_EQ(zb_subset1(c, chain, 1) >= 2, 1);
  CHECK_EQ(zb_remove_base(c, chain), chain);
  CHECK_EQ(zb_atomize(c, zb_cube1(c, 1)), zb_cube1(c, 1));
  CHECK_EQ(zb_contains(c, chain, chain), 1);

  zb_destroy(c);
}

static void test_create_failure(zb_format format) {
  /* every allocation of zb_create and of changing the format may fail, without leaving anything behind */
  for (int32_t n = 1; n <= 9; n++) {
    const int64_t before = zb_live_blocks();

    zb_test_fail_alloc(n);

    zb_ctx *c = create_ctx(format, 128, cache_entries, NULL);

    zb_test_fail_alloc(0);

    if (c != NULL)
      zb_destroy(c);

    CHECK_EQ(zb_live_blocks(), before);
  }
}

static void test_allocation_failures_leave_state_intact(zb_format format) {
  zb_ctx *c = new_ctx(format);

  for (int i = 0; i < 64; i++)
    zb_create_var(c);

  int32_t set = ZB_BASE;

  for (int32_t v = 1; v <= 16; v++) {
    set = zb_inc_ref(c, zb_union(c, set, zb_change(c, set, v)));
  }

  for (int32_t n = 1; n <= 8; n++) {
    const int64_t before = zb_live_blocks();
    uint8_t *bytes = NULL;
    size_t len = 0;

    zb_test_fail_alloc(n);

    const int32_t rc = zb_count_exact(c, set, &bytes, &len);

    zb_test_fail_alloc(0);

    if (rc >= 0)
      zb_free(bytes);
    else
      CHECK_EQ(rc, ZB_ERR_NOMEM);

    /* exact counts are kept by the operation cache on purpose */
    zb_cache_clear(c);

    CHECK_EQ(zb_live_blocks(), before);
    CHECK_EQ(c->prot.size, 0);
    CHECK_EQ(c->tasks.size, 0);
    CHECK_EQ(c->results.size, 0);
  }

  CHECK_EQ(zb_count(c, set), 65536);

  zb_destroy(c);
}

static void test_clear(zb_format format) {
  zb_ctx *c = new_ctx(format);
  const int32_t v = zb_create_var(c);

  zb_inc_ref(c, zb_cube1(c, v));

  CHECK_EQ(zb_clear(c, ZB_CLEAR_BEFORE), 0);
  CHECK_EQ(zb_clear(c, ZB_CLEAR_RESET), 0);
  CHECK_EQ(zb_clear(c, ZB_CLEAR_AFTER), 0);

  CHECK_EQ(zb_stat(c, ZB_STAT_VARS), 0);
  CHECK_EQ(zb_stat(c, ZB_STAT_FREE), zb_stat(c, ZB_STAT_CAPACITY) - 2);
  CHECK_EQ(zb_stat(c, ZB_STAT_LOOKUPS), 0);
  CHECK(!zb_is_valid_zbdd(c, 2));
  CHECK(!zb_is_valid_var(c, v));

  zb_destroy(c);
}

static void test_cache_sizes(void) {
  const int32_t requested[][2] = {
    { -5, 1024 }, { 0, 1024 }, { 1, 1024 }, { 1024, 1024 }, { 1025, 2048 }, { 70000, 131072 }
  };

  zb_ctx *smallest = zb_create(128, 1024, NULL);
  const int64_t memory = zb_stat(smallest, ZB_STAT_MEMORY);

  for (size_t i = 0; i < sizeof(requested) / sizeof(requested[0]); i++) {
    zb_ctx *c = zb_create(128, requested[i][0], NULL);

    CHECK_EQ(zb_stat(c, ZB_STAT_CACHE_ENTRIES), requested[i][1]);
    CHECK_EQ(zb_stat(c, ZB_STAT_MEMORY) - memory, ((int64_t)requested[i][1] - 1024) * 12);

    zb_destroy(c);
  }

  zb_destroy(smallest);
}

/* Each operation returns the same result from the cache, without looking up a single node. */
static void test_cache_hits(zb_format format) {
  zb_ctx *c = create_ctx(format, 128, 65536, NULL);

  for (int i = 0; i < 12; i++)
    zb_create_var(c);

  int32_t p = ZB_EMPTY, q = ZB_EMPTY;

  for (int32_t v = 1; v <= 12; v++) {
    int32_t pair[] = { v, 1 + v % 12 };
    int32_t triple[] = { v, 1 + v % 12, 1 + (v + 3) % 12 };

    p = zb_inc_ref(c, zb_union(c, p, zb_inc_ref(c, zb_cube_n(c, pair, 2))));
    q = zb_inc_ref(c, zb_union(c, q, zb_inc_ref(c, zb_cube_n(c, triple, 3))));
  }

  int32_t vars[] = { 3, 7 };
  const int32_t cube = zb_inc_ref(c, zb_cube1(c, 9));

#define CACHED(call)                                                 \
  do {                                                               \
    const int32_t first = (call);                                    \
    const int64_t lookups = zb_stat(c, ZB_STAT_LOOKUPS);             \
    const int64_t hits = zb_stat(c, ZB_STAT_CACHE_HITS);             \
    CHECK(first >= 0);                                               \
    CHECK_EQ((call), first);                                         \
    CHECK_EQ(zb_stat(c, ZB_STAT_LOOKUPS), lookups);                  \
    CHECK_EQ(zb_stat(c, ZB_STAT_CACHE_HITS), hits + 1);              \
  } while (0)

  CACHED(zb_subset0(c, p, 5));
  CACHED(zb_subset1(c, p, 5));
  CACHED(zb_change(c, p, 5));
  CACHED(zb_union(c, p, q));
  CACHED(zb_union(c, q, p));
  CACHED(zb_intersect(c, p, q));
  CACHED(zb_intersect(c, q, p));
  CACHED(zb_difference(c, p, q));
  CACHED(zb_multiply(c, p, cube));
  CACHED(zb_multiply(c, cube, p));
  CACHED(zb_divide(c, p, cube));
  CACHED(zb_modulo(c, p, cube));
  CACHED(zb_atomize(c, p));
  CACHED(zb_remove_base(c, p));
  CACHED(zb_count(c, p));
  CACHED(zb_contains(c, p, q));
  CACHED(zb_has_cube_with_var(c, p, 2));

  /* building the cube of the variables is not part of the cached result */
  const int32_t subset = zb_subset0_n(c, p, vars, 2);
  const int64_t subset_hits = zb_stat(c, ZB_STAT_CACHE_HITS);

  CHECK(subset >= 0);
  CHECK_EQ(zb_subset0_n(c, p, vars, 2), subset);
  CHECK_EQ(zb_stat(c, ZB_STAT_CACHE_HITS), subset_hits + 1);

  /* exact counts are kept per node, so counts of subgraphs are found as well */
  uint8_t *bytes = NULL;
  size_t len = 0;
  const int64_t exact_hits = zb_stat(c, ZB_STAT_CACHE_HITS);

  CHECK_EQ(zb_count_exact(c, p, &bytes, &len), 0);
  zb_free(bytes);
  CHECK_EQ(zb_stat(c, ZB_STAT_CACHE_HITS), exact_hits);
  CHECK_EQ(zb_count_exact(c, p, &bytes, &len), 0);
  zb_free(bytes);
  CHECK_EQ(zb_stat(c, ZB_STAT_CACHE_HITS), exact_hits + 1);
  CHECK_EQ(zb_count_exact(c, zb_get_p0(c, p), &bytes, &len), 0);
  zb_free(bytes);
  CHECK_EQ(zb_stat(c, ZB_STAT_CACHE_HITS), exact_hits + 2);
  CHECK(zb_stat(c, ZB_STAT_MEMORY) > (int64_t)65536 * 12);

  /* a garbage collection empties them, large counts exceeding the size of the cache are dropped */
  CHECK(zb_gc(c) >= 0);
  CHECK_EQ(zb_count_exact(c, p, &bytes, &len), 0);
  zb_free(bytes);
  CHECK_EQ(zb_stat(c, ZB_STAT_CACHE_HITS), exact_hits + 2);

  CHECK_EQ(zb_union(c, p, q), zb_union(c, q, p));

  zb_destroy(c);
}

/* A result must not survive the node numbers it refers to. */
static void test_cache_cleared_with_nodes(zb_format format) {
  zb_ctx *c = create_ctx(format, 128, 4096, NULL);

  const int32_t a = zb_create_var(c);
  const int32_t b = zb_create_var(c);
  const int32_t d = zb_create_var(c);
  const int32_t ca = zb_inc_ref(c, zb_cube1(c, a));
  const int32_t cb = zb_inc_ref(c, zb_cube1(c, b));

  zb_union(c, ca, cb);
  CHECK(zb_stat(c, ZB_STAT_CACHE_LOOKUPS) > 0);

  /* the union is freed, its number is recycled by a different node */
  CHECK(zb_gc(c) >= 1);

  const int32_t other = zb_inc_ref(c, zb_cube1(c, d));
  const int32_t u = zb_union(c, ca, cb);

  CHECK(u != other);
  CHECK_EQ(zb_count(c, u), 2);
  CHECK_EQ(zb_get_var(c, other), d);
  CHECK_EQ(zb_has_cube_with_var(c, u, a), 1);
  CHECK_EQ(zb_has_cube_with_var(c, u, d), 0);

  /* the same for clear, which also resets the counters */
  CHECK_EQ(zb_clear(c, ZB_CLEAR_BEFORE), 0);
  CHECK_EQ(zb_clear(c, ZB_CLEAR_RESET), 0);
  CHECK_EQ(zb_clear(c, ZB_CLEAR_AFTER), 0);

  CHECK_EQ(zb_stat(c, ZB_STAT_CACHE_LOOKUPS), 0);
  CHECK_EQ(zb_stat(c, ZB_STAT_CACHE_HITS), 0);

  zb_create_var(c);
  zb_create_var(c);

  const int32_t x = zb_cube1(c, 2);
  const int32_t y = zb_cube1(c, 1);

  CHECK_EQ(zb_count(c, zb_union(c, x, y)), 2);
  CHECK_EQ(zb_stat(c, ZB_STAT_CACHE_HITS), 0);

  zb_destroy(c);
}

/* Cached results stay valid across the format change. */
/* Exact counts stay bounded by the size of the cache and are correct when computed again. */
static void test_exact_counts_bounded(void) {
  zb_ctx *c = create_ctx(ZB_FORMAT_LARGE, 128, 1024, NULL);

  int32_t z = ZB_BASE;

  for (int i = 0; i < 1000; i++)
    z = zb_inc_ref(c, zb_get_node(c, zb_create_var(c), z, z));

  uint8_t *bytes = NULL;
  size_t len = 0;

  for (int round = 0; round < 3; round++) {
    CHECK_EQ(zb_count_exact(c, z, &bytes, &len), 0);

    /* 2^1000 */
    size_t first = 0;

    while (first < len && bytes[first] == 0)
      first++;

    CHECK_EQ(len - first, 126);
    CHECK_EQ(bytes[first], 1);

    zb_free(bytes);
    CHECK(zb_exact_counts_memory(c) <= (size_t)1024 * 12);
  }

  zb_destroy(c);
}

static void test_cache_across_migration(void) {
  zb_ctx *c = zb_create(128, 4096, NULL);

  zb_create_var(c);
  zb_create_var(c);

  const int32_t ca = zb_inc_ref(c, zb_cube1(c, 1));
  const int32_t cb = zb_inc_ref(c, zb_cube1(c, 2));
  const int32_t u = zb_inc_ref(c, zb_union(c, ca, cb));
  const int64_t hits = zb_stat(c, ZB_STAT_CACHE_HITS);

  while (zb_get_format(c) == ZB_FORMAT_SMALL)
    zb_create_var(c);

  CHECK_EQ(zb_union(c, ca, cb), u);
  CHECK_EQ(zb_stat(c, ZB_STAT_CACHE_HITS), hits + 1);

  zb_destroy(c);
}

static void run_for_format(zb_format format) {
  test_terminals(format);
  test_validation(format);
  test_set_operations(format);
  test_iterator(format);
  test_count(format);
  test_ref_counts(format);
  test_ref_count_locked(format);
  test_ref_table_allocation_failure(format);
  test_gc(format);
  test_gc_mark_failure(format);
  test_growth_and_gc_during_operations(format);
  test_deep_chain(format);
  test_create_failure(format);
  test_allocation_failures_leave_state_intact(format);
  test_clear(format);
}

static void run_all(void) {
  run_for_format(ZB_FORMAT_SMALL);
  run_for_format(ZB_FORMAT_LARGE);
  test_formats_agree();
  test_migration_on_var();
  test_migration_on_nodes();
  test_migration_keeps_ref_counts();
  test_migration_from_callback();
  test_migration_with_open_iterator();
  test_migration_allocation_failure();
}

int main(void) {
  run_all();

  cache_entries = 65536;
  run_all();

  test_initial_format();
  test_cache_sizes();
  test_cache_hits(ZB_FORMAT_SMALL);
  test_cache_hits(ZB_FORMAT_LARGE);
  test_cache_cleared_with_nodes(ZB_FORMAT_SMALL);
  test_cache_cleared_with_nodes(ZB_FORMAT_LARGE);
  test_cache_across_migration();
  test_exact_counts_bounded();

  CHECK_EQ(zb_live_contexts(), 0);
  CHECK_EQ(zb_live_blocks(), 0);

  printf("%d checks, %d failures\n", checks, failures);

  return failures != 0;
}
