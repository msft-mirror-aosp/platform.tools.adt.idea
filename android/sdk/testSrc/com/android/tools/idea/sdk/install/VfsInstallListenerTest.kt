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
package com.android.tools.idea.sdk.install

import com.android.repository.api.PackageOperation
import com.android.repository.testframework.FakeProgressIndicator
import com.android.sdklib.IAndroidTarget
import com.android.testutils.TestUtils
import com.android.testutils.waitForCondition
import com.android.tools.idea.IdeInfo
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.sdk.AndroidSdksImpl
import com.android.tools.idea.sdk.IdeSdks
import com.android.tools.idea.testing.Sdks
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.ui.OrderRoot
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.replaceService
import com.intellij.util.ui.EDT
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** Tests for [VfsInstallListener]. */
class VfsInstallListenerTest {
  @get:Rule val projectRule = ProjectRule()
  @get:Rule val disposableRule = DisposableRule()

  private lateinit var sdkPath: File

  @Before
  fun setUp() {
    sdkPath = TestUtils.getSdk().toFile()
    Sdks.allowAccessToSdk(disposableRule.disposable, projectRule.project)
    IdeSdks.removeJdksOn(disposableRule.disposable)
  }

  @Test
  fun statusChangedCompleteUpdatesSdksOffEdtAndOutsideWriteLock() {
    val ideInfo =
      mock<IdeInfo> {
        on { isAndroidStudio } doReturn true
      }

    val getAllSdksCalled = AtomicBoolean(false)
    val getAllSdksOnEdt = AtomicBoolean(true)
    val getAllSdksWithWriteLock = AtomicBoolean(true)
    val getLibraryRootsCalled = AtomicBoolean(false)
    val getLibraryRootsOnEdt = AtomicBoolean(true)
    val getLibraryRootsWithWriteLock = AtomicBoolean(true)
    val getLibraryRootsWithReadLock = AtomicBoolean(true)

    val testAndroidSdks =
      object : AndroidSdksImpl(ideInfo) {
        override fun getAllAndroidSdks(): List<Sdk> {
          val app = ApplicationManager.getApplication()
          getAllSdksOnEdt.set(EDT.isCurrentThreadEdt())
          getAllSdksWithWriteLock.set(app.isWriteAccessAllowed)
          getAllSdksCalled.set(true)
          return super.getAllAndroidSdks()
        }

        override fun getLibraryRootsForTarget(
          target: IAndroidTarget,
          sdkPath: File?,
          addPlatformAndAddOnJars: Boolean,
        ): List<OrderRoot> {
          val app = ApplicationManager.getApplication()
          getLibraryRootsOnEdt.set(EDT.isCurrentThreadEdt())
          getLibraryRootsWithWriteLock.set(app.isWriteAccessAllowed)
          getLibraryRootsWithReadLock.set(app.holdsReadLock())
          getLibraryRootsCalled.set(true)
          return super.getLibraryRootsForTarget(target, sdkPath, addPlatformAndAddOnJars)
        }
      }

    ApplicationManager.getApplication().replaceService(AndroidSdks::class.java, testAndroidSdks, disposableRule.disposable)

    val target = Sdks.findLatestAndroidTarget(sdkPath)
    val sdk = checkNotNull(testAndroidSdks.create(target, sdkPath, "testSdk", /* addRoots= */ false))
    assertThat(sdk.rootProvider.getFiles(OrderRootType.CLASSES)).isEmpty()

    val installDir = FileUtil.createTempDirectory("installed-sdk-pkg", null).toPath()
    val installedMarker = installDir.resolve("source.properties")
    Files.writeString(installedMarker, "Pkg.Revision=1.0.0\n")

    val op =
      mock<PackageOperation> {
        on { installStatus } doReturn PackageOperation.InstallStatus.COMPLETE
        on { getLocation(any()) } doReturn installDir
      }

    VfsInstallListener().statusChanged(op, FakeProgressIndicator())

    waitForCondition(15.seconds) {
      sdk.rootProvider.getFiles(OrderRootType.CLASSES).isNotEmpty()
    }

    assertThat(VfsUtil.findFile(installedMarker, false)).isNotNull()

    assertThat(getAllSdksCalled.get()).isTrue()
    assertThat(getAllSdksOnEdt.get()).isFalse()
    assertThat(getAllSdksWithWriteLock.get()).isFalse()

    assertThat(getLibraryRootsCalled.get()).isTrue()
    assertThat(getLibraryRootsOnEdt.get()).isFalse()
    assertThat(getLibraryRootsWithWriteLock.get()).isFalse()
    assertThat(getLibraryRootsWithReadLock.get()).isFalse()

    val classRootNames = sdk.rootProvider.getFiles(OrderRootType.CLASSES).map { it.name }
    assertThat(classRootNames).contains("android.jar")
  }

  @Test
  fun statusChangedIgnoresNonCompleteStatuses() {
    val op =
      mock<PackageOperation> {
        on { installStatus } doReturn PackageOperation.InstallStatus.RUNNING
      }

    VfsInstallListener().statusChanged(op, FakeProgressIndicator())

    verify(op, never()).getLocation(any())
  }
}
