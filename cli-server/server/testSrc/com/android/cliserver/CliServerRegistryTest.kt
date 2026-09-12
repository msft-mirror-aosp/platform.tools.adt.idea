/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.cliserver

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CliServerRegistryTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun testClean_keepsRunningProcessWithMatchingStartTime() {
    val androidHome = tempFolder.newFolder("android_home").toPath()
    val registryDir = androidHome.resolve("cli/studio")
    registryDir.createDirectories()

    val process1 = FakeProcessHandle(pid = 123, alive = true, startInstant = Instant.ofEpochSecond(1000))
    val process2 = FakeProcessHandle(pid = 456, alive = true, startInstant = Instant.ofEpochSecond(2000))

    val processes = mapOf(123L to process1, 456L to process2)
    val processService =
      object : ProcessService {
        override val current = process1

        override fun of(pid: Long) = processes[pid]
      }

    // Existing registry entry for process2 written by a previous registration
    val file2 = registryDir.resolve("456")
    file2.writeText("5000 test-uuid-456 2000")

    val registry = CliServerRegistry(androidHome, processService)
    // Registering process1 triggers clean()
    registry.register(port = 1000)

    // file2 should NOT be deleted because process2 is alive and start times match
    assertThat(file2.exists()).isTrue()
    assertThat(file2.readText()).isEqualTo("5000 test-uuid-456 2000")
  }

  @Test
  fun testClean_removesDeadProcess() {
    val androidHome = tempFolder.newFolder("android_home").toPath()
    val registryDir = androidHome.resolve("cli/studio")
    registryDir.createDirectories()

    val process1 = FakeProcessHandle(pid = 123, alive = true, startInstant = Instant.ofEpochSecond(1000))
    val deadProcess = FakeProcessHandle(pid = 456, alive = false, startInstant = Instant.ofEpochSecond(2000))

    val processes = mapOf(123L to process1, 456L to deadProcess)
    val processService =
      object : ProcessService {
        override val current = process1

        override fun of(pid: Long) = processes[pid]
      }

    val deadFile = registryDir.resolve("456")
    deadFile.writeText("5000 test-uuid-456 2000")

    val registry = CliServerRegistry(androidHome, processService)
    registry.register(port = 1000)

    assertThat(deadFile.exists()).isFalse()
  }

  @Test
  fun testClean_removesProcessWithMismatchedStartTime() {
    val androidHome = tempFolder.newFolder("android_home").toPath()
    val registryDir = androidHome.resolve("cli/studio")
    registryDir.createDirectories()

    val process1 = FakeProcessHandle(pid = 123, alive = true, startInstant = Instant.ofEpochSecond(1000))
    // PID 456 was recycled: current process 456 started at 9999, but file says 2000
    val recycledProcess = FakeProcessHandle(pid = 456, alive = true, startInstant = Instant.ofEpochSecond(9999))

    val processes = mapOf(123L to process1, 456L to recycledProcess)
    val processService =
      object : ProcessService {
        override val current = process1

        override fun of(pid: Long) = processes[pid]
      }

    val file = registryDir.resolve("456")
    file.writeText("5000 test-uuid-456 2000")

    val registry = CliServerRegistry(androidHome, processService)
    registry.register(port = 1000)

    assertThat(file.exists()).isFalse()
  }

  @Test
  fun testRegisterAndUnregister() {
    val androidHome = tempFolder.newFolder("android_home").toPath()
    val registryDir = androidHome.resolve("cli/studio")

    val process1 = FakeProcessHandle(pid = 123, alive = true, startInstant = Instant.ofEpochSecond(1000))
    val processService =
      object : ProcessService {
        override val current = process1

        override fun of(pid: Long) = if (pid == 123L) process1 else null
      }

    val registry = CliServerRegistry(androidHome, processService)
    val uuid = registry.register(port = 8080)

    val file = registryDir.resolve("123")
    assertThat(file.exists()).isTrue()
    assertThat(file.readText()).isEqualTo("8080 $uuid 1000")

    registry.unregister()
    assertThat(file.exists()).isFalse()
  }

  private class FakeProcessHandle(
    private val pid: Long,
    private val alive: Boolean = true,
    private val startInstant: Instant? = Instant.ofEpochSecond(1000),
  ) : ProcessHandle {
    override fun pid(): Long = pid

    override fun parent(): Optional<ProcessHandle> = Optional.empty()

    override fun children(): Stream<ProcessHandle> = Stream.empty()

    override fun descendants(): Stream<ProcessHandle> = Stream.empty()

    override fun info(): ProcessHandle.Info =
      object : ProcessHandle.Info {
        override fun command(): Optional<String> = Optional.empty()

        override fun commandLine(): Optional<String> = Optional.empty()

        override fun arguments(): Optional<Array<String>> = Optional.empty()

        override fun startInstant(): Optional<Instant> = Optional.ofNullable(startInstant)

        override fun totalCpuDuration(): Optional<java.time.Duration> = Optional.empty()

        override fun user(): Optional<String> = Optional.empty()
      }

    override fun onExit(): CompletableFuture<ProcessHandle> = CompletableFuture.completedFuture(this)

    override fun supportsNormalTermination(): Boolean = true

    override fun destroy(): Boolean = true

    override fun destroyForcibly(): Boolean = true

    override fun isAlive(): Boolean = alive

    override fun hashCode(): Int = pid.hashCode()

    override fun equals(other: Any?): Boolean = other is ProcessHandle && other.pid() == pid

    override fun compareTo(other: ProcessHandle?): Int = pid.compareTo(other?.pid() ?: 0)
  }
}
