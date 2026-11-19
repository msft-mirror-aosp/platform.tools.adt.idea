/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.streaming.emulator

import com.android.SdkConstants.PRIMARY_DISPLAY_ID
import com.android.annotations.concurrency.AnyThread
import com.android.emulator.control.DisplayPowerModeNotification
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.streaming.core.AbstractDisplayPanel
import com.android.tools.idea.streaming.core.DisplayIndicatorPopup
import com.android.tools.idea.streaming.emulator.EmulatorController.ConnectionState
import com.android.tools.idea.streaming.emulator.EmulatorController.ConnectionStateListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.JComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Represents a single Emulator display. */
internal class EmulatorDisplayPanel(
  disposableParent: Disposable,
  emulator: EmulatorController,
  project: Project,
  displayId: Int,
  displaySize: Dimension?,
  zoomToolbarVisible: Boolean,
  deviceFrameVisible: Boolean = false,
) :
  AbstractDisplayPanel<EmulatorView>(disposableParent, zoomToolbarVisible),
  DisplayViewContainer<EmulatorDisplayView>,
  ConnectionStateListener {

  override val component: JComponent
    get() = this

  override val deviceType: DeviceType
    get() = displayView.emulator.emulatorConfig.deviceType

  /** The "Display off" or "No display" indicator, if any. */
  private var displayIndicator: DisplayIndicatorPopup? = null
  /** True if the state of the display calls for showing [displayIndicator]. */
  private var displayIndicatorNeeded = false
  private var videoPlaybackOverlay: VideoPlaybackOverlay? = null
  private var videoPlaybackPanelVisible = false

  init {
    displayView = EmulatorView(this, emulator, project, displayId, displaySize, deviceFrameVisible)

    if (displayId == PRIMARY_DISPLAY_ID) {
      loadingPanel.setLoadingText("Connecting to the Emulator")
      loadingPanel.startLoading() // The stopLoading method is called by EmulatorView after the gRPC connection is established.
    }

    if (deviceType == DeviceType.AI_GLASSES && StudioFlags.EMBEDDED_EMULATOR_DISPLAY_OFF_INDICATOR.get()) {
      if (emulator.emulatorConfig.displayWidth > 0 && emulator.emulatorConfig.displayHeight > 0) {
        val popup = DisplayIndicatorPopup("Display off").apply { isVisible = false }
        displayIndicator = popup
        notificationLayerPane.add(popup)
        val coroutineScope = createCoroutineScope()
        val notificationTracker = NotificationTracker.forEmulator(emulator)
        coroutineScope.launch(Dispatchers.EDT) {
          notificationTracker.displayPowerModes.collect { modes ->
            displayIndicatorNeeded = modes[displayId] == DisplayPowerModeNotification.PowerMode.OFF
            updateDisplayIndicatorVisibility()
          }
        }
      } else {
        val popup = DisplayIndicatorPopup("No display")
        displayIndicator = popup
        displayIndicatorNeeded = true
        notificationLayerPane.add(popup)
      }
    }

    if (deviceType == DeviceType.AI_GLASSES && displayId == PRIMARY_DISPLAY_ID && StudioFlags.EMBEDDED_EMULATOR_VIDEO_ENVIRONMENT.get()) {
      val overlay = VideoPlaybackOverlay(this, emulator)
      overlay.onPlaybackPanelVisibilityChanged = { visible ->
        videoPlaybackPanelVisible = visible
        zoomToolbarCollapsed = visible
        updateDisplayIndicatorVisibility()
      }
      videoPlaybackOverlay = overlay
      addOverlayPane(overlay)
    }

    emulator.addConnectionStateListener(this)
  }

  override fun onZoomToolbarActivated() {
    videoPlaybackOverlay?.hidePlaybackPanel()
  }

  /** Shows the display indicator when the state of the display calls for it, unless the video playback panel is shown. */
  private fun updateDisplayIndicatorVisibility() {
    displayIndicator?.isVisible = displayIndicatorNeeded && !videoPlaybackPanelVisible
  }

  @AnyThread
  override fun connectionStateChanged(emulator: EmulatorController, connectionState: ConnectionState) {
    if (connectionState == ConnectionState.CONNECTED) {
      UIUtil.invokeLaterIfNeeded { createFloatingToolbar() }
    }
  }

  override fun uiDataSnapshot(sink: DataSink) {
    super.uiDataSnapshot(sink)
    sink[EMULATOR_VIEW_KEY] = displayView
    sink[EMULATOR_CONTROLLER_KEY] = displayView.emulator
  }
}
