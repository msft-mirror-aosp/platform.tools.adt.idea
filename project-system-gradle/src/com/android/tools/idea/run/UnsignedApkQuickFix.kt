/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.run

import com.android.tools.idea.concurrency.finallySync
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel
import com.android.tools.idea.gradle.dsl.api.GradleModelProvider
import com.android.tools.idea.gradle.dsl.api.ext.ReferenceTo
import com.android.tools.idea.gradle.dsl.model.android.android
import com.android.tools.idea.projectsystem.getSyncManager
import com.android.tools.idea.projectsystem.toReason
import com.google.common.annotations.VisibleForTesting
import com.google.common.util.concurrent.MoreExecutors
import com.google.wireless.android.sdk.stats.GradleSyncStats.Trigger.TRIGGER_QF_SIGNING_CONFIG_SELECTED
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.options.ConfigurationQuickFix
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.function.Consumer
import javax.swing.JComponent
import javax.swing.JPanel

/** Runnable to fix unsigned APK error. */
@Suppress("UnstableApiUsage")
class UnsignedApkQuickFix
@VisibleForTesting
constructor(
  val module: Module,
  val selectedBuildTypeName: String,
  val callback: Runnable?,
  private val makeSigningConfigSelector: (Collection<String>) -> SigningConfigSelector,
) : ConfigurationQuickFix, Disposable {
  /**
   * Instantiates a dialog as the quick fix action for unsigned APK error. When user closed the dialog with the OK button, the selected
   * signing config is picked.
   *
   * @param module an IDEA module
   * @param selectedBuildTypeName name of the currently selected build type, e.g. debug
   * @param callback a Runnable to execute after Gradle sync finishes
   */
  constructor(
    module: Module,
    selectedBuildTypeName: String,
    callback: Runnable?,
  ) : this(module, selectedBuildTypeName, callback, ::SigningConfigSelectorDialog)

  init {
    @Suppress("IncorrectParentDisposable") Disposer.register(module, this)
  }

  override fun dispose() {
    unsignedApkQuickFix = null
  }

  override fun applyFix(dataContext: DataContext) {
    val project = module.project
    val (initialBuildModel, signingConfigNames, initialStamps) =
      computeInBackgroundReadAction(project, "Loading Signing Configurations...") {
        val buildModel =
          GradleModelProvider.getInstance().getProjectModel(project).getModuleBuildModel(module)
            ?: return@computeInBackgroundReadAction null
        val configNames = buildModel.android().signingConfigs().map { it.name() }
        Triple(buildModel, configNames, buildModel.modificationStamps())
      } ?: throw IllegalStateException("Gradle build model should not be null for module: ${module.name}.")

    val signingConfigSelector = makeSigningConfigSelector(signingConfigNames)
    if (!signingConfigSelector.showAndGet()) return
    val selectedConfigName = signingConfigSelector.selectedConfig()

    val (gradleBuildModel, preparedStamps) =
      computeInBackgroundReadAction(project, "Applying Signing Configuration...") {
        val buildModel =
          // The read lock is released while showAndGet() awaits user selection, and
          // modificationStamps() queries live VFS/Document/PSI stamps: reuse initialBuildModel if
          // the underlying build files were not modified in the meantime, or re-parse otherwise.
          if (initialBuildModel.modificationStamps() == initialStamps) {
            initialBuildModel
          } else {
            GradleModelProvider.getInstance().getProjectModel(project).getModuleBuildModel(module)
              ?: return@computeInBackgroundReadAction null
          }
        val selectedConfig =
          buildModel.android().signingConfigs().find { it.name() == selectedConfigName } ?: return@computeInBackgroundReadAction null
        val selectedBuildType =
          buildModel.android().buildTypes().find { it.name() == selectedBuildTypeName } ?: return@computeInBackgroundReadAction null
        selectedBuildType.signingConfig().setValue(ReferenceTo(selectedConfig))
        buildModel to buildModel.modificationStamps()
      } ?: return

    // Write signingConfig to Gradle.
    val applied =
      WriteCommandAction.writeCommandAction(project).withName(DIALOG_TITLE).compute<Boolean, RuntimeException> {
        // Guard against build files changing between the background read action and acquiring the write lock.
        if (gradleBuildModel.modificationStamps() != preparedStamps) return@compute false
        gradleBuildModel.applyChanges()
        true
      }
    if (!applied) return

    // Trigger Gradle sync for the signingConfig to take effect.
    project.getSyncManager().requestSyncProject(TRIGGER_QF_SIGNING_CONFIG_SELECTED.toReason()).finallySync(MoreExecutors.directExecutor()) {
      callback?.run()
    }
  }

  companion object {
    @VisibleForTesting var unsignedApkQuickFix: UnsignedApkQuickFix? = null

    /**
     * To avoid repeatedly creating a new QuickFix (and losing the calling SettingsEditor's callback), only instantiate a new
     * UnsignedApkQuickFix if the cached one doesn't match. Null callbacks also will not overwrite the existing cache if the module and
     * build type remain the same.
     */
    @JvmStatic
    fun create(module: Module, buildType: String, quickFixCallback: Runnable?): UnsignedApkQuickFix? {
      if (
        unsignedApkQuickFix == null ||
          unsignedApkQuickFix?.module != module ||
          unsignedApkQuickFix?.selectedBuildTypeName != buildType ||
          (quickFixCallback != null && unsignedApkQuickFix?.callback != quickFixCallback)
      ) {
        unsignedApkQuickFix?.let { Disposer.dispose(it) }
        unsignedApkQuickFix = UnsignedApkQuickFix(module, buildType, quickFixCallback)
      }

      return unsignedApkQuickFix
    }
  }
}

interface SigningConfigSelector {
  /**
   * Shows the selector UI component and blocks on user selection
   *
   * @return true if the OK button is clicked
   */
  fun showAndGet(): Boolean

  /** @return the selected signing config name */
  fun selectedConfig(): String
}

/** Dialog for selecting an existing signing config, useful for quick-fixing unsigned APK Run config error. */
class SigningConfigSelectorDialog(signingConfigs: Collection<String>) : DialogWrapper(false), SigningConfigSelector {
  private val rootPanel = JPanel(BorderLayout())

  @VisibleForTesting val signingConfigComboBox = ComboBox<String>()

  init {
    title = DIALOG_TITLE
    signingConfigs.forEach(Consumer { item: String -> signingConfigComboBox.addItem(item) })
    signingConfigComboBox.renderer = textListCellRenderer("<unnamed>") { it }
    init()
  }

  override fun selectedConfig(): String = signingConfigComboBox.item

  override fun createCenterPanel(): JComponent {
    return rootPanel.apply {
      add(
        JPanel(FlowLayout()).apply {
          add(JBLabel("Debug keys should be strictly used for development purposes only."))
          add(HyperlinkLabel("Learn more").apply { setHyperlinkTarget(DOC_URL) })
        },
        BorderLayout.NORTH,
      )
      add(signingConfigComboBox, BorderLayout.CENTER)
    }
  }

  companion object {
    const val DOC_URL = "https://developer.android.com/studio/publish/app-signing#debug-mode"
  }
}

private const val DIALOG_TITLE = "Select Signing Config"

private fun <T> computeInBackgroundReadAction(project: Project, title: String, action: () -> T): T {
  return if (ApplicationManager.getApplication().isDispatchThread) {
    runWithModalProgressBlocking(project, title) { readAction(action) }
  } else {
    ReadAction.compute<T, RuntimeException>(action)
  }
}

private fun GradleBuildModel.modificationStamps(): Map<VirtualFile, Triple<Long, Long?, Long?>> {
  val fileDocumentManager = FileDocumentManager.getInstance()
  return involvedFiles.associate { fileModel ->
    val file = fileModel.virtualFile
    file to
      Triple(
        file.modificationStamp,
        fileDocumentManager.getCachedDocument(file)?.modificationStamp,
        fileModel.psiFile?.viewProvider?.modificationStamp,
      )
  }
}
