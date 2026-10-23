/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.command.info

import com.google.common.truth.Truth.assertThat
import com.google.idea.blaze.base.BlazeTestCase
import com.google.idea.blaze.base.async.executor.BlazeExecutor
import com.google.idea.blaze.base.async.executor.MockBlazeExecutor
import com.google.idea.blaze.base.bazel.BazelExitCodeException
import com.google.idea.blaze.base.bazel.BuildSystem
import com.google.idea.blaze.base.bazel.BuildSystem.BuildEventStreamConsumer
import com.google.idea.blaze.base.bazel.BuildSystem.BuildInvoker
import com.google.idea.blaze.base.bazel.BuildSystem.BuildInvoker.Capability
import com.google.idea.blaze.base.command.BlazeCommand
import com.google.idea.blaze.base.scope.BlazeContext
import com.google.idea.blaze.base.settings.BuildBinaryType
import com.google.idea.blaze.base.settings.BuildSystemName
import com.intellij.execution.process.ProcessHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ExecutionException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [BlazeInfoRunnerImpl] verifying stdout parsing and [BuildException] propagation when [BuildInvoker.invokeInfo] fails. */
@RunWith(JUnit4::class)
class BlazeInfoRunnerImplTest : BlazeTestCase() {

  override fun initTest(applicationServices: Container, projectServices: Container) {
    applicationServices.register(BlazeExecutor::class.java, MockBlazeExecutor())
  }

  @Test
  fun runBlazeInfoGetBytes_invokerThrowsBazelExitCodeException_propagatesException() {
    val invoker = testInvoker {
      throw BazelExitCodeException("info failed", 2)
    }
    val runner = BlazeInfoRunnerImpl()
    val context = BlazeContext.create()

    val future =
      runner.runBlazeInfoGetBytes(
        project,
        invoker,
        context,
        emptyList(),
        BlazeInfo.RELEASE,
      )

    val exception = assertThrows(ExecutionException::class.java) { future.get() }
    assertThat(exception).hasCauseThat().isInstanceOf(BazelExitCodeException::class.java)
  }

  @Test
  fun runBlazeInfo_parsesKeyValueOutput() {
    val stdout =
      """
      execution_root: /workspace/execroot
      bazel-bin: /workspace/execroot/bazel-out/k8-fastbuild/bin
      bazel-genfiles: /workspace/execroot/bazel-out/k8-fastbuild/genfiles
      bazel-testlogs: /workspace/execroot/bazel-out/k8-fastbuild/testlogs
      output_base: /workspace/output_base
      release: release 8.0.0
      extraneous line without colon
      """
        .trimIndent()
    val invoker = testInvoker { ByteArrayInputStream(stdout.toByteArray(UTF_8)) }
    val runner = BlazeInfoRunnerImpl()
    val context = BlazeContext.create()

    val blazeInfo = runner.runBlazeInfo(project, invoker, context, BuildSystemName.Bazel, emptyList()).get()

    assertThat(blazeInfo.executionRoot).isEqualTo(File("/workspace/execroot"))
    assertThat(blazeInfo.outputBase).isEqualTo(File("/workspace/output_base"))
    assertThat(blazeInfo[BlazeInfo.RELEASE]).isEqualTo("release 8.0.0")
  }

  private fun testInvoker(onInvokeInfo: () -> InputStream): BuildInvoker =
    object : BuildInvoker {
      override val capabilities: Set<Capability> = emptySet()
      override val type: BuildBinaryType = BuildBinaryType.BAZEL
      override val invokeCommand: List<String> = listOf("bazel")
      override val canOverrideBinaryPath: Boolean = false
      override val buildSystem: BuildSystem
        get() = error("unused")

      override fun <T : Any> invoke(
        blazeCommandBuilder: BlazeCommand.Builder,
        blazeContext: BlazeContext,
        consumer: BuildEventStreamConsumer<T>,
      ): T = error("unused")

      override fun invokeAsProcessHandler(
        blazeCommandBuilder: BlazeCommand.Builder,
        blazeContext: BlazeContext,
        consumer: BuildEventStreamConsumer<Unit>,
      ): ProcessHandler = error("unused")

      override fun invokeQuery(
        blazeCommandBuilder: BlazeCommand.Builder,
        blazeContext: BlazeContext,
      ): InputStream = error("unused")

      override fun invokeInfo(
        blazeCommandBuilder: BlazeCommand.Builder,
        blazeContext: BlazeContext,
      ): InputStream = onInvokeInfo()

      override fun getBlazeInfo(blazeContext: BlazeContext): BlazeInfo = error("unused")
    }
}
