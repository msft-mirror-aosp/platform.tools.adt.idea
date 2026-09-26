/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea

import com.android.repository.api.LocalPackage
import com.android.repository.api.RemotePackage
import com.android.repository.impl.meta.RepositoryPackages
import com.android.repository.testframework.FakePackage
import com.android.repository.testframework.FakeProgressIndicator
import com.android.repository.testframework.FakeRepoManager
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.sdk.IdeSdks
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.IdeComponents
import com.android.tools.idea.testing.NamedExternalResource
import java.io.File
import java.nio.file.Path
import org.junit.runner.Description
import org.mockito.Mockito.spy
import org.mockito.kotlin.whenever

/**
 * Beginning of an attempt to allow the Sdk to be mocked out with one that contains only the given components.
 *
 * TODO: combine this and com.android.sdklib.TempSdkManager
 */
class FakeSdkRule(val projectRule: AndroidProjectRule, val sdkPath: Path = createInMemoryFileSystemAndFolder("sdk")) :
  NamedExternalResource() {

  lateinit var repoManager: FakeRepoManager
  val localPackages = mutableListOf<LocalPackage>()
  val remotePackages = mutableListOf<RemotePackage>()

  fun withLocalPackage(localPackage: LocalPackage) = apply { localPackages.add(localPackage) }

  fun withLocalPackages(packages: Collection<LocalPackage>) = apply { localPackages.addAll(packages) }

  fun withLocalPackage(path: String, location: String) = apply {
    localPackages.add(FakePackage.FakeLocalPackage(path, sdkPath.resolve(location)))
  }

  fun withRemotePackage(remotePackage: RemotePackage) = apply { remotePackages.add(remotePackage) }

  /** Reads the packages from [sdkPath] into the [FakeRepoManager]. */
  fun withExistingPackages() = apply {
    val existingPackages = AndroidSdkHandler(sdkPath, null).getRepoManagerAndLoadSynchronously(FakeProgressIndicator()).packages
    localPackages.addAll(existingPackages.localPackages.values)
    remotePackages.addAll(existingPackages.remotePackages.values)
  }

  fun addLocalPackage(path: String, location: String) {
    repoManager.setLocalPackages(repoManager.packages.localPackages.values + FakePackage.FakeLocalPackage(path, sdkPath.resolve(location)))
  }

  fun setLocalPackages(packages: Collection<LocalPackage>) {
    repoManager.setLocalPackages(packages)
  }

  override fun before(description: Description) {
    repoManager = FakeRepoManager(sdkPath, RepositoryPackages(localPackages, remotePackages))
    val sdkHandler = AndroidSdkHandler(sdkPath, null, repoManager)

    val ideSdks = spy(IdeSdks.getInstance())
    whenever(ideSdks.androidSdkPath).thenReturn(File(sdkPath.toString()))
    IdeComponents(projectRule.fixture).replaceApplicationService(IdeSdks::class.java, ideSdks)

    val androidSdks = spy(AndroidSdks.getInstance())
    whenever(androidSdks.tryToChooseSdkHandler()).thenReturn(sdkHandler)
    IdeComponents(projectRule.fixture).replaceApplicationService(AndroidSdks::class.java, androidSdks)
  }

  override fun after(description: Description) {}
}
