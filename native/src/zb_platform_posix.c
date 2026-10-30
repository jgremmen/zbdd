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

/* Resolved from the C library already loaded by the JVM. */
void *malloc(size_t size);
void *realloc(void *ptr, size_t size);
void free(void *ptr);

void *zb_platform_alloc(size_t size) { return malloc(size); }
void *zb_platform_realloc(void *ptr, size_t size) { return realloc(ptr, size); }
void zb_platform_free(void *ptr) { free(ptr); }
