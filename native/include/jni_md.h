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
 * Replaces the platform specific jni_md.h of a JDK, which only provides the header for the platform it runs on. All
 * supported targets are 64-bit, where these definitions are ABI compatible with those of the JDK.
 */
#ifndef ZB_JNI_MD_H
#define ZB_JNI_MD_H

#ifdef _WIN32
#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall
#else
#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT __attribute__((visibility("default")))
#define JNICALL
#endif

typedef int jint;
typedef long long jlong;
typedef signed char jbyte;

#endif
