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
 * Small format, one 64-bit word per node:
 *
 *   bits   field
 *   0-14   var (0 = free or terminal)
 *   15-29  p0
 *   30-44  p1
 *   45-59  next (hash chain or free list, 0 ends the list)
 *   60     mark (during gc only)
 *   61-63  refs (0 = dead, 1-5 = count, 6 = new, 7 = count in the overflow table)
 *
 * Bits 0-44 are the lookup key, so a comparison takes a single load.
 */
#include "zb_core.h"

typedef uint64_t zb_node;
typedef uint16_t zb_head;

#define F_OPS_NAME     zb_ops_small
#define F_MAX_NODES    ZB_SMALL_MAX_NODES
#define F_MAX_VAR      ZB_SMALL_MAX_VAR
#define F_MIGRATES

#define F_REF_INLINE_MAX ((uint32_t)ZB_SMALL_REF_INLINE_MAX)
#define F_REF_NEW        6u
#define F_REF_OVERFLOW   7u

#define F_MASK         UINT64_C(0x7FFF)
#define F_SHIFT_P0     15
#define F_SHIFT_P1     30
#define F_SHIFT_NEXT   45
#define F_SHIFT_MARK   60
#define F_SHIFT_REFS   61
#define F_KEY_MASK     ((UINT64_C(1) << F_SHIFT_NEXT) - 1)
#define F_MARK         (UINT64_C(1) << F_SHIFT_MARK)

#define F_NODE(c, z)   (((zb_node *)(c)->nodes)[z])
#define F_HEAD(c, h)   (((zb_head *)(c)->heads)[h])

static inline uint64_t f_key(int32_t var, int32_t p0, int32_t p1) {
  return (uint64_t)(uint32_t)var | ((uint64_t)(uint32_t)p0 << F_SHIFT_P0) | ((uint64_t)(uint32_t)p1 << F_SHIFT_P1);
}

static inline int32_t n_var(const zb_ctx *c, int32_t z) { return (int32_t)(F_NODE(c, z) & F_MASK); }
static inline int32_t n_p0(const zb_ctx *c, int32_t z) { return (int32_t)((F_NODE(c, z) >> F_SHIFT_P0) & F_MASK); }
static inline int32_t n_p1(const zb_ctx *c, int32_t z) { return (int32_t)((F_NODE(c, z) >> F_SHIFT_P1) & F_MASK); }
static inline uint32_t n_next(const zb_ctx *c, int32_t z) { return (uint32_t)((F_NODE(c, z) >> F_SHIFT_NEXT) & F_MASK); }

static inline void n_set_next(zb_ctx *c, int32_t z, uint32_t next) {
  F_NODE(c, z) = (F_NODE(c, z) & ~(F_MASK << F_SHIFT_NEXT)) | ((uint64_t)next << F_SHIFT_NEXT);
}

static inline uint32_t n_refs(const zb_ctx *c, int32_t z) { return (uint32_t)(F_NODE(c, z) >> F_SHIFT_REFS); }

static inline void n_set_refs(zb_ctx *c, int32_t z, uint32_t refs) {
  F_NODE(c, z) = (F_NODE(c, z) & ((UINT64_C(1) << F_SHIFT_REFS) - 1)) | ((uint64_t)refs << F_SHIFT_REFS);
}

static inline int n_is_marked(const zb_ctx *c, int32_t z) { return (F_NODE(c, z) & F_MARK) != 0; }
static inline void n_mark(zb_ctx *c, int32_t z) { F_NODE(c, z) |= F_MARK; }
static inline void n_unmark(zb_ctx *c, int32_t z) { F_NODE(c, z) &= ~F_MARK; }

/* A new node, not linked into a hash chain yet. */
static inline void n_init(zb_ctx *c, int32_t z, int32_t var, int32_t p0, int32_t p1) {
  F_NODE(c, z) = f_key(var, p0, p1) | ((uint64_t)F_REF_NEW << F_SHIFT_REFS);
}

static inline void n_init_terminal(zb_ctx *c, int32_t z) { F_NODE(c, z) = f_key(0, z, z); }

static inline void n_set_free(zb_ctx *c, int32_t z, uint32_t next) { F_NODE(c, z) = (uint64_t)next << F_SHIFT_NEXT; }

static inline int n_matches(const zb_ctx *c, int32_t z, int32_t var, int32_t p0, int32_t p1) {
  return (F_NODE(c, z) & F_KEY_MASK) == f_key(var, p0, p1);
}

static inline uint32_t hash_of(const zb_ctx *c, int32_t var, int32_t p0, int32_t p1) {
  return (uint32_t)((f_key(var, p0, p1) * UINT64_C(0x9E3779B97F4A7C15)) >> c->hash_shift);
}

static inline uint32_t head_get(const zb_ctx *c, uint32_t h) { return F_HEAD(c, h); }
static inline void head_set(zb_ctx *c, uint32_t h, uint32_t z) { F_HEAD(c, h) = (zb_head)z; }
