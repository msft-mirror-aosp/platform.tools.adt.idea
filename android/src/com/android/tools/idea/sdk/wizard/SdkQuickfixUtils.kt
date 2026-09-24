/*
 * Copyright (C) 2015 The Android Open Source Project
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

import com.android.annotations.concurrency.Slow
import com.android.repository.api.LocalPackage
import com.android.repository.api.ProgressIndicator
import com.android.repository.api.ProgressIndicatorAdapter
import com.android.repository.api.RepoManager
import com.android.repository.api.RepoPackage
import com.android.repository.api.UpdatablePackage
import com.android.repository.impl.meta.RepositoryPackages
import com.android.repository.util.InstallerUtil
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.tools.idea.progress.StudioLoggerProgressIndicator
import com.android.tools.idea.progress.StudioProgressRunner
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.sdk.StudioDownloader
import com.android.tools.idea.sdk.StudioSettingsController
import com.android.tools.idea.wizard.model.ModelWizard
import com.android.tools.idea.wizard.model.ModelWizardDialog
import com.android.tools.idea.wizard.ui.StudioWizardDialogBuilder
import com.android.utils.HtmlBuilder
import com.google.common.annotations.VisibleForTesting
import com.intellij.CommonBundle
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.ui.JBUI
import java.awt.Component
import org.jetbrains.android.util.AndroidBundle

object SdkQuickfixUtils {
  private val REPO_LOGGER: ProgressIndicator = StudioLoggerProgressIndicator(SdkQuickfixUtils::class.java)

  /**
   * Create an SdkQuickFix dialog.
   *
   * @param parent The component to use as a parent for the wizard dialog.
   * @param requestedPackages The packages to install. Callers should ensure that the given packages include remote versions.
   * @param uninstallPackages The packages to uninstall.
   * @param backgroundable Whether the dialog should show a "background" button on the progress step.
   */
  @JvmStatic
  @JvmOverloads
  fun createDialogForPackages(
    parent: Component?,
    requestedPackages: Collection<UpdatablePackage>,
    uninstallPackages: Collection<LocalPackage>? = null,
    backgroundable: Boolean = false,
  ): ModelWizardDialog? = createDialog(null, parent, null, requestedPackages, uninstallPackages, sdkHandler, null, backgroundable)

  /**
   * Create an SdkQuickFix dialog.
   *
   * @param parent The component to use as a parent for the wizard dialog.
   * @param requestedPaths The package paths to install. See [RepoPackage.getPath]. Callers should make reasonably sure that there is a
   *   package with the given path available.
   */
  @JvmStatic
  fun createDialogForPaths(parent: Component?, requestedPaths: Collection<String>, backgroundable: Boolean): ModelWizardDialog? =
    createDialog(null, parent, requestedPaths, null, null, sdkHandler, null, backgroundable)

  /**
   * Create an SdkQuickFix dialog.
   *
   * @param project The [Project] to use as a parent for the wizard dialog.
   * @param requestedPaths The paths of packages to install. Callers should ensure that the given packages include remote versions.
   * @param noOpMessage Error message to show when nothing is going to be installed or uninstalled after resolving the resolved paths or
   *   null if there is no need to show an error.
   */
  @JvmStatic
  @JvmOverloads
  fun createDialogForPaths(project: Project?, requestedPaths: Collection<String>, noOpMessage: String? = null): ModelWizardDialog? =
    createDialog(project, null, requestedPaths, null, null, sdkHandler, noOpMessage, false)

  /**
   * Create an SdkQuickFix dialog.
   *
   * @param project The [Project] to use as a parent for the wizard dialog.
   * @param requestedPaths The paths of packages to install. Callers should ensure that the given packages include remote versions.
   * @param backgroundable Whether the dialog should show a "background" button on the progress step.
   */
  @JvmStatic
  fun createDialogForPaths(project: Project?, requestedPaths: Collection<String>, backgroundable: Boolean): ModelWizardDialog? =
    createDialog(project, null, requestedPaths, null, null, sdkHandler, null, backgroundable)

  @JvmStatic
  fun showSdkMissingDialog() {
    val msg = AndroidBundle.message("android.sdk.missing.msg")
    val title = AndroidBundle.message("android.sdk.missing.title")
    val okText = AndroidBundle.message("android.sdk.open.manager")
    val cancelText = CommonBundle.getCancelButtonText()

    if (Messages.showOkCancelDialog(null as Project?, msg, title, okText, cancelText, Messages.getErrorIcon()) == Messages.OK) {
      showAndroidSdkManager()
    }
  }

  @JvmStatic
  fun showAndroidSdkManager() {
    val action = ActionManager.getInstance().getAction("Android.RunAndroidSdkManager") ?: return
    val event = AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
    ActionUtil.performAction(action, event)
  }

  private val sdkHandler: AndroidSdkHandler?
    get() =
      AndroidSdks.getInstance().tryToChooseAndroidSdk()?.sdkHandler
        ?: run {
          showSdkMissingDialog()
          null
        }

  @VisibleForTesting
  @JvmStatic
  fun createDialog(
    project: Project?,
    parent: Component?,
    requestedPaths: Collection<String>?,
    requestedPackages: Collection<UpdatablePackage>?,
    requestedUninstalls: Collection<LocalPackage>?,
    sdkHandler: AndroidSdkHandler?,
    noOpMessage: String?,
    backgroundable: Boolean,
  ): ModelWizardDialog? {
    if (sdkHandler == null) {
      return null
    }

    val repoManager = sdkHandler.getRepoManager(REPO_LOGGER)
    val localPath = repoManager.localPath
    if (localPath == null) {
      showSdkMissingDialog()
      return null
    }

    val unknownPaths = mutableListOf<String>()
    val resolvedPackages: List<UpdatablePackage> =
      if (!requestedPackages.isNullOrEmpty() || !requestedPaths.isNullOrEmpty()) {
        // This is an expensive call involving a number of manifest download operations,
        // so make it only when some installations are requested.
        repoManager.loadSynchronously(
          cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS,
          runner = StudioProgressRunner(false, "Finding Available SDK Components", project),
          downloader = StudioDownloader(),
          settings = StudioSettingsController.getInstance(),
        )
        val packages = repoManager.packages
        val packagesToResolve = (requestedPackages ?: emptyList()) + lookupPaths(requestedPaths, packages, unknownPaths)
        try {
          resolve(packagesToResolve, packages)
        } catch (e: PackageResolutionException) {
          Messages.showErrorDialog(e.message, "Error Resolving Packages")
          return null
        }
      } else {
        emptyList()
      }

    // We don't want to uninstall something required by a package we're installing
    val installedLocals = resolvedPackages.mapNotNullTo(mutableSetOf()) { it.local }
    val resolvedUninstalls = requestedUninstalls.orEmpty().toSet() - installedLocals

    val (availablePackages, unavailableDownloads) = resolvedPackages.partition { it.hasRemote() }

    // If there were requests we didn't understand or can't download, show an error.
    if (unknownPaths.isNotEmpty() || unavailableDownloads.isNotEmpty()) {
      val builder =
        HtmlBuilder().apply {
          openHtmlBody()
          add("${if (availablePackages.isEmpty()) "All" else "Some"} packages are not available for download!")
          newline()
          newline()
          add("The following packages are not available:")
          beginList()
          unavailableDownloads.forEach { listItem().add(it.representative.displayName) }
          unknownPaths.forEach { listItem().add("Package id $it") }
          endList()
          closeHtmlBody()
        }
      Messages.showErrorDialog(builder.html, "Packages Unavailable")
    }

    // If everything was removed, don't continue.
    if (availablePackages.isEmpty() && resolvedUninstalls.isEmpty()) {
      if (noOpMessage != null) {
        Messages.showErrorDialog(project, noOpMessage, "SDK Manager")
      }
      return null
    }
    val installRequests = availablePackages.mapNotNull { it.remote }
    val wizard =
      ModelWizard.Builder()
        .apply {
          addStep(LicenseAgreementStep(LicenseAgreementModel(localPath)) { installRequests })
          addStep(InstallSelectedPackagesStep(availablePackages, resolvedUninstalls, { sdkHandler }, backgroundable, JBUI.emptyInsets()))
        }
        .build()

    return StudioWizardDialogBuilder(wizard, "SDK Quickfix Installation", parent)
      .setProject(project)
      .setModalityType(DialogWrapper.IdeModalityType.IDE)
      .setCancellationPolicy(ModelWizardDialog.CancellationPolicy.CAN_CANCEL_UNTIL_CAN_FINISH)
      .build()
  }

  private fun lookupPaths(
    requestedPaths: Collection<String>?,
    packages: RepositoryPackages,
    unknownPaths: MutableList<String>,
  ): Collection<UpdatablePackage> {
    if (requestedPaths == null) return emptyList()
    val result = mutableListOf<UpdatablePackage>()
    for (path in requestedPaths) {
      val p = packages.consolidatedPkgs[path]
      if (p == null || !p.hasRemote()) {
        unknownPaths.add(path)
      } else {
        result.add(p)
      }
    }
    return result
  }

  /**
   * Checks whether a given package path is available for download.
   *
   * @param path The package path to check, corresponding to [RepoPackage.getPath].
   */
  @Slow
  @JvmStatic
  fun checkPathIsAvailableForDownload(path: String): Boolean {
    // Loading the manager below can require waiting for something on the EDT. If this code has a read lock, this can result in deadlock.
    ThreadingAssertions.assertNoOwnReadAccess()

    val repoManager = AndroidSdks.getInstance().tryToChooseSdkHandler().getRepoManager(REPO_LOGGER)
    repoManager.loadSynchronously(
      cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS,
      runner = StudioProgressRunner(false, "Finding Available SDK Components", null),
      downloader = StudioDownloader(),
      settings = StudioSettingsController.getInstance(),
    )
    val packages = repoManager.packages

    return packages.remotePackages.containsKey(path)
  }

  /**
   * Finds and adds dependencies for the given packages.
   *
   * @return The requested packages and dependencies.
   * @throws PackageResolutionException If the required packages have dependencies that are invalid or cannot be met.
   */
  // TODO: Once welcome wizard is rewritten using ModelWizard this should be refactored as needed.
  @Throws(PackageResolutionException::class)
  @JvmStatic
  fun resolve(
    requestedPackages: Collection<UpdatablePackage>,
    packages: RepositoryPackages,
  ): List<UpdatablePackage> {
    val remotes = requestedPackages.mapNotNull { it.remote }
    var warning: String? = null
    val errorCollector =
      object : ProgressIndicatorAdapter() {
        override fun logWarning(s: String) {
          warning = s
        }
      }
    val requiredPackages =
      InstallerUtil.computeRequiredPackages(remotes, packages, errorCollector) ?: throw PackageResolutionException(warning)

    return requiredPackages.map {
      packages.consolidatedPkgs[it.path] ?: throw PackageResolutionException("Failed to find package with key ${it.path}")
    }
  }

  class PackageResolutionException(message: String? = null) : Exception(message)
}
