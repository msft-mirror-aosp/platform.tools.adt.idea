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

import com.google.common.util.concurrent.MoreExecutors
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.util.ui.JBImageIcon
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class ThumbnailLoaderTest {

  companion object {
    @JvmField @ClassRule val applicationRule = ApplicationRule()
  }

  @get:Rule val temporaryFolder = TemporaryFolder()

  private val executor = ManualExecutorService()
  private val uiQueue = ArrayDeque<Runnable>()

  private fun createLoader(maxCacheEntries: Int = 200, decode: (String) -> JBImageIcon?) =
    ThumbnailLoader(executor = executor, decode = decode, uiExecutor = { uiQueue.add(it) }, maxCacheEntries = maxCacheEntries)

  private fun runUiQueue() {
    while (uiQueue.isNotEmpty()) uiQueue.removeFirst().run()
  }

  @Test
  fun concurrentRequestsForSamePathShareOneDecode() {
    var decodeCount = 0
    val icon = mock<JBImageIcon>()
    val loader =
      createLoader {
        decodeCount++
        icon
      }
    val results = mutableListOf<JBImageIcon?>()

    repeat(5) { loader.load("a.png") { results.add(it) } }
    assertTrue(loader.isLoading("a.png"))
    assertEquals("Only one decode should be queued", 1, executor.queuedCount)

    executor.runAll()
    runUiQueue()

    assertEquals(1, decodeCount)
    assertEquals(List(5) { icon }, results)
    assertFalse(loader.isLoading("a.png"))
    assertSame(icon, loader.getCached("a.png"))
  }

  @Test
  fun cachedPathIsNotDecodedAgainAndCallbackIsAsync() {
    var decodeCount = 0
    val icon = mock<JBImageIcon>()
    val loader =
      createLoader {
        decodeCount++
        icon
      }
    loader.load("a.png") {}
    executor.runAll()
    runUiQueue()

    var result: JBImageIcon? = null
    loader.load("a.png") { result = it }
    assertNull("Callback must not run synchronously", result)
    assertEquals(0, executor.queuedCount)

    runUiQueue()
    assertSame(icon, result)
    assertEquals(1, decodeCount)
  }

  @Test
  fun failureIsRemembered() {
    var decodeCount = 0
    val loader =
      createLoader {
        decodeCount++
        null
      }
    loader.load("bad.png") {}
    executor.runAll()
    runUiQueue()
    assertTrue(loader.hasFailed("bad.png"))
    assertNull(loader.getCached("bad.png"))

    var invoked = false
    loader.load("bad.png") { invoked = true }
    runUiQueue()

    assertTrue(invoked)
    assertEquals("A failed path should not be decoded again", 1, decodeCount)
  }

  @Test
  fun decodeExceptionIsReportedAsFailure() {
    val loader = createLoader { throw IllegalStateException("boom") }
    var invoked = false
    var result: JBImageIcon? = mock()

    loader.load("bad.png") {
      invoked = true
      result = it
    }
    executor.runAll()
    runUiQueue()

    assertTrue(invoked)
    assertNull(result)
    assertTrue(loader.hasFailed("bad.png"))
  }

  @Test
  fun rejectedDecodeIsReportedAsFailure() {
    val loader =
      ThumbnailLoader(
        executor = Executors.newSingleThreadExecutor().apply { shutdown() },
        decode = { mock() },
        uiExecutor = { uiQueue.add(it) },
      )
    val results = mutableListOf<JBImageIcon?>()

    loader.load("a.png") { results.add(it) }
    assertTrue("Callbacks must not run synchronously", results.isEmpty())
    runUiQueue()

    assertEquals(listOf<JBImageIcon?>(null), results)
    assertFalse(loader.isLoading("a.png"))
    assertTrue(loader.hasFailed("a.png"))

    // Later requests are answered from the failure cache instead of being scheduled again.
    loader.load("a.png") { results.add(it) }
    runUiQueue()
    assertEquals(listOf<JBImageIcon?>(null, null), results)
  }

  @Test
  fun leastRecentlyUsedEntryIsEvicted() {
    val decodedPaths = mutableListOf<String>()
    val loader =
      createLoader(maxCacheEntries = 2) {
        decodedPaths.add(it)
        mock()
      }
    fun loadNow(path: String) {
      loader.load(path) {}
      executor.runAll()
      runUiQueue()
    }

    loadNow("a")
    loadNow("b")
    // Touch "a" so that "b" becomes the least recently used entry.
    assertNotNull(loader.getCached("a"))
    loadNow("c")

    assertNotNull(loader.getCached("a"))
    assertNull(loader.getCached("b"))
    assertNotNull(loader.getCached("c"))

    loadNow("b")
    assertEquals(listOf("a", "b", "c", "b"), decodedPaths)
  }

  @Test
  fun computeSubsampling_keepsDecodedImageAtLeastTwiceTheThumbnail() {
    assertEquals(1, computeSubsampling(100, 100, 200))
    assertEquals(1, computeSubsampling(200, 200, 200))
    assertEquals(1, computeSubsampling(399, 399, 200))
    assertEquals(1, computeSubsampling(400, 400, 200))
    assertEquals(2, computeSubsampling(800, 800, 200))
    // A typical phone screenshot.
    val step = computeSubsampling(1079, 2339, 200)
    assertEquals(5, step)
    assertTrue(2339 / step >= 2 * 200)
    assertEquals(1, computeSubsampling(0, 100, 200))
    assertEquals(1, computeSubsampling(100, 100, 0))
  }

  @Test
  fun computeFitSize_preservesAspectRatio() {
    assertEquals(100 to 50, computeFitSize(100, 50, 200))
    assertEquals(200 to 100, computeFitSize(400, 200, 200))
    assertEquals(100 to 200, computeFitSize(200, 400, 200))
    assertEquals(92 to 200, computeFitSize(1079, 2339, 200))
    assertEquals(200 to 1, computeFitSize(10000, 1, 200))
  }

  @Test
  fun decodeThumbnailIcon_scalesLargeImageToFit() {
    val file = writePng("large.png", 1079, 2339)

    val icon = decodeThumbnailIcon(file.absolutePath, 200)

    assertNotNull(icon)
    assertEquals(200, icon!!.iconHeight)
    assertEquals(92, icon.iconWidth)
  }

  @Test
  fun decodeThumbnailIcon_keepsSmallImageSize() {
    val file = writePng("small.png", 120, 80)

    val icon = decodeThumbnailIcon(file.absolutePath, 200)

    assertNotNull(icon)
    assertEquals(120, icon!!.iconWidth)
    assertEquals(80, icon.iconHeight)
  }

  @Test
  fun decodeThumbnailIcon_returnsNullForInvalidFiles() {
    val corrupt = temporaryFolder.newFile("corrupt.png").apply { writeText("not an image") }
    val empty = temporaryFolder.newFile("empty.png")
    val missing = File(temporaryFolder.root, "missing.png")
    val truncated = writePng("truncated.png", 300, 300).apply { writeBytes(readBytes().copyOf(60)) }

    assertNull(decodeThumbnailIcon(corrupt.absolutePath, 200))
    assertNull(decodeThumbnailIcon(empty.absolutePath, 200))
    assertNull(decodeThumbnailIcon(missing.absolutePath, 200))
    assertNull(decodeThumbnailIcon(truncated.absolutePath, 200))
    assertNull(decodeThumbnailIcon(temporaryFolder.root.absolutePath, 200))
  }

  @Test
  fun defaultExecutorLimitsConcurrentDecodes() {
    val decodeExecutor = ThumbnailLoader.createDecodeExecutor()
    val maxAllowed = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    val running = AtomicInteger()
    val maxRunning = AtomicInteger()
    val tasks = 16
    val done = CountDownLatch(tasks)

    repeat(tasks) {
      decodeExecutor.execute {
        val now = running.incrementAndGet()
        maxRunning.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        Thread.sleep(20)
        running.decrementAndGet()
        done.countDown()
      }
    }

    assertTrue(done.await(30, TimeUnit.SECONDS))
    assertTrue("Expected at most $maxAllowed concurrent decodes but saw ${maxRunning.get()}", maxRunning.get() <= maxAllowed)
  }

  @Test
  fun callbacksRunOnEdtWithDefaultUiExecutor() {
    val loader = ThumbnailLoader(executor = MoreExecutors.newDirectExecutorService(), decode = { mock() })
    val onEdt = CountDownLatch(1)
    var wasEdt = false

    ApplicationManager.getApplication().invokeAndWait {
      loader.load("a.png") {
        wasEdt = ApplicationManager.getApplication().isDispatchThread
        onEdt.countDown()
      }
    }

    assertTrue(onEdt.await(10, TimeUnit.SECONDS))
    assertTrue(wasEdt)
  }

  private fun writePng(name: String, width: Int, height: Int): File {
    val file = temporaryFolder.newFile(name)
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file)
    return file
  }
}
