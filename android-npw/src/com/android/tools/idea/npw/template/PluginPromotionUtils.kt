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
package com.android.tools.idea.npw.template

import androidx.compose.runtime.mutableStateOf
import com.android.tools.idea.npw.project.GridItemImage
import com.android.tools.idea.npw.startup.PromotionTemplateStateService
import com.android.tools.idea.npw.templateengine.api.PromotionCardSpec
import com.android.tools.idea.npw.toWizardFormFactor
import com.android.tools.idea.wizard.template.FormFactor
import com.intellij.ide.IdeBundle
import com.intellij.ide.plugins.InstalledPluginsState
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.util.ProgressWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.installAndEnable
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.util.concurrent.CopyOnWriteArrayList
import org.jetbrains.android.util.AndroidBundle

/** Tracks promoted plugins whose download has started, so the wizard can disable their promotion cards. */
internal object PromotedPluginInstallTracker {
  private val installingPluginIds = mutableStateOf<Set<String>>(emptySet())

  fun isInstalling(pluginId: String): Boolean = pluginId in installingPluginIds.value

  fun markInstalling(pluginId: String) {
    installingPluginIds.value += pluginId
  }
}

/**
 * Initiates the installation and enabling of a promoted plugin via the IDE plugin manager. If the plugin is successfully installed and
 * requires a restart, requests that the New Project Wizard be reopened with the promoted template pre-selected upon next startup, and
 * restarts the IDE.
 */
@RequiresEdt
internal fun installPromotedPlugin(
  pluginId: String,
  templateName: String,
  formFactor: FormFactor,
  project: Project? = null,
  onSuccess: Runnable? = null,
) {
  if (PromotedPluginInstallTracker.isInstalling(pluginId)) return

  val id = PluginId.getId(pluginId)
  val createdProgressWindows = CopyOnWriteArrayList<ProgressWindow>()
  val connection = ApplicationManager.getApplication().messageBus.connect()
  connection.subscribe(ProgressWindow.TOPIC, ProgressWindow.Listener { createdProgressWindows += it })
  try {
    installAndEnable(
      project = project,
      pluginIds = setOf(id),
      showDialog = true,
      selectAlInDialog = true,
      onSuccess =
        Runnable {
          if (InstalledPluginsState.getInstance().wasInstalled(id)) {
            PromotionTemplateStateService.getInstance().requestNpwReopenOnNextStartup(pluginId, templateName, formFactor)
            PluginManagerConfigurable.shutdownOrRestartApp()
          }
          onSuccess?.run()
        },
    )
  } finally {
    connection.disconnect()
  }
  val downloadTitle = IdeBundle.message("progress.download.plugins")
  if (createdProgressWindows.any { it.title == downloadTitle }) {
    PromotedPluginInstallTracker.markInstalling(pluginId)
  }
}

/** Converts a [PluginPromotionTemplate] into a [PromotionCardSpec] ready for rendering in the Compose Revamp New Project Wizard gallery. */
internal fun PluginPromotionTemplate.toPromotionCardSpec(): PromotionCardSpec =
  PromotionCardSpec(
    id = pluginId,
    title = AndroidBundle.message("android.wizard.project.plugin.promotion.template.name", name),
    description = "",
    formFactor = formFactor.toWizardFormFactor(),
    thumb = { GridItemImage(thumb()) },
    pluginId = pluginId,
    templateName = name,
    onClickAction = { params ->
      val project = params["project"] as? Project
      installPromotedPlugin(pluginId = pluginId, templateName = name, formFactor = formFactor, project = project)
    },
  )
