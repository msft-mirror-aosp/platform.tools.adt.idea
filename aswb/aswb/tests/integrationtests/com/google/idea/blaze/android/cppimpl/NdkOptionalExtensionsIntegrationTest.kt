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
package com.google.idea.blaze.android.cppimpl

import com.android.sdklib.devices.Abi
import com.android.tools.ndk.run.NativeDebuggerAppContextProvider
import com.android.tools.ndk.run.SymbolDir
import com.google.common.truth.Truth.assertThat
import com.google.idea.bazel.cpp.CppSupportChecker
import com.google.idea.blaze.android.run.BazelApplicationProjectContext
import com.google.idea.blaze.android.run.BazelNativeDebuggerAppContextProvider
import com.google.idea.blaze.base.BlazeIntegrationTestCase
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Integration tests verifying that NDK-dependent ASwB extensions declared in `ndk_contents.xml` are registered and functional when
 * `com.android.tools.ndk` is loaded.
 */
@RunWith(JUnit4::class)
class NdkOptionalExtensionsIntegrationTest : BlazeIntegrationTestCase() {

  @Test
  fun `ndk_contents registers NdkCppSupportChecker when NDK plugin is loaded`() {
    val checkers = CppSupportChecker.EP_NAME.extensionList

    assertThat(checkers.map { it.javaClass }).contains(NdkCppSupportChecker::class.java)
  }

  @Test
  fun `ndk_contents registers BazelNativeDebuggerAppContextProvider and wraps File symbolDirs`() {
    val providers = NativeDebuggerAppContextProvider.EP_NAME.getExtensions(project)
    assertThat(providers.map { it.javaClass }).contains(BazelNativeDebuggerAppContextProvider::class.java)

    val appSymbolDir = File("/cache/app/symbols")
    val testSymbolDir = File("/cache/test/symbols")
    val appContext =
      BazelApplicationProjectContext.forDeployedApplication(
        project = project,
        applicationId = "com.example.app",
        liveEditDataExtractor = null,
        symbolDirs = listOf(appSymbolDir, testSymbolDir),
      )

    val nativeContext = NativeDebuggerAppContextProvider.getNativeDebuggerAppContext(project, appContext)
    assertThat(nativeContext).isNotNull()
    assertThat(nativeContext!!.getSymDirs(listOf(Abi.ARM64_V8A)))
      .containsExactly(
        SymbolDir.WithoutSubdirectories(appSymbolDir),
        SymbolDir.WithoutSubdirectories(testSymbolDir),
      )
      .inOrder()
    assertThat(nativeContext.sourceMap).containsExactly("/proc/self/cwd", workspaceRoot.directory().path)
  }
}
