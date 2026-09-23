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
import com.android.repository.api.RepoManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The progress of an SDK package install, as reported by [SdkDownloadTracker]. */
sealed interface SdkDownloadState {
  /** The install hasn't finished, successfully or otherwise. */
  sealed interface InProgress : SdkDownloadState

  /** The package is being downloaded. */
  data object Downloading : InProgress

  /** The download has finished and the package is being unpacked into the SDK, which is slow enough to be worth reporting separately. */
  data object Installing : InProgress

  data object Completed : SdkDownloadState

  data object Failed : SdkDownloadState
}

/**
 * Tracks the progress of SDK package installs, so that UI that cares about a package can report on it without running the install itself.
 * The Device Manager uses this to show system image downloads on the devices that need them, including after the SDK Quickfix wizard has
 * been sent to the background and closed.
 *
 * Installs register themselves as they start, via [track]; an install that finishes elsewhere, such as in another instance of Studio, is
 * still noticed, because the SDK reloads its local packages either way.
 */
@Service(Service.Level.APP)
class SdkDownloadTracker : Disposable {
  private val _downloadStates = MutableStateFlow<Map<String, SdkDownloadState>>(emptyMap())

  /** The state of each tracked install, by package path. */
  val downloadStates: StateFlow<Map<String, SdkDownloadState>> = _downloadStates.asStateFlow()

  /** The [RepoManager]s we are listening to for install completion, and the listener registered on each. */
  private val watchedRepoManagers = mutableMapOf<RepoManager, RepoManager.RepoLoadedListener>()

  /**
   * The install operations we have already registered a status listener on. [PackageOperation] doesn't support unregistering listeners, so
   * this is only used to avoid registering the same listener twice.
   */
  private val watchedOperations = mutableSetOf<PackageOperation>()

  /**
   * Follows [operation], so that the state of the package it installs reflects its progress. Safe to call more than once for the same
   * operation.
   */
  fun track(operation: PackageOperation) {
    val packagePath = operation.`package`.path
    _downloadStates.update { it + (packagePath to SdkDownloadState.Downloading) }
    watchForCompletion(operation.repoManager)
    watchInstallOperation(operation)
  }

  /**
   * Listens for local package changes on [repoManager], which is how we learn that an install has finished. We wait for this rather than
   * acting on [PackageOperation.InstallStatus.COMPLETE] because consumers care about the package being usable, which it isn't until the SDK
   * has reloaded. It also covers installs made outside of Studio, which reach us no other way.
   */
  private fun watchForCompletion(repoManager: RepoManager) {
    synchronized(watchedRepoManagers) {
      if (watchedRepoManagers.containsKey(repoManager)) return
      val listener = RepoManager.RepoLoadedListener { packages -> completeInstalledPackages(packages.localPackages.keys) }
      watchedRepoManagers[repoManager] = listener
      repoManager.addLocalChangeListener(listener)
    }
  }

  /** Listens to [operation] for the phase of the install, and for failure. */
  private fun watchInstallOperation(operation: PackageOperation) {
    synchronized(watchedOperations) {
      if (watchedOperations.add(operation)) {
        operation.registerStateChangeListener { op, _ ->
          // PackageOperation fails the operation if a listener throws, so make sure we never break an install we're only observing.
          try {
            updateStateFromInstallStatus(op)
            if (op.installStatus in FINISHED_STATUSES) {
              synchronized(watchedOperations) { watchedOperations.remove(op) }
            }
          } catch (e: Exception) {
            thisLogger().warn("Failed to record the install status of ${op.`package`.path}", e)
          }
        }
      }
    }
    // The operation may already have advanced past the states we care about before we got here.
    updateStateFromInstallStatus(operation)
  }

  /**
   * Updates the state of [operation]'s package to reflect its status. [PackageOperation.InstallStatus.COMPLETE] is deliberately not handled
   * here: at that point the SDK's local packages haven't been reloaded yet, so we wait for [completeInstalledPackages] instead.
   */
  private fun updateStateFromInstallStatus(operation: PackageOperation) {
    val packagePath = operation.`package`.path

    val newState =
      when (operation.installStatus) {
        // The download is done and the package is being unpacked into the SDK.
        PackageOperation.InstallStatus.PREPARED,
        PackageOperation.InstallStatus.RUNNING -> SdkDownloadState.Installing
        PackageOperation.InstallStatus.FAILED -> SdkDownloadState.Failed
        else -> return
      }

    // Only update packages we're already tracking; we don't want to start tracking a package because of an unrelated install.
    _downloadStates.update { states -> if (packagePath in states) states + (packagePath to newState) else states }
  }

  /** Marks any unfinished download whose package is present in [localPackagePaths] as completed. */
  private fun completeInstalledPackages(localPackagePaths: Set<String>) {
    _downloadStates.update { states ->
      states.mapValues { (path, state) ->
        when {
          state is SdkDownloadState.InProgress && path in localPackagePaths -> SdkDownloadState.Completed
          else -> state
        }
      }
    }
  }

  /**
   * Forgets every install we're tracking. This is for when the SDK changes: everything we're tracking is an install into the SDK we just
   * stopped using, so none of it is relevant to the new one, and the listeners we registered on the old SDK's [RepoManager] would otherwise
   * be left behind. Installs that are still running are left alone to finish into the SDK they started in.
   */
  fun stopTracking() {
    synchronized(watchedRepoManagers) {
      watchedRepoManagers.forEach { (repoManager, listener) -> repoManager.removeLocalChangeListener(listener) }
      watchedRepoManagers.clear()
    }
    // PackageOperation listeners can't be unregistered, but the operations themselves are short-lived; just drop our references to them.
    synchronized(watchedOperations) { watchedOperations.clear() }
    // Any listener that outlives this only updates packages that are still tracked, so it won't resurrect anything we drop here.
    _downloadStates.value = emptyMap()
  }

  override fun dispose() {
    stopTracking()
  }

  companion object {
    /** Statuses after which a [PackageOperation] will never change again, so there's no point in watching it. */
    private val FINISHED_STATUSES = setOf(PackageOperation.InstallStatus.COMPLETE, PackageOperation.InstallStatus.FAILED)

    val instance: SdkDownloadTracker
      get() = service()
  }
}

/** Stops tracking installs into the previous SDK when the SDK path changes. */
class SdkDownloadTrackerEventListener : IdeSdks.AndroidSdkEventListener {
  override fun afterSdkPathChange(sdkPath: File, project: Project) {
    SdkDownloadTracker.instance.stopTracking()
  }
}
