/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.profilers

import com.android.tools.idea.execution.common.AndroidSessionInfo
import com.android.tools.idea.profilers.AndroidProfilerToolWindow.Companion.getDeviceDisplayName
import com.android.tools.idea.profilers.AndroidProfilerToolWindowFactory.Companion.getProfilerToolWindow
import com.google.common.annotations.VisibleForTesting
import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

class ProfilerExecutionListener
@VisibleForTesting
internal constructor(
  private val mappingLocatorProvider: (Project) -> MappingFilesLocator,
  private val backgroundExecutor: Executor,
) : ExecutionListener {

  constructor() :
    this(
      { MappingFilesLocator(ProjectR8MappingSource(it)) },
      AppExecutorUtil.getAppExecutorService(),
    )

  override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
    val info = AndroidSessionInfo.from(handler) ?: return
    val project = env.project

    backgroundExecutor.execute { recordDeployment(project, info, mappingLocatorProvider(project)) }

    if (info.devices.size != 1) {
      return
    }

    // There are two scenarios here:
    // 1. If the profiler window is opened
    // 2. If the profiler window is closed, we cache the device+module info so the profilers can auto-start if the user opens the window
    // manually at a later time.
    runInEdt {
      val window = ToolWindowManager.getInstance(project).getToolWindow(AndroidProfilerToolWindowFactory.ID) ?: return@runInEdt
      window.isShowStripeButton = true
      val deviceName = getDeviceDisplayName(info.devices.first())
      val preferredProcessInfo = PreferredProcessInfo(deviceName, info.applicationId) { true }
      // If the window is currently not shown, either if the users click on Run/Debug or if they manually collapse/hide the window,
      // then we shouldn't start profiling the launched app.
      var profileStarted = false
      if (window.isVisible) {
        val profilerToolWindow = getProfilerToolWindow(project)
        if (profilerToolWindow != null) {
          profilerToolWindow.profile(preferredProcessInfo)
          profileStarted = true
        }
      }
      // Caching the device+process info in case auto-profiling should kick in at a later time.
      if (!profileStarted) {
        project.putUserData(AndroidProfilerToolWindow.LAST_RUN_APP_INFO, preferredProcessInfo)
      }
    }

    // When Studio detects that the process is terminated, remove the LAST_RUN_APP_INFO cache to prevent the profilers from waiting
    // to auto-profiling a process that has already been killed.
    handler.addProcessListener(
      object : ProcessAdapter() {
        override fun processTerminated(event: ProcessEvent) {
          project.putUserData(AndroidProfilerToolWindow.LAST_RUN_APP_INFO, null)
        }
      }
    )
  }

  companion object {
    private data class DevicePackageKey(val deviceSerial: String, val packageName: String)

    // Holds nullable mappingPath (null when a non-minified variant is deployed) and its last-modified timestamp at deploy time.
    private data class DeployedProguardMapping(val mappingPath: String?, val lastModifiedMillis: Long = -1L)

    private val DEPLOYED_PROGUARD_MAPPINGS_KEY =
      Key.create<ConcurrentHashMap<DevicePackageKey, DeployedProguardMapping>>("Profiler.Deployed.Proguard.Mappings")

    private fun getDeployedProguardMappings(project: Project): ConcurrentHashMap<DevicePackageKey, DeployedProguardMapping> =
      (project as UserDataHolderEx).putUserDataIfAbsent(DEPLOYED_PROGUARD_MAPPINGS_KEY, ConcurrentHashMap())

    /** Records the R8/Proguard mapping file path for the variant being deployed to each target device. */
    @VisibleForTesting
    internal fun recordDeployment(
      project: Project,
      info: AndroidSessionInfo,
      mappingLocator: MappingFilesLocator,
    ) {
      val applicationId = info.applicationId
      if (project.isDisposed || applicationId.isEmpty()) return
      val mappingPath = mappingLocator.getMappings()[applicationId]
      val record =
        mappingPath?.let { path ->
          getLastModifiedMillis(path)?.let { DeployedProguardMapping(path, it) }
        } ?: DeployedProguardMapping(null)
      val mappings = getDeployedProguardMappings(project)
      for (device in info.devices) {
        val serial = device.serialNumber
        if (!serial.isNullOrEmpty()) {
          mappings[DevicePackageKey(serial, applicationId)] = record
        }
      }
    }

    /**
     * Returns the Proguard/R8 mapping file path for [packageName] on [deviceSerial] if it was deployed by Android Studio with a
     * minify-enabled variant during the current project session and the mapping file on disk has not been modified since deployment, or
     * null otherwise.
     *
     * Entries are updated on each Studio deploy and discarded with the Project. They are intentionally not evicted on process termination
     * (so users can stop a run, restart the app, or record a startup trace and still deobfuscate) or on device disconnect (so transient ADB
     * reconnects do not drop the mapping).
     *
     * Known limitation: if the same package is overwritten outside Studio (e.g. via `adb install`) while the project is open, the last
     * Studio-deployed record remains until Studio redeploys.
     */
    @JvmStatic
    fun getDeployedAppProguardMapping(project: Project, deviceSerial: String, packageName: String): String? {
      if (project.isDisposed || deviceSerial.isEmpty() || packageName.isEmpty()) return null
      val deployedMapping =
        project.getUserData(DEPLOYED_PROGUARD_MAPPINGS_KEY)?.get(DevicePackageKey(deviceSerial, packageName)) ?: return null
      val path = deployedMapping.mappingPath ?: return null
      if (getLastModifiedMillis(path) != deployedMapping.lastModifiedMillis) {
        Logger.getInstance(ProfilerExecutionListener::class.java)
          .info("R8 mapping file for $packageName on device '$deviceSerial' was modified or deleted since deployment: $path")
        return null
      }
      return path
    }

    private fun getLastModifiedMillis(path: String): Long? =
      try {
        Files.getLastModifiedTime(Paths.get(path)).toMillis()
      } catch (_: IOException) {
        null
      }
  }
}
