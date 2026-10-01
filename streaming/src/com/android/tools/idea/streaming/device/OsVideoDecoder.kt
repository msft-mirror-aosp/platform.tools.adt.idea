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
package com.android.tools.idea.streaming.device

import com.android.tools.idea.util.StudioPathManager
import com.android.tools.idea.util.StudioPathManager.isRunningFromSources
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.system.CpuArch
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.VisibleForTesting

/** OS-provided video decoder (VideoToolbox on macOS, Media Foundation on Windows, system FFmpeg/GStreamer on Linux). */
internal class OsVideoDecoder(codecName: String) : AutoCloseable {

  private var nativeHandle: Long = 0

  init {
    val codecType = getCodecType(codecName)
    check(isSupported(codecName)) { "OsVideoDecoder is not supported for $codecName" }
    nativeHandle = createNativeDecoder(codecType)
    check(nativeHandle != 0L) { "Failed to create native OsVideoDecoder for $codecName" }
  }

  /**
   * Decodes a video packet.
   *
   * @param packetBuffer direct [ByteBuffer] containing encoded packet data
   * @param packetOffset offset into [packetBuffer]
   * @param packetSize size of the packet data
   * @param outputPixelBuffer direct [ByteBuffer] to receive 32-bit BGRA pixels
   * @param outputCapacity capacity of [outputPixelBuffer] in bytes
   * @param outDimensions 2-element int array that receives [width, height] if a frame is produced
   * @return [DECODE_FRAME_PRODUCED] if a frame was decoded and written to [outputPixelBuffer], [DECODE_NO_FRAME] if the packet was
   *   processed without producing a frame, [DECODE_INVALID_FRAME] if the packet was rejected after the decoder had already initialized, or
   *   [DECODE_ERROR] if the decoder failed to initialize or decode
   */
  fun decodeFrame(
    packetBuffer: ByteBuffer,
    packetOffset: Int,
    packetSize: Int,
    outputPixelBuffer: ByteBuffer,
    outputCapacity: Int,
    outDimensions: IntArray,
  ): Int {
    check(nativeHandle != 0L) { "OsVideoDecoder is closed" }
    return decodeFrame(nativeHandle, packetBuffer, packetOffset, packetSize, outputPixelBuffer, outputCapacity, outDimensions)
  }

  override fun close() {
    if (nativeHandle != 0L) {
      destroyNativeDecoder(nativeHandle)
      nativeHandle = 0
    }
  }

  companion object {
    const val DECODE_INVALID_FRAME = -2
    const val DECODE_ERROR = -1
    const val DECODE_NO_FRAME = 0
    const val DECODE_FRAME_PRODUCED = 1

    private const val CODEC_TYPE_AV1 = 0x61763031 // 'av01'
    private const val CODEC_TYPE_AVC = 0x61766331 // 'avc1'
    private const val CODEC_TYPE_HEVC = 0x68766331 // 'hvc1'
    private const val CODEC_TYPE_VP8 = 0x76703038 // 'vp08'
    private const val CODEC_TYPE_VP9 = 0x76703039 // 'vp09'

    private val isLoaded: Boolean by lazy {
      try {
        loadNativeLibrary()
        true
      } catch (e: Throwable) {
        thisLogger().warn("Failed to load native video decoder library", e)
        false
      }
    }

    private fun getCodecType(codecName: String): Int {
      return if (SystemInfoRt.isMac || SystemInfoRt.isWindows || SystemInfoRt.isLinux) {
        when (codecName) {
          "av01",
          "av1" -> CODEC_TYPE_AV1

          "avc",
          "h264" -> CODEC_TYPE_AVC

          "hevc",
          "h265" -> CODEC_TYPE_HEVC

          "vp8" -> if (SystemInfoRt.isWindows || SystemInfoRt.isLinux) CODEC_TYPE_VP8 else 0
          "vp9" -> CODEC_TYPE_VP9
          else -> 0
        }
      } else {
        0
      }
    }

    private val supportedCodecs = ConcurrentHashMap<Int, Boolean>()

    fun isSupported(codecName: String): Boolean {
      val codecType = getCodecType(codecName)
      if (codecType == 0 || !isLoaded) {
        return false
      }
      return supportedCodecs.computeIfAbsent(codecType) {
        val handle = createNativeDecoder(it)
        if (handle == 0L) {
          false
        } else {
          destroyNativeDecoder(handle)
          true
        }
      }
    }

    @VisibleForTesting
    @Synchronized
    fun loadNativeLibrary() {
      val libFile = getLibLocation()
      System.load(libFile.toString())
    }

    private fun getLibLocation(): Path {
      val libName = System.mapLibraryName("video_decoder")
      val homePath = Paths.get(PathManager.getHomePath())
      val libFile = homePath.resolve("plugins/android/resources/native").resolve(libName)
      if (Files.exists(libFile)) {
        return libFile
      }

      if (isRunningFromSources()) {
        val hostSegment =
          when {
            SystemInfoRt.isLinux -> "linux-x86_64"
            SystemInfoRt.isMac -> if (CpuArch.isArm64()) "darwin-arm64" else "darwin-x86_64"
            SystemInfoRt.isWindows -> "windows-x86_64"
            else -> throw UnsatisfiedLinkError("Unsupported OS")
          }
        val devLibFile = StudioPathManager.resolvePathFromSourcesRoot("prebuilts/tools/$hostSegment/streaming").resolve(libName)
        if (Files.exists(devLibFile)) {
          return devLibFile
        }
        throw UnsatisfiedLinkError("Unable to find $devLibFile")
      } else {
        throw UnsatisfiedLinkError("Unable to find $libName. Possibly corrupted Studio installation")
      }
    }

    @JvmStatic private external fun createNativeDecoder(codecType: Int): Long

    @JvmStatic
    private external fun decodeFrame(
      handle: Long,
      packetBuffer: ByteBuffer,
      packetOffset: Int,
      packetSize: Int,
      outputPixelBuffer: ByteBuffer,
      outputCapacity: Int,
      outDimensions: IntArray,
    ): Int

    @JvmStatic private external fun destroyNativeDecoder(handle: Long)
  }
}
