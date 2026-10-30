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

import de.sayayi.lib.zbdd.ZbddFactory;
import de.sayayi.lib.zbdd.exception.ZbddException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ROOT;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * The handling of library files used on Windows. Apart from the choice of the resource it does not depend on the
 * platform, so it runs on every host.
 *
 * @author Jeroen Gremmen
 */
@DisplayName("Native library loader")
class NativeLoaderTest
{
  @Test
  @DisplayName("Local application data is preferred to the temporary directory, if it can be used")
  void windowsDirectory(@TempDir Path localAppData, @TempDir Path tmpDir)
  {
    assertEquals(localAppData.resolve("zbdd-native"), NativeLoader.windowsDirectory(localAppData.toString(), tmpDir.toString()));
    assertEquals(tmpDir.resolve("zbdd-native"), NativeLoader.windowsDirectory(null, tmpDir.toString()));
    assertEquals(tmpDir.resolve("zbdd-native"),
        NativeLoader.windowsDirectory(localAppData.resolve("missing").toString(), tmpDir.toString()));
  }


  @Test
  @DisplayName("Each write creates a new file with the content, creating the directory if required")
  void writeToOwnDirectory(@TempDir Path tmpDir) throws IOException
  {
    final var directory = tmpDir.resolve("zbdd-native");
    final var content = new byte[] { 1, 2, 3 };
    final var file1 = NativeLoader.writeToOwnDirectory(directory, content);
    final var file2 = NativeLoader.writeToOwnDirectory(directory, content);

    assertNotEquals(file1, file2);
    assertArrayEquals(content, Files.readAllBytes(file1));
    assertArrayEquals(content, Files.readAllBytes(file2));
  }


  @Test
  @DisplayName("A directory of another user is refused, without leaving a file behind")
  void directoryOfAnotherUser(@TempDir Path tmpDir) throws IOException
  {
    final var shared = Path.of("/tmp").toRealPath();

    // root owns this directory on Linux and macOS, while everyone can write to it
    assumeTrue(Files.isWritable(shared));

    final var probe = Files.createTempFile(tmpDir, "probe", null);

    assumeTrue(!Files.getOwner(shared).equals(Files.getOwner(probe)));

    final var exception = assertThrowsExactly(ZbddException.class,
        () -> NativeLoader.writeToOwnDirectory(shared, new byte[] { 1 }));

    assertTrue(exception.getMessage().contains("belongs to another user"));

    final var before = Instant.now().minusSeconds(60);

    try(var files = Files.newDirectoryStream(shared, "zbdd-*.dll")) {
      for(var file: files)
        assertTrue(Files.getLastModifiedTime(file).toInstant().isBefore(before), file::toString);
    }
  }


  @Test
  @DisplayName("Only library files before the limit are deleted")
  void deleteStaleFiles(@TempDir Path directory) throws IOException
  {
    final var limit = Instant.now().minus(Duration.ofHours(1));
    final var stale = Files.createFile(directory.resolve("zbdd-stale.dll"));
    final var recent = Files.createFile(directory.resolve("zbdd-recent.dll"));
    final var unrelated = Files.createFile(directory.resolve("unrelated.dll"));

    Files.setLastModifiedTime(stale, FileTime.from(limit.minusSeconds(1)));
    Files.setLastModifiedTime(unrelated, FileTime.from(limit.minusSeconds(1)));

    NativeLoader.deleteStaleFiles(directory, limit);

    assertFalse(Files.exists(stale));
    assertTrue(Files.exists(recent));
    assertTrue(Files.exists(unrelated));
  }


  @Test
  @DisplayName("A missing directory is ignored when deleting stale files")
  void deleteStaleFilesWithoutDirectory(@TempDir Path tmpDir) {
    assertDoesNotThrow(() -> NativeLoader.deleteStaleFiles(tmpDir.resolve("missing"), Instant.now()));
  }


  @Test
  @DisplayName("Several JVMs starting together all load the library from a new directory")
  void simultaneousStart(@TempDir Path tmpDir) throws Exception
  {
    assumeTrue(ZbddFactory.isNativeAvailable());

    final var directory = tmpDir.resolve("zbdd-native");
    final var processes = new ArrayList<Process>();

    for(var i = 0; i < 4; i++)
    {
      processes.add(new ProcessBuilder(
          Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp", System.getProperty("java.class.path"),
          LoadProbe.class.getName(),
          directory.toString())
          .redirectErrorStream(true)
          .start());
    }

    for(var process: processes)
    {
      final var output = new String(process.getInputStream().readAllBytes(), UTF_8);

      assertEquals(0, process.waitFor(), output);
      assertTrue(output.contains("loaded"), output);
    }

    try(var files = Files.list(directory)) {
      assertEquals(4, files.count());
    }
  }


  @Test
  @DisplayName("A failure is reported with a new exception per attempt, having the failure as its cause")
  void failureReportedPerAttempt() throws Exception
  {
    assumeTrue(ZbddFactory.isNativeAvailable());
    assumeFalse(System.getProperty("os.name").toLowerCase(ROOT).startsWith("windows"));

    final var process = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Djava.io.tmpdir=/nonexistent/zbdd-test",
        "-cp", System.getProperty("java.class.path"),
        FailureProbe.class.getName())
        .redirectError(ProcessBuilder.Redirect.DISCARD)  // the JVM warns about the temporary directory
        .start();
    final var output = new String(process.getInputStream().readAllBytes(), UTF_8);

    assertEquals(0, process.waitFor(), output);
    assertEquals(List.of("available=false", "distinct=true", "sameCause=true", "cause=java.nio.file.NoSuchFileException",
        "prefix=true", "stack=true"), output.lines().toList());
  }


  /** Started in a JVM of its own by {@link #failureReportedPerAttempt()}, as a failure is remembered per JVM. */
  static final class FailureProbe
  {
    private static ZbddException firstAttempt() {
      return assertThrowsExactly(ZbddException.class, ZbddFactory::createNative);
    }


    private static ZbddException secondAttempt() {
      return assertThrowsExactly(ZbddException.class, ZbddFactory::createNative);
    }


    public static void main(String[] args)
    {
      System.out.println("available=" + ZbddFactory.isNativeAvailable());

      final var first = firstAttempt();
      final var second = secondAttempt();

      System.out.println("distinct=" + (first != second));
      System.out.println("sameCause=" + (first.getCause() == second.getCause()));
      System.out.println("cause=" + second.getCause().getClass().getName());
      System.out.println("prefix=" + second.getMessage().startsWith("failed to load native zbdd library: "));
      System.out.println("stack=" + Arrays.stream(second.getStackTrace())
          .anyMatch(frame -> frame.getMethodName().equals("ensureLoaded")));
    }
  }


  /** Started in a JVM of its own by {@link #simultaneousStart(Path)}, as a library is loaded once per JVM. */
  static final class LoadProbe
  {
    public static void main(String[] args) throws IOException
    {
      final var resource = NativeLoader.libraryResource(
          System.getProperty("os.name").toLowerCase(ROOT), System.getProperty("os.arch").toLowerCase(ROOT));

      try(var stream = Objects.requireNonNull(NativeLoader.class.getResourceAsStream(resource))) {
        NativeLoader.loadFromOwnDirectory(Path.of(args[0]), stream.readAllBytes());
      }

      System.out.println("loaded");
    }
  }
}
