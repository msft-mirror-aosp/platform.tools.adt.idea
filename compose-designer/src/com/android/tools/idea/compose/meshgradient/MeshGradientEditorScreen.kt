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
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.tools.idea.compose.meshgradient.components.AddColorButton
import com.android.tools.idea.compose.meshgradient.components.ColorSwatch
import com.android.tools.idea.compose.meshgradient.components.DimensionInputField
import com.android.tools.idea.compose.meshgradient.components.PaletteSwatchGrid
import com.android.tools.idea.compose.meshgradient.components.colorSwatchDescription
import com.android.tools.idea.compose.meshgradient.components.showColorPicker
import com.android.tools.idea.compose.meshgradient.components.toHexString
import com.android.tools.idea.compose.meshgradient.components.tryRequestFocus
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
import java.awt.datatransfer.StringSelection
import javax.swing.ScrollPaneConstants
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.typography
import org.jetbrains.kotlin.idea.KotlinLanguage

/** Delay between the last change to the generated code and the code viewer update, so drags don't re-highlight every frame. */
internal val CODE_UPDATE_DEBOUNCE = 100.milliseconds

/** Maximum width of the popup listing the palette colors that can be applied to a vertex. */
private val VERTEX_COLOR_POPUP_MAX_WIDTH = 200.dp

/** Maximum height of the popup listing the palette colors that can be applied to a vertex. Larger palettes scroll. */
private val VERTEX_COLOR_POPUP_MAX_HEIGHT = 160.dp

@Composable
internal fun DynamicValuesWarningBanner(text: String) {
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
      contentDescription = message("gradient.editor.warning"),
      modifier = Modifier.size(16.dp),
    )
    Text(text)
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
    OutlinedButton(onClick = { CopyPasteManager.getInstance().setContents(StringSelection(currentGeneratedCode())) }) {
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

@Composable
internal fun MeshGradientEditorScreen(project: Project, state: GradientEditorState, isEditingExisting: Boolean = false) {
  val scrollState = rememberScrollState()
  var canvasSize by remember { mutableStateOf(IntSize.Zero) }
  var selectedVertex by remember { mutableStateOf<VertexIndex?>(null) }

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
        DynamicValuesWarningBanner(message("gradient.editor.mesh.dynamic.values.warning"))
      }

      // 1. Canvas Preview
      Box(modifier = Modifier.fillMaxWidth().height(220.dp).onGloballyPositioned { canvasSize = it.size }) {
        GradientCanvas(
          meshPoints = state.meshPoints,
          hasBicubicColor = state.hasBicubicColor,
          showPoints = state.showPoints,
          constrainEdgePoints = state.constrainEdgePoints,
          onPointDrag = { row, col, offset -> state.updateMeshPoint(row, col, offset) },
          onPointClick = { row, col -> selectedVertex = VertexIndex(row, col) },
        )

        selectedVertex?.let { vertex ->
          VertexColorPopup(state = state, vertex = vertex, canvasSize = canvasSize, onDismiss = { selectedVertex = null })
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
          min = MIN_MESH_DIMENSION,
          max = MAX_MESH_DIMENSION,
          paramName = message("gradient.editor.mesh.rows"),
          onUpdate = {
            selectedVertex = null
            state.updateRows(it)
          },
          modifier = Modifier.width(100.dp),
        )
        DimensionInputField(
          value = state.cols,
          min = MIN_MESH_DIMENSION,
          max = MAX_MESH_DIMENSION,
          paramName = message("gradient.editor.mesh.cols"),
          onUpdate = {
            selectedVertex = null
            state.updateCols(it)
          },
          modifier = Modifier.width(100.dp),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { state.distributeMeshPointsEvenly() }) { Text(message("gradient.editor.mesh.reset.points")) }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        CheckboxRow(
          text = message("gradient.editor.mesh.show.points"),
          checked = state.showPoints,
          onCheckedChange = { state.showPoints = it },
        )
        CheckboxRow(
          text = message("gradient.editor.mesh.constrain.edge"),
          checked = state.constrainEdgePoints,
          onCheckedChange = { state.constrainEdgePoints = it },
        )
      }

      Divider(orientation = Orientation.Horizontal)

      // 3. Color Palette
      Text(message("gradient.editor.palette.title"), style = JewelTheme.typography.h4TextStyle, fontWeight = FontWeight.SemiBold)
      ColorPalette(state)
    }

    if (!isEditingExisting) {
      GeneratedCodeSection(project) { state.generatedCode }
    }
  }
}

/**
 * Popup anchored at [vertex] on the canvas that applies one of the palette colors to it. The popup takes the focus, starting on the
 * vertex's current color (or the first color), and is dismissed with Escape.
 */
@Composable
private fun VertexColorPopup(state: GradientEditorState, vertex: VertexIndex, canvasSize: IntSize, onDismiss: () -> Unit) {
  val (row, col) = vertex
  val point = state.meshPoints.getOrNull(row)?.getOrNull(col) ?: return
  val paddingPx = with(LocalDensity.current) { CANVAS_PADDING.roundToPx() }
  val innerWidth = (canvasSize.width - 2 * paddingPx).coerceAtLeast(0)
  val innerHeight = (canvasSize.height - 2 * paddingPx).coerceAtLeast(0)
  val xOffset = (paddingPx + point.position.x * innerWidth).toInt()
  val yOffset = (paddingPx + point.position.y * innerHeight).toInt()
  val applyLabel = message("gradient.editor.mesh.vertex.apply.color")

  Popup(
    alignment = Alignment.TopStart,
    offset = IntOffset(xOffset, yOffset),
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = true),
  ) {
    PaletteSwatchGrid(
      palette = state.availableColors,
      selectedColor = point.color,
      onColorSelected = { color ->
        state.updateVertexColor(row, col, color)
        onDismiss()
      },
      onClickLabel = applyLabel,
      modifier =
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
              onDismiss()
              true
            } else {
              false
            }
          }
          .widthIn(max = VERTEX_COLOR_POPUP_MAX_WIDTH)
          .heightIn(max = VERTEX_COLOR_POPUP_MAX_HEIGHT)
          .clip(RoundedCornerShape(6.dp))
          .background(JewelTheme.globalColors.panelBackground)
          .border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp))
          .verticalScroll(rememberScrollState())
          .padding(6.dp),
    )
  }
}

/** The editable color palette, followed by a button to add a color. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorPalette(state: GradientEditorState) {
  val addColorFocusRequester = remember { FocusRequester() }
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    val canDelete = state.availableColors.size > 1
    state.availableColors.forEachIndexed { index, color ->
      val swatchFocusRequester = remember { FocusRequester() }
      PaletteSwatch(
        color = color,
        onEdit = {
          state.beginPaletteColorEdit(index)?.let { edit ->
            showColorPicker(color, edit::update) {
              edit.finish()
              swatchFocusRequester.tryRequestFocus()
            }
          }
        },
        onDelete = if (canDelete) ({ state.removePaletteColor(index) }) else null,
        modifier = Modifier.focusRequester(swatchFocusRequester),
      )
    }
    AddColorButton(
      contentDescription = message("gradient.editor.palette.add"),
      onClick = {
        val edit = state.beginNewPaletteColor()
        showColorPicker(Color.White, edit::update) {
          edit.finish()
          addColorFocusRequester.tryRequestFocus()
        }
      },
      modifier = Modifier.focusRequester(addColorFocusRequester),
    )
  }
}

/**
 * A palette entry. Clicking it calls [onEdit]. Unless [onDelete] is null, it can be deleted through its context menu, the Delete or
 * Backspace key while focused, or the accessibility "Delete" action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteSwatch(
  color: Color,
  onEdit: () -> Unit,
  onDelete: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val deleteLabel = message("gradient.editor.palette.delete")
  val editLabel = message("gradient.editor.palette.edit")
  val description = colorSwatchDescription(color)
  val tooltip =
    if (onDelete != null) {
      message("gradient.editor.palette.swatch.tooltip", color.toHexString())
    } else {
      message("gradient.editor.palette.swatch.tooltip.edit.only", color.toHexString())
    }

  val swatch: @Composable () -> Unit = {
    Tooltip(tooltip = { Text(tooltip) }) {
      ColorSwatch(
        color = color,
        modifier =
          modifier
            .onKeyEvent { event ->
              val isDeleteKey = event.key == Key.Delete || event.key == Key.Backspace
              if (onDelete != null && event.type == KeyEventType.KeyDown && isDeleteKey) {
                onDelete()
                true
              } else {
                false
              }
            }
            .semantics {
              contentDescription = description
              if (onDelete != null) {
                customActions =
                  listOf(
                    CustomAccessibilityAction(deleteLabel) {
                      onDelete()
                      true
                    }
                  )
              }
            }
            .clickable(onClickLabel = editLabel, role = Role.Button, onClick = onEdit),
      )
    }
  }

  if (onDelete != null) {
    ContextMenuArea(items = { listOf(ContextMenuItem(deleteLabel, onDelete)) }, content = swatch)
  } else {
    swatch()
  }
}
