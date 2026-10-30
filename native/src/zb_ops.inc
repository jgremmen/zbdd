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

/*
 * All format dependent algorithms, included once per format. This is a port of ZbddImpl: traversals use the stacks of
 * the context instead of recursion, working from the stack size found on entry. Stacks only ever grow, so room reserved
 * by RESERVE() stays valid when an operation invokes another one, as that one restores the stack sizes it found.
 *
 * Node pointers are never cached, as creating a node can move the node table.
 *
 * The small format can change to the large format while an operation is running, in get_node or in user code invoked
 * by it. Results of finished operations stay valid as node numbers don't change, so ZB_RETRY travels up like an error
 * and ZB_ENTRY runs the operation again with the functions of the large format.
 */

#define TASK_COMPUTE 0
#define TASK_COMBINE 1
#define TASK_COMBINE_P1 2
#define TASK_CONTINUE 3
#define TASK_COMBINE_PRODUCTS 4
#define TASK_STORE 5  /* p, q: cache the result on top of the results stack for p and q */

#define T_PUSH(v) (c->tasks.data[c->tasks.size++] = (int32_t)(v))
#define T_POP() (c->tasks.data[--c->tasks.size])
#define R_PUSH(v) (c->results.data[c->results.size++] = (int32_t)(v))
#define R_POP() (c->results.data[--c->results.size])
#define R_TOP() (c->results.data[c->results.size - 1])
#define PROTECT(z) do { if ((z) >= 2) c->prot.data[c->prot.size++] = (int32_t)(z); } while (0)

/* Operands of commutative operations are ordered, so both orders share a cache entry. */
#define MIN_NODE(p, q) ((p) < (q) ? (p) : (q))
#define MAX_NODE(p, q) ((p) < (q) ? (q) : (p))

/* Runs after the tasks pushed following this one. Operands stay reachable until then, so they can't be recycled. */
#define PUSH_STORE(p, q)   \
  do {                     \
    T_PUSH(p);             \
    T_PUSH(q);             \
    T_PUSH(TASK_STORE);    \
  } while (0)

#define TRY(x) do { const int32_t rc_ = (x); if (rc_ < 0) return rc_; } while (0)
#define TRY_SET(v, x) do { (v) = (x); if ((v) < 0) return (v); } while (0)

/* Room for one iteration of any operation loop. */
#define RESERVE()                                                                  \
  do {                                                                             \
    if (c->tasks.cap - c->tasks.size < 24 || c->results.cap - c->results.size < 4 || \
        c->prot.cap - c->prot.size < 8)                                            \
      TRY(reserve_iteration(c));                                                   \
  } while (0)

static int32_t reserve_iteration(zb_ctx *c) {
  if (zb_stack_reserve(&c->tasks, 24) != 0 || zb_stack_reserve(&c->results, 4) != 0 ||
      zb_stack_reserve(&c->prot, 8) != 0)
    return ZB_ERR_NOMEM;

  return 0;
}

static int32_t protect2(zb_ctx *c, int32_t p, int32_t q) {
  if (zb_stack_reserve(&c->prot, 2) != 0)
    return ZB_ERR_NOMEM;

  PROTECT(p);
  PROTECT(q);

  return 0;
}

static inline int32_t v_of(const zb_ctx *c, int32_t z) { return z < 2 ? -1 : n_var(c, z); }

static inline int32_t check_zbdd(zb_ctx *c, int32_t z, int32_t param) {
  if (z < 0 || (uint32_t)z >= c->capacity)
    return zb_error(c, ZB_ERR_RANGE, z, (int32_t)c->capacity - 1, param, 0);

  if (z >= 2 && n_var(c, z) == 0)
    return zb_error(c, ZB_ERR_INVALID, z, 0, param, 0);

  return z;
}

static inline void chain_prepend(zb_ctx *c, int32_t z, uint32_t h) {
  n_set_next(c, z, head_get(c, h));
  head_set(c, h, (uint32_t)z);
}

static void set_buckets(zb_ctx *c, uint32_t buckets) {
  c->bucket_count = buckets;
  c->hash_shift = 64 - zb_log2(buckets);
}

static inline void heads_clear(zb_ctx *c) { memset(c->heads, 0, (size_t)c->bucket_count * sizeof(zb_head)); }


/*
 * Reference counts
 */

static inline int ref_is_positive(uint32_t refs) { return refs != 0 && refs != F_REF_NEW; }

static int32_t ref_get(const zb_ctx *c, int32_t z) {
  const uint32_t refs = n_refs(c, z);

  return refs == F_REF_NEW ? -1 : refs == F_REF_OVERFLOW ? (int32_t)zb_ovf_get(c, (uint32_t)z) : (int32_t)refs;
}

/* Sets a count >= 0, which can only fail when moving the count into the overflow table. */
static int32_t ref_set(zb_ctx *c, int32_t z, int32_t ref) {
  const uint32_t refs = n_refs(c, z);

  if ((uint32_t)ref > F_REF_INLINE_MAX) {
    TRY(zb_ovf_put(c, (uint32_t)z, (uint32_t)ref));
    n_set_refs(c, z, F_REF_OVERFLOW);
  } else {
    if (refs == F_REF_OVERFLOW)
      zb_ovf_remove(c, (uint32_t)z);

    n_set_refs(c, z, (uint32_t)ref);
  }

  return 0;
}

static int32_t op_inc_ref(zb_ctx *c, int32_t z) {
  TRY(check_zbdd(c, z, ZB_PARAM_ZBDD));

  if (z >= 2) {
    const int32_t ref = ref_get(c, z);

    if (ref == -1) {
      n_set_refs(c, z, 1);
      c->new_count--;
    } else if (ref != ZB_REF_LOCKED) {
      TRY(ref_set(c, z, ref + 1));

      if (ref == 0)
        c->dead_count--;
    }
  }

  return z;
}

/* Returns 1 on an underflow, 0 otherwise. */
static int32_t op_dec_ref(zb_ctx *c, int32_t z) {
  TRY(check_zbdd(c, z, ZB_PARAM_ZBDD));

  if (z >= 2) {
    const int32_t ref = ref_get(c, z);

    if (ref <= 0)
      return 1;

    if (ref != ZB_REF_LOCKED) {
      TRY(ref_set(c, z, ref - 1));

      if (ref == 1)
        c->dead_count++;
    }
  }

  return 0;
}

static int32_t op_get_ref(zb_ctx *c, int32_t z) {
  TRY(check_zbdd(c, z, ZB_PARAM_ZBDD));

  return z < 2 ? 1 : ref_get(c, z);
}

static int32_t op_is_valid_zbdd(zb_ctx *c, int32_t z) {
  return z < 2 ? z >= 0 : (uint32_t)z < c->capacity && n_var(c, z) > 0;
}


/*
 * Capacity advisor. The default advisor equals DefaultCapacityAdvisor and needs no call to the host.
 */

static int32_t advisor_is_gc_required(zb_ctx *c, int32_t *required) {
  if (c->custom_advisor)
    return c->host->is_gc_required(c, required);

  *required = c->capacity > 250000 || c->dead_count + c->new_count > c->capacity / 10;

  return 0;
}

static int32_t advisor_min_free(zb_ctx *c, int32_t *nodes) {
  if (c->custom_advisor)
    return c->host->min_free_nodes(c, nodes);

  *nodes = (int32_t)(c->capacity / 20);

  return 0;
}

static int32_t advisor_increment(zb_ctx *c, int32_t *nodes) {
  if (c->custom_advisor)
    return c->host->advise_increment(c, nodes);

  *nodes = (int32_t)(c->capacity < 500000 ? c->capacity * 3 / 2 : (c->capacity / 10) * 3);

  return 0;
}


/*
 * Garbage collection
 */

static int32_t invoke_callbacks(zb_ctx *c, int32_t event) {
  if (!c->has_callbacks)
    return 0;

  c->callbacks_running = 1;
  const int32_t rc = c->host->callbacks(c, event);
  c->callbacks_running = 0;

  return rc;
}

static int32_t gc_mark_node(zb_ctx *c, int32_t zbdd) {
  const uint32_t base = c->tasks.size;

  if (zb_stack_reserve(&c->tasks, 1) != 0)
    return ZB_ERR_NOMEM;

  T_PUSH(zbdd);

  while (c->tasks.size > base) {
    const int32_t z = T_POP();

    if (!n_is_marked(c, z)) {
      if (zb_stack_reserve(&c->tasks, 2) != 0)
        return ZB_ERR_NOMEM;

      n_mark(c, z);

      const int32_t p0 = n_p0(c, z);
      const int32_t p1 = n_p1(c, z);

      if (p0 >= 2)
        T_PUSH(p0);
      if (p1 >= 2)
        T_PUSH(p1);
    }
  }

  return 0;
}

static int32_t gc_mark_referenced(zb_ctx *c) {
  for (uint32_t i = 0; i < c->prot.size; i++)
    TRY(gc_mark_node(c, c->prot.data[i]));

  for (uint32_t z = 2; z < c->capacity; z++)
    if (n_var(c, (int32_t)z) != 0 && ref_is_positive(n_refs(c, (int32_t)z)))
      TRY(gc_mark_node(c, (int32_t)z));

  return 0;
}

/* Hash chains are untouched by the mark phase, so an aborted mark phase only has to remove the marks. */
static void gc_unmark(zb_ctx *c) {
  for (uint32_t z = 2; z < c->capacity; z++)
    n_unmark(c, (int32_t)z);
}

static void gc_sweep(zb_ctx *c) {
  c->next_free = 0;
  c->free_count = 0;
  c->dead_count = 0;
  c->new_count = 0;

  heads_clear(c);

  for (int32_t z = (int32_t)c->capacity - 1; z >= 2; z--) {
    if (n_var(c, z) != 0 && n_is_marked(c, z)) {
      n_unmark(c, z);
      chain_prepend(c, z, hash_of(c, n_var(c, z), n_p0(c, z), n_p1(c, z)));

      const uint32_t refs = n_refs(c, z);

      if (refs == 0)
        c->dead_count++;
      else if (refs == F_REF_NEW)
        c->new_count++;
    } else {
      n_set_free(c, z, c->next_free);
      c->next_free = (uint32_t)z;
      c->free_count++;
    }
  }
}

/* Everything following the beforeGc callbacks, which may have changed the format. */
static int32_t op_gc_collect(zb_ctx *c) {
  /* node numbers are going to be recycled, this includes results cached by the callbacks */
  zb_cache_clear(c);

  const uint32_t old_free = c->free_count;
  const int32_t rc = gc_mark_referenced(c);

  if (rc < 0) {
    gc_unmark(c);
    return rc;
  }

  gc_sweep(c);

  const int32_t freed = (int32_t)(c->free_count - old_free);

  c->stat_gc_freed += (uint64_t)(int64_t)freed;
  c->stat_gc_count++;

  TRY(invoke_callbacks(c, ZB_EVENT_AFTER_GC));

  return freed;
}

static int32_t op_gc(zb_ctx *c) {
  if (c->callbacks_running)
    return ZB_ERR_CB_GC;

  TRY(invoke_callbacks(c, ZB_EVENT_BEFORE_GC));

  return c->ops->gc_collect(c);
}


/*
 * Capacity
 */

static int32_t op_grow(zb_ctx *c, uint32_t new_capacity) {
  const uint32_t old_capacity = c->capacity;
  const uint32_t buckets = zb_bucket_count(new_capacity);

  /* allocate first, so the instance is left intact if an allocation fails */
  void *nodes = zb_realloc(c->nodes, (size_t)new_capacity * sizeof(zb_node));
  if (nodes == NULL)
    return ZB_ERR_NOMEM;

  c->nodes = nodes;

  const int rechain = buckets != c->bucket_count;

  if (rechain) {
    void *heads = zb_realloc(c->heads, (size_t)buckets * sizeof(zb_head));
    if (heads == NULL)
      return ZB_ERR_NOMEM;

    c->heads = heads;
    set_buckets(c, buckets);
    heads_clear(c);
  }

  c->capacity = new_capacity;
  c->stat_capacity_increases++;
  c->next_free = 0;
  c->free_count = new_capacity - old_capacity;

  for (int32_t z = (int32_t)new_capacity - 1; z >= (int32_t)old_capacity; z--) {
    n_set_free(c, z, c->next_free);
    c->next_free = (uint32_t)z;
  }

  /* free nodes keep their ascending order, in front of the new nodes */
  for (int32_t z = (int32_t)old_capacity - 1; z >= 2; z--) {
    if (n_var(c, z) != 0) {
      if (rechain)
        chain_prepend(c, z, hash_of(c, n_var(c, z), n_p0(c, z), n_p1(c, z)));
    } else {
      n_set_next(c, z, c->next_free);
      c->next_free = (uint32_t)z;
      c->free_count++;
    }
  }

  return 0;
}

/* User code may have changed the format, this code can't continue in that case. */
#define CHECK_FORMAT() do { if (c->ops != &F_OPS_NAME) return ZB_RETRY; } while (0)

static int32_t ensure_capacity(zb_ctx *c) {
  /* garbage collection is not possible while callbacks are running, so new nodes are provided by growing only */
  if (!c->callbacks_running && c->dead_count + c->new_count > 0) {
    int32_t required;

    TRY(advisor_is_gc_required(c, &required));
    CHECK_FORMAT();

    if (required) {
      TRY(op_gc(c));
      CHECK_FORMAT();

      int32_t min_free;

      TRY(advisor_min_free(c, &min_free));
      CHECK_FORMAT();

      if ((int64_t)c->free_count >= (int64_t)min_free)
        return 0;
    }
  }

  int32_t increment;

  TRY(advisor_increment(c, &increment));
  CHECK_FORMAT();

  const int64_t wanted = (int64_t)c->capacity + increment;

#ifdef F_MIGRATES
  /* the capacity the advisor asks for doesn't fit this format */
  if (wanted > F_MAX_NODES) {
    TRY(zb_migrate(c, wanted > ZB_LARGE_MAX_NODES ? ZB_LARGE_MAX_NODES : (uint32_t)wanted));
    return ZB_RETRY;
  }
#endif

  const int64_t limit = F_MAX_NODES;
  const uint32_t new_capacity =
      wanted <= (int64_t)c->capacity ? c->capacity : wanted >= limit ? (uint32_t)limit : (uint32_t)wanted;

  return new_capacity == c->capacity ? 0 : op_grow(c, new_capacity);
}

static int32_t get_node(zb_ctx *c, int32_t var, int32_t p0, int32_t p1) {
  c->stat_lookups++;

  /* suppress 0's */
  if (p1 == ZB_EMPTY) {
    c->stat_hits++;
    return p0;
  }

  uint32_t h = hash_of(c, var, p0, p1);

  for (uint32_t r = head_get(c, h); r != 0; r = n_next(c, (int32_t)r)) {
    if (n_matches(c, (int32_t)r, var, p0, p1)) {
      c->stat_hits++;
      return (int32_t)r;
    }
  }

  if (c->free_count < 2) {
    const uint32_t mark = c->prot.size;

    TRY(protect2(c, p0, p1));

    const int32_t rc = ensure_capacity(c);

    c->prot.size = mark;

    if (rc < 0)
      return rc;

    if (c->free_count == 0)
      return ZB_ERR_CAPACITY;

    /* the number of buckets may have changed */
    h = hash_of(c, var, p0, p1);
  }

  const int32_t r = (int32_t)c->next_free;

  c->next_free = n_next(c, r);
  c->free_count--;
  c->new_count++;
  c->stat_created++;

  n_init(c, r, var, p0, p1);
  chain_prepend(c, r, h);

  return r;
}


/*
 * Set operations on a single node
 */

enum { UNARY_SUBSET0, UNARY_SUBSET1, UNARY_CHANGE };

static inline int32_t unary_at_or_below(zb_ctx *c, int kind, int32_t node, int32_t top, int32_t var) {
  if (top < var) {
    switch (kind) {
      case UNARY_SUBSET0: return node;
      case UNARY_SUBSET1: return ZB_EMPTY;
      default: return get_node(c, var, ZB_EMPTY, node);
    }
  }

  switch (kind) {
    case UNARY_SUBSET0: return n_p0(c, node);
    case UNARY_SUBSET1: return n_p1(c, node);
    default: return get_node(c, var, n_p1(c, node), n_p0(c, node));
  }
}

static int32_t unary_op(zb_ctx *c, int kind, int32_t zbdd, int32_t var) {
  const int32_t cache_op =
      kind == UNARY_SUBSET0 ? ZB_CACHE_SUBSET0 : kind == UNARY_SUBSET1 ? ZB_CACHE_SUBSET1 : ZB_CACHE_CHANGE;
  int32_t top = v_of(c, zbdd);

  if (top <= var)
    return unary_at_or_below(c, kind, zbdd, top, var);

  const uint32_t mark = c->prot.size;
  const uint32_t base = c->tasks.size;

  TRY(protect2(c, zbdd, ZB_EMPTY));
  RESERVE();

  T_PUSH(zbdd);
  T_PUSH(TASK_COMPUTE);

  do {
    RESERVE();

    const int32_t task = T_POP();
    const int32_t node = T_POP();
    int32_t r;

    if (task == TASK_COMBINE) {
      const int32_t p1 = R_POP();
      const int32_t p0 = R_POP();

      TRY_SET(r, get_node(c, n_var(c, node), p0, p1));

      /* release the protection of the branch results (leaf nodes are not protected) */
      c->prot.size -= (uint32_t)(p0 >= 2) + (uint32_t)(p1 >= 2);
      zb_cache_put(c, cache_op, node, var, r);
    } else if ((top = v_of(c, node)) <= var) {
      TRY_SET(r, unary_at_or_below(c, kind, node, top, var));
    } else if ((r = zb_cache_get(c, cache_op, node, var)) < 0) {
      T_PUSH(node);
      T_PUSH(TASK_COMBINE);
      T_PUSH(n_p1(c, node));
      T_PUSH(TASK_COMPUTE);
      T_PUSH(n_p0(c, node));
      T_PUSH(TASK_COMPUTE);
      continue;
    }

    R_PUSH(r);
    PROTECT(r);
  } while (c->tasks.size > base);

  c->prot.size = mark;

  return R_POP();
}

static int32_t subset0_cube_loop(zb_ctx *c, zb_map *subsets, int32_t zbdd, int32_t cube) {
  const uint32_t base = c->tasks.size;

  RESERVE();

  T_PUSH(zbdd);
  T_PUSH(cube);
  T_PUSH(TASK_COMPUTE);

  do {
    RESERVE();

    const int32_t task = T_POP();
    int32_t r;

    if (task == TASK_COMBINE) {
      const int32_t node = T_POP();
      const int32_t p1 = R_POP();
      const int32_t p0 = R_POP();

      TRY_SET(r, get_node(c, n_var(c, node), p0, p1));

      /* the result remains protected until the operation has finished, as it is looked up again for shared nodes */
      TRY(zb_map_put(subsets, node, r) != 0 ? ZB_ERR_NOMEM : 0);
      PROTECT(r);
    } else {
      int32_t cube_node = T_POP();
      int32_t node = T_POP();
      int32_t subset;

      /* skip the cube variables above the top variable of the node and take the 0-branch for a matching variable */
      for (int32_t top = v_of(c, node), cube_var; node >= 2 && cube_node >= 2 && (cube_var = n_var(c, cube_node)) >= top;
           cube_node = n_p1(c, cube_node)) {
        if (cube_var == top)
          top = v_of(c, node = n_p0(c, node));
      }

      /* nodes not requiring a new node are reachable from zbdd and therefore protected */
      if (node < 2 || cube_node < 2)
        r = node;
      else if ((subset = zb_map_get(subsets, node)) != -1)
        r = subset;
      else {
        T_PUSH(node);
        T_PUSH(TASK_COMBINE);
        T_PUSH(n_p1(c, node));
        T_PUSH(cube_node);
        T_PUSH(TASK_COMPUTE);
        T_PUSH(n_p0(c, node));
        T_PUSH(cube_node);
        T_PUSH(TASK_COMPUTE);
        continue;
      }
    }

    R_PUSH(r);
  } while (c->tasks.size > base);

  return R_POP();
}

static int32_t subset0_cube(zb_ctx *c, int32_t zbdd, int32_t cube) {
  if (zbdd < 2 || cube < 2)
    return zbdd;

  const int32_t cached = zb_cache_get(c, ZB_CACHE_SUBSET0_CUBE, zbdd, cube);

  if (cached >= 0)
    return cached;

  const uint32_t mark = c->prot.size;
  zb_map subsets;

  TRY(protect2(c, zbdd, cube));

  zb_map_init(&subsets);

  const int32_t r = subset0_cube_loop(c, &subsets, zbdd, cube);

  zb_map_free(&subsets);

  if (r >= 0) {
    c->prot.size = mark;
    zb_cache_put(c, ZB_CACHE_SUBSET0_CUBE, zbdd, cube, r);
  }

  return r;
}


/*
 * Set operations on two nodes
 */

static int32_t remove_base(zb_ctx *c, int32_t zbdd);

static int32_t union2(zb_ctx *c, int32_t p, int32_t q) {
  if (q == ZB_EMPTY || p == q)
    return p;
  if (p == ZB_EMPTY)
    return q;

  const uint32_t mark = c->prot.size;
  const uint32_t base = c->tasks.size;

  TRY(protect2(c, p, q));
  RESERVE();

  T_PUSH(p);
  T_PUSH(q);
  T_PUSH(TASK_COMPUTE);

  do {
    RESERVE();

    const int32_t task = T_POP();
    int32_t r;

    if (task == TASK_STORE) {
      q = T_POP();
      p = T_POP();
      zb_cache_put(c, ZB_CACHE_UNION, p, q, R_TOP());
      continue;
    }

    if (task == TASK_COMBINE) {
      const int32_t p1 = R_POP();
      const int32_t p0 = R_POP();

      TRY_SET(r, get_node(c, T_POP(), p0, p1));

      c->prot.size -= (uint32_t)(p0 >= 2) + (uint32_t)(p1 >= 2);
    } else if (task == TASK_COMBINE_P1) {
      const int32_t var = T_POP();
      const int32_t p1 = T_POP();
      const int32_t p0 = R_POP();

      TRY_SET(r, get_node(c, var, p0, p1));

      c->prot.size -= (uint32_t)(p0 >= 2);
    } else {
      q = T_POP();
      p = T_POP();

      if (q == ZB_EMPTY || p == q)
        r = p;
      else if (p == ZB_EMPTY)
        r = q;
      else if ((r = zb_cache_get(c, ZB_CACHE_UNION, MIN_NODE(p, q), MAX_NODE(p, q))) < 0) {
        int32_t p_var = v_of(c, p);
        int32_t q_var = v_of(c, q);

        PUSH_STORE(MIN_NODE(p, q), MAX_NODE(p, q));

        if (p_var > q_var) {
          int32_t tmp = p; p = q; q = tmp;
          tmp = p_var; p_var = q_var; q_var = tmp;
        }

        if (p_var < q_var) {
          /* union(p, q) = getNode(q_var, union(p, q0), q1) */
          T_PUSH(n_p1(c, q));
          T_PUSH(q_var);
          T_PUSH(TASK_COMBINE_P1);
          T_PUSH(p);
          T_PUSH(n_p0(c, q));
          T_PUSH(TASK_COMPUTE);
        } else {
          T_PUSH(p_var);
          T_PUSH(TASK_COMBINE);
          T_PUSH(n_p1(c, p));
          T_PUSH(n_p1(c, q));
          T_PUSH(TASK_COMPUTE);
          T_PUSH(n_p0(c, p));
          T_PUSH(n_p0(c, q));
          T_PUSH(TASK_COMPUTE);
        }

        continue;
      }
    }

    R_PUSH(r);
    PROTECT(r);
  } while (c->tasks.size > base);

  c->prot.size = mark;

  return R_POP();
}

static int32_t intersect(zb_ctx *c, int32_t p, int32_t q) {
  if (p == ZB_EMPTY || q == ZB_EMPTY)
    return ZB_EMPTY;
  if (p == q)
    return p;

  const uint32_t mark = c->prot.size;
  const uint32_t base = c->tasks.size;

  TRY(protect2(c, p, q));
  RESERVE();

  T_PUSH(p);
  T_PUSH(q);
  T_PUSH(TASK_COMPUTE);

  do {
    RESERVE();

    const int32_t task = T_POP();
    int32_t r;

    if (task == TASK_STORE) {
      q = T_POP();
      p = T_POP();
      zb_cache_put(c, ZB_CACHE_INTERSECT, p, q, R_TOP());
      continue;
    }

    if (task == TASK_COMBINE) {
      const int32_t p1 = R_POP();
      const int32_t p0 = R_POP();

      TRY_SET(r, get_node(c, T_POP(), p0, p1));

      c->prot.size -= (uint32_t)(p0 >= 2) + (uint32_t)(p1 >= 2);
    } else {
      q = T_POP();
      p = T_POP();

      if (p == ZB_EMPTY || q == ZB_EMPTY)
        r = ZB_EMPTY;
      else if (p == q)
        r = p;
      else if ((r = zb_cache_get(c, ZB_CACHE_INTERSECT, MIN_NODE(p, q), MAX_NODE(p, q))) < 0) {
        const int32_t p_var = v_of(c, p);
        const int32_t q_var = v_of(c, q);

        PUSH_STORE(MIN_NODE(p, q), MAX_NODE(p, q));

        if (p_var > q_var) {
          T_PUSH(n_p0(c, p));
          T_PUSH(q);
        } else if (p_var < q_var) {
          T_PUSH(p);
          T_PUSH(n_p0(c, q));
        } else {
          T_PUSH(p_var);
          T_PUSH(TASK_COMBINE);
          T_PUSH(n_p1(c, p));
          T_PUSH(n_p1(c, q));
          T_PUSH(TASK_COMPUTE);
          T_PUSH(n_p0(c, p));
          T_PUSH(n_p0(c, q));
        }

        T_PUSH(TASK_COMPUTE);
        continue;
      }
    }

    R_PUSH(r);
    PROTECT(r);
  } while (c->tasks.size > base);

  c->prot.size = mark;

  return R_POP();
}

static int32_t difference(zb_ctx *c, int32_t p, int32_t q) {
  if (p == ZB_EMPTY || p == q)
    return ZB_EMPTY;
  if (q == ZB_BASE)
    return remove_base(c, p);
  if (q == ZB_EMPTY)
    return p;

  const uint32_t mark = c->prot.size;
  const uint32_t base = c->tasks.size;

  TRY(protect2(c, p, q));
  RESERVE();

  T_PUSH(p);
  T_PUSH(q);
  T_PUSH(TASK_COMPUTE);

  do {
    RESERVE();

    const int32_t task = T_POP();
    int32_t r;

    if (task == TASK_STORE) {
      q = T_POP();
      p = T_POP();
      zb_cache_put(c, ZB_CACHE_DIFFERENCE, p, q, R_TOP());
      continue;
    }

    if (task == TASK_COMBINE) {
      const int32_t p1 = R_POP();
      const int32_t p0 = R_POP();

      TRY_SET(r, get_node(c, T_POP(), p0, p1));

      c->prot.size -= (uint32_t)(p0 >= 2) + (uint32_t)(p1 >= 2);
    } else if (task == TASK_COMBINE_P1) {
      const int32_t var = T_POP();
      const int32_t p1 = T_POP();
      const int32_t p0 = R_POP();

      TRY_SET(r, get_node(c, var, p0, p1));

      c->prot.size -= (uint32_t)(p0 >= 2);
    } else {
      q = T_POP();
      p = T_POP();

      if (p == ZB_EMPTY || p == q)
        r = ZB_EMPTY;
      else if (q == ZB_BASE)
        TRY_SET(r, remove_base(c, p));
      else if (q == ZB_EMPTY)
        r = p;
      else if ((r = zb_cache_get(c, ZB_CACHE_DIFFERENCE, p, q)) < 0) {
        const int32_t p_var = v_of(c, p);
        const int32_t q_var = v_of(c, q);

        PUSH_STORE(p, q);

        if (p_var < q_var) {
          T_PUSH(p);
          T_PUSH(n_p0(c, q));
        } else if (p_var > q_var) {
          /* difference(p, q) = getNode(p_var, difference(p0, q), p1) */
          T_PUSH(n_p1(c, p));
          T_PUSH(p_var);
          T_PUSH(TASK_COMBINE_P1);
          T_PUSH(n_p0(c, p));
          T_PUSH(q);
        } else {
          T_PUSH(p_var);
          T_PUSH(TASK_COMBINE);
          T_PUSH(n_p1(c, p));
          T_PUSH(n_p1(c, q));
          T_PUSH(TASK_COMPUTE);
          T_PUSH(n_p0(c, p));
          T_PUSH(n_p0(c, q));
        }

        T_PUSH(TASK_COMPUTE);
        continue;
      }
    }

    R_PUSH(r);
    PROTECT(r);
  } while (c->tasks.size > base);

  c->prot.size = mark;

  return R_POP();
}

static int32_t multiply(zb_ctx *c, int32_t p, int32_t q) {
  if (p == ZB_EMPTY || q == ZB_EMPTY)
    return ZB_EMPTY;
  if (p == ZB_BASE)
    return q;
  if (q == ZB_BASE)
    return p;

  const uint32_t mark = c->prot.size;
  const uint32_t base = c->tasks.size;

  TRY(protect2(c, p, q));
  RESERVE();

  T_PUSH(p);
  T_PUSH(q);
  T_PUSH(TASK_COMPUTE);

  do {
    RESERVE();

    const int32_t task = T_POP();
    int32_t r;

    if (task == TASK_STORE) {
      q = T_POP();
      p = T_POP();
      zb_cache_put(c, ZB_CACHE_MULTIPLY, p, q, R_TOP());
      continue;
    }

    if (task == TASK_COMBINE) {
      const int32_t var = T_POP();
      const int32_t results_mark = T_POP();
      const int32_t pq1 = R_POP();
      const int32_t pq0 = R_POP();

      TRY_SET(r, get_node(c, var, pq0, pq1));

      c->prot.size = (uint32_t)results_mark;
    } else if (task == TASK_COMBINE_PRODUCTS) {
      const int32_t var = T_POP();
      const int32_t results_mark = T_POP();
      const int32_t p1q1 = R_POP();
      const int32_t p1q0 = R_POP();
      const int32_t p0q1 = R_POP();
      const int32_t p0q0 = R_POP();
      int32_t u;

      /* r = (p0 + v * p1) * (q0 + v * q1) = p0q0 + v * (p0q1 + p1q0 + p1q1) */
      TRY_SET(u, union2(c, p0q1, p1q0));
      TRY_SET(u, union2(c, u, p1q1));
      TRY_SET(r, get_node(c, var, p0q0, u));

      c->prot.size = (uint32_t)results_mark;
    } else {
      q = T_POP();
      p = T_POP();

      if (p == ZB_EMPTY || q == ZB_EMPTY)
        r = ZB_EMPTY;
      else if (p == ZB_BASE)
        r = q;
      else if (q == ZB_BASE)
        r = p;
      else if ((r = zb_cache_get(c, ZB_CACHE_MULTIPLY, MIN_NODE(p, q), MAX_NODE(p, q))) < 0) {
        int32_t p_var = v_of(c, p);
        int32_t q_var = v_of(c, q);

        PUSH_STORE(MIN_NODE(p, q), MAX_NODE(p, q));

        if (p_var > q_var) {
          int32_t tmp = p; p = q; q = tmp;
          tmp = p_var; p_var = q_var; q_var = tmp;
        }

        T_PUSH(c->prot.size);
        T_PUSH(q_var);

        if (p_var < q_var) {
          /* v = q_var does not occur in p: r = p * (q0 + v * q1) = p * q0 + v * (p * q1) */
          T_PUSH(TASK_COMBINE);
          T_PUSH(p);
          T_PUSH(n_p1(c, q));
          T_PUSH(TASK_COMPUTE);
          T_PUSH(p);
          T_PUSH(n_p0(c, q));
          T_PUSH(TASK_COMPUTE);
        } else {
          /* compute the products p0q0, p0q1, p1q0 and p1q1 in this order */
          const int32_t p0 = n_p0(c, p);
          const int32_t p1 = n_p1(c, p);
          const int32_t q0 = n_p0(c, q);
          const int32_t q1 = n_p1(c, q);

          T_PUSH(TASK_COMBINE_PRODUCTS);
          T_PUSH(p1);
          T_PUSH(q1);
          T_PUSH(TASK_COMPUTE);
          T_PUSH(p1);
          T_PUSH(q0);
          T_PUSH(TASK_COMPUTE);
          T_PUSH(p0);
          T_PUSH(q1);
          T_PUSH(TASK_COMPUTE);
          T_PUSH(p0);
          T_PUSH(q0);
          T_PUSH(TASK_COMPUTE);
        }

        continue;
      }
    }

    R_PUSH(r);
    PROTECT(r);
  } while (c->tasks.size > base);

  c->prot.size = mark;

  return R_POP();
}

static int32_t subset0_var(zb_ctx *c, int32_t z, int32_t var) { return unary_op(c, UNARY_SUBSET0, z, var); }
static int32_t subset1_var(zb_ctx *c, int32_t z, int32_t var) { return unary_op(c, UNARY_SUBSET1, z, var); }

static int32_t divide(zb_ctx *c, int32_t p, int32_t q) {
  if (q == ZB_BASE)
    return p;  /* p / { o } = p */
  if (p == ZB_EMPTY || p == ZB_BASE)
    return ZB_EMPTY;  /* {} / q = {} or { o } / q = {} for q != { o } */
  if (p == q)
    return ZB_BASE;  /* p / p = { o } */

  const uint32_t mark = c->prot.size;
  const uint32_t base = c->tasks.size;

  TRY(protect2(c, p, q));
  RESERVE();

  T_PUSH(p);
  T_PUSH(q);
  T_PUSH(TASK_COMPUTE);

  do {
    RESERVE();

    const int32_t task = T_POP();
    int32_t r;

    if (task == TASK_STORE) {
      q = T_POP();
      p = T_POP();
      zb_cache_put(c, ZB_CACHE_DIVIDE, p, q, R_TOP());
      continue;
    }

    if (task == TASK_CONTINUE) {
      const int32_t q0 = T_POP();
      const int32_t p0 = T_POP();

      if ((r = R_POP()) != ZB_EMPTY && q0 != ZB_EMPTY) {
        /* r = r1 & (p0 / q0); r1 remains protected on top of the results stack */
        R_PUSH(r);
        T_PUSH(TASK_COMBINE);
        T_PUSH(p0);
        T_PUSH(q0);
        T_PUSH(TASK_COMPUTE);
        continue;
      }

      /* r = r1; release the protection of the factored operands and r1 */
      c->prot.size = (uint32_t)T_POP();
    } else if (task == TASK_COMBINE) {
      const int32_t r0 = R_POP();
      const int32_t r1 = R_POP();

      TRY_SET(r, intersect(c, r1, r0));

      /* release the protection of the factored operands, r1 and r0 */
      c->prot.size = (uint32_t)T_POP();
    } else {
      q = T_POP();
      p = T_POP();

      if (q == ZB_BASE)
        r = p;
      else if (p == ZB_EMPTY || p == ZB_BASE)
        r = ZB_EMPTY;
      else if (p == q)
        r = ZB_BASE;
      else if ((r = zb_cache_get(c, ZB_CACHE_DIVIDE, p, q)) < 0) {
        const int32_t v = v_of(c, q);
        int32_t p0, p1, q0, q1;

        PUSH_STORE(p, q);

        T_PUSH(c->prot.size);

        /* factor P = p0 + v * p1 */
        TRY_SET(p0, subset0_var(c, p, v));
        PROTECT(p0);
        TRY_SET(p1, subset1_var(c, p, v));
        PROTECT(p1);

        /* factor Q = q0 + v * q1 */
        TRY_SET(q0, subset0_var(c, q, v));
        PROTECT(q0);
        TRY_SET(q1, subset1_var(c, q, v));
        PROTECT(q1);

        /* r1 = p1 / q1 is computed first; the continuation decides whether p0 / q0 is required */
        T_PUSH(p0);
        T_PUSH(q0);
        T_PUSH(TASK_CONTINUE);
        T_PUSH(p1);
        T_PUSH(q1);
        T_PUSH(TASK_COMPUTE);
        continue;
      }
    }

    R_PUSH(r);
    PROTECT(r);
  } while (c->tasks.size > base);

  c->prot.size = mark;

  return R_POP();
}

static int32_t modulo(zb_ctx *c, int32_t p, int32_t q) {
  const uint32_t mark = c->prot.size;
  int32_t d, m, r = zb_cache_get(c, ZB_CACHE_MODULO, p, q);

  if (r >= 0)
    return r;

  TRY(protect2(c, p, q));
  TRY_SET(d, divide(c, p, q));
  TRY_SET(m, multiply(c, q, d));
  TRY_SET(r, difference(c, p, m));

  c->prot.size = mark;
  zb_cache_put(c, ZB_CACHE_MODULO, p, q, r);

  return r;
}

/* The 1-branch of every node in p and q points to base. */
static int32_t atomize_union(zb_ctx *c, int32_t p, int32_t q) {
  const uint32_t mark = c->prot.size;
  const uint32_t base = c->tasks.size;
  int32_t r;

  TRY(protect2(c, p, q));

  /* descend the 0-branch chains, collecting the variables of the result */
  for (;;) {
    /* trivial cases: remove base from union */
    if (p < 2) {
      r = q < 2 ? ZB_EMPTY : q;
      break;
    }

    if (q < 2 || p == q) {
      r = p;
      break;
    }

    int32_t p_var = v_of(c, p);
    int32_t q_var = v_of(c, q);

    if (p_var > q_var) {
      int32_t tmp = p; p = q; q = tmp;
      tmp = p_var; p_var = q_var; q_var = tmp;
    }

    RESERVE();
    T_PUSH(q_var);

    if (p_var == q_var)
      p = n_p0(c, p);
    q = n_p0(c, q);
  }

  /* rebuild the result bottom-up */
  while (c->tasks.size > base)
    TRY_SET(r, get_node(c, T_POP(), r, ZB_BASE));

  c->prot.size = mark;

  return r;
}

static int32_t atomize(zb_ctx *c, int32_t zbdd) {
  if (zbdd < 2)
    return ZB_EMPTY;

  const uint32_t mark = c->prot.size;
  const uint32_t base = c->tasks.size;

  TRY(protect2(c, zbdd, ZB_EMPTY));
  RESERVE();

  T_PUSH(zbdd);
  T_PUSH(TASK_COMPUTE);

  do {
    RESERVE();

    const int32_t task = T_POP();
    const int32_t node = T_POP();
    int32_t r;

    if (task == TASK_COMBINE) {
      const int32_t p1 = R_POP();
      const int32_t p0 = R_POP();
      int32_t u;

      TRY_SET(u, atomize_union(c, p0, p1));
      TRY_SET(r, get_node(c, n_var(c, node), u, ZB_BASE));

      c->prot.size -= (uint32_t)(p0 >= 2) + (uint32_t)(p1 >= 2);
      zb_cache_put(c, ZB_CACHE_ATOMIZE, node, 0, r);
    } else if (node < 2)
      r = ZB_EMPTY;
    else if ((r = zb_cache_get(c, ZB_CACHE_ATOMIZE, node, 0)) < 0) {
      T_PUSH(node);
      T_PUSH(TASK_COMBINE);
      T_PUSH(n_p1(c, node));
      T_PUSH(TASK_COMPUTE);
      T_PUSH(n_p0(c, node));
      T_PUSH(TASK_COMPUTE);
      continue;
    }

    R_PUSH(r);
    PROTECT(r);
  } while (c->tasks.size > base);

  c->prot.size = mark;

  return R_POP();
}

static int32_t remove_base(zb_ctx *c, int32_t zbdd) {
  if (zbdd < 2)
    return ZB_EMPTY;

  int32_t r = zb_cache_get(c, ZB_CACHE_REMOVE_BASE, zbdd, 0);

  if (r >= 0)
    return r;

  const uint32_t mark = c->prot.size;
  const uint32_t base = c->tasks.size;

  TRY(protect2(c, zbdd, ZB_EMPTY));

  /* descend the 0-branch chain until a terminal node or a cached result is reached */
  do {
    RESERVE();
    T_PUSH(zbdd);
  } while ((zbdd = n_p0(c, zbdd)) >= 2 && (r = zb_cache_get(c, ZB_CACHE_REMOVE_BASE, zbdd, 0)) < 0);

  /* rebuild the 0-branch chain bottom-up, ending in the empty set instead of the base set */
  if (zbdd < 2)
    r = ZB_EMPTY;

  do {
    const int32_t node = T_POP();

    TRY_SET(r, get_node(c, n_var(c, node), r, n_p1(c, node)));
    zb_cache_put(c, ZB_CACHE_REMOVE_BASE, node, 0, r);
  } while (c->tasks.size > base);

  c->prot.size = mark;

  return r;
}


/*
 * Queries
 */

static int32_t has_cube_with_var_loop(zb_ctx *c, zb_map *visited, int32_t zbdd, int32_t var) {
  const uint32_t base = c->tasks.size;

  RESERVE();
  T_PUSH(zbdd);
  TRY(zb_map_put(visited, zbdd, 1));

  do {
    RESERVE();

    const int32_t node = T_POP();
    const int32_t current = n_var(c, node);

    if (current == var)
      return 1;

    if (current > var) {
      const int32_t branches[2] = { n_p0(c, node), n_p1(c, node) };

      for (int i = 0; i < 2; i++) {
        if (branches[i] >= 2 && zb_map_get(visited, branches[i]) == -1) {
          T_PUSH(branches[i]);
          TRY(zb_map_put(visited, branches[i], 1));
        }
      }
    }
  } while (c->tasks.size > base);

  return 0;
}

static int32_t count_loop(zb_ctx *c, zb_map *counts, int32_t zbdd) {
  const uint32_t base = c->tasks.size;

  RESERVE();
  T_PUSH(zbdd);

  do {
    RESERVE();

    const int32_t z = T_POP();

    if (zb_map_get(counts, z) == -1) {
      /* each node is cached, as subgraphs are shared between nodes */
      const int32_t cached = zb_cache_get(c, ZB_CACHE_COUNT, z, 0);

      if (cached >= 0) {
        TRY(zb_map_put(counts, z, cached));
        continue;
      }

      const int32_t p0 = n_p0(c, z);
      const int32_t p1 = n_p1(c, z);
      const int32_t p0_count = p0 < 2 ? p0 : zb_map_get(counts, p0);
      const int32_t p1_count = p1 < 2 ? p1 : zb_map_get(counts, p1);

      if (p0_count >= 0 && p1_count >= 0) {
        const int64_t count = (int64_t)p0_count + p1_count;

        if (count > INT32_MAX)
          return ZB_ERR_COUNT;

        TRY(zb_map_put(counts, z, (int32_t)count));
        zb_cache_put(c, ZB_CACHE_COUNT, z, 0, (int32_t)count);
      } else {
        T_PUSH(z);

        if (p0_count < 0)
          T_PUSH(p0);
        if (p1_count < 0)
          T_PUSH(p1);
      }
    }
  } while (c->tasks.size > base);

  return zb_map_get(counts, zbdd);
}

static int32_t count_exact_loop(zb_ctx *c, zb_map *entries, zb_big *big, int32_t zbdd) {
  const uint32_t base = c->tasks.size;

  RESERVE();
  T_PUSH(zbdd);

  do {
    RESERVE();

    const int32_t z = T_POP();

    if (zb_map_get(entries, z) == -1) {
      const int32_t p0 = n_p0(c, z);
      const int32_t p1 = n_p1(c, z);
      const int32_t p0_entry = p0 < 2 ? p0 : zb_map_get(entries, p0);
      const int32_t p1_entry = p1 < 2 ? p1 : zb_map_get(entries, p1);

      if (p0_entry >= 0 && p1_entry >= 0) {
        int32_t sum;

        TRY_SET(sum, zb_big_add(big, p0_entry, p1_entry));
        TRY(zb_map_put(entries, z, sum));
      } else {
        T_PUSH(z);

        if (p0_entry < 0)
          T_PUSH(p0);
        if (p1_entry < 0)
          T_PUSH(p1);
      }
    }
  } while (c->tasks.size > base);

  return zb_map_get(entries, zbdd);
}

static int32_t contains_loop(zb_ctx *c, zb_set64 *visited, int32_t p, int32_t q) {
  const uint32_t base = c->tasks.size;

  for (;;) {
    /* q is contained in p, if q = {} or p = q */
    if (q != ZB_EMPTY && p != q) {
      if (p == ZB_EMPTY)
        return 0;

      if (q == ZB_BASE) {
        /* the empty combination is reachable from p following the 0-branches only */
        while (p >= 2)
          p = n_p0(c, p);

        if (p == ZB_EMPTY)
          return 0;
      } else if (p == ZB_BASE)
        return 0;
      else {
        const int32_t added = zb_set64_add(visited, ((uint64_t)(uint32_t)p << 32) | (uint32_t)q);

        if (added < 0)
          return added;

        if (added) {
          const int32_t p_var = n_var(c, p);
          const int32_t q_var = n_var(c, q);

          /* q has a combination with q_var, which p does not have */
          if (q_var > p_var)
            return 0;

          if (q_var < p_var) {
            /* combinations of q do not contain p_var */
            p = n_p0(c, p);
            continue;
          }

          /* same top variable: q0 must be contained in p0 and q1 in p1 */
          RESERVE();
          T_PUSH(n_p1(c, q));
          T_PUSH(n_p1(c, p));

          p = n_p0(c, p);
          q = n_p0(c, q);
          continue;
        }
      }
    }

    if (c->tasks.size == base)
      return 1;

    p = T_POP();
    q = T_POP();
  }
}


/*
 * Entry points: parameters are validated in the same order as in ZbddImpl.
 */

#define CHECK_ZBDD(z, param) TRY(check_zbdd(c, (z), (param)))
#define CHECK_VAR(v) TRY(zb_check_var(c, (v)))

static int32_t op_get_var(zb_ctx *c, int32_t z) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);
  /* negative results are errors, so terminal nodes report 0, which is never a variable; the host maps it to -1 */
  return z < 2 ? 0 : n_var(c, z);
}

static int32_t op_get_p0(zb_ctx *c, int32_t z) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);
  return n_p0(c, z);
}

static int32_t op_get_p1(zb_ctx *c, int32_t z) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);
  return n_p1(c, z);
}

static int32_t check_var_order(zb_ctx *c, int32_t var, int32_t z, int32_t param) {
  CHECK_ZBDD(z, param);

  const int32_t z_var = v_of(c, z);

  return z_var >= var ? zb_error(c, ZB_ERR_VAR_ORDER, z, z_var, param, var) : z;
}

static int32_t op_get_node(zb_ctx *c, int32_t var, int32_t p0, int32_t p1) {
  CHECK_VAR(var);
  TRY(check_var_order(c, var, p0, ZB_PARAM_P0));
  TRY(check_var_order(c, var, p1, ZB_PARAM_P1));

  return get_node(c, var, p0, p1);
}

static int32_t op_cube1(zb_ctx *c, int32_t var) {
  CHECK_VAR(var);

  return get_node(c, var, ZB_EMPTY, ZB_BASE);
}

static int32_t cube_of(zb_ctx *c, int32_t *vars, int32_t n) {
  int32_t r = ZB_BASE;

  zb_sort(vars, n);

  for (int32_t i = 0, top = -1; i < n; i++) {
    if (vars[i] != top) {
      top = vars[i];
      CHECK_VAR(top);
      TRY_SET(r, get_node(c, top, ZB_EMPTY, r));
    }
  }

  return r;
}

static int32_t op_has_cube_with_var(zb_ctx *c, int32_t z, int32_t var) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);
  CHECK_VAR(var);

  if (z < 2)
    return 0;

  const int32_t top = n_var(c, z);

  if (top == var)
    return 1;

  if (top < var)
    return 0;

  const int32_t cached = zb_cache_get(c, ZB_CACHE_HAS_CUBE_WITH_VAR, z, var);

  if (cached >= 0)
    return cached;

  zb_map visited;

  zb_map_init(&visited);

  const int32_t r = has_cube_with_var_loop(c, &visited, z, var);

  zb_map_free(&visited);

  if (r >= 0)
    zb_cache_put(c, ZB_CACHE_HAS_CUBE_WITH_VAR, z, var, r);

  return r;
}

static int32_t op_subset0(zb_ctx *c, int32_t z, int32_t var) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);
  CHECK_VAR(var);

  return subset0_var(c, z, var);
}

static int32_t op_subset0_n(zb_ctx *c, int32_t z, int32_t *vars, int32_t n) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);
  TRY(protect2(c, z, ZB_EMPTY));

  int32_t cube;

  TRY_SET(cube, cube_of(c, vars, n));
  TRY(protect2(c, cube, ZB_EMPTY));

  return subset0_cube(c, z, cube);
}

static int32_t op_subset1(zb_ctx *c, int32_t z, int32_t var) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);
  CHECK_VAR(var);

  return subset1_var(c, z, var);
}

static int32_t op_change(zb_ctx *c, int32_t z, int32_t var) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);
  CHECK_VAR(var);

  return unary_op(c, UNARY_CHANGE, z, var);
}

static int32_t op_count(zb_ctx *c, int32_t z) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);

  if (z < 2)
    return z;

  const int32_t cached = zb_cache_get(c, ZB_CACHE_COUNT, z, 0);

  if (cached >= 0)
    return cached;

  zb_map counts;

  zb_map_init(&counts);

  const int32_t r = count_loop(c, &counts, z);

  zb_map_free(&counts);

  return r;
}

static int32_t op_count_exact(zb_ctx *c, int32_t z, uint8_t **bytes, size_t *len) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);

  struct zb_exact_counts *counts = zb_exact_counts_get(c);

  if (counts == NULL)
    return ZB_ERR_NOMEM;

  c->cache_lookups++;

  int32_t r = z < 2 ? z : zb_map_get(&counts->entries, z);

  if (z >= 2 && r >= 0)
    c->cache_hits++;

  if (r < 0)
    r = count_exact_loop(c, &counts->entries, &counts->big, z);

  if (r >= 0)
    r = zb_big_export(&counts->big, r, bytes, len);

  zb_exact_counts_trim(c);

  return r;
}

static int32_t op_union2(zb_ctx *c, int32_t p, int32_t q) {
  CHECK_ZBDD(p, ZB_PARAM_P);
  CHECK_ZBDD(q, ZB_PARAM_Q);

  return union2(c, p, q);
}

static int32_t op_union_n(zb_ctx *c, const int32_t *p, int32_t n) {
  /* check validity first before protecting nodes */
  for (int32_t i = 0; i < n; i++)
    CHECK_ZBDD(p[i], ZB_PARAM_ARRAY + i);

  if (zb_stack_reserve(&c->prot, (uint32_t)n) != 0)
    return ZB_ERR_NOMEM;

  for (int32_t i = 0; i < n; i++)
    PROTECT(p[i]);

  int32_t r = p[0];

  for (int32_t i = 1; i < n; i++)
    TRY_SET(r, union2(c, r, p[i]));

  return r;
}

static int32_t op_intersect(zb_ctx *c, int32_t p, int32_t q) {
  CHECK_ZBDD(p, ZB_PARAM_P);
  CHECK_ZBDD(q, ZB_PARAM_Q);

  return intersect(c, p, q);
}

static int32_t op_difference(zb_ctx *c, int32_t p, int32_t q) {
  CHECK_ZBDD(p, ZB_PARAM_P);
  CHECK_ZBDD(q, ZB_PARAM_Q);

  return difference(c, p, q);
}

static int32_t op_multiply(zb_ctx *c, int32_t p, int32_t q) {
  CHECK_ZBDD(p, ZB_PARAM_P);
  CHECK_ZBDD(q, ZB_PARAM_Q);

  return multiply(c, p, q);
}

static int32_t check_divisor(zb_ctx *c, int32_t q) {
  CHECK_ZBDD(q, ZB_PARAM_Q);

  return q == ZB_EMPTY ? ZB_ERR_DIV_EMPTY : q;
}

static int32_t op_divide(zb_ctx *c, int32_t p, int32_t q) {
  CHECK_ZBDD(p, ZB_PARAM_P);
  TRY(check_divisor(c, q));

  return divide(c, p, q);
}

static int32_t op_modulo(zb_ctx *c, int32_t p, int32_t q) {
  CHECK_ZBDD(p, ZB_PARAM_P);
  TRY(check_divisor(c, q));

  return modulo(c, p, q);
}

static int32_t op_atomize(zb_ctx *c, int32_t z) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);

  return atomize(c, z);
}

static int32_t op_remove_base(zb_ctx *c, int32_t z) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);

  return remove_base(c, z);
}

static int32_t op_contains(zb_ctx *c, int32_t p, int32_t q) {
  CHECK_ZBDD(p, ZB_PARAM_P);
  CHECK_ZBDD(q, ZB_PARAM_Q);

  const int32_t cached = zb_cache_get(c, ZB_CACHE_CONTAINS, p, q);

  if (cached >= 0)
    return cached;

  zb_set64 visited;

  zb_set64_init(&visited);

  const int32_t r = contains_loop(c, &visited, p, q);

  zb_set64_free(&visited);

  if (r >= 0)
    zb_cache_put(c, ZB_CACHE_CONTAINS, p, q, r);

  return r;
}


/*
 * Traversals handing results to the host
 */

static int32_t dependency_loop(zb_ctx *c, uint64_t *sequenced, int32_t *sequence) {
  int32_t index = 2;

  sequence[0] = ZB_EMPTY;
  sequence[1] = ZB_BASE;

#define IS_SEQUENCED(z) ((sequenced[(uint32_t)(z) >> 6] >> ((uint32_t)(z) & 63)) & 1)

  /* post-order depth-first traversal: a node is sequenced after both of its branches */
  for (int32_t zbdd = 2; (uint32_t)zbdd < c->capacity; zbdd++) {
    if (n_var(c, zbdd) == 0 || IS_SEQUENCED(zbdd))
      continue;

    const uint32_t base = c->tasks.size;

    RESERVE();
    T_PUSH(zbdd);

    do {
      RESERVE();

      const int32_t node = T_POP();
      const int32_t p0 = n_p0(c, node);
      const int32_t p1 = n_p1(c, node);

      if (p0 >= 2 && !IS_SEQUENCED(p0)) {
        T_PUSH(node);
        T_PUSH(p0);
      } else if (p1 >= 2 && !IS_SEQUENCED(p1)) {
        T_PUSH(node);
        T_PUSH(p1);
      } else {
        sequenced[(uint32_t)node >> 6] |= UINT64_C(1) << ((uint32_t)node & 63);
        sequence[index++] = node;
      }
    } while (c->tasks.size > base);
  }

#undef IS_SEQUENCED

  return index;
}

static int32_t op_dependency(zb_ctx *c, int32_t **out) {
  const size_t words = ((size_t)c->capacity + 63) / 64;
  const uint32_t occupied = c->capacity - c->free_count;
  uint64_t *sequenced = zb_alloc(words * sizeof(uint64_t));
  int32_t *sequence = zb_alloc((size_t)occupied * sizeof(int32_t));

  if (sequenced == NULL || sequence == NULL) {
    zb_free(sequenced);
    zb_free(sequence);
    return ZB_ERR_NOMEM;
  }

  memset(sequenced, 0, words * sizeof(uint64_t));

  const int32_t n = dependency_loop(c, sequenced, sequence);

  zb_free(sequenced);

  if (n < 0)
    zb_free(sequence);
  else
    *out = sequence;

  return n;
}

static int32_t op_iter_open(zb_ctx *c, int32_t z, zb_iter *it) {
  CHECK_ZBDD(z, ZB_PARAM_ZBDD);

  if (zb_stack_init(&it->work, 32) != 0)
    return ZB_ERR_NOMEM;

  if (zb_stack_init(&it->vars, 16) != 0 || protect2(c, z, ZB_EMPTY) != 0) {
    zb_stack_free(&it->work);
    zb_stack_free(&it->vars);
    return ZB_ERR_NOMEM;
  }

  it->prot_mark = c->prot.size - (uint32_t)(z >= 2);

  if (z != ZB_EMPTY)
    it->work.data[it->work.size++] = z;

  return 0;
}

static int32_t op_iter_next_cube(zb_ctx *c, zb_iter *it) {
  while (it->work.size > 0) {
    const int32_t z = it->work.data[--it->work.size];

    if (z == -1)
      it->vars.size--;
    else if (z != ZB_BASE) {
      if (zb_stack_reserve(&it->work, 3) != 0 || zb_stack_reserve(&it->vars, 1) != 0)
        return ZB_ERR_NOMEM;

      const int32_t p0 = n_p0(c, z);
      const int32_t p1 = n_p1(c, z);

      /* 0-branch */
      if (p0 != ZB_EMPTY)
        it->work.data[it->work.size++] = p0;

      /* 1-branch */
      it->work.data[it->work.size++] = -1;

      if (p1 != ZB_EMPTY)
        it->work.data[it->work.size++] = p1;

      it->vars.data[it->vars.size++] = n_var(c, z);
    } else
      return (int32_t)it->vars.size;
  }

  return ZB_ITER_END;
}

static int32_t op_cube_of_vars(zb_ctx *c, const int32_t *vars, int32_t n) {
  int32_t cube = ZB_BASE;

  for (int32_t i = n; i-- > 0;)
    TRY_SET(cube, get_node(c, vars[i], ZB_EMPTY, cube));

  return cube;
}

static int32_t as_single_cubes_loop(zb_ctx *c, zb_iter *it, int32_t **out) {
  const uint32_t base = c->results.size;

  for (;;) {
    const int32_t n = op_iter_next_cube(c, it);

    if (n == ZB_ITER_END)
      break;
    if (n < 0)
      return n;

    int32_t cube;

    TRY_SET(cube, op_cube_of_vars(c, it->vars.data, n));

    if (zb_stack_reserve(&c->results, 1) != 0 || zb_stack_reserve(&c->prot, 1) != 0)
      return ZB_ERR_NOMEM;

    R_PUSH(cube);
    PROTECT(cube);
  }

  const uint32_t n = c->results.size - base;
  int32_t *cubes = zb_alloc((size_t)n * sizeof(int32_t));

  if (cubes == NULL)
    return ZB_ERR_NOMEM;

  memcpy(cubes, c->results.data + base, (size_t)n * sizeof(int32_t));
  *out = cubes;

  return (int32_t)n;
}

static int32_t op_as_single_cubes(zb_ctx *c, int32_t z, int32_t **out) {
  if (z == ZB_EMPTY) {
    *out = zb_alloc(sizeof(int32_t));
    return *out != NULL ? 0 : ZB_ERR_NOMEM;
  }

  zb_iter it;

  TRY(op_iter_open(c, z, &it));

  const int32_t r = as_single_cubes_loop(c, &it, out);

  zb_stack_free(&it.work);
  zb_stack_free(&it.vars);

  return r;
}


/*
 * Lifecycle
 */

static void op_reset(zb_ctx *c) {
  c->last_var = 0;
  c->dead_count = 0;
  c->new_count = 0;
  c->next_free = 2;
  c->free_count = c->capacity - 2;

  heads_clear(c);

  for (uint32_t z = 2; z < c->capacity; z++)
    n_set_free(c, (int32_t)z, z + 1 == c->capacity ? 0 : z + 1);

  if (c->ovf_cap != 0)
    memset(c->ovf_keys, 0, (size_t)c->ovf_cap * sizeof(uint32_t));

  zb_cache_clear(c);

  c->ovf_size = 0;
  c->prot.size = 0;

  c->stat_lookups = 0;
  c->stat_hits = 0;
  c->stat_gc_count = 0;
  c->stat_gc_freed = 0;
  c->stat_capacity_increases = 0;
  c->cache_lookups = 0;
  c->cache_hits = 0;
}

static int32_t op_init(zb_ctx *c, int32_t capacity) {
  const int64_t wanted = capacity < 8 ? 8 : capacity;
  const uint32_t nodes_capacity = wanted > F_MAX_NODES ? F_MAX_NODES : (uint32_t)wanted;
  const uint32_t buckets = zb_bucket_count(nodes_capacity);

  c->nodes = zb_alloc((size_t)nodes_capacity * sizeof(zb_node));
  c->heads = zb_alloc((size_t)buckets * sizeof(zb_head));

  if (c->nodes == NULL || c->heads == NULL)
    return ZB_ERR_NOMEM;

  c->capacity = nodes_capacity;
  set_buckets(c, buckets);

  n_init_terminal(c, ZB_EMPTY);
  n_init_terminal(c, ZB_BASE);

  op_reset(c);

  return 0;
}

/*
 * Format change
 */

static void op_read_node(const zb_ctx *c, int32_t z, zb_node_fields *node) {
  node->var = n_var(c, z);
  node->p0 = n_p0(c, z);
  node->p1 = n_p1(c, z);
  node->next = n_next(c, z);
  node->ref = z >= 2 && node->var != 0 ? ref_get(c, z) : 0;
}

/* Node pointers refer to the table of the given context. Counts exceeding the inline range are in the overflow table. */
static void op_write_node(zb_ctx *c, int32_t z, const zb_node_fields *node) {
  if (z < 2)
    n_init_terminal(c, z);
  else if (node->var == 0)
    n_set_free(c, z, node->next);
  else {
    n_init(c, z, node->var, node->p0, node->p1);
    n_set_next(c, z, node->next);
    n_set_refs(c, z, node->ref == -1 ? F_REF_NEW : (uint32_t)node->ref > F_REF_INLINE_MAX ? F_REF_OVERFLOW :
        (uint32_t)node->ref);
  }
}

static void op_rechain(zb_ctx *c) {
  heads_clear(c);

  for (int32_t z = (int32_t)c->capacity - 1; z >= 2; z--)
    if (n_var(c, z) != 0)
      chain_prepend(c, z, hash_of(c, n_var(c, z), n_p0(c, z), n_p1(c, z)));
}

#ifdef ZB_TEST
static int32_t test_set_ref(zb_ctx *c, int32_t z, int32_t ref) {
  const uint32_t refs = n_refs(c, z);

  if (refs == F_REF_NEW)
    c->new_count--;
  else if (refs == 0)
    c->dead_count--;

  return ref_set(c, z, ref);
}
#endif

const zb_ops F_OPS_NAME = {
  .get_var = op_get_var,
  .get_p0 = op_get_p0,
  .get_p1 = op_get_p1,
  .get_node = op_get_node,
  .cube1 = op_cube1,
  .cube_n = cube_of,
  .has_cube_with_var = op_has_cube_with_var,
  .subset0 = op_subset0,
  .subset0_n = op_subset0_n,
  .subset1 = op_subset1,
  .change = op_change,
  .count = op_count,
  .count_exact = op_count_exact,
  .union2 = op_union2,
  .union_n = op_union_n,
  .intersect = op_intersect,
  .difference = op_difference,
  .multiply = op_multiply,
  .divide = op_divide,
  .modulo = op_modulo,
  .atomize = op_atomize,
  .remove_base = op_remove_base,
  .contains = op_contains,
  .gc = op_gc,
  .gc_collect = op_gc_collect,
  .inc_ref = op_inc_ref,
  .dec_ref = op_dec_ref,
  .get_ref = op_get_ref,
  .is_valid_zbdd = op_is_valid_zbdd,
  .dependency = op_dependency,
  .as_single_cubes = op_as_single_cubes,
  .iter_open = op_iter_open,
  .iter_next_cube = op_iter_next_cube,
  .cube_of_vars = op_cube_of_vars,
  .read_node = op_read_node,
  .write_node = op_write_node,
  .rechain = op_rechain,
  .grow = op_grow,
  .reset = op_reset,
  .init = op_init,
#ifdef ZB_TEST
  .test_set_ref = test_set_ref,
#endif
  .node_size = sizeof(zb_node),
  .head_size = sizeof(zb_head)
};
