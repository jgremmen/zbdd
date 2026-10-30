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
#include "zb_core.h"

#define ZB_PHI UINT64_C(0x9E3779B97F4A7C15)
#define ZB_STACK_INITIAL 64u
#define ZB_TABLE_INITIAL 64u

static int64_t live_blocks;
static int64_t live_contexts;

#ifdef ZB_TEST
static int32_t fail_alloc_countdown;

void zb_test_fail_alloc(int32_t n) { fail_alloc_countdown = n; }

static int alloc_fails(void) {
  return fail_alloc_countdown > 0 && --fail_alloc_countdown == 0;
}
#else
#define alloc_fails() 0
#endif


void *zb_alloc(size_t size) {
  void *p = alloc_fails() ? NULL : zb_platform_alloc(size != 0 ? size : 1);

  if (p != NULL)
    __atomic_add_fetch(&live_blocks, 1, __ATOMIC_RELAXED);

  return p;
}


void *zb_realloc(void *ptr, size_t size) {
  void *p = alloc_fails() ? NULL : zb_platform_realloc(ptr, size != 0 ? size : 1);

  if (p != NULL && ptr == NULL)
    __atomic_add_fetch(&live_blocks, 1, __ATOMIC_RELAXED);

  return p;
}


void zb_free(void *ptr) {
  if (ptr != NULL) {
    zb_platform_free(ptr);
    __atomic_sub_fetch(&live_blocks, 1, __ATOMIC_RELAXED);
  }
}


int64_t zb_live_blocks(void) { return __atomic_load_n(&live_blocks, __ATOMIC_RELAXED); }
int64_t zb_live_contexts(void) { return __atomic_load_n(&live_contexts, __ATOMIC_RELAXED); }


int32_t zb_stack_init(zb_stack *s, uint32_t cap) {
  s->size = 0;
  s->cap = cap;
  s->data = zb_alloc(cap * sizeof(int32_t));

  return s->data != NULL ? 0 : ZB_ERR_NOMEM;
}


int32_t zb_stack_reserve(zb_stack *s, uint32_t room) {
  if (s->cap - s->size < room) {
    uint64_t cap = (uint64_t)s->cap * 2;

    if (cap < (uint64_t)s->size + room)
      cap = (uint64_t)s->size + room;

    if (cap > UINT32_MAX)
      return ZB_ERR_NOMEM;

    int32_t *data = zb_realloc(s->data, (size_t)cap * sizeof(int32_t));
    if (data == NULL)
      return ZB_ERR_NOMEM;

    s->data = data;
    s->cap = (uint32_t)cap;
  }

  return 0;
}


void zb_stack_free(zb_stack *s) {
  zb_free(s->data);
  s->data = NULL;
  s->size = s->cap = 0;
}


static inline uint32_t table_index(uint64_t key, uint32_t cap) {
  return (uint32_t)((key * ZB_PHI) >> 32) & (cap - 1);
}


void zb_map_init(zb_map *m) {
  m->keys = NULL;
  m->values = NULL;
  m->cap = 0;
  m->size = 0;
}


int32_t zb_map_get(const zb_map *m, int32_t key) {
  if (m->cap == 0)
    return -1;

  for (uint32_t i = table_index((uint32_t)key, m->cap);; i = (i + 1) & (m->cap - 1)) {
    if (m->keys[i] == (uint32_t)key)
      return m->values[i];
    if (m->keys[i] == 0)
      return -1;
  }
}


static void map_insert(uint32_t *keys, int32_t *values, uint32_t cap, uint32_t key, int32_t value) {
  uint32_t i = table_index(key, cap);

  while (keys[i] != 0 && keys[i] != key)
    i = (i + 1) & (cap - 1);

  keys[i] = key;
  values[i] = value;
}


int32_t zb_map_put(zb_map *m, int32_t key, int32_t value) {
  if ((uint64_t)(m->size + 1) * 4 > (uint64_t)m->cap * 3) {
    const uint32_t cap = m->cap != 0 ? m->cap * 2 : ZB_TABLE_INITIAL;
    uint32_t *keys = zb_alloc(cap * sizeof(uint32_t));
    int32_t *values = zb_alloc(cap * sizeof(int32_t));

    if (keys == NULL || values == NULL) {
      zb_free(keys);
      zb_free(values);
      return ZB_ERR_NOMEM;
    }

    memset(keys, 0, cap * sizeof(uint32_t));

    for (uint32_t i = 0; i < m->cap; i++)
      if (m->keys[i] != 0)
        map_insert(keys, values, cap, m->keys[i], m->values[i]);

    zb_free(m->keys);
    zb_free(m->values);

    m->keys = keys;
    m->values = values;
    m->cap = cap;
  }

  map_insert(m->keys, m->values, m->cap, (uint32_t)key, value);
  m->size++;

  return 0;
}


void zb_map_free(zb_map *m) {
  zb_free(m->keys);
  zb_free(m->values);
  zb_map_init(m);
}


void zb_set64_init(zb_set64 *s) {
  s->keys = NULL;
  s->cap = 0;
  s->size = 0;
}


int32_t zb_set64_add(zb_set64 *s, uint64_t key) {
  if ((uint64_t)(s->size + 1) * 4 > (uint64_t)s->cap * 3) {
    const uint32_t cap = s->cap != 0 ? s->cap * 2 : ZB_TABLE_INITIAL;
    uint64_t *keys = zb_alloc(cap * sizeof(uint64_t));

    if (keys == NULL)
      return ZB_ERR_NOMEM;

    memset(keys, 0, cap * sizeof(uint64_t));

    for (uint32_t n = 0; n < s->cap; n++) {
      if (s->keys[n] != 0) {
        uint32_t i = table_index(s->keys[n], cap);

        while (keys[i] != 0)
          i = (i + 1) & (cap - 1);

        keys[i] = s->keys[n];
      }
    }

    zb_free(s->keys);

    s->keys = keys;
    s->cap = cap;
  }

  for (uint32_t i = table_index(key, s->cap);; i = (i + 1) & (s->cap - 1)) {
    if (s->keys[i] == key)
      return 0;

    if (s->keys[i] == 0) {
      s->keys[i] = key;
      s->size++;
      return 1;
    }
  }
}


void zb_set64_free(zb_set64 *s) {
  zb_free(s->keys);
  zb_set64_init(s);
}


int32_t zb_big_init(zb_big *b) {
  b->limbs_cap = 64;
  b->entries_cap = 64;
  b->limbs = zb_alloc(b->limbs_cap * sizeof(uint64_t));
  b->offsets = zb_alloc((b->entries_cap + 1) * sizeof(size_t));

  if (b->limbs == NULL || b->offsets == NULL) {
    zb_big_free(b);
    return ZB_ERR_NOMEM;
  }

  /* entry 0 is the value 0 without limbs, entry 1 is the value 1 */
  b->limbs[0] = 1;
  b->limbs_used = 1;
  b->offsets[0] = 0;
  b->offsets[1] = 0;
  b->offsets[2] = 1;
  b->entries = 2;

  return 0;
}


int32_t zb_big_add(zb_big *b, int32_t x, int32_t y) {
  const size_t x_len = b->offsets[x + 1] - b->offsets[x];
  const size_t y_len = b->offsets[y + 1] - b->offsets[y];
  const size_t max_len = x_len > y_len ? x_len : y_len;

  if (b->limbs_used + max_len + 1 > b->limbs_cap) {
    size_t cap = b->limbs_cap * 2;

    if (cap < b->limbs_used + max_len + 1)
      cap = b->limbs_used + max_len + 1;

    uint64_t *limbs = zb_realloc(b->limbs, cap * sizeof(uint64_t));
    if (limbs == NULL)
      return ZB_ERR_NOMEM;

    b->limbs = limbs;
    b->limbs_cap = cap;
  }

  if (b->entries == b->entries_cap) {
    size_t *offsets = zb_realloc(b->offsets, ((size_t)b->entries_cap * 2 + 1) * sizeof(size_t));
    if (offsets == NULL)
      return ZB_ERR_NOMEM;

    b->offsets = offsets;
    b->entries_cap *= 2;
  }

  const uint64_t *xs = b->limbs + b->offsets[x];
  const uint64_t *ys = b->limbs + b->offsets[y];
  uint64_t *r = b->limbs + b->limbs_used;
  uint64_t carry = 0;
  size_t n = 0;

  for (; n < max_len; n++) {
    const uint64_t a = n < x_len ? xs[n] : 0;
    const uint64_t s = a + (n < y_len ? ys[n] : 0);
    const uint64_t t = s + carry;

    carry = (uint64_t)(s < a) + (uint64_t)(t < s);
    r[n] = t;
  }

  if (carry != 0)
    r[n++] = carry;

  b->limbs_used += n;
  b->offsets[++b->entries] = b->limbs_used;

  return (int32_t)(b->entries - 1);
}


int32_t zb_big_export(const zb_big *b, int32_t x, uint8_t **bytes, size_t *len) {
  const size_t limbs = b->offsets[x + 1] - b->offsets[x];
  const size_t size = limbs * sizeof(uint64_t);
  uint8_t *out = zb_alloc(size);

  if (out == NULL)
    return ZB_ERR_NOMEM;

  /* big-endian magnitude, as expected by java.math.BigInteger */
  for (size_t n = 0; n < limbs; n++) {
    const uint64_t limb = b->limbs[b->offsets[x] + n];

    for (size_t i = 0; i < sizeof(uint64_t); i++)
      out[size - 1 - (n * sizeof(uint64_t) + i)] = (uint8_t)(limb >> (i * 8));
  }

  *bytes = out;
  *len = size;

  return 0;
}


void zb_big_free(zb_big *b) {
  zb_free(b->limbs);
  zb_free(b->offsets);
  b->limbs = NULL;
  b->offsets = NULL;
}


/* Heapsort: no recursion, no additional memory and no dependency on the C runtime. */
void zb_sort(int32_t *values, int32_t n) {
  for (int32_t start = n / 2 - 1, end = n - 1; end > 0;) {
    int32_t root;

    if (start >= 0)
      root = start--;
    else {
      const int32_t tmp = values[0];

      values[0] = values[end];
      values[end] = tmp;
      end--;
      root = 0;
    }

    for (int32_t child; (child = 2 * root + 1) <= end; root = child) {
      if (child < end && values[child] < values[child + 1])
        child++;

      if (values[root] >= values[child])
        break;

      const int32_t tmp = values[root];

      values[root] = values[child];
      values[child] = tmp;
    }
  }
}


uint32_t zb_ovf_get(const zb_ctx *c, uint32_t z) {
  for (uint32_t i = table_index(z, c->ovf_cap);; i = (i + 1) & (c->ovf_cap - 1))
    if (c->ovf_keys[i] == z)
      return c->ovf_counts[i];
}


int32_t zb_ovf_put(zb_ctx *c, uint32_t z, uint32_t count) {
  /* updating an existing count never allocates, so decRef can't fail */
  if (c->ovf_cap != 0) {
    for (uint32_t i = table_index(z, c->ovf_cap); c->ovf_keys[i] != 0; i = (i + 1) & (c->ovf_cap - 1)) {
      if (c->ovf_keys[i] == z) {
        c->ovf_counts[i] = count;
        return 0;
      }
    }
  }

  if ((uint64_t)(c->ovf_size + 1) * 4 > (uint64_t)c->ovf_cap * 3) {
    const uint32_t cap = c->ovf_cap != 0 ? c->ovf_cap * 2 : 16;
    uint32_t *keys = zb_alloc(cap * sizeof(uint32_t));
    uint32_t *counts = zb_alloc(cap * sizeof(uint32_t));

    if (keys == NULL || counts == NULL) {
      zb_free(keys);
      zb_free(counts);
      return ZB_ERR_NOMEM;
    }

    memset(keys, 0, cap * sizeof(uint32_t));

    for (uint32_t n = 0; n < c->ovf_cap; n++) {
      if (c->ovf_keys[n] != 0) {
        uint32_t i = table_index(c->ovf_keys[n], cap);

        while (keys[i] != 0)
          i = (i + 1) & (cap - 1);

        keys[i] = c->ovf_keys[n];
        counts[i] = c->ovf_counts[n];
      }
    }

    zb_free(c->ovf_keys);
    zb_free(c->ovf_counts);

    c->ovf_keys = keys;
    c->ovf_counts = counts;
    c->ovf_cap = cap;
  }

  uint32_t i = table_index(z, c->ovf_cap);

  while (c->ovf_keys[i] != 0)
    i = (i + 1) & (c->ovf_cap - 1);

  c->ovf_keys[i] = z;
  c->ovf_counts[i] = count;
  c->ovf_size++;

  return 0;
}


/* Backward shift deletion, so lookups never need tombstones. */
void zb_ovf_remove(zb_ctx *c, uint32_t z) {
  const uint32_t mask = c->ovf_cap - 1;
  uint32_t i = table_index(z, c->ovf_cap);

  while (c->ovf_keys[i] != z)
    i = (i + 1) & mask;

  for (uint32_t j = i;;) {
    j = (j + 1) & mask;

    if (c->ovf_keys[j] == 0)
      break;

    const uint32_t home = table_index(c->ovf_keys[j], c->ovf_cap);

    if (((j - home) & mask) >= ((j - i) & mask)) {
      c->ovf_keys[i] = c->ovf_keys[j];
      c->ovf_counts[i] = c->ovf_counts[j];
      i = j;
    }
  }

  c->ovf_keys[i] = 0;
  c->ovf_size--;
}


uint32_t zb_bucket_count(uint32_t capacity) {
  const uint32_t wanted = capacity / 2;
  uint32_t buckets = 4;

  while (buckets < wanted)
    buckets *= 2;

  return buckets;
}


uint32_t zb_log2(uint32_t power_of_2) {
  uint32_t bits = 0;

  while ((UINT32_C(1) << bits) < power_of_2)
    bits++;

  return bits;
}


int32_t zb_error(zb_ctx *c, int32_t code, int32_t value, int32_t limit, int32_t param, int32_t extra) {
  c->err_value = value;
  c->err_limit = limit;
  c->err_param = param;
  c->err_extra = extra;

  return code;
}


static int32_t cache_init(zb_ctx *c, int32_t entries) {
  uint32_t rounded = ZB_CACHE_MIN_ENTRIES;

  while ((int64_t)rounded < (int64_t)entries && rounded < ZB_CACHE_MAX_ENTRIES)
    rounded *= 2;

  c->cache = zb_alloc((size_t)rounded * 3 * sizeof(uint32_t));

  if (c->cache == NULL)
    return ZB_ERR_NOMEM;

  c->cache_entries = rounded;
  c->cache_shift = 64 - zb_log2(rounded);

  zb_cache_clear(c);

  return 0;
}


static void exact_counts_free(zb_ctx *c) {
  if (c->exact_counts != NULL) {
    zb_big_free(&c->exact_counts->big);
    zb_map_free(&c->exact_counts->entries);
    zb_free(c->exact_counts);
    c->exact_counts = NULL;
  }
}


void zb_cache_clear(zb_ctx *c) {
  memset(c->cache, 0, (size_t)c->cache_entries * 3 * sizeof(uint32_t));

  exact_counts_free(c);
}


struct zb_exact_counts *zb_exact_counts_get(zb_ctx *c) {
  if (c->exact_counts == NULL) {
    struct zb_exact_counts *counts = zb_alloc(sizeof(struct zb_exact_counts));

    if (counts == NULL)
      return NULL;

    if (zb_big_init(&counts->big) != 0) {
      zb_free(counts);
      return NULL;
    }

    zb_map_init(&counts->entries);
    c->exact_counts = counts;
  }

  return c->exact_counts;
}


size_t zb_exact_counts_memory(const zb_ctx *c) {
  const struct zb_exact_counts *counts = c->exact_counts;

  return counts == NULL ? 0 : sizeof(struct zb_exact_counts) +
      counts->big.limbs_cap * sizeof(uint64_t) +
      ((size_t)counts->big.entries_cap + 1) * sizeof(size_t) +
      (size_t)counts->entries.cap * (sizeof(uint32_t) + sizeof(int32_t));
}


/* Large counts of many nodes can take more memory than the cache, the counts are simply computed again in that case. */
void zb_exact_counts_trim(zb_ctx *c) {
  if (zb_exact_counts_memory(c) > (size_t)c->cache_entries * 3 * sizeof(uint32_t))
    exact_counts_free(c);
}


zb_ctx *zb_create(int32_t initial_capacity, int32_t cache_entries, const zb_host *host) {
  zb_ctx *c = zb_alloc(sizeof(zb_ctx));

  if (c == NULL)
    return NULL;

  memset(c, 0, sizeof(zb_ctx));

  /* a context starts with the small format, unless a custom advisor asks for more than it can hold */
  c->ops = initial_capacity > ZB_SMALL_MAX_NODES ? &zb_ops_large : &zb_ops_small;
  c->host = host;

  if (zb_stack_init(&c->tasks, ZB_STACK_INITIAL) != 0 ||
      zb_stack_init(&c->results, ZB_STACK_INITIAL) != 0 ||
      zb_stack_init(&c->prot, ZB_STACK_INITIAL) != 0 ||
      cache_init(c, cache_entries) != 0 ||
      c->ops->init(c, initial_capacity) != 0) {
    zb_stack_free(&c->tasks);
    zb_stack_free(&c->results);
    zb_stack_free(&c->prot);
    zb_free(c->cache);
    zb_free(c->nodes);
    zb_free(c->heads);
    zb_free(c);
    return NULL;
  }

  __atomic_add_fetch(&live_contexts, 1, __ATOMIC_RELAXED);

  return c;
}


void zb_destroy(zb_ctx *c) {
  zb_free(c->nodes);
  zb_free(c->heads);
  zb_free(c->ovf_keys);
  zb_free(c->ovf_counts);
  zb_free(c->cache);
  exact_counts_free(c);
  zb_stack_free(&c->tasks);
  zb_stack_free(&c->results);
  zb_stack_free(&c->prot);
  zb_free(c);

  __atomic_sub_fetch(&live_contexts, 1, __ATOMIC_RELAXED);
}


zb_format zb_get_format(const zb_ctx *c) { return c->ops == &zb_ops_large ? ZB_FORMAT_LARGE : ZB_FORMAT_SMALL; }


int64_t zb_stat(const zb_ctx *c, int32_t stat) {
  switch (stat) {
    case ZB_STAT_CAPACITY: return c->capacity;
    case ZB_STAT_FREE: return c->free_count;
    case ZB_STAT_DEAD: return c->dead_count;
    case ZB_STAT_NEW: return c->new_count;
    case ZB_STAT_LOOKUPS: return (int64_t)c->stat_lookups;
    case ZB_STAT_HITS: return (int64_t)c->stat_hits;
    case ZB_STAT_GC_COUNT: return c->stat_gc_count;
    case ZB_STAT_GC_FREED: return (int64_t)c->stat_gc_freed;
    case ZB_STAT_CAPACITY_INCREASES: return c->stat_capacity_increases;
    case ZB_STAT_VARS: return c->last_var;
    case ZB_STAT_CACHE_ENTRIES: return c->cache_entries;
    case ZB_STAT_CACHE_LOOKUPS: return (int64_t)c->cache_lookups;
    case ZB_STAT_CACHE_HITS: return (int64_t)c->cache_hits;
    case ZB_STAT_MEMORY:
      return (int64_t)(sizeof(zb_ctx) +
          (size_t)c->capacity * c->ops->node_size +
          (size_t)c->bucket_count * c->ops->head_size +
          ((size_t)c->tasks.cap + c->results.cap + c->prot.cap) * sizeof(int32_t) +
          (size_t)c->ovf_cap * 2 * sizeof(uint32_t) +
          (size_t)c->cache_entries * 3 * sizeof(uint32_t) +
          zb_exact_counts_memory(c));
    default: return -1;
  }
}


int32_t zb_migrate(zb_ctx *c, uint32_t new_capacity) {
  const uint32_t buckets = zb_bucket_count(c->capacity);
  void *nodes = zb_alloc((size_t)c->capacity * zb_ops_large.node_size);
  void *heads = zb_alloc((size_t)buckets * zb_ops_large.head_size);

  if (nodes == NULL || heads == NULL) {
    zb_free(nodes);
    zb_free(heads);
    return ZB_ERR_NOMEM;
  }

  /* writes to the new table through a copy of the context, while the original context reads from the old one */
  zb_ctx view = *c;

  view.nodes = nodes;

  for (uint32_t z = 0; z < c->capacity; z++) {
    zb_node_fields node;

    c->ops->read_node(c, (int32_t)z, &node);
    zb_ops_large.write_node(&view, (int32_t)z, &node);

    /* this count moves into the node, removing an entry never allocates */
    if (node.ref > ZB_SMALL_REF_INLINE_MAX && node.ref <= ZB_LARGE_REF_INLINE_MAX)
      zb_ovf_remove(c, z);
  }

  zb_free(c->nodes);
  zb_free(c->heads);

  c->nodes = nodes;
  c->heads = heads;
  c->ops = &zb_ops_large;

  c->ops->rechain(c);

  return new_capacity > c->capacity ? c->ops->grow(c, new_capacity) : 0;
}


int32_t zb_create_var(zb_ctx *c) {
  if (zb_get_format(c) == ZB_FORMAT_SMALL) {
    if (c->last_var == ZB_SMALL_MAX_VAR) {
      const int32_t rc = zb_migrate(c, c->capacity);

      if (rc < 0)
        return rc;
    }
  } else if (c->last_var == ZB_LARGE_MAX_VAR)
    return ZB_ERR_VAR_COUNT;

  return ++c->last_var;
}


int32_t zb_is_valid_var(const zb_ctx *c, int32_t var) { return var > 0 && var <= c->last_var; }


#ifdef ZB_TEST
int32_t zb_test_set_ref(zb_ctx *c, int32_t z, int32_t ref) { return c->ops->test_set_ref(c, z, ref); }
#endif


/*
 * Every entry point restores the stacks, so a failing operation never leaves nodes protected. An operation interrupted
 * by a change to the large format runs again. The nodes it has created until then are found by that run, so these
 * lookups are not counted as hits, as far as they have not been freed by a garbage collection in the meantime.
 */
#define ZB_ENTRY(call)                                                         \
  const uint32_t prot_mark_ = c->prot.size;                                    \
  const uint32_t tasks_mark_ = c->tasks.size;                                  \
  const uint32_t results_mark_ = c->results.size;                              \
  const uint64_t lookups_ = c->stat_lookups;                                   \
  const uint64_t hits_ = c->stat_hits;                                         \
  const uint64_t created_ = c->stat_created;                                   \
  int32_t rc_ = (call);                                                        \
  if (c->prot.size > prot_mark_)                                               \
    c->prot.size = prot_mark_;                                                 \
  c->tasks.size = tasks_mark_;                                                 \
  c->results.size = results_mark_;                                             \
  if (rc_ == ZB_RETRY) {                                                       \
    const uint64_t created_by_first_run_ = c->stat_created - created_;         \
    c->stat_lookups = lookups_;                                                \
    c->stat_hits = 0;                                                          \
    rc_ = (call);                                                              \
    c->stat_hits = hits_ + (c->stat_hits > created_by_first_run_ ?             \
        c->stat_hits - created_by_first_run_ : 0);                             \
    if (c->prot.size > prot_mark_)                                             \
      c->prot.size = prot_mark_;                                               \
    c->tasks.size = tasks_mark_;                                               \
    c->results.size = results_mark_;                                           \
  }                                                                            \
  return rc_

int32_t zb_get_var(zb_ctx *c, int32_t z) { return c->ops->get_var(c, z); }
int32_t zb_get_p0(zb_ctx *c, int32_t z) { return c->ops->get_p0(c, z); }
int32_t zb_get_p1(zb_ctx *c, int32_t z) { return c->ops->get_p1(c, z); }
int32_t zb_get_node(zb_ctx *c, int32_t var, int32_t p0, int32_t p1) { ZB_ENTRY(c->ops->get_node(c, var, p0, p1)); }
int32_t zb_cube1(zb_ctx *c, int32_t var) { ZB_ENTRY(c->ops->cube1(c, var)); }
int32_t zb_cube_n(zb_ctx *c, int32_t *vars, int32_t n) { ZB_ENTRY(c->ops->cube_n(c, vars, n)); }
int32_t zb_has_cube_with_var(zb_ctx *c, int32_t z, int32_t var) { ZB_ENTRY(c->ops->has_cube_with_var(c, z, var)); }
int32_t zb_subset0(zb_ctx *c, int32_t z, int32_t var) { ZB_ENTRY(c->ops->subset0(c, z, var)); }
int32_t zb_subset0_n(zb_ctx *c, int32_t z, int32_t *vars, int32_t n) { ZB_ENTRY(c->ops->subset0_n(c, z, vars, n)); }
int32_t zb_subset1(zb_ctx *c, int32_t z, int32_t var) { ZB_ENTRY(c->ops->subset1(c, z, var)); }
int32_t zb_change(zb_ctx *c, int32_t z, int32_t var) { ZB_ENTRY(c->ops->change(c, z, var)); }
int32_t zb_count(zb_ctx *c, int32_t z) { ZB_ENTRY(c->ops->count(c, z)); }
int32_t zb_count_exact(zb_ctx *c, int32_t z, uint8_t **bytes, size_t *len) { ZB_ENTRY(c->ops->count_exact(c, z, bytes, len)); }
int32_t zb_union(zb_ctx *c, int32_t p, int32_t q) { ZB_ENTRY(c->ops->union2(c, p, q)); }
int32_t zb_union_n(zb_ctx *c, const int32_t *p, int32_t n) { ZB_ENTRY(c->ops->union_n(c, p, n)); }
int32_t zb_intersect(zb_ctx *c, int32_t p, int32_t q) { ZB_ENTRY(c->ops->intersect(c, p, q)); }
int32_t zb_difference(zb_ctx *c, int32_t p, int32_t q) { ZB_ENTRY(c->ops->difference(c, p, q)); }
int32_t zb_multiply(zb_ctx *c, int32_t p, int32_t q) { ZB_ENTRY(c->ops->multiply(c, p, q)); }
int32_t zb_divide(zb_ctx *c, int32_t p, int32_t q) { ZB_ENTRY(c->ops->divide(c, p, q)); }
int32_t zb_modulo(zb_ctx *c, int32_t p, int32_t q) { ZB_ENTRY(c->ops->modulo(c, p, q)); }
int32_t zb_atomize(zb_ctx *c, int32_t z) { ZB_ENTRY(c->ops->atomize(c, z)); }
int32_t zb_remove_base(zb_ctx *c, int32_t z) { ZB_ENTRY(c->ops->remove_base(c, z)); }
int32_t zb_contains(zb_ctx *c, int32_t p, int32_t q) { ZB_ENTRY(c->ops->contains(c, p, q)); }
int32_t zb_gc(zb_ctx *c) { ZB_ENTRY(c->ops->gc(c)); }
int32_t zb_inc_ref(zb_ctx *c, int32_t z) { return c->ops->inc_ref(c, z); }
int32_t zb_dec_ref(zb_ctx *c, int32_t z) { return c->ops->dec_ref(c, z); }
int32_t zb_get_ref(zb_ctx *c, int32_t z) { return c->ops->get_ref(c, z); }
int32_t zb_is_valid_zbdd(zb_ctx *c, int32_t z) { return c->ops->is_valid_zbdd(c, z); }

int32_t zb_dependency(zb_ctx *c, int32_t **out) {
  /* make sure we're only dealing with valid nodes */
  const int32_t freed = zb_gc(c);

  if (freed < 0)
    return freed;

  ZB_ENTRY(c->ops->dependency(c, out));
}

int32_t zb_as_single_cubes(zb_ctx *c, int32_t z, int32_t **out) { ZB_ENTRY(c->ops->as_single_cubes(c, z, out)); }
int32_t zb_iter_next_cube(zb_ctx *c, zb_iter *it) { return c->ops->iter_next_cube(c, it); }

/* Only creating the node may run again, the iterator must not advance twice. */
int32_t zb_iter_next_zbdd(zb_ctx *c, zb_iter *it) {
  const int32_t n = c->ops->iter_next_cube(c, it);

  if (n < 0)
    return n;

  ZB_ENTRY(c->ops->cube_of_vars(c, it->vars.data, n));
}


int32_t zb_clear(zb_ctx *c, int32_t phase) {
  if (c->callbacks_running)
    return ZB_ERR_CB_CLEAR;

  int32_t rc = 0;

  switch (phase) {
    case ZB_CLEAR_RESET:
      c->ops->reset(c);
      break;

    case ZB_CLEAR_BEFORE:
    case ZB_CLEAR_AFTER:
      if (c->has_callbacks) {
        c->callbacks_running = 1;
        rc = c->host->callbacks(c, phase == ZB_CLEAR_BEFORE ? ZB_EVENT_BEFORE_CLEAR : ZB_EVENT_AFTER_CLEAR);
        c->callbacks_running = 0;
      }
      break;
  }

  return rc;
}


int32_t zb_iter_open(zb_ctx *c, int32_t z, zb_iter **it) {
  zb_iter *iter = zb_alloc(sizeof(zb_iter));

  if (iter == NULL)
    return ZB_ERR_NOMEM;

  const int32_t rc = c->ops->iter_open(c, z, iter);

  if (rc < 0) {
    zb_free(iter);
    return rc;
  }

  *it = iter;

  return 0;
}


void zb_iter_close(zb_ctx *c, zb_iter *it) {
  if (c->prot.size > it->prot_mark)
    c->prot.size = it->prot_mark;

  zb_stack_free(&it->work);
  zb_stack_free(&it->vars);
  zb_free(it);
}
