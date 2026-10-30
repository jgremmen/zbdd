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
package de.sayayi.lib.zbdd.internal;

import de.sayayi.lib.zbdd.exception.ZbddException;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static java.nio.file.Files.*;
import static java.util.Locale.ROOT;


/**
 * Loads the native library for the current platform from the jar. A failure is remembered and reported again by each
 * subsequent attempt with a new exception having the failure as its cause, as retrying doesn't change the outcome.
 *
 * @author Jeroen Gremmen
 * @since 0.8.0
 */
final class NativeLoader
{
  private static final String RESOURCE_ROOT = "/META-INF/native/";

  private static final String WINDOWS_DIRECTORY = "zbdd-native";
  private static final String WINDOWS_FILE_PREFIX = "zbdd-";
  private static final String WINDOWS_FILE_SUFFIX = ".dll";

  // far longer than it takes from writing a library to loading it, as another JVM must not delete it in between
  private static final Duration WINDOWS_STALE_AGE = Duration.ofHours(1);

  private static boolean loaded;
  private static Throwable failure;


  private NativeLoader() {
  }


  /**
   * Makes sure the native library has been loaded.
   *
   * @throws ZbddException  if the platform is not supported or the library cannot be loaded
   */
  static synchronized void ensureLoaded()
  {
    if (!loaded && failure == null)
    {
      try {
        load();
        loaded = true;
      } catch(IOException | RuntimeException | LinkageError ex) {
        failure = ex;
      }
    }

    if (failure != null)
    {
      // a new exception for each attempt, as its stack trace belongs to the current caller
      final var exception = new ZbddException(failure instanceof ZbddException
          ? failure.getMessage()
          : "failed to load native zbdd library: " + failure);

      exception.initCause(failure);

      throw exception;
    }
  }


  /**
   * Tells whether the native library can be used, loading it if this hasn't been attempted yet.
   *
   * @return  {@code true} if the native library has been loaded, {@code false} if the platform is not supported or the
   *          library cannot be loaded
   */
  static boolean isAvailable()
  {
    try {
      ensureLoaded();
      return true;
    } catch(ZbddException ex) {
      return false;
    }
  }


  private static void load() throws IOException
  {
    final var osName = System.getProperty("os.name").toLowerCase(ROOT);
    final var arch = System.getProperty("os.arch").toLowerCase(ROOT);
    final var library = libraryResource(osName, arch);
    final byte[] content;

    try(var stream = NativeLoader.class.getResourceAsStream(library)) {
      if (stream == null)
        throw new ZbddException("native zbdd library " + library + " is missing from the jar");

      content = stream.readAllBytes();
    }

    if (osName.startsWith("windows"))
      loadWindows(content);
    else
      loadAndDelete(content);
  }


  /**
   * Returns the resource name of the library.
   *
   * @param osName  lower case operating system name, not {@code null}
   * @param arch    lower case architecture name, not {@code null}
   *
   * @return  resource name of the library, never {@code null}
   *
   * @throws ZbddException  if the platform is not supported
   */
  @Contract(pure = true)
  static @NotNull String libraryResource(@NotNull String osName, @NotNull String arch)
  {
    final var x86_64 = arch.equals("amd64") || arch.equals("x86_64");

    if (osName.startsWith("linux") && x86_64)
      return RESOURCE_ROOT + "linux-x86_64/libzbdd.so";

    if (osName.startsWith("windows") && x86_64)
      return RESOURCE_ROOT + "windows-x86_64/zbdd.dll";

    if (osName.startsWith("mac") && (arch.equals("aarch64") || arch.equals("arm64")))
      return RESOURCE_ROOT + "macos-arm64/libzbdd.dylib";

    throw new ZbddException("native zbdd library is not available for " + osName + '/' + arch);
  }


  /**
   * A loaded library stays mapped after its file has been deleted, so no file is left behind. The temporary file is
   * only accessible by the current user.
   */
  private static void loadAndDelete(byte @NotNull [] content) throws IOException
  {
    final var file = createTempFile("zbdd-native-", ".lib");

    try {
      Files.write(file, content);
      System.load(file.toAbsolutePath().toString());
    } finally {
      deleteIfExists(file);
    }
  }


  private static void loadWindows(byte @NotNull [] content) throws IOException {
    loadFromOwnDirectory(windowsDirectory(System.getenv("LOCALAPPDATA"), System.getProperty("java.io.tmpdir")), content);
  }


  /**
   * Returns the directory for the library on Windows, which prefers the local application data of the user, as the
   * temporary directory can be shared between users, e.g. for a service.
   *
   * @param localAppData  local application data directory, or {@code null} if there is none
   * @param tmpDir        temporary directory, not {@code null}
   *
   * @return  directory for the library, never {@code null}
   */
  @Contract(pure = true)
  static @NotNull Path windowsDirectory(String localAppData, @NotNull String tmpDir)
  {
    final var base = localAppData != null && isDirectory(Path.of(localAppData)) && isWritable(Path.of(localAppData))
        ? localAppData : tmpDir;

    return Path.of(base, WINDOWS_DIRECTORY);
  }


  /**
   * A loaded DLL cannot be deleted or replaced, so each load uses a file of its own and files of earlier loads are
   * deleted later on. As no file is shared, several JVMs and class loaders do not interfere with each other, and no file
   * written by someone else is ever loaded.
   *
   * @param directory  directory for the library, not {@code null}
   * @param content    content of the library, not {@code null}
   *
   * @throws ZbddException  if the directory belongs to another user
   */
  static void loadFromOwnDirectory(@NotNull Path directory, byte @NotNull [] content) throws IOException
  {
    final var file = writeToOwnDirectory(directory, content);
    var loaded = false;

    try {
      System.load(file.toAbsolutePath().toString());
      loaded = true;
    } finally {
      if (!loaded)
        deleteIfExists(file);
    }

    deleteStaleFiles(directory, Instant.now().minus(WINDOWS_STALE_AGE));
  }


  /**
   * Writes the library to a new file in the given directory, which is created if required.
   *
   * @param directory  directory for the library, not {@code null}
   * @param content    content of the library, not {@code null}
   *
   * @return  new file, never {@code null}
   *
   * @throws ZbddException  if the directory belongs to another user, who could replace the file before it is loaded
   */
  static @NotNull Path writeToOwnDirectory(@NotNull Path directory, byte @NotNull [] content) throws IOException
  {
    createDirectories(directory);

    final var file = createTempFile(directory, WINDOWS_FILE_PREFIX, WINDOWS_FILE_SUFFIX);
    var written = false;

    try {
      // a new file belongs to the current user, whichever principal that is for the operating system
      if (!getOwner(directory).equals(getOwner(file)))
        throw new ZbddException("native zbdd library directory " + directory + " belongs to another user");

      Files.write(file, content);
      written = true;

      return file;
    } finally {
      if (!written)
        deleteIfExists(file);
    }
  }


  /**
   * Deletes the library files last modified before {@code limit}. Files which are still loaded by a JVM cannot be
   * deleted and remain until a later attempt. Failures are ignored, as the library has been loaded at this point.
   *
   * @param directory  directory with library files, not {@code null}
   * @param limit      files last modified before this instant are deleted, not {@code null}
   */
  static void deleteStaleFiles(@NotNull Path directory, @NotNull Instant limit)
  {
    try(var files = newDirectoryStream(directory, WINDOWS_FILE_PREFIX + '*' + WINDOWS_FILE_SUFFIX)) {
      for(var file: files)
      {
        try {
          if (getLastModifiedTime(file).toInstant().isBefore(limit))
            deleteIfExists(file);
        } catch(IOException ignored) {
          // still loaded or deleted by another JVM
        }
      }
    } catch(IOException | DirectoryIteratorException ignored) {
      // the files are left for a later attempt
    }
  }
}
