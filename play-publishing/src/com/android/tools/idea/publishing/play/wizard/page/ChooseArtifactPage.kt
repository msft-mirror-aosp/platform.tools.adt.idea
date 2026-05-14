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
package com.android.tools.idea.publishing.play.wizard.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.adtui.compose.LocalProject
import com.android.tools.adtui.compose.WizardAction
import com.android.tools.adtui.compose.WizardPageScope
import com.android.tools.idea.publishing.play.AppMetadata
import com.android.tools.idea.publishing.play.client.type.App
import com.android.tools.idea.publishing.play.client.type.parseGoogleApiError
import com.android.tools.idea.publishing.play.extractAppMetadata
import com.android.tools.idea.publishing.play.wizard.PlayPublishingWizardState
import com.google.api.client.http.HttpResponseException
import com.google.gct.login2.GoogleLoginService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.ImageUtil
import icons.GoogleLoginIcons
import icons.StudioIllustrationsCompose
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.io.path.Path
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalComponent
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.ExternalLink
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.InlineErrorBanner
import org.jetbrains.jewel.ui.component.InlineSuccessBanner
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private const val UPLOAD_BUNDLE_DAC_URL = "https://developer.android.com/r/studio-ui/publish/upload-app-bundle"

@Suppress("UnstableApiUsage")
@OptIn(ExperimentalFoundationApi::class, ExperimentalJewelApi::class)
@Composable
fun WizardPageScope.ChooseArtifactPage(extractMetadata: suspend (Path) -> AppMetadata = ::extractAppMetadata) {
  val user by GoogleLoginService.instance.activeUserFlow.collectAsState()
  val project = LocalProject.current
  val component = LocalComponent.current
  val state = getOrCreateState<PlayPublishingWizardState> { error("State not initialized") }
  val isPathLocked = !state.artifactPath.isNullOrEmpty()
  val initialArtifactPath = remember { state.artifactPath ?: project?.guessProjectDir()?.path ?: "" }
  val artifactPathState = rememberTextFieldState(initialArtifactPath)
  var errorMessage: String? by remember { mutableStateOf(null) }
  var isAppsLoading by remember { mutableStateOf(true) }
  val apps: List<App>? by
    produceState(initialValue = null) {
      isAppsLoading = true
      value =
        try {
          state.client.listApps()
        } catch (e: HttpResponseException) {
          val error = e.parseGoogleApiError()
          errorMessage = "Failed to check package availability: ${error?.message ?: e.message ?: "Unknown error"}"
          null
        } catch (e: Exception) {
          errorMessage = "Failed to check package availability: ${e.message}"
          null
        } finally {
          isAppsLoading = false
        }
    }
  val isAppInConsole = remember(state.packageName, apps) { apps?.any { it.packageName == state.packageName } ?: false }
  var versionCode: String? by remember { mutableStateOf(null) }
  var versionName: String? by remember { mutableStateOf(null) }
  var bannerData: BannerData? by remember { mutableStateOf(null) }
  var packageNameCheck: PackageNameCheck? by remember { mutableStateOf(null) }

  LaunchedEffect(apps, isAppInConsole, isAppsLoading, state.packageName, state.isRegistered) {
    bannerData =
      when {
        // Don't show any banner when we are still determining if the user has access to the app.
        isAppsLoading || state.packageName.isNullOrEmpty() -> null
        apps != null && state.isRegistered == true && !isAppInConsole -> {
          BannerData(
            ElementType.ERROR,
            """
      The package name (${state.packageName}) is not available. You can change your package name to another available name from Project Settings and rebuild the distributable.
    """
              .trimIndent(),
          )
        }
        isPathLocked -> {
          BannerData(ElementType.SUCCESS, "Field pre-filled from the 'Generate Signed App Bundle or APK' wizard.")
        }
        else -> null
      }

    packageNameCheck =
      when {
        isAppsLoading || apps == null -> null
        state.isRegistered == false && !state.packageName.isNullOrEmpty() -> {
          PackageNameCheck(ElementType.SUCCESS, "Package name available")
        }
        state.isRegistered == true && isAppInConsole -> {
          PackageNameCheck(ElementType.SUCCESS, "Matches existing app")
        }
        else -> null
      }
  }

  LaunchedEffect(artifactPathState.text) {
    val path = artifactPathState.text.toString()
    state.artifactPath = path
    val metadata =
      try {
        extractMetadata(Path(path))
      } catch (e: Exception) {
        Logger.getInstance("ChooseArtifactPage").warn("Failed to read metadata from artifact", e)
        null
      }
    state.appName = metadata?.appName
    state.packageName = metadata?.packageName
    versionName = metadata?.versionName
    versionCode = metadata?.versionCode
    state.isBundle = metadata?.isBundle ?: false
  }

  val fileChooserDescriptor = remember {
    FileChooserDescriptor(true, false, false, false, false, false).withFileFilter { it.extension?.lowercase() in listOf("aab", "apk") }
  }

  val avatarPainter =
    remember(user) {
      val icon =
        user?.picture?.let { src ->
          val width = 64
          val height = 64
          val dest = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
          val g2d = dest.createGraphics()
          g2d.clip(Ellipse2D.Float(0f, 0f, width.toFloat(), height.toFloat()))
          g2d.drawImage(src, 0, 0, width, height, null)
          g2d.dispose()
          dest
        }
          ?: run {
            val fallbackIcon = GoogleLoginIcons.LOGGED_IN_FALLBACK_USER_AVATAR
            val width = fallbackIcon.iconWidth
            val height = fallbackIcon.iconHeight
            val image = ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            fallbackIcon.paintIcon(null, g, 0, 0)
            g.dispose()
            image
          }
      icon.toPainter()
    }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp).focusTarget()) {
      // Header
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(key = StudioIllustrationsCompose.Common.PlayConsoleIcon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = "Upload to Play", style = JewelTheme.defaultTextStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "Choose App Bundle or APK", style = JewelTheme.defaultTextStyle.copy(fontSize = 18.sp, color = Color.Gray))
      }

      Spacer(modifier = Modifier.height(24.dp))

      // User Info
      Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painter = avatarPainter, contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Signed in as: ", style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp))
        Text(
          text = user?.email ?: throw IllegalStateException("Logged in user not found"),
          style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
      }

      Spacer(modifier = Modifier.height(24.dp))

      Text(
        text = "Select the App Bundle (.aab) or APK you want to upload to Google Play.",
        style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp),
      )

      Spacer(modifier = Modifier.height(16.dp))

      // Artifact path field
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "App bundle or APK:", modifier = Modifier.width(150.dp), style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp))
        TextField(
          state = artifactPathState,
          modifier = Modifier.weight(1f),
          enabled = !isPathLocked,
          trailingIcon =
            if (isPathLocked) null
            else {
              {
                Icon(
                  key = AllIconsKeys.General.OpenDisk,
                  contentDescription = "Browse",
                  modifier =
                    Modifier.padding(end = 4.dp).pointerHoverIcon(PointerIcon.Hand).clickable {
                      val currentPath = artifactPathState.text.toString()
                      val toSelect =
                        (if (currentPath.isNotBlank()) LocalFileSystem.getInstance().findFileByPath(currentPath) else null)
                          ?: project?.guessProjectDir()
                      val virtualFile = FileChooser.chooseFile(fileChooserDescriptor, component, project, toSelect)
                      if (virtualFile != null) {
                        artifactPathState.setTextAndPlaceCursorAtEnd(virtualFile.toNioPath().toString())
                      }
                    },
                )
              }
            },
        )
      }

      bannerData?.let {
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          // Match the width of the spacer with the label above to align the banner with the path field
          Spacer(Modifier.width(150.dp))
          when (it.type) {
            ElementType.SUCCESS -> InlineSuccessBanner(it.message)
            ElementType.ERROR -> InlineErrorBanner(it.message)
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Artifact Details
      Column(modifier = Modifier.padding(start = 150.dp)) {
        // Package Name
        Row(modifier = Modifier.testTag("PackageNameRow"), verticalAlignment = Alignment.CenterVertically) {
          Text(text = "Package name", modifier = Modifier.width(100.dp), style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp))
          Text(text = state.packageName.takeIf { !it.isNullOrEmpty() } ?: "—", style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp))
          packageNameCheck?.let {
            Spacer(modifier = Modifier.width(8.dp))
            when (it.type) {
              ElementType.SUCCESS -> Icon(key = AllIconsKeys.Status.Success, contentDescription = null, modifier = Modifier.size(14.dp))
              ElementType.ERROR -> Icon(key = AllIconsKeys.General.Error, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = it.message, style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
          }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Column(modifier = Modifier.padding(start = 100.dp)) {
          Text(
            text = "This wizard will guide you through uploading a new release for this application.",
            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, color = Color.Gray),
          )
          Row(verticalAlignment = Alignment.CenterVertically) {
            ExternalLink("Learn more", UPLOAD_BUNDLE_DAC_URL, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand))
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Version Name
        Row(Modifier.testTag("VersionNameRow")) {
          Text(text = "Version name", modifier = Modifier.width(100.dp), style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp))
          Text(text = versionName.takeIf { !it.isNullOrEmpty() } ?: "—", style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Version Code
        Row(Modifier.testTag("VersionCodeRow")) {
          Text(text = "Version code", modifier = Modifier.width(100.dp), style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp))
          Text(text = versionCode.takeIf { !it.isNullOrEmpty() } ?: "—", style = JewelTheme.defaultTextStyle.copy(fontSize = 13.sp))
        }
      }
    }

    errorMessage?.let { InlineErrorBanner(it, Modifier.align(Alignment.BottomEnd).padding(24.dp)) }
  }

  prevButtonEnabled = false
  nextActionName = "Next"
  nextAction =
    if (state.packageName.isNullOrEmpty() || state.appName.isNullOrEmpty() || (state.isRegistered == true && !isAppInConsole))
      WizardAction.Disabled
    else
      WizardAction {
        if (isAppInConsole) {
          pushPage { CreateReleasePage() }
        } else {
          pushPage { CreateAppRecordPage() }
        }
      }
}

private enum class ElementType {
  SUCCESS,
  ERROR,
}

private data class BannerData(val type: ElementType, val message: String)

private data class PackageNameCheck(val type: ElementType, val message: String)
