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
#ifndef ZB_CORE_H
#define ZB_CORE_H

#include <stddef.h>
#include <stdint.h>

/* The library links no C runtime headers, these are the only C library functions used besides allocation. */
void *memcpy(void *dest, const void *src, size_t n);
void *memset(void *dest, int value, size_t n);

/* Terminal nodes. */
#define ZB_EMPTY 0
#define ZB_BASE  1

/* Limits of the small format: node numbers and variables up to 32767, which is 32768 slots including terminals. */
#define ZB_SMALL_MAX_NODES 32768
#define ZB_SMALL_MAX_VAR   32767

/* Limits of the large format, identical to ZbddImpl.MAX_NODES and ZbddImpl.MAX_VAR. */
#define ZB_LARGE_MAX_NODES 357913941
#define ZB_LARGE_MAX_VAR   2147483646

/* Highest reference count stored in a node, higher counts are kept in the overflow table. */
#define ZB_SMALL_REF_INLINE_MAX 5
#define ZB_LARGE_REF_INLINE_MAX 61

/* Highest reference count. A count reaching this value is locked and no longer changes. */
#define ZB_REF_LOCKED 2147483647

typedef enum { ZB_FORMAT_SMALL = 0, ZB_FORMAT_LARGE = 1 } zb_format;

/* All errors are negative, so results >= 0 are node numbers, variables, counts or flags. */
enum {
  ZB_ERR_RANGE = -1,         /* zbdd out of range: value, limit = highest node, param */
  ZB_ERR_INVALID = -2,       /* zbdd not allocated: value, param */
  ZB_ERR_VAR = -3,           /* invalid variable: value, limit = last variable */
  ZB_ERR_VAR_ORDER = -4,     /* child var not below var: value, limit = child var, param, extra = var */
  ZB_ERR_DIV_EMPTY = -5,     /* division by the empty set */
  ZB_ERR_CAPACITY = -6,      /* no free node left and no capacity increase possible */
  ZB_ERR_VAR_COUNT = -7,     /* highest variable of the large format reached */
  ZB_ERR_NOMEM = -9,         /* native allocation failed */
  ZB_ERR_COUNT = -10,        /* number of cubes exceeds Integer.MAX_VALUE */
  ZB_ERR_HOST = -11,         /* host callback failed, in Java an exception is pending */
  ZB_ERR_CB_GC = -12,        /* gc invoked from a callback */
  ZB_ERR_CB_CLEAR = -13,     /* clear invoked from a callback */
  ZB_RETRY = -14             /* format changed to large, the operation must be run again */
};

/* Returned by the cube iterator once all cubes have been visited. */
#define ZB_ITER_END (-100)

/* Operations with cached results. 0 is not used, so a key is never 0, which denotes an empty cache entry. */
enum {
  ZB_CACHE_SUBSET0 = 1, ZB_CACHE_SUBSET0_CUBE, ZB_CACHE_SUBSET1, ZB_CACHE_CHANGE, ZB_CACHE_UNION, ZB_CACHE_INTERSECT,
  ZB_CACHE_DIFFERENCE, ZB_CACHE_MULTIPLY, ZB_CACHE_DIVIDE, ZB_CACHE_MODULO, ZB_CACHE_ATOMIZE, ZB_CACHE_REMOVE_BASE,
  ZB_CACHE_COUNT, ZB_CACHE_CONTAINS, ZB_CACHE_HAS_CUBE_WITH_VAR
};

#define ZB_CACHE_MIN_ENTRIES 1024
#define ZB_CACHE_MAX_ENTRIES (INT32_C(1) << 30)

/* Parameter names reported with an error, see ZbddNative#paramName. Values >= ZB_PARAM_ARRAY stand for p[n]. */
enum { ZB_PARAM_ZBDD = 0, ZB_PARAM_P, ZB_PARAM_Q, ZB_PARAM_P0, ZB_PARAM_P1, ZB_PARAM_ARRAY };

/* Events passed to the host callback function. */
enum { ZB_EVENT_BEFORE_CLEAR = 0, ZB_EVENT_AFTER_CLEAR, ZB_EVENT_BEFORE_GC, ZB_EVENT_AFTER_GC };

/* Phases of zb_clear, the host resets its own state between ZB_CLEAR_RESET and ZB_CLEAR_AFTER. */
enum { ZB_CLEAR_BEFORE = 0, ZB_CLEAR_RESET, ZB_CLEAR_AFTER };

/* Statistics returned by zb_stat. */
enum {
  ZB_STAT_CAPACITY = 0, ZB_STAT_FREE, ZB_STAT_DEAD, ZB_STAT_NEW, ZB_STAT_LOOKUPS, ZB_STAT_HITS, ZB_STAT_GC_COUNT,
  ZB_STAT_GC_FREED, ZB_STAT_CAPACITY_INCREASES, ZB_STAT_MEMORY, ZB_STAT_VARS,
  ZB_STAT_CACHE_ENTRIES, ZB_STAT_CACHE_LOOKUPS, ZB_STAT_CACHE_HITS
};

typedef struct zb_ctx zb_ctx;

/* Growable stack. Operations share one stack of each kind per context and work from a saved size. */
typedef struct {
  int32_t *data;
  uint32_t size;
  uint32_t cap;
} zb_stack;

/*
 * Functions implemented by the host for user supplied code. Each returns 0, or ZB_ERR_HOST on failure. The host keeps
 * no reference to the context owner between calls, its per-call state is in host_env and host_self.
 */
typedef struct {
  int32_t (*callbacks)(zb_ctx *c, int32_t event);
  int32_t (*is_gc_required)(zb_ctx *c, int32_t *required);
  int32_t (*min_free_nodes)(zb_ctx *c, int32_t *nodes);
  int32_t (*advise_increment)(zb_ctx *c, int32_t *nodes);
} zb_host;

/* Cube iterator. Owns its stacks, as it stays open while the host runs user code. */
typedef struct {
  zb_stack work;
  zb_stack vars;
  uint32_t prot_mark;
} zb_iter;

/* A node independent of its format. */
typedef struct {
  int32_t var;
  int32_t p0;
  int32_t p1;
  uint32_t next;
  int32_t ref;
} zb_node_fields;

/* Format dependent functions, implemented once in zb_ops.inc. Node and variable arguments are validated. */
typedef struct {
  int32_t (*get_var)(zb_ctx *c, int32_t z);
  int32_t (*get_p0)(zb_ctx *c, int32_t z);
  int32_t (*get_p1)(zb_ctx *c, int32_t z);
  int32_t (*get_node)(zb_ctx *c, int32_t var, int32_t p0, int32_t p1);
  int32_t (*cube1)(zb_ctx *c, int32_t var);
  int32_t (*cube_n)(zb_ctx *c, int32_t *vars, int32_t n);
  int32_t (*has_cube_with_var)(zb_ctx *c, int32_t z, int32_t var);
  int32_t (*subset0)(zb_ctx *c, int32_t z, int32_t var);
  int32_t (*subset0_n)(zb_ctx *c, int32_t z, int32_t *vars, int32_t n);
  int32_t (*subset1)(zb_ctx *c, int32_t z, int32_t var);
  int32_t (*change)(zb_ctx *c, int32_t z, int32_t var);
  int32_t (*count)(zb_ctx *c, int32_t z);
  int32_t (*count_exact)(zb_ctx *c, int32_t z, uint8_t **bytes, size_t *len);
  int32_t (*union2)(zb_ctx *c, int32_t p, int32_t q);
  int32_t (*union_n)(zb_ctx *c, const int32_t *p, int32_t n);
  int32_t (*intersect)(zb_ctx *c, int32_t p, int32_t q);
  int32_t (*difference)(zb_ctx *c, int32_t p, int32_t q);
  int32_t (*multiply)(zb_ctx *c, int32_t p, int32_t q);
  int32_t (*divide)(zb_ctx *c, int32_t p, int32_t q);
  int32_t (*modulo)(zb_ctx *c, int32_t p, int32_t q);
  int32_t (*atomize)(zb_ctx *c, int32_t z);
  int32_t (*remove_base)(zb_ctx *c, int32_t z);
  int32_t (*contains)(zb_ctx *c, int32_t p, int32_t q);
  int32_t (*gc)(zb_ctx *c);
  int32_t (*gc_collect)(zb_ctx *c);
  int32_t (*inc_ref)(zb_ctx *c, int32_t z);
  int32_t (*dec_ref)(zb_ctx *c, int32_t z);
  int32_t (*get_ref)(zb_ctx *c, int32_t z);
  int32_t (*is_valid_zbdd)(zb_ctx *c, int32_t z);
  int32_t (*dependency)(zb_ctx *c, int32_t **out);
  int32_t (*as_single_cubes)(zb_ctx *c, int32_t z, int32_t **out);
  int32_t (*iter_open)(zb_ctx *c, int32_t z, zb_iter *it);
  int32_t (*iter_next_cube)(zb_ctx *c, zb_iter *it);
  int32_t (*cube_of_vars)(zb_ctx *c, const int32_t *vars, int32_t n);
  void (*read_node)(const zb_ctx *c, int32_t z, zb_node_fields *node);
  void (*write_node)(zb_ctx *c, int32_t z, const zb_node_fields *node);
  void (*rechain)(zb_ctx *c);
  int32_t (*grow)(zb_ctx *c, uint32_t new_capacity);
  void (*reset)(zb_ctx *c);
  int32_t (*init)(zb_ctx *c, int32_t capacity);
#ifdef ZB_TEST
  int32_t (*test_set_ref)(zb_ctx *c, int32_t z, int32_t ref);
#endif
  size_t node_size;
  size_t head_size;
} zb_ops;

extern const zb_ops zb_ops_small;
extern const zb_ops zb_ops_large;

struct zb_ctx {
  const zb_ops *ops;

  void *nodes;
  void *heads;

  uint32_t capacity;
  uint32_t free_count;
  uint32_t dead_count;
  uint32_t new_count;
  uint32_t next_free;
  uint32_t bucket_count;
  uint32_t hash_shift;
  int32_t last_var;

  uint8_t callbacks_running;
  uint8_t has_callbacks;
  uint8_t custom_advisor;

  zb_stack tasks;
  zb_stack results;
  zb_stack prot;

  /* operation cache: three 32-bit words per entry, being the low and high word of the key and the result */
  uint32_t *cache;
  uint32_t cache_entries;
  uint32_t cache_shift;
  uint64_t cache_lookups;
  uint64_t cache_hits;

  /* exact counts per node, as they don't fit a cache entry: allocated on demand, limited to the size of the cache */
  struct zb_exact_counts *exact_counts;

  /* reference counts exceeding the inline range, open addressing; key 0 is an empty slot */
  uint32_t *ovf_keys;
  uint32_t *ovf_counts;
  uint32_t ovf_cap;
  uint32_t ovf_size;

  uint64_t stat_lookups;
  uint64_t stat_hits;
  uint64_t stat_gc_freed;
  uint64_t stat_created;
  uint32_t stat_gc_count;
  uint32_t stat_capacity_increases;

  const zb_host *host;
  void *host_env;
  void *host_self;

  int32_t err_value;
  int32_t err_limit;
  int32_t err_param;
  int32_t err_extra;
  int32_t err_code;  /* set by the host for results which can't carry an error code */
};

/* Memory: zb_platform_*.c */
void *zb_platform_alloc(size_t size);
void *zb_platform_realloc(void *ptr, size_t size);
void zb_platform_free(void *ptr);

/* Memory, counted so that tests can assert that no block outlives its context. */
void *zb_alloc(size_t size);
void *zb_realloc(void *ptr, size_t size);
void zb_free(void *ptr);
int64_t zb_live_blocks(void);
int64_t zb_live_contexts(void);

#ifdef ZB_TEST
/* Lets the n-th allocation from now on fail, n = 0 disables the failure. */
void zb_test_fail_alloc(int32_t n);
/* Seeds the reference count of node z, as reaching high counts through zb_inc_ref takes too long. */
int32_t zb_test_set_ref(zb_ctx *c, int32_t z, int32_t ref);
#endif

/* Stacks */
int32_t zb_stack_init(zb_stack *s, uint32_t cap);
int32_t zb_stack_reserve(zb_stack *s, uint32_t room);
void zb_stack_free(zb_stack *s);

/* Maps node numbers (key >= 2) to values >= 0, returning -1 for an unknown key. */
typedef struct {
  uint32_t *keys;
  int32_t *values;
  uint32_t cap;
  uint32_t size;
} zb_map;

void zb_map_init(zb_map *m);
int32_t zb_map_get(const zb_map *m, int32_t key);
int32_t zb_map_put(zb_map *m, int32_t key, int32_t value);
void zb_map_free(zb_map *m);

/* Set of non-zero 64-bit values. */
typedef struct {
  uint64_t *keys;
  uint32_t cap;
  uint32_t size;
} zb_set64;

void zb_set64_init(zb_set64 *s);
int32_t zb_set64_add(zb_set64 *s, uint64_t key);  /* 1 if added, 0 if present, ZB_ERR_NOMEM */
void zb_set64_free(zb_set64 *s);

/* Arbitrary sized non-negative counts. Entry 0 has the value 0 and entry 1 has the value 1. */
typedef struct {
  uint64_t *limbs;
  size_t limbs_used;
  size_t limbs_cap;
  size_t *offsets;  /* offset of entry n in limbs, entry n ends at offsets[n + 1] */
  uint32_t entries;
  uint32_t entries_cap;
} zb_big;

/* Exact counts known for nodes. */
struct zb_exact_counts {
  zb_big big;
  zb_map entries;
};

int32_t zb_big_init(zb_big *b);
int32_t zb_big_add(zb_big *b, int32_t x, int32_t y);  /* new entry holding the sum, or ZB_ERR_NOMEM */
int32_t zb_big_export(const zb_big *b, int32_t x, uint8_t **bytes, size_t *len);
void zb_big_free(zb_big *b);

/* Sorts n values in ascending order. */
void zb_sort(int32_t *values, int32_t n);

/* Reference counts exceeding the inline range. */
uint32_t zb_ovf_get(const zb_ctx *c, uint32_t z);
int32_t zb_ovf_put(zb_ctx *c, uint32_t z, uint32_t count);
void zb_ovf_remove(zb_ctx *c, uint32_t z);

/* Smallest power of 2 not below capacity / 2. */
uint32_t zb_bucket_count(uint32_t capacity);
uint32_t zb_log2(uint32_t power_of_2);

/* Validation and error details, shared by both formats. */
int32_t zb_error(zb_ctx *c, int32_t code, int32_t value, int32_t limit, int32_t param, int32_t extra);

static inline int32_t zb_check_var(zb_ctx *c, int32_t var) {
  return var <= 0 || var > c->last_var ? zb_error(c, ZB_ERR_VAR, var, c->last_var, 0, 0) : var;
}

/*
 * Operation cache: direct mapped, so an entry replaces the one found at its position. Keys and results consist of node
 * numbers, which don't depend on the format, so the cache survives a format change. Node numbers are recycled only by a
 * garbage collection or by clearing, which empties the cache before any node is freed.
 *
 * A key holds the operation in 4 bits, the 1st operand in 29 bits and the 2nd operand, which can be a variable, in 31.
 */
void zb_cache_clear(zb_ctx *c);

/* The exact counts of the context, or NULL if an allocation fails. */
struct zb_exact_counts *zb_exact_counts_get(zb_ctx *c);
void zb_exact_counts_trim(zb_ctx *c);
size_t zb_exact_counts_memory(const zb_ctx *c);

static inline uint64_t zb_cache_key(int32_t op, int32_t p, int32_t q) {
  return ((uint64_t)(uint32_t)op << 60) | ((uint64_t)(uint32_t)p << 31) | (uint32_t)q;
}

static inline uint32_t *zb_cache_entry(const zb_ctx *c, uint64_t key) {
  return c->cache + 3 * (size_t)((key * UINT64_C(0x9E3779B97F4A7C15)) >> c->cache_shift);
}

/* Returns the cached result >= 0, or -1 if there is none. */
static inline int32_t zb_cache_get(zb_ctx *c, int32_t op, int32_t p, int32_t q) {
  const uint64_t key = zb_cache_key(op, p, q);
  const uint32_t *entry = zb_cache_entry(c, key);

  c->cache_lookups++;

  if (entry[0] != (uint32_t)key || entry[1] != (uint32_t)(key >> 32))
    return -1;

  c->cache_hits++;

  return (int32_t)entry[2];
}

static inline void zb_cache_put(zb_ctx *c, int32_t op, int32_t p, int32_t q, int32_t result) {
  const uint64_t key = zb_cache_key(op, p, q);
  uint32_t *entry = zb_cache_entry(c, key);

  entry[0] = (uint32_t)key;
  entry[1] = (uint32_t)(key >> 32);
  entry[2] = (uint32_t)result;
}

/*
 * Switches from the small to the large format with the given capacity. Node numbers don't change. On failure the small
 * format stays intact, unless only the capacity increase failed. A running operation must return ZB_RETRY afterwards.
 */
int32_t zb_migrate(zb_ctx *c, uint32_t new_capacity);

/* Host independent API: every call leaves the stacks as it found them. */
zb_ctx *zb_create(int32_t initial_capacity, int32_t cache_entries, const zb_host *host);
void zb_destroy(zb_ctx *c);
zb_format zb_get_format(const zb_ctx *c);
int64_t zb_stat(const zb_ctx *c, int32_t stat);

int32_t zb_create_var(zb_ctx *c);
int32_t zb_is_valid_var(const zb_ctx *c, int32_t var);
int32_t zb_get_var(zb_ctx *c, int32_t z);
int32_t zb_get_p0(zb_ctx *c, int32_t z);
int32_t zb_get_p1(zb_ctx *c, int32_t z);
int32_t zb_get_node(zb_ctx *c, int32_t var, int32_t p0, int32_t p1);
int32_t zb_cube1(zb_ctx *c, int32_t var);
int32_t zb_cube_n(zb_ctx *c, int32_t *vars, int32_t n);
int32_t zb_has_cube_with_var(zb_ctx *c, int32_t z, int32_t var);
int32_t zb_subset0(zb_ctx *c, int32_t z, int32_t var);
int32_t zb_subset0_n(zb_ctx *c, int32_t z, int32_t *vars, int32_t n);
int32_t zb_subset1(zb_ctx *c, int32_t z, int32_t var);
int32_t zb_change(zb_ctx *c, int32_t z, int32_t var);
int32_t zb_count(zb_ctx *c, int32_t z);
int32_t zb_count_exact(zb_ctx *c, int32_t z, uint8_t **bytes, size_t *len);
int32_t zb_union(zb_ctx *c, int32_t p, int32_t q);
int32_t zb_union_n(zb_ctx *c, const int32_t *p, int32_t n);
int32_t zb_intersect(zb_ctx *c, int32_t p, int32_t q);
int32_t zb_difference(zb_ctx *c, int32_t p, int32_t q);
int32_t zb_multiply(zb_ctx *c, int32_t p, int32_t q);
int32_t zb_divide(zb_ctx *c, int32_t p, int32_t q);
int32_t zb_modulo(zb_ctx *c, int32_t p, int32_t q);
int32_t zb_atomize(zb_ctx *c, int32_t z);
int32_t zb_remove_base(zb_ctx *c, int32_t z);
int32_t zb_contains(zb_ctx *c, int32_t p, int32_t q);
int32_t zb_gc(zb_ctx *c);
int32_t zb_clear(zb_ctx *c, int32_t phase);
int32_t zb_inc_ref(zb_ctx *c, int32_t z);
int32_t zb_dec_ref(zb_ctx *c, int32_t z);  /* 1 on an underflow, in which case the count is unchanged */
int32_t zb_get_ref(zb_ctx *c, int32_t z);
int32_t zb_is_valid_zbdd(zb_ctx *c, int32_t z);
int32_t zb_dependency(zb_ctx *c, int32_t **out);
int32_t zb_as_single_cubes(zb_ctx *c, int32_t z, int32_t **out);
int32_t zb_iter_open(zb_ctx *c, int32_t z, zb_iter **it);
int32_t zb_iter_next_cube(zb_ctx *c, zb_iter *it);  /* number of variables in it->vars, or ZB_ITER_END */
int32_t zb_iter_next_zbdd(zb_ctx *c, zb_iter *it);  /* node of the next cube, or ZB_ITER_END */
void zb_iter_close(zb_ctx *c, zb_iter *it);

#endif
