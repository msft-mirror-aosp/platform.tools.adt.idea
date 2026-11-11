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
package com.android.tools.idea.streaming.device

import com.android.annotations.concurrency.AnyThread
import com.android.annotations.concurrency.UiThread
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.tools.idea.concurrency.createCoroutineScope
import com.android.tools.idea.streaming.core.AbstractDisplayPanel
import com.android.tools.idea.streaming.core.DisplayIndicatorPopup
import com.android.tools.idea.streaming.core.DisplayType
import com.android.tools.idea.streaming.device.DeviceView.ConnectionState
import com.android.tools.idea.streaming.device.DeviceView.ConnectionStateListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.SinglePixelPackedSampleModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting

private const val BLACK_CHANNEL_THRESHOLD = 16
private const val UPPER_BLACK_FRACTION = 0.8

/** Represents a single display of an Android device. */
internal class DeviceDisplayPanel(
  disposableParent: Disposable,
  deviceClient: DeviceClient,
  displayId: Int,
  displayType: DisplayType,
  displaySize: Dimension,
  initialDisplayOrientation: Int,
  project: Project,
  zoomToolbarVisible: Boolean,
) : AbstractDisplayPanel<DeviceView>(disposableParent, zoomToolbarVisible), ConnectionStateListener {

  override val deviceType: DeviceType
    get() = displayView.deviceClient.deviceConfig.deviceType

  private val protectedContentPopup = DisplayIndicatorPopup("Sensitive content hidden").apply { isVisible = false }
  private var disposed = false
  @Volatile private var lockScreenActive = false
  @Volatile private var lockGeneration = 0
  @get:VisibleForTesting
  internal var lastCheckedFrameNumber: UInt = 0u
    private set

  private val frameCheckChannel = Channel<Int>(Channel.CONFLATED)
  private val lockScreenListener =
    object : DeviceController.LockScreenListener {
      @AnyThread
      override fun onLockScreenStateChanged(locked: Boolean) {
        UIUtil.invokeLaterIfNeeded {
          if (!disposed && displayView.isConnected) {
            lockScreenActive = locked
            if (locked) {
              frameCheckChannel.trySend(++lockGeneration)
            } else {
              lastCheckedFrameNumber = 0u
              protectedContentPopup.isVisible = false
            }
          }
        }
      }
    }
  private val videoFrameListener =
    object : VideoDecoder.FrameListener {
      override fun onNewFrameAvailable() {
        val generation = lockGeneration
        if (lockScreenActive && generation > 0) {
          frameCheckChannel.trySend(generation)
        }
      }

      override fun onEndOfVideoStream() {}

      override fun onInvalidFrame(e: InvalidFrameException) {}
    }

  init {
    displayView = DeviceView(this, deviceClient, project, displayId, displayType, displaySize, initialDisplayOrientation)

    if (displayType == DisplayType.GLASSES_PROJECTION) {
      addToTop(DeviceGlassesInputPanel(deviceClient, displayId, displaySize))
    }

    notificationLayerPane.add(protectedContentPopup)

    loadingPanel.setLoadingText("Connecting to the device")
    loadingPanel.startLoading() // The stopLoading method is called by DeviceView after a connection to the device is established.

    displayView.addConnectionStateListener(this)
    Disposer.register(this) {
      disposed = true
      frameCheckChannel.close()
      displayView.deviceClient.videoDecoder?.removeFrameListener(displayId, videoFrameListener)
      displayView.deviceController?.removeLockScreenListener(lockScreenListener)
    }

    createCoroutineScope().launch(Dispatchers.Default) {
      var lastWorkerFrameNumber = 0u
      var lastWorkerGeneration = 0
      for (generation in frameCheckChannel) {
        if (!lockScreenActive) continue
        var frameNumber = 0u
        var isEmpty = false
        var image: BufferedImage? = null
        displayView.deviceClient.videoDecoder?.consumeDisplayFrame(displayId) { frame ->
          frameNumber = frame.frameNumber
          isEmpty = frame.isEmpty
          image = frame.image
        }
        val currentImage = image ?: continue
        if (frameNumber == lastWorkerFrameNumber && generation == lastWorkerGeneration) continue
        val upperBlack = !isEmpty && isUpperRegionBlack(currentImage)
        lastWorkerFrameNumber = frameNumber
        lastWorkerGeneration = generation
        withContext(Dispatchers.EDT) {
          if (!disposed && displayView.isConnected && lockScreenActive && lockGeneration == generation) {
            lastCheckedFrameNumber = frameNumber
            protectedContentPopup.isVisible = upperBlack
          }
        }
      }
    }
  }

  @UiThread
  override fun connectionStateChanged(deviceSerialNumber: String, connectionState: ConnectionState) {
    when (connectionState) {
      ConnectionState.CONNECTED -> {
        createFloatingToolbar()
        displayView.deviceClient.videoDecoder?.addFrameListener(displayId, videoFrameListener)
        displayView.deviceController?.addLockScreenListener(lockScreenListener)
      }
      ConnectionState.DISCONNECTED -> {
        displayView.deviceClient.videoDecoder?.removeFrameListener(displayId, videoFrameListener)
        displayView.deviceController?.removeLockScreenListener(lockScreenListener)
        lockScreenActive = false
        lastCheckedFrameNumber = 0u
        protectedContentPopup.isVisible = false
      }
      else -> {}
    }
  }

  override fun uiDataSnapshot(sink: DataSink) {
    super.uiDataSnapshot(sink)
    sink[DEVICE_VIEW_KEY] = displayView
    sink[DEVICE_CLIENT_KEY] = displayView.deviceClient
    sink[DEVICE_CONTROLLER_KEY] = displayView.deviceController
  }
}

private fun isUpperRegionBlack(image: BufferedImage): Boolean {
  val width = image.width
  val height = (image.height * UPPER_BLACK_FRACTION).toInt()
  if (width <= 0 || height <= 0) {
    return false
  }
  val raster = image.raster
  val dataBuffer = raster.dataBuffer
  val sampleModel = image.sampleModel
  val isSupportedType =
    when (image.type) {
      BufferedImage.TYPE_INT_ARGB,
      BufferedImage.TYPE_INT_RGB,
      BufferedImage.TYPE_INT_BGR -> true
      else -> false
    }
  if (
    isSupportedType &&
      dataBuffer is DataBufferInt &&
      dataBuffer.offset == 0 &&
      sampleModel is SinglePixelPackedSampleModel &&
      sampleModel.scanlineStride == width &&
      raster.sampleModelTranslateX == 0 &&
      raster.sampleModelTranslateY == 0
  ) {
    val pixels = dataBuffer.data
    val limit = height * width
    if (pixels.size >= limit) {
      for (i in 0 until limit) {
        if (!isBlackPixel(pixels[i])) {
          return false
        }
      }
      return true
    }
  }
  for (y in 0 until height) {
    for (x in 0 until width) {
      if (!isBlackPixel(image.getRGB(x, y))) {
        return false
      }
    }
  }
  return true
}

private fun isBlackPixel(argb: Int): Boolean {
  val r = (argb ushr 16) and 0xFF
  val g = (argb ushr 8) and 0xFF
  val b = argb and 0xFF
  return r <= BLACK_CHANNEL_THRESHOLD && g <= BLACK_CHANNEL_THRESHOLD && b <= BLACK_CHANNEL_THRESHOLD
}
