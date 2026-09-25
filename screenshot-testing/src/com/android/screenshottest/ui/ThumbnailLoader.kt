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
package com.android.screenshottest.ui

import com.android.annotations.concurrency.UiThread
import com.android.annotations.concurrency.WorkerThread
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBImageIcon
import java.awt.Image
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/** Upper bound of the width and height of a preview thumbnail. */
internal val MAX_THUMBNAIL_SIZE: Int
  get() = JBUIScale.scale(200)

/** Upper bound of the number of threads decoding screenshot images at the same time. */
private val MAX_DECODE_THREADS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

private const val DEFAULT_MAX_CACHE_ENTRIES = 200

private val LOG = Logger.getInstance(ThumbnailLoader::class.java)

/**
 * Loads preview thumbnails in the background and caches them.
 *
 * Concurrent requests for the same path share a single decode, and paths that fail to decode are remembered so they are not retried on
 * every repaint. All state is confined to the EDT: [load] and [getCached] must be called on the EDT, and callbacks are invoked on the EDT.
 *
 * @param executor runs the decodes. Defaults to a bounded pool so large suites don't saturate the CPU.
 * @param decode turns a file path into a thumbnail, or returns `null` if the file can't be decoded.
 * @param uiExecutor hands decode results back to the EDT.
 * @param maxCacheEntries the number of thumbnails kept in memory before the least recently used one is evicted.
 */
class ThumbnailLoader(
  private val executor: ExecutorService = createDecodeExecutor(),
  private val decode: (String) -> JBImageIcon? = { path -> decodeThumbnailIcon(path, MAX_THUMBNAIL_SIZE) },
  private val uiExecutor: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it, ModalityState.any()) },
  private val maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
) {
  private val cache =
    object : LinkedHashMap<String, JBImageIcon>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JBImageIcon>?): Boolean = size > maxCacheEntries
    }
  private val failedPaths = mutableSetOf<String>()
  private val pendingCallbacks = mutableMapOf<String, MutableList<(JBImageIcon?) -> Unit>>()

  /** Returns the thumbnail for [path] if it is already decoded. */
  @UiThread fun getCached(path: String): JBImageIcon? = cache[path]

  /** Returns true if [path] was already decoded and failed. */
  @UiThread fun hasFailed(path: String): Boolean = path in failedPaths

  /** Returns true if a decode for [path] is running or queued. */
  @UiThread fun isLoading(path: String): Boolean = path in pendingCallbacks

  /**
   * Requests the thumbnail for [path].
   *
   * [onLoaded] is always invoked asynchronously on the EDT, with the thumbnail or `null` if decoding failed. Callers should check
   * [getCached] and [hasFailed] first when they need the result synchronously.
   */
  @UiThread
  fun load(path: String, onLoaded: (JBImageIcon?) -> Unit) {
    val cached = cache[path]
    if (cached != null || path in failedPaths) {
      uiExecutor.invoke(Runnable { onLoaded(cached) })
      return
    }
    val callbacks = pendingCallbacks[path]
    if (callbacks != null) {
      callbacks.add(onLoaded)
      return
    }
    pendingCallbacks[path] = mutableListOf(onLoaded)
    try {
      executor.execute {
        val icon =
          try {
            decode(path)
          } catch (t: Throwable) {
            if (t is ControlFlowException || t is CancellationException) throw t
            LOG.warn("Exception occurred while loading image from path: $path", t)
            null
          }
        uiExecutor.invoke(Runnable { onDecoded(path, icon) })
      }
    } catch (e: RejectedExecutionException) {
      // The executor only rejects work once it is shut down, so the decode will never run.
      LOG.warn("Failed to schedule thumbnail decode for path: $path", e)
      uiExecutor.invoke(Runnable { onDecoded(path, null) })
    }
  }

  private fun onDecoded(path: String, icon: JBImageIcon?) {
    if (icon != null) {
      cache[path] = icon
    } else {
      failedPaths.add(path)
    }
    pendingCallbacks.remove(path)?.forEach { it(icon) }
  }

  companion object {
    /** Creates the executor used for decoding screenshot images. */
    fun createDecodeExecutor(): ExecutorService =
      AppExecutorUtil.createBoundedApplicationPoolExecutor("Screenshot Image Decoder", MAX_DECODE_THREADS)
  }
}

/** Decodes the image at [path] into a thumbnail that fits into [maxSize] x [maxSize], or returns `null` if it can't be decoded. */
@WorkerThread
fun decodeThumbnailIcon(path: String, maxSize: Int): JBImageIcon? {
  val file = File(path)
  if (!file.isFile) {
    LOG.warn("Image file not found. Path: $path")
    return null
  }
  if (file.length() == 0L) {
    LOG.warn("Image file is empty. Path: $path")
    return null
  }
  val image =
    try {
      decodeSubsampled(file, maxSize)
    } catch (e: IOException) {
      LOG.warn("Failed to read image from path: $path", e)
      return null
    }
  if (image == null) {
    LOG.warn("Failed to parse image data from path: $path")
    return null
  }
  return JBImageIcon(scaleToFit(image, maxSize))
}

/**
 * Returns the pixel step to use when reading an image of [width] x [height] that will be shown within [maxSize] x [maxSize].
 *
 * Reading every n-th pixel avoids decoding the full image. The step keeps the decoded image at least twice as large as the thumbnail, so
 * the final smooth downscale still has enough pixels for a clean result.
 */
@VisibleForTesting
fun computeSubsampling(width: Int, height: Int, maxSize: Int): Int {
  if (width <= 0 || height <= 0 || maxSize <= 0) return 1
  val scale = min(maxSize.toDouble() / width, maxSize.toDouble() / height)
  if (scale >= 1.0) return 1
  return max(1, (1.0 / (2.0 * scale)).toInt())
}

/** Returns the size that fits [width] x [height] into [maxSize] x [maxSize] while preserving the aspect ratio. */
@VisibleForTesting
fun computeFitSize(width: Int, height: Int, maxSize: Int): Pair<Int, Int> {
  if (width <= maxSize && height <= maxSize) return width to height
  return if (width > height) {
    maxSize to (height.toDouble() * maxSize / width).toInt().coerceAtLeast(1)
  } else {
    (width.toDouble() * maxSize / height).toInt().coerceAtLeast(1) to maxSize
  }
}

/** Reads [file] with ImageIO, skipping pixels so the result is not much larger than needed. Returns `null` if the format is unknown. */
private fun decodeSubsampled(file: File, maxSize: Int): Image? {
  val input = ImageIO.createImageInputStream(file) ?: return null
  input.use { stream ->
    val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
    try {
      reader.setInput(stream, true, true)
      val step = computeSubsampling(reader.getWidth(0), reader.getHeight(0), maxSize)
      val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
      return reader.read(0, param)
    } finally {
      reader.dispose()
    }
  }
}

private fun scaleToFit(image: Image, maxSize: Int): Image {
  val width = image.getWidth(null)
  val height = image.getHeight(null)
  if (width <= 0 || height <= 0) return image
  val (targetWidth, targetHeight) = computeFitSize(width, height, maxSize)
  if (targetWidth == width && targetHeight == height) return image
  return ImageUtil.scaleImage(image, targetWidth, targetHeight)
}
