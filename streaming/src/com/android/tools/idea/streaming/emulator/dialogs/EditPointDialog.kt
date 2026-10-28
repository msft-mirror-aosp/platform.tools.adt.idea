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
package com.android.tools.idea.streaming.emulator.dialogs

import com.android.tools.idea.streaming.StreamingBundle.message
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.dialog
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import java.awt.Component
import java.util.Locale

/** Modal sub-dialog for adding or editing a saved point. */
internal class EditPointDialog(
  private val title: String,
  pointName: String,
  private val latitude: Double,
  private val longitude: Double,
  private val elevation: Double,
  description: String,
) {
  var pointName: String = pointName
    private set

  var description: String = description
    private set

  private fun createPanel(): DialogPanel {
    return panel {
      row {
          textField()
            .label(message("location.dialog.label.name"), LabelPosition.TOP)
            .bindText(::pointName)
            .align(AlignX.FILL)
            .focused()
            .validationOnInput { if (it.text.isBlank()) error(message("location.dialog.error.name.empty")) else null }
            .validationOnApply { if (it.text.isBlank()) error(message("location.dialog.error.name.empty")) else null }
            .applyToComponent {
              name = "pointNameField"
              columns = 24
            }
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          textField()
            .label(message("location.dialog.label.latitude"), LabelPosition.TOP)
            .align(AlignX.FILL)
            .resizableColumn()
            .applyToComponent {
              name = "pointLatitudeField"
              text = String.format(Locale.US, "%.6f", latitude)
              isEditable = false
            }

          textField()
            .label(message("location.dialog.label.longitude"), LabelPosition.TOP)
            .align(AlignX.FILL)
            .resizableColumn()
            .applyToComponent {
              name = "pointLongitudeField"
              text = String.format(Locale.US, "%.6f", longitude)
              isEditable = false
            }

          textField()
            .label(message("location.dialog.label.elevation"), LabelPosition.TOP)
            .align(AlignX.FILL)
            .resizableColumn()
            .applyToComponent {
              name = "pointElevationField"
              text = String.format(Locale.US, "%.1f", elevation)
              isEditable = false
            }
        }
        .layout(RowLayout.PARENT_GRID)

      row {
          textArea()
            .label(message("location.dialog.label.description"), LabelPosition.TOP)
            .bindText(::description)
            .rows(3)
            .align(Align.FILL)
            .applyToComponent {
              name = "pointDescriptionArea"
              lineWrap = true
              wrapStyleWord = true
            }
        }
        .layout(RowLayout.PARENT_GRID)
    }
  }

  fun createWrapper(project: Project? = null, parent: Component? = null): DialogWrapper {
    return dialog(
      title = title,
      resizable = false,
      panel = createPanel(),
      project = project,
      parent = parent,
    )
  }
}
