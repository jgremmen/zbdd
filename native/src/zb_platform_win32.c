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
 * Windows platform layer: the library imports kernel32 only and links no C runtime. The compiler may emit calls to
 * memcpy, memset and memmove, so they are defined here, without letting the compiler turn their loops into calls to
 * themselves.
 */
#include <stddef.h>

#include "zb_core.h"

typedef void *zb_win_handle;

__declspec(dllimport) zb_win_handle __stdcall GetProcessHeap(void);
__declspec(dllimport) void *__stdcall HeapAlloc(zb_win_handle heap, unsigned long flags, size_t size);
__declspec(dllimport) void *__stdcall HeapReAlloc(zb_win_handle heap, unsigned long flags, void *ptr, size_t size);
__declspec(dllimport) int __stdcall HeapFree(zb_win_handle heap, unsigned long flags, void *ptr);

void *zb_platform_alloc(size_t size) { return HeapAlloc(GetProcessHeap(), 0, size); }

void *zb_platform_realloc(void *ptr, size_t size) {
  return ptr != NULL ? HeapReAlloc(GetProcessHeap(), 0, ptr, size) : HeapAlloc(GetProcessHeap(), 0, size);
}

void zb_platform_free(void *ptr) {
  if (ptr != NULL)
    HeapFree(GetProcessHeap(), 0, ptr);
}

__attribute__((no_builtin)) void *memset(void *dest, int value, size_t n) {
  unsigned char *d = dest;

  while (n-- > 0)
    *d++ = (unsigned char)value;

  return dest;
}

__attribute__((no_builtin)) void *memcpy(void *dest, const void *src, size_t n) {
  unsigned char *d = dest;
  const unsigned char *s = src;

  while (n-- > 0)
    *d++ = *s++;

  return dest;
}

__attribute__((no_builtin)) void *memmove(void *dest, const void *src, size_t n) {
  unsigned char *d = dest;
  const unsigned char *s = src;

  if (d < s)
    while (n-- > 0)
      *d++ = *s++;
  else
    while (n-- > 0)
      d[n] = s[n];

  return dest;
}

int __stdcall DllMain(void *instance, unsigned long reason, void *reserved) {
  (void)instance;
  (void)reason;
  (void)reserved;

  return 1;
}
