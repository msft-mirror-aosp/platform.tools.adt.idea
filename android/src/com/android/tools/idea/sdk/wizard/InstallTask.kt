/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.sdk.wizard

import com.android.repository.api.DelegatingProgressIndicator
import com.android.repository.api.Downloader
import com.android.repository.api.Installer
import com.android.repository.api.InstallerFactory
import com.android.repository.api.LocalPackage
import com.android.repository.api.PackageOperation
import com.android.repository.api.ProgressIndicator
import com.android.repository.api.RemotePackage
import com.android.repository.api.RepoManager
import com.android.repository.api.RepoPackage
import com.android.repository.api.SettingsController
import com.android.repository.api.Uninstaller
import com.android.repository.api.UpdatablePackage
import com.android.repository.impl.installer.AbstractPackageOperation
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.progress.RawProgressReporterAdapter
import com.android.tools.idea.progress.StudioLoggerProgressIndicator
import com.android.tools.idea.sdk.StudioDownloader
import com.android.tools.idea.sdk.StudioSettingsController
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.android.AndroidPluginDisposable

private val log: Logger
  get() = logger<InstallTask>()

/** Task that installs SDK packages. */
class InstallTask(
  private val installerFactory: InstallerFactory,
  private val sdkHandler: AndroidSdkHandler,
  private val settingsController: SettingsController = StudioSettingsController.getInstance(),
  private val logger: ProgressIndicator = StudioLoggerProgressIndicator(InstallTask::class.java),
  val installRequests: Collection<UpdatablePackage> = emptyList(),
  val uninstallRequests: Collection<LocalPackage> = emptyList(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  val completeCallback: ((List<RepoPackage>) -> Unit)? = null,
) {
  private val repoManager: RepoManager = sdkHandler.getRepoManagerAndLoadSynchronously(logger)

  /** Runs the installation with IntelliJ's [withBackgroundProgress] if a project is available. */
  suspend fun run(
    project: Project? = null,
    title: String = "Installing Android SDK",
    cancellable: Boolean = true,
  ): List<RepoPackage> {
    val targetProject =
      (project ?: IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project)?.takeUnless { it.isDisposed || it.isDefault }
        ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed && !it.isDefault }

    return if (targetProject != null) {
      withBackgroundProgress(targetProject, title, cancellable) {
        reportRawProgress { reporter ->
          val progressAdapter = RawProgressReporterAdapter(reporter)
          val combinedProgress =
            DelegatingProgressIndicator(logger).apply {
              addDelegate(progressAdapter)
            }
          execute(combinedProgress)
        }
      }
    } else {
      withContext(ioDispatcher) {
        execute(logger)
      }
    }
  }

  /** Asynchronous launch helper for Java / non-suspending callers. */
  @JvmOverloads
  fun runAsync(
    project: Project? = null,
    coroutineScope: CoroutineScope = AndroidPluginDisposable.getApplicationInstance().createCoroutineScope(),
    title: String = "Installing Android SDK",
    cancellable: Boolean = true,
  ): Job = coroutineScope.launch {
    run(project, title, cancellable)
  }

  @VisibleForTesting
  fun execute(progress: ProgressIndicator): List<RepoPackage> {
    val failures = mutableListOf<RepoPackage>()
    val operations = mutableMapOf<RepoPackage, PackageOperation>()

    if (installRequests.isNotEmpty()) {
      logger.logInfo("Packages to install: ")
      for (install in installRequests) {
        val remote = install.remote
        if (remote != null) {
          logger.logInfo("- ${remote.displayName} (${remote.path})")
          operations[remote] = getOrCreateInstaller(remote)
        }
      }
      logger.logInfo("\n")
    }

    if (uninstallRequests.isNotEmpty()) {
      logger.logInfo("Packages to uninstall: ")
      for (uninstall in uninstallRequests) {
        logger.logInfo("- ${uninstall.displayName} (${uninstall.path})")
        operations[uninstall] = getOrCreateUninstaller(uninstall)
      }
      logger.logInfo("\n")
    }

    try {
      progress.fraction = 0.0
      preparePackages(operations, failures, progress)
      progress.checkCanceled()
      completePackages(operations, failures, progress.createSubProgress(0.9), progress)
      progress.fraction = 0.9
    } finally {
      if (failures.isNotEmpty()) {
        logger.logInfo("Failed packages:")
        for (p in failures) {
          logger.logInfo("- ${p.displayName} (${p.path})")
        }
      }
    }
    // Use a simple progress indicator here so we don't pick up the log messages from the reload.
    val reloadProgress = StudioLoggerProgressIndicator(javaClass)
    repoManager.loadSynchronously(RepoManager.DEFAULT_EXPIRATION_PERIOD_MS, reloadProgress, null, settingsController)
    completeCallback?.invoke(failures)
    progress.fraction = 1.0
    return failures
  }

  /**
   * Complete installation of the given packages using the given operations. If a package is completed successfully, it is removed from
   * {@code operations}. If a package fails to be installed, it is removed from {@code operations} and added to {@code failures}.
   */
  @VisibleForTesting
  fun completePackages(
    operations: MutableMap<RepoPackage, PackageOperation>,
    failures: MutableList<RepoPackage>,
    progress: ProgressIndicator,
    taskProgressIndicator: ProgressIndicator,
  ) {
    var progressMax = 0.0
    val packages = operations.keys.toList()
    val progressIncrement = 1.0 / packages.size

    for (p in packages) {
      taskProgressIndicator.checkCanceled()
      val installer = operations[p] ?: continue
      progressMax += progressIncrement

      if (!installer.complete(progress.createSubProgress(progressMax))) {
        taskProgressIndicator.checkCanceled()
        progress.fraction = progressMax
        failures.add(p)
        operations.remove(p)
      } else {
        operations.remove(p)
        progress.fraction = progressMax
      }
    }
  }

  private fun getOrCreateInstaller(remote: RemotePackage): PackageOperation {
    var op = repoManager.getInProgressInstallOperation(remote)
    if (op !is Installer) {
      val downloader: Downloader =
        StudioDownloader().apply {
          val localPath = repoManager.localPath
          if (localPath != null) {
            setDownloadIntermediatesLocation(localPath.resolve(AbstractPackageOperation.DOWNLOAD_INTERMEDIATES_DIR_FN))
          }
        }
      op = installerFactory.createInstaller(remote, repoManager, downloader)
    }
    return op
  }

  private fun getOrCreateUninstaller(local: LocalPackage): PackageOperation {
    val op = repoManager.getInProgressInstallOperation(local)
    if (op !is Uninstaller || op.installStatus == PackageOperation.InstallStatus.FAILED) {
      return installerFactory.createUninstaller(local, repoManager)
    }
    return op
  }

  /**
   * Prepare the given packages using the given operations. If preparation for a package fails, it is removed from {@code
   * packageOperationMap} and added to {@code failures}.
   */
  @VisibleForTesting
  fun preparePackages(
    packageOperationMap: MutableMap<RepoPackage, PackageOperation>,
    failures: MutableList<RepoPackage>,
    progress: ProgressIndicator,
  ) {
    // Preparing is half of the work; completing the operations is the other half.
    val progressIncrement = 1.0 / (packageOperationMap.size * 2.0)

    for ((pack, op) in packageOperationMap.entries.toList()) {
      progress.checkCanceled()
      var success = false

      try {
        val progressMax = progress.fraction + progressIncrement
        success = op.prepare(progress.createSubProgress(progressMax))
        progress.checkCanceled()
        progress.fraction = progressMax
      } catch (e: ProcessCanceledException) {
        throw e
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.warn(e)
      }

      if (!success) {
        failures.add(pack)
        packageOperationMap.remove(pack)
      }
    }
  }

  private fun ProgressIndicator.checkCanceled() {
    if (isCanceled) {
      throw ProcessCanceledException()
    }
  }
}
