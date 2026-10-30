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
 * JNI binding of de.sayayi.lib.zbdd.internal.ZbddNative. Natives are registered in JNI_OnLoad, which is the only symbol
 * exported by the library.
 *
 * Instance methods receive the ZbddNative object, which JNI keeps reachable for the duration of the call, so its Cleaner
 * can't release the context during an operation. The object is only stored in the context for the duration of a call:
 * a JNI global reference would make it a garbage collection root and leak the instance.
 */
#include <jni.h>

#include "zb_core.h"

static jmethodID method_callbacks;
static jmethodID method_is_gc_required;
static jmethodID method_min_free_nodes;
static jmethodID method_advise_increment;

#define HOST_ENV(c) ((JNIEnv *)(c)->host_env)
#define HOST_SELF(c) ((jobject)(c)->host_self)

static int32_t host_result(zb_ctx *c) {
  return (*HOST_ENV(c))->ExceptionCheck(HOST_ENV(c)) ? ZB_ERR_HOST : 0;
}

static int32_t host_callbacks(zb_ctx *c, int32_t event) {
  (*HOST_ENV(c))->CallVoidMethod(HOST_ENV(c), HOST_SELF(c), method_callbacks, (jint)event);

  return host_result(c);
}

static int32_t host_is_gc_required(zb_ctx *c, int32_t *required) {
  *required = (*HOST_ENV(c))->CallBooleanMethod(HOST_ENV(c), HOST_SELF(c), method_is_gc_required) != JNI_FALSE;

  return host_result(c);
}

static int32_t host_min_free_nodes(zb_ctx *c, int32_t *nodes) {
  *nodes = (*HOST_ENV(c))->CallIntMethod(HOST_ENV(c), HOST_SELF(c), method_min_free_nodes);

  return host_result(c);
}

static int32_t host_advise_increment(zb_ctx *c, int32_t *nodes) {
  *nodes = (*HOST_ENV(c))->CallIntMethod(HOST_ENV(c), HOST_SELF(c), method_advise_increment);

  return host_result(c);
}

static const zb_host jni_host = {
  host_callbacks, host_is_gc_required, host_min_free_nodes, host_advise_increment
};

/* Operations may be nested through callbacks, each one with its own local reference to the object. */
#define ENTER                                 \
  zb_ctx *c = (zb_ctx *)(intptr_t)handle;     \
  void *const saved_env = c->host_env;        \
  void *const saved_self = c->host_self;      \
  c->host_env = env;                          \
  c->host_self = self

#define LEAVE                                 \
  c->host_env = saved_env;                    \
  c->host_self = saved_self

#define NATIVE_I(name, api)                                                                     \
  static jint JNICALL name(JNIEnv *env, jobject self, jlong handle) {                           \
    ENTER;                                                                                      \
    const jint r = api(c);                                                                      \
    LEAVE;                                                                                      \
    return r;                                                                                   \
  }

#define NATIVE_II(name, api)                                                                    \
  static jint JNICALL name(JNIEnv *env, jobject self, jlong handle, jint a) {                   \
    ENTER;                                                                                      \
    const jint r = api(c, a);                                                                   \
    LEAVE;                                                                                      \
    return r;                                                                                   \
  }

#define NATIVE_III(name, api)                                                                   \
  static jint JNICALL name(JNIEnv *env, jobject self, jlong handle, jint a, jint b) {           \
    ENTER;                                                                                      \
    const jint r = api(c, a, b);                                                                \
    LEAVE;                                                                                      \
    return r;                                                                                   \
  }

#define NATIVE_IIII(name, api)                                                                  \
  static jint JNICALL name(JNIEnv *env, jobject self, jlong handle, jint a, jint b, jint d) {   \
    ENTER;                                                                                      \
    const jint r = api(c, a, b, d);                                                             \
    LEAVE;                                                                                      \
    return r;                                                                                   \
  }

NATIVE_I(native_create_var, zb_create_var)
NATIVE_I(native_gc, zb_gc)
NATIVE_II(native_get_var, zb_get_var)
NATIVE_II(native_get_p0, zb_get_p0)
NATIVE_II(native_get_p1, zb_get_p1)
NATIVE_II(native_cube1, zb_cube1)
NATIVE_II(native_atomize, zb_atomize)
NATIVE_II(native_remove_base, zb_remove_base)
NATIVE_II(native_count, zb_count)
NATIVE_II(native_inc_ref, zb_inc_ref)
NATIVE_II(native_dec_ref, zb_dec_ref)
NATIVE_II(native_get_ref, zb_get_ref)
NATIVE_II(native_is_valid_zbdd, zb_is_valid_zbdd)
NATIVE_II(native_clear, zb_clear)
NATIVE_II(native_check_var, zb_check_var)
NATIVE_III(native_has_cube_with_var, zb_has_cube_with_var)
NATIVE_III(native_subset0, zb_subset0)
NATIVE_III(native_subset1, zb_subset1)
NATIVE_III(native_change, zb_change)
NATIVE_III(native_union, zb_union)
NATIVE_III(native_intersect, zb_intersect)
NATIVE_III(native_difference, zb_difference)
NATIVE_III(native_multiply, zb_multiply)
NATIVE_III(native_divide, zb_divide)
NATIVE_III(native_modulo, zb_modulo)
NATIVE_III(native_contains, zb_contains)
NATIVE_IIII(native_get_node, zb_get_node)

static jint JNICALL native_is_valid_var(JNIEnv *env, jobject self, jlong handle, jint var) {
  (void)env;
  (void)self;

  return zb_is_valid_var((zb_ctx *)(intptr_t)handle, var);
}

static jlong JNICALL native_create(JNIEnv *env, jclass cls, jint capacity, jint cache_entries,
                                   jboolean custom_advisor) {
  (void)env;
  (void)cls;

  zb_ctx *c = zb_create(capacity, cache_entries, &jni_host);

  if (c != NULL)
    c->custom_advisor = custom_advisor != JNI_FALSE;

  return (jlong)(intptr_t)c;
}

static void JNICALL native_destroy(JNIEnv *env, jclass cls, jlong handle) {
  (void)env;
  (void)cls;

  zb_destroy((zb_ctx *)(intptr_t)handle);
}

static jlong JNICALL native_live_contexts(JNIEnv *env, jclass cls) {
  (void)env;
  (void)cls;

  return zb_live_contexts();
}

static jlong JNICALL native_stat(JNIEnv *env, jobject self, jlong handle, jint stat) {
  (void)env;
  (void)self;

  return zb_stat((zb_ctx *)(intptr_t)handle, stat);
}

static void JNICALL native_set_has_callbacks(JNIEnv *env, jobject self, jlong handle) {
  (void)env;
  (void)self;

  ((zb_ctx *)(intptr_t)handle)->has_callbacks = 1;
}

static void JNICALL native_last_error(JNIEnv *env, jobject self, jlong handle, jintArray out) {
  (void)self;

  const zb_ctx *c = (zb_ctx *)(intptr_t)handle;
  const jint error[5] = { c->err_value, c->err_limit, c->err_param, c->err_extra, c->err_code };

  (*env)->SetIntArrayRegion(env, out, 0, 5, error);
}

/* Copies a Java int array, the caller must release the copy. Returns NULL with err_code set or an exception pending. */
static int32_t *copy_ints(JNIEnv *env, zb_ctx *c, jintArray array, jsize *length) {
  const jsize n = (*env)->GetArrayLength(env, array);
  int32_t *copy = zb_alloc((size_t)n * sizeof(int32_t));

  if (copy == NULL) {
    c->err_code = ZB_ERR_NOMEM;
    return NULL;
  }

  (*env)->GetIntArrayRegion(env, array, 0, n, copy);
  *length = n;

  return copy;
}

static jint JNICALL native_cube_n(JNIEnv *env, jobject self, jlong handle, jintArray vars) {
  ENTER;

  jsize n = 0;
  int32_t *copy = copy_ints(env, c, vars, &n);
  const jint r = copy != NULL ? zb_cube_n(c, copy, n) : ZB_ERR_NOMEM;

  zb_free(copy);
  LEAVE;

  return r;
}

static jint JNICALL native_subset0_n(JNIEnv *env, jobject self, jlong handle, jint z, jintArray vars) {
  ENTER;

  jsize n = 0;
  int32_t *copy = copy_ints(env, c, vars, &n);
  const jint r = copy != NULL ? zb_subset0_n(c, z, copy, n) : ZB_ERR_NOMEM;

  zb_free(copy);
  LEAVE;

  return r;
}

static jint JNICALL native_union_n(JNIEnv *env, jobject self, jlong handle, jintArray p) {
  ENTER;

  jsize n = 0;
  int32_t *copy = copy_ints(env, c, p, &n);
  const jint r = copy != NULL ? zb_union_n(c, copy, n) : ZB_ERR_NOMEM;

  zb_free(copy);
  LEAVE;

  return r;
}

/* Hands over an array allocated by the core, or records the error for a negative count. */
static jintArray to_int_array(JNIEnv *env, zb_ctx *c, int32_t rc, int32_t *values) {
  if (rc < 0) {
    c->err_code = rc;
    return NULL;
  }

  jintArray array = (*env)->NewIntArray(env, rc);

  if (array != NULL)
    (*env)->SetIntArrayRegion(env, array, 0, rc, values);

  zb_free(values);

  return array;
}

static jintArray JNICALL native_dependency(JNIEnv *env, jobject self, jlong handle) {
  ENTER;

  int32_t *values = NULL;
  const int32_t rc = zb_dependency(c, &values);
  jintArray array = to_int_array(env, c, rc, values);

  LEAVE;

  return array;
}

static jintArray JNICALL native_as_single_cubes(JNIEnv *env, jobject self, jlong handle, jint z) {
  ENTER;

  int32_t *values = NULL;
  const int32_t rc = zb_as_single_cubes(c, z, &values);
  jintArray array = to_int_array(env, c, rc, values);

  LEAVE;

  return array;
}

static jbyteArray JNICALL native_count_exact(JNIEnv *env, jobject self, jlong handle, jint z) {
  ENTER;

  uint8_t *bytes = NULL;
  size_t len = 0;
  const int32_t rc = zb_count_exact(c, z, &bytes, &len);
  jbyteArray array = NULL;

  if (rc < 0)
    c->err_code = rc;
  else {
    array = (*env)->NewByteArray(env, (jsize)len);

    if (array != NULL)
      (*env)->SetByteArrayRegion(env, array, 0, (jsize)len, (const jbyte *)bytes);

    zb_free(bytes);
  }

  LEAVE;

  return array;
}

static jlong JNICALL native_iter_open(JNIEnv *env, jobject self, jlong handle, jint z) {
  ENTER;

  zb_iter *it = NULL;
  const int32_t rc = zb_iter_open(c, z, &it);

  if (rc < 0)
    c->err_code = rc;

  LEAVE;

  return (jlong)(intptr_t)it;
}

/* Returns the next cube or NULL with err_code set, which is ZB_ITER_END after the last cube. */
static jintArray JNICALL native_iter_next_cube(JNIEnv *env, jobject self, jlong handle, jlong iter) {
  ENTER;

  zb_iter *it = (zb_iter *)(intptr_t)iter;
  const int32_t n = zb_iter_next_cube(c, it);
  jintArray array = NULL;

  if (n < 0)
    c->err_code = n;
  else {
    array = (*env)->NewIntArray(env, n);

    if (array != NULL)
      (*env)->SetIntArrayRegion(env, array, 0, n, it->vars.data);
  }

  LEAVE;

  return array;
}

static jint JNICALL native_iter_next_zbdd(JNIEnv *env, jobject self, jlong handle, jlong iter) {
  ENTER;

  const jint r = zb_iter_next_zbdd(c, (zb_iter *)(intptr_t)iter);

  LEAVE;

  return r;
}

static void JNICALL native_iter_close(JNIEnv *env, jobject self, jlong handle, jlong iter) {
  (void)env;
  (void)self;

  zb_iter_close((zb_ctx *)(intptr_t)handle, (zb_iter *)(intptr_t)iter);
}

#define METHOD(name, signature, fn) { (char *)(name), (char *)(signature), (void *)(fn) }

static const JNINativeMethod methods[] = {
  METHOD("nCreate", "(IIZ)J", native_create),
  METHOD("nDestroy", "(J)V", native_destroy),
  METHOD("nLiveContexts", "()J", native_live_contexts),
  METHOD("nStat", "(JI)J", native_stat),
  METHOD("nSetHasCallbacks", "(J)V", native_set_has_callbacks),
  METHOD("nLastError", "(J[I)V", native_last_error),
  METHOD("nCreateVar", "(J)I", native_create_var),
  METHOD("nIsValidVar", "(JI)I", native_is_valid_var),
  METHOD("nCheckVar", "(JI)I", native_check_var),
  METHOD("nGetVar", "(JI)I", native_get_var),
  METHOD("nGetP0", "(JI)I", native_get_p0),
  METHOD("nGetP1", "(JI)I", native_get_p1),
  METHOD("nGetNode", "(JIII)I", native_get_node),
  METHOD("nCube1", "(JI)I", native_cube1),
  METHOD("nCubeN", "(J[I)I", native_cube_n),
  METHOD("nHasCubeWithVar", "(JII)I", native_has_cube_with_var),
  METHOD("nSubset0", "(JII)I", native_subset0),
  METHOD("nSubset0N", "(JI[I)I", native_subset0_n),
  METHOD("nSubset1", "(JII)I", native_subset1),
  METHOD("nChange", "(JII)I", native_change),
  METHOD("nCount", "(JI)I", native_count),
  METHOD("nCountExact", "(JI)[B", native_count_exact),
  METHOD("nUnion", "(JII)I", native_union),
  METHOD("nUnionN", "(J[I)I", native_union_n),
  METHOD("nIntersect", "(JII)I", native_intersect),
  METHOD("nDifference", "(JII)I", native_difference),
  METHOD("nMultiply", "(JII)I", native_multiply),
  METHOD("nDivide", "(JII)I", native_divide),
  METHOD("nModulo", "(JII)I", native_modulo),
  METHOD("nAtomize", "(JI)I", native_atomize),
  METHOD("nRemoveBase", "(JI)I", native_remove_base),
  METHOD("nContains", "(JII)I", native_contains),
  METHOD("nGc", "(J)I", native_gc),
  METHOD("nClear", "(JI)I", native_clear),
  METHOD("nIncRef", "(JI)I", native_inc_ref),
  METHOD("nDecRef", "(JI)I", native_dec_ref),
  METHOD("nGetRef", "(JI)I", native_get_ref),
  METHOD("nIsValidZbdd", "(JI)I", native_is_valid_zbdd),
  METHOD("nDependency", "(J)[I", native_dependency),
  METHOD("nAsSingleCubes", "(JI)[I", native_as_single_cubes),
  METHOD("nIterOpen", "(JI)J", native_iter_open),
  METHOD("nIterNextCube", "(JJ)[I", native_iter_next_cube),
  METHOD("nIterNextZbdd", "(JJ)I", native_iter_next_zbdd),
  METHOD("nIterClose", "(JJ)V", native_iter_close)
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;

  JNIEnv *env;

  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK)
    return JNI_ERR;

  jclass cls = (*env)->FindClass(env, "de/sayayi/lib/zbdd/internal/ZbddNative");

  if (cls == NULL)
    return JNI_ERR;

  method_callbacks = (*env)->GetMethodID(env, cls, "upcallCallbacks", "(I)V");
  method_is_gc_required = (*env)->GetMethodID(env, cls, "upcallIsGcRequired", "()Z");
  method_min_free_nodes = (*env)->GetMethodID(env, cls, "upcallMinFreeNodes", "()I");
  method_advise_increment = (*env)->GetMethodID(env, cls, "upcallAdviseIncrement", "()I");

  if (method_callbacks == NULL || method_is_gc_required == NULL || method_min_free_nodes == NULL ||
      method_advise_increment == NULL)
    return JNI_ERR;

  if ((*env)->RegisterNatives(env, cls, methods, (jint)(sizeof(methods) / sizeof(methods[0]))) != JNI_OK)
    return JNI_ERR;

  return JNI_VERSION_1_8;
}
