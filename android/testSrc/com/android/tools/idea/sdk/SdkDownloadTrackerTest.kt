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
package com.android.tools.idea.sdk

import com.android.repository.api.PackageOperation
import com.android.repository.impl.meta.RepositoryPackages
import com.android.repository.testframework.FakePackage.FakeLocalPackage
import com.android.repository.testframework.FakePackage.FakeRemotePackage
import com.android.repository.testframework.FakePackageOperation
import com.android.repository.testframework.FakeRepoManager
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.ApplicationRule
import org.junit.Rule
import org.junit.Test

private const val PATH = "system-images;android-34;google_apis;x86_64"

class SdkDownloadTrackerTest {
  @get:Rule val applicationRule = ApplicationRule()

  private val repoPackages = RepositoryPackages()
  private val repoManager = FakeRepoManager(repoPackages)

  private val tracker = SdkDownloadTracker()

  /** An install operation that has begun, as one started by the SDK Quickfix wizard would be. */
  private fun startInstall(path: String = PATH): FakePackageOperation {
    val remotePackage = FakeRemotePackage(path)
    repoManager.setRemotePackages(repoPackages.remotePackages.values + remotePackage)
    return FakePackageOperation(remotePackage, repoManager).also {
      it.setInstallStatus(PackageOperation.InstallStatus.PREPARING)
      repoManager.installBeginning(remotePackage, it)
    }
  }

  @Test
  fun testTrack() {
    assertThat(tracker.downloadStates.value[PATH]).isNull()

    tracker.track(startInstall())

    assertThat(tracker.downloadStates.value[PATH]).isEqualTo(SdkDownloadState.Downloading)
  }

  /**
   * An install completes when its package turns up in the SDK's local packages, which is also how installs made outside Studio reach us.
   */
  @Test
  fun testDownloadCompletesWhenPackageIsInstalled() {
    tracker.track(startInstall())
    assertThat(tracker.downloadStates.value[PATH]).isEqualTo(SdkDownloadState.Downloading)

    repoManager.setLocalPackages(FakeLocalPackage(PATH))

    assertThat(tracker.downloadStates.value[PATH]).isEqualTo(SdkDownloadState.Completed)
  }

  @Test
  fun testDownloadRemainsInProgressWhenAnotherPackageIsInstalled() {
    tracker.track(startInstall())

    repoManager.setLocalPackages(FakeLocalPackage("emulator"))

    assertThat(tracker.downloadStates.value[PATH]).isEqualTo(SdkDownloadState.Downloading)
    // Installing something we aren't following doesn't make us start following it.
    assertThat(tracker.downloadStates.value["emulator"]).isNull()
  }

  /** Once the download finishes, the operation reaches [PackageOperation.InstallStatus.PREPARED] and starts unpacking into the SDK. */
  @Test
  fun testDownloadReportsInstallingWhenDownloadFinishes() {
    val operation = startInstall()
    tracker.track(operation)
    assertThat(tracker.downloadStates.value[PATH]).isEqualTo(SdkDownloadState.Downloading)

    operation.setInstallStatus(PackageOperation.InstallStatus.PREPARED)

    assertThat(tracker.downloadStates.value[PATH]).isEqualTo(SdkDownloadState.Installing)

    // Finishing the install still completes the download.
    repoManager.setLocalPackages(FakeLocalPackage(PATH))
    assertThat(tracker.downloadStates.value[PATH]).isEqualTo(SdkDownloadState.Completed)
  }

  @Test
  fun testAlreadyPreparedDownloadIsDetectedImmediately() {
    val operation = startInstall()
    operation.setInstallStatus(PackageOperation.InstallStatus.PREPARED)

    tracker.track(operation)

    assertThat(tracker.downloadStates.value[PATH]).isEqualTo(SdkDownloadState.Installing)
  }

  @Test
  fun testDownloadFails() {
    val operation = startInstall()
    tracker.track(operation)

    operation.setInstallStatus(PackageOperation.InstallStatus.FAILED)

    assertThat(tracker.downloadStates.value[PATH]).isEqualTo(SdkDownloadState.Failed)
  }

  @Test
  fun testStopTrackingForgetsDownloads() {
    val operation = startInstall()
    tracker.track(operation)

    tracker.stopTracking()
    assertThat(tracker.downloadStates.value[PATH]).isNull()

    // Neither the install finishing nor the operation's own listener, which can't be unregistered, brings the state back.
    operation.setInstallStatus(PackageOperation.InstallStatus.COMPLETE)
    repoManager.setLocalPackages(FakeLocalPackage(PATH))

    assertThat(tracker.downloadStates.value[PATH]).isNull()
  }
}
