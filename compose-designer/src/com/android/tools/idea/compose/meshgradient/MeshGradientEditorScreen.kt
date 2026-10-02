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
package com.android.tools.idea.compose.meshgradient

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.android.tools.adtui.LightCalloutPopup
import com.android.tools.adtui.MaterialColorPaletteProvider
import com.android.tools.adtui.stdui.KeyStrokes
import com.android.tools.idea.compose.meshgradient.components.ColorSwatch
import com.android.tools.idea.compose.meshgradient.components.DimensionInputField
import com.android.tools.idea.compose.preview.message
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.colorpicker.ColorPickerBuilder
import com.intellij.ui.colorpicker.MaterialGraphicalColorPipetteProvider
import java.awt.Color as AwtColor
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.ScrollPaneConstants
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import org.jetbrains.kotlin.idea.KotlinLanguage

/** Delay between the last change to the generated code and the code viewer update, so drags don't re-highlight every frame. */
internal val CODE_UPDATE_DEBOUNCE = 100.milliseconds

@Composable
internal fun DynamicValuesWarningBanner(message: String) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(6.dp))
        .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp))
        .padding(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      key = AllIconsKeys.General.Warning,
      iconClass = AllIconsKeys::class.java,
      contentDescription = "Warning",
      modifier = Modifier.size(16.dp),
    )
    Text(message)
  }
}

/**
 * Shows the code returned by [generatedCode] in a read-only Kotlin editor with a button to copy it. [generatedCode] is only called outside
 * of composition, so changes to the code don't recompose the caller.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun ColumnScope.GeneratedCodeSection(
  project: Project,
  onViewerCreated: ((GeneratedCodeViewer) -> Unit)? = null,
  renderEditor: Boolean = true,
  generatedCode: () -> String,
) {
  val currentGeneratedCode by rememberUpdatedState(generatedCode)
  val codeViewer =
    remember(project) {
      GeneratedCodeViewer(project, Snapshot.withoutReadObservation { currentGeneratedCode() }).also {
        onViewerCreated?.invoke(it)
      }
    }

  LaunchedEffect(codeViewer) {
    snapshotFlow { currentGeneratedCode() }.debounce(CODE_UPDATE_DEBOUNCE).collect { codeViewer.setText(it) }
  }

  Divider(orientation = Orientation.Horizontal)

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(message("gradient.editor.code.title"), style = JewelTheme.typography.h4TextStyle, fontWeight = FontWeight.SemiBold)
    DefaultButton(onClick = { CopyPasteManager.getInstance().setContents(StringSelection(currentGeneratedCode())) }) {
      Text(message("gradient.editor.code.copy"))
    }
  }

  if (renderEditor) {
    key(codeViewer) { SwingPanel(factory = { codeViewer.editor.component }, modifier = Modifier.fillMaxWidth().weight(1f)) }
  }
}

/**
 * Read-only Kotlin editor used to display generated code. The editor is released when this object leaves the composition, when the
 * composition that created it is abandoned, or when [project] is disposed, whichever happens first.
 */
@VisibleForTesting
internal class GeneratedCodeViewer(private val project: Project, initialText: String) : RememberObserver {
  private val disposable = Disposer.newDisposable(project, "Gradient editor generated code viewer")
  private val document = EditorFactory.getInstance().createDocument(initialText)

  val editor: EditorEx

  init {
    try {
      val viewer = EditorFactory.getInstance().createViewer(document, project)
      // Releasing the editor and applying a color scheme require the write-intent lock, which Compose callbacks such as onForgotten and
      // message bus listeners don't necessarily hold. Document changes in setText take the write lock, which also acquires it.
      Disposer.register(disposable) { WriteIntentReadAction.run { EditorFactory.getInstance().releaseEditor(viewer) } }
      editor =
        (viewer as EditorEx).apply {
          val fileType = KotlinLanguage.INSTANCE.associatedFileType ?: FileTypes.PLAIN_TEXT
          highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType)
          scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
          scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
          setBorder(null)
          settings.isCaretRowShown = false
          applyColorsScheme(EditorColorsManager.getInstance().schemeForCurrentUITheme)
        }
      ApplicationManager.getApplication()
        .messageBus
        .connect(disposable)
        .subscribe(
          EditorColorsManager.TOPIC,
          EditorColorsListener { newScheme ->
            if (project.isDisposed || editor.isDisposed) return@EditorColorsListener
            val scheme = newScheme ?: EditorColorsManager.getInstance().schemeForCurrentUITheme
            WriteIntentReadAction.run { editor.applyColorsScheme(scheme) }
          },
        )
    } catch (t: Throwable) {
      Disposer.dispose(disposable)
      throw t
    }
  }

  fun setText(text: String) {
    if (project.isDisposed || editor.isDisposed) return
    if (document.text == text) return
    val app = ApplicationManager.getApplication()
    val runnable = Runnable {
      if (project.isDisposed || editor.isDisposed) return@Runnable
      if (document.text == text) return@Runnable
      WriteAction.run<Throwable> { document.setText(text) }
    }
    if (app.isDispatchThread) {
      runnable.run()
    } else {
      app.invokeLater(runnable, project.disposed)
    }
  }

  override fun onRemembered() {}

  override fun onForgotten() {
    Disposer.dispose(disposable)
  }

  override fun onAbandoned() {
    Disposer.dispose(disposable)
  }
}

private fun EditorEx.applyColorsScheme(scheme: EditorColorsScheme) {
  colorsScheme = scheme
  setBackgroundColor(scheme.defaultBackground)
  scrollPane.background = scheme.defaultBackground
  scrollPane.viewport.background = scheme.defaultBackground
  component.background = scheme.defaultBackground
  contentComponent.background = scheme.defaultBackground
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("UseJBColor")
@Composable
fun MeshGradientEditorScreen(project: Project, state: GradientEditorState, isEditingExisting: Boolean = false) {
  val scrollState = rememberScrollState()
  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  var showColorPickerForVertex by remember { mutableStateOf<Pair<Int, Int>?>(null) }

  Column(
    modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(
      modifier =
        if (isEditingExisting) {
          Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState)
        } else {
          Modifier.fillMaxWidth().verticalScroll(scrollState)
        },
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (isEditingExisting && state.hasDynamicOrUnresolvedValues) {
        DynamicValuesWarningBanner(
          "Some vertices use dynamic or unresolved expressions. Initial or default values are shown; editing a vertex will replace its expression."
        )
      }

      // 1. Canvas Preview
      Box(modifier = Modifier.fillMaxWidth().height(220.dp).onGloballyPositioned { canvasSize = it.size }) {
        GradientCanvas(
          meshPoints = state.meshPoints,
          hasBicubicColor = state.hasBicubicColor,
          showPoints = state.showPoints,
          constrainEdgePoints = state.constrainEdgePoints,
          onPointDrag = { row, col, offset -> state.updateMeshPoint(row, col, offset) },
          onPointClick = { row, col -> showColorPickerForVertex = Pair(row, col) },
        )

        val activeVertex = showColorPickerForVertex
        val selectedPoint = activeVertex?.let { (row, col) -> state.meshPoints.getOrNull(row)?.getOrNull(col) }
        if (activeVertex != null && selectedPoint != null) {
          val (row, col) = activeVertex
          val relativeOffset = selectedPoint.position
          val paddingPx = with(LocalDensity.current) { 16.dp.toPx() }
          val innerWidth = (canvasSize.width - 2 * paddingPx).coerceAtLeast(0f)
          val innerHeight = (canvasSize.height - 2 * paddingPx).coerceAtLeast(0f)

          // Calculate pixel coordinates inside the Box container accounting for 16.dp canvas padding
          val xOffset = (paddingPx + relativeOffset.x * innerWidth).toInt()
          val yOffset = (paddingPx + relativeOffset.y * innerHeight).toInt()

          Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(xOffset, yOffset),
            onDismissRequest = { showColorPickerForVertex = null },
          ) {
            Row(
              modifier =
                Modifier.clip(RoundedCornerShape(6.dp))
                  .background(JewelTheme.globalColors.panelBackground)
                  .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp))
                  .padding(6.dp),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              state.availableColors.forEach { color ->
                ColorSwatch(
                  color = color,
                  modifier =
                    Modifier.clickable {
                      state.updateVertexColor(row, col, color)
                      showColorPickerForVertex = null
                    },
                )
              }
            }
          }
        }
      }

      Divider(orientation = Orientation.Horizontal)

      // 2. Grid Config
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        DimensionInputField(
          value = state.rows,
          min = 2,
          max = 10,
          paramName = "Rows",
          onUpdate = {
            showColorPickerForVertex = null
            state.updateRows(it)
          },
          modifier = Modifier.width(100.dp),
        )
        DimensionInputField(
          value = state.cols,
          min = 2,
          max = 10,
          paramName = "Cols",
          onUpdate = {
            showColorPickerForVertex = null
            state.updateCols(it)
          },
          modifier = Modifier.width(100.dp),
        )
        Spacer(Modifier.width(8.dp))
        DefaultButton(onClick = { state.distributeMeshPointsEvenly() }) { Text("Reset Points") }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        CheckboxRow(text = "Show points", checked = state.showPoints, onCheckedChange = { state.showPoints = it })
        CheckboxRow(text = "Constrain edge", checked = state.constrainEdgePoints, onCheckedChange = { state.constrainEdgePoints = it })
      }

      Divider(orientation = Orientation.Horizontal)

      // 4. Color Palette
      Text("Color Palette", style = JewelTheme.typography.h4TextStyle, fontWeight = FontWeight.SemiBold)
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        state.availableColors.forEachIndexed { index, color ->
          ContextMenuArea(items = { listOf(ContextMenuItem("Delete") { state.removePaletteColor(index) }) }) {
            ColorSwatch(
              color = color,
              Modifier.clickable { state.beginPaletteColorEdit(index)?.let { edit -> showColorPicker(color, edit::update, edit::finish) } },
            )
          }
        }
        Box(
          contentAlignment = Alignment.Center,
          modifier =
            Modifier.clip(RoundedCornerShape(4.dp))
              .size(16.dp)
              .background(JewelTheme.globalColors.panelBackground)
              .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(4.dp))
              .clickable {
                val edit = state.beginNewPaletteColor()
                showColorPicker(Color.White, edit::update, edit::finish)
              },
        ) {
          Icon(
            key = AllIconsKeys.General.InlineAdd,
            iconClass = AllIconsKeys::class.java,
            contentDescription = "Add Color",
            modifier = Modifier.size(10.dp),
          )
        }
      }
    }

    if (!isEditingExisting) {
      GeneratedCodeSection(project) { state.generatedCode }
    }
  }
}

/**
 * Shows a color picker popup at the mouse location, starting at [initialColor]. [onColorPicked] is called for every color picked while the
 * popup is open, and [onClosed] once the popup closes, however it is closed. In a headless environment, or if the mouse location is
 * unknown, e.g. in some remote sessions, no popup is shown and [onClosed] is called right away.
 */
private fun showColorPicker(initialColor: Color, onColorPicked: (Color) -> Unit, onClosed: () -> Unit) {
  // MouseInfo.getPointerInfo throws a HeadlessException in a headless environment.
  if (GraphicsEnvironment.isHeadless()) return onClosed()
  val location = MouseInfo.getPointerInfo()?.location ?: return onClosed()
  val popup = LightCalloutPopup(closedCallback = onClosed, cancelCallBack = onClosed)
  val picker =
    ColorPickerBuilder(showAlpha = true, showAlphaAsPercent = false)
      .setOriginalColor(initialColor.toAwtColor())
      .addSaturationBrightnessComponent()
      .addColorAdjustPanel(MaterialGraphicalColorPipetteProvider())
      .addColorValuePanel()
      .withFocus()
      .addSeparator()
      .addCustomComponent(MaterialColorPaletteProvider)
      .addColorListener { color, _ -> onColorPicked(Color(color.rgb)) }
      .focusWhenDisplay(true)
      .setFocusCycleRoot(true)
      .addKeyAction(
        KeyStrokes.ESCAPE,
        object : AbstractAction() {
          override fun actionPerformed(event: ActionEvent) {
            popup.close()
          }
        },
      )
      .build()
  popup.show(picker.content, null, location)
}

@Suppress("UseJBColor") // The color picker API takes plain AWT colors.
private fun Color.toAwtColor(): AwtColor = AwtColor(toArgb(), true)
