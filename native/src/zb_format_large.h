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
 * Large format, two 64-bit words per node:
 *
 *   word  bits   field
 *   a     0-28   p0
 *   a     29-57  p1
 *   a     58-63  refs (0 = dead, 1-61 = count, 62 = new, 63 = count in the overflow table)
 *   b     0-30   var (0 = free or terminal)
 *   b     31     mark (during gc only)
 *   b     32-60  next (hash chain or free list, 0 ends the list)
 *   b     61-63  spare
 */
#include "zb_core.h"

typedef struct {
  uint64_t a;
  uint64_t b;
} zb_node;

typedef uint32_t zb_head;

#define F_OPS_NAME     zb_ops_large
#define F_MAX_NODES    ZB_LARGE_MAX_NODES
#define F_MAX_VAR      ZB_LARGE_MAX_VAR

#define F_REF_INLINE_MAX ((uint32_t)ZB_LARGE_REF_INLINE_MAX)
#define F_REF_NEW        62u
#define F_REF_OVERFLOW   63u

#define F_MASK_NODE    UINT64_C(0x1FFFFFFF)
#define F_MASK_VAR     UINT64_C(0x7FFFFFFF)
#define F_SHIFT_P1     29
#define F_SHIFT_REFS   58
#define F_SHIFT_NEXT   32
#define F_KEY_MASK     ((UINT64_C(1) << F_SHIFT_REFS) - 1)
#define F_MARK         (UINT64_C(1) << 31)

#define F_NODE(c, z)   (((zb_node *)(c)->nodes)[z])
#define F_HEAD(c, h)   (((zb_head *)(c)->heads)[h])

static inline uint64_t f_key(int32_t p0, int32_t p1) {
  return (uint64_t)(uint32_t)p0 | ((uint64_t)(uint32_t)p1 << F_SHIFT_P1);
}

static inline int32_t n_var(const zb_ctx *c, int32_t z) { return (int32_t)(F_NODE(c, z).b & F_MASK_VAR); }
static inline int32_t n_p0(const zb_ctx *c, int32_t z) { return (int32_t)(F_NODE(c, z).a & F_MASK_NODE); }
static inline int32_t n_p1(const zb_ctx *c, int32_t z) { return (int32_t)((F_NODE(c, z).a >> F_SHIFT_P1) & F_MASK_NODE); }
static inline uint32_t n_next(const zb_ctx *c, int32_t z) {
  return (uint32_t)((F_NODE(c, z).b >> F_SHIFT_NEXT) & F_MASK_NODE);
}

static inline void n_set_next(zb_ctx *c, int32_t z, uint32_t next) {
  F_NODE(c, z).b = (F_NODE(c, z).b & ~(F_MASK_NODE << F_SHIFT_NEXT)) | ((uint64_t)next << F_SHIFT_NEXT);
}

static inline uint32_t n_refs(const zb_ctx *c, int32_t z) { return (uint32_t)(F_NODE(c, z).a >> F_SHIFT_REFS); }

static inline void n_set_refs(zb_ctx *c, int32_t z, uint32_t refs) {
  F_NODE(c, z).a = (F_NODE(c, z).a & F_KEY_MASK) | ((uint64_t)refs << F_SHIFT_REFS);
}

static inline int n_is_marked(const zb_ctx *c, int32_t z) { return (F_NODE(c, z).b & F_MARK) != 0; }
static inline void n_mark(zb_ctx *c, int32_t z) { F_NODE(c, z).b |= F_MARK; }
static inline void n_unmark(zb_ctx *c, int32_t z) { F_NODE(c, z).b &= ~F_MARK; }

/* A new node, not linked into a hash chain yet. */
static inline void n_init(zb_ctx *c, int32_t z, int32_t var, int32_t p0, int32_t p1) {
  F_NODE(c, z).a = f_key(p0, p1) | ((uint64_t)F_REF_NEW << F_SHIFT_REFS);
  F_NODE(c, z).b = (uint64_t)(uint32_t)var;
}

static inline void n_init_terminal(zb_ctx *c, int32_t z) {
  F_NODE(c, z).a = f_key(z, z);
  F_NODE(c, z).b = 0;
}

static inline void n_set_free(zb_ctx *c, int32_t z, uint32_t next) {
  F_NODE(c, z).a = 0;
  F_NODE(c, z).b = (uint64_t)next << F_SHIFT_NEXT;
}

static inline int n_matches(const zb_ctx *c, int32_t z, int32_t var, int32_t p0, int32_t p1) {
  return (F_NODE(c, z).a & F_KEY_MASK) == f_key(p0, p1) && (F_NODE(c, z).b & F_MASK_VAR) == (uint64_t)(uint32_t)var;
}

static inline uint32_t hash_of(const zb_ctx *c, int32_t var, int32_t p0, int32_t p1) {
  return (uint32_t)(((f_key(p0, p1) ^ ((uint64_t)(uint32_t)var << 31)) * UINT64_C(0x9E3779B97F4A7C15)) >> c->hash_shift);
}

static inline uint32_t head_get(const zb_ctx *c, uint32_t h) { return F_HEAD(c, h); }
static inline void head_set(zb_ctx *c, uint32_t h, uint32_t z) { F_HEAD(c, h) = z; }
