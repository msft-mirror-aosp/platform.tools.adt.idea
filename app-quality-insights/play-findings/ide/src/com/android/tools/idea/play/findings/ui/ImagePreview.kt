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
package com.android.tools.idea.play.findings.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.testFramework.BinaryLightVirtualFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

private val LOG = Logger.getInstance(PreviewState::class.java)

private sealed interface PreviewState {
  data object Loading : PreviewState

  /**
   * Holds both representations of a fetched image:
   * - [bitmap]: Decoded [ImageBitmap] required by Compose's [Image] to render the inline preview.
   * - [bytes]: Raw encoded image bytes reused by [BinaryLightVirtualFile] when opening the image in the IDE editor.
   */
  class Loaded(val bytes: ByteArray, val bitmap: ImageBitmap) : PreviewState

  data object Error : PreviewState
}

/** Loads and renders an image preview, opening the image in the IDE editor when clicked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ImagePreview(
  imageUrl: String,
  fileName: String,
  project: Project,
  modifier: Modifier = Modifier,
) {
  var state by remember(imageUrl) { mutableStateOf<PreviewState>(PreviewState.Loading) }

  LaunchedEffect(imageUrl) {
    state =
      try {
        val bytes = FindingsAssetLoader.getInstance(project).load(imageUrl)
        val bitmap = withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
        PreviewState.Loaded(bytes = bytes, bitmap = bitmap)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        LOG.warn("Failed to load image preview from $imageUrl", e)
        PreviewState.Error
      }
  }

  Box(modifier = modifier.testTag("image_panel")) {
    when (val currentState = state) {
      PreviewState.Loading ->
        Text(
          text = "Loading preview...",
          color = JewelTheme.globalColors.text.info,
          modifier = Modifier.testTag("image_status"),
        )
      is PreviewState.Loaded ->
        Tooltip(tooltip = { Text("Click to open in editor") }) {
          Image(
            bitmap = currentState.bitmap,
            contentDescription = "Image preview",
            contentScale = ContentScale.Fit,
            modifier =
              Modifier.sizeIn(maxWidth = 300.dp, maxHeight = 500.dp)
                .border(1.dp, JewelTheme.globalColors.borders.normal)
                .pointerHoverIcon(PointerIcon.Hand)
                .focusProperties { canFocus = false }
                .clickable { openImageInEditor(project, imageUrl, fileName, currentState.bytes) }
                .testTag("image_preview"),
          )
        }
      PreviewState.Error ->
        Text(
          text = "Unable to load preview.",
          color = JewelTheme.globalColors.text.info,
          modifier = Modifier.testTag("image_status"),
        )
    }
  }
}

private val IMAGE_URL_KEY = Key.create<String>("play.findings.image.url")

internal fun openImageInEditor(project: Project, imageUrl: String, fileName: String, imageBytes: ByteArray) {
  val editorManager = FileEditorManager.getInstance(project)
  val virtualFile =
    editorManager.openFiles.firstOrNull { it.getUserData(IMAGE_URL_KEY) == imageUrl }
      ?: BinaryLightVirtualFile(fileName, imageBytes).apply {
        isWritable = false
        putUserData(IMAGE_URL_KEY, imageUrl)
      }
  editorManager.openFile(virtualFile, true)
}
