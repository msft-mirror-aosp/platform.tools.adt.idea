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
package com.android.tools.idea.testartifacts.instrumented.testsuite.model.benchmark

import com.android.tools.idea.project.AndroidNotification
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorNavigatable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TemporaryDirectory
import com.intellij.testFramework.replaceService
import java.io.File
import java.net.URI
import org.jetbrains.android.ComponentStack
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@RunsInEdt
class BenchmarkLinkListenerTest {
  private val projectRule = AndroidProjectRule.inMemory()
  private val temporaryDirectoryRule = TemporaryDirectory()

  @get:Rule val rules = checkNotNull(RuleChain.outerRule(projectRule).around(EdtRule()).around(temporaryDirectoryRule))
  private val mockEditorService = mock<FileEditorManager>()
  private val mockBrowserService = mock<BrowserLauncher>()
  private lateinit var mockNotification: AndroidNotification
  private val fileCapture = ArgumentCaptor.forClass(FileEditorNavigatable::class.java)
  private lateinit var componentStack: ComponentStack

  @Before
  fun setup() {
    mockNotification = mock()
    componentStack = ComponentStack(projectRule.project)
    componentStack.registerServiceInstance(FileEditorManager::class.java, mockEditorService)
    componentStack.registerServiceInstance(AndroidNotification::class.java, mockNotification)
    ApplicationManager.getApplication().replaceService(BrowserLauncher::class.java, mockBrowserService, projectRule.testRootDisposable)
    whenever(mockEditorService.openEditor(any(), anyBoolean())).thenCallRealMethod()
    whenever(mockEditorService.openFileEditor(fileCapture.capture(), any())).thenReturn(ArrayList<FileEditor>())
  }

  @After
  fun tearDown() {
    componentStack.restore()
  }

  @Test
  fun listenerOpensV2FileLink() {
    val listener = BenchmarkLinkListener(projectRule.project)
    val traceFile = FileUtil.createTempFile("traceFile", ".trace")
    traceFile.deleteOnExit()
    listener.hyperlinkClicked("file://${traceFile.name}")
    assertThat(fileCapture.value.file.name).isEqualTo(traceFile.name)
  }

  @Test
  fun listenerOpensV3FileLink() {
    val listener = BenchmarkLinkListener(projectRule.project)
    val traceFile = FileUtil.createTempFile("traceFile", ".trace")
    traceFile.deleteOnExit()
    listener.hyperlinkClicked("uri://${traceFile.name}?param1=value1&param2=value2")
    assertThat(fileCapture.value.file.name).isEqualTo(traceFile.name)
  }

  @Test
  fun listenerRejectsPathTraversalLink() {
    val listener = BenchmarkLinkListener(projectRule.project)
    listener.hyperlinkClicked("file://../evil.trace")
    verifyNoInteractions(mockEditorService)
  }

  @Test
  fun listenerDoesNotOpenNonTraceFiles() {
    val listener = BenchmarkLinkListener(projectRule.project)
    val suffixes = listOf(".ttf", ".otf", ".apk", ".tflite", ".hprof", ".li", ".heapprofd", ".trace.ttf", "")
    for (suffix in suffixes) {
      val file = FileUtil.createTempFile("benchmarkFile", suffix)
      file.deleteOnExit()
      listener.hyperlinkClicked("file://${file.name}")
      listener.hyperlinkClicked("uri://${file.name}?param=value")
    }
    verifyNoInteractions(mockEditorService)
    verify(mockNotification, times(suffixes.size * 2))
      .showBalloon(eq("Unsupported benchmark file"), any<String>(), eq(NotificationType.WARNING))
  }

  @Test
  fun rejectedFileNameIsEscapedInNotification() {
    val listener = BenchmarkLinkListener(projectRule.project)
    listener.hyperlinkClicked("uri://a&b'c.ttf")
    verify(mockNotification)
      .showBalloon(
        eq("Unsupported benchmark file"),
        eq("Only trace files can be opened from benchmark results (a&amp;b&#39;c.ttf)"),
        eq(NotificationType.WARNING),
      )
    verifyNoInteractions(mockEditorService)
  }

  @Test
  fun listenerRejectsLinkThatIsNotAValidPath() {
    val listener = BenchmarkLinkListener(projectRule.project)
    listener.hyperlinkClicked("uri://bad\u0000name.trace")
    verify(mockNotification).showBalloon(eq("Invalid benchmark path"), any<String>(), eq(NotificationType.WARNING))
    verifyNoInteractions(mockEditorService)
  }

  @Test
  fun listenerDoesNotOpenNonTraceFilesInPerfettoWeb() {
    val fakePerfettoLoader = FakePerfettoLoader()
    val listener =
      BenchmarkLinkListener(projectRule.project, isPerfettoWebLoaderEnabled = true, openTraceInPerfettoWebLoader = fakePerfettoLoader::load)
    val file = FileUtil.createTempFile("benchmarkFile", ".ttf")
    file.deleteOnExit()
    listener.hyperlinkClicked("uri://${file.name}")
    assertThat(fakePerfettoLoader.callCount).isEqualTo(0)
    verifyNoInteractions(mockEditorService)
  }

  @Test
  fun listenerOpensTraceFilesIgnoringExtensionCase() {
    val listener = BenchmarkLinkListener(projectRule.project)
    val traceFile = FileUtil.createTempFile("traceFile", ".TRACE")
    traceFile.deleteOnExit()
    listener.hyperlinkClicked("file://${traceFile.name}")
    assertThat(fileCapture.value.file.name).isEqualTo(traceFile.name)
  }

  @Test
  fun listenerOpensPerfettoTraceFileInEditor() {
    val listener = BenchmarkLinkListener(projectRule.project)
    val traceFile = FileUtil.createTempFile("traceFile", ".perfetto-trace")
    traceFile.deleteOnExit()
    listener.hyperlinkClicked("uri://${traceFile.name}")
    assertThat(fileCapture.value.file.name).isEqualTo(traceFile.name)
  }

  @Test
  fun listenerOpensV2FileLinkInPerfettoWeb() {
    val fakePerfettoLoader = FakePerfettoLoader()
    assertThat(fakePerfettoLoader.callCount).isEqualTo(0)
    val listener =
      BenchmarkLinkListener(projectRule.project, isPerfettoWebLoaderEnabled = true, openTraceInPerfettoWebLoader = fakePerfettoLoader::load)
    val traceFile = FileUtil.createTempFile("traceFile", ".perfetto-trace")
    traceFile.deleteOnExit()
    listener.hyperlinkClicked("file://${traceFile.name}")
    with(fakePerfettoLoader) {
      assertThat(callCount).isEqualTo(1)
      assertThat(capturedFile.name).isEqualTo(traceFile.name)
      assertThat(capturedQuery).isEqualTo(null)
    }
  }

  @Test
  fun listenerOpensV3FileLinkInPerfettoWeb() {
    val fakePerfettoLoader = FakePerfettoLoader()
    assertThat(fakePerfettoLoader.callCount).isEqualTo(0)
    val listener =
      BenchmarkLinkListener(projectRule.project, isPerfettoWebLoaderEnabled = true, openTraceInPerfettoWebLoader = fakePerfettoLoader::load)
    val traceFile = FileUtil.createTempFile("traceFile", ".perfetto-trace")
    traceFile.deleteOnExit()
    val query = "param1=value1&param%202=value%202"
    listener.hyperlinkClicked("uri://${traceFile.name}?$query")
    with(fakePerfettoLoader) {
      assertThat(callCount).isEqualTo(1)
      assertThat(capturedFile.name).isEqualTo(traceFile.name)
      assertThat(capturedQuery).isEqualTo(query)
    }
  }

  @Test
  fun listenerOpensTrustedHttpsUrl() {
    listenerOpensWebLink("https://developer.android.com/topic/performance/benchmarking/microbenchmark-overview")
    listenerOpensWebLink("https://d.android.com/test#JIT_ACTIVITY")
  }

  @Test
  fun listenerOpensTrustedHttpsUrlIgnoringCase() {
    listenerOpensWebLink("https://Developer.Android.COM/studio")
    listenerOpensWebLink("HTTPS://d.android.com/test#JIT_ACTIVITY")
  }

  private fun listenerOpensWebLink(url: String) {
    val listener = BenchmarkLinkListener(projectRule.project)
    listener.hyperlinkClicked(url)
    verify(mockBrowserService).browse(URI.create(url))
    verifyNoInteractions(mockNotification)
  }

  @Test
  fun listenerDoesNotOpenUntrustedWebLinks() {
    val listener = BenchmarkLinkListener(projectRule.project)
    val links =
      listOf(
        "http://foo.bar.baz",
        "https://foo.bar.baz",
        "HTTPS://foo.bar.baz",
        "http://127.0.0.1:8000/aswb045",
        "https://127.0.0.1/aswb045",
        "http://developer.android.com/studio",
        "https://ui.perfetto.dev",
        "https://source.android.com/docs",
        "https://developer.android.com.evil.example/studio",
        "https://evil.example/#@developer.android.com",
        "https://developer.android.com@evil.example/",
        "https://user@developer.android.com/",
        "https://developer.android.com:8443/",
        "https://evil.example/?x=https://developer.android.com",
        "https://developer.android.com/has space",
      )
    links.forEach { listener.hyperlinkClicked(it) }
    verifyNoInteractions(mockBrowserService)
    verifyNoInteractions(mockEditorService)
    verify(mockNotification, times(links.size)).showBalloon(eq("Benchmark link not opened"), any<String>(), eq(NotificationType.WARNING))
  }

  @Test
  fun rejectedWebLinkIsEscapedInNotification() {
    val listener = BenchmarkLinkListener(projectRule.project)
    listener.hyperlinkClicked("https://evil.example/<img src='x'>")
    verify(mockNotification)
      .showBalloon(
        eq("Benchmark link not opened"),
        eq("Only links to Android documentation can be opened (https://evil.example/&lt;img src=&#39;x&#39;&gt;)"),
        eq(NotificationType.WARNING),
      )
  }

  @Test
  fun rejectedLongWebLinkIsTrimmedInNotification() {
    val listener = BenchmarkLinkListener(projectRule.project)
    val longLink = "https://evil.example/" + "a".repeat(10_000)
    listener.hyperlinkClicked(longLink)
    val textCaptor = ArgumentCaptor.forClass(String::class.java)
    verify(mockNotification).showBalloon(eq("Benchmark link not opened"), textCaptor.capture(), eq(NotificationType.WARNING))
    val shownLink = textCaptor.value.substringAfter("(").removeSuffix(")")
    assertThat(shownLink.length).isAtMost(200)
    assertThat(shownLink).startsWith("https://evil.example/")
    assertThat(shownLink).contains("…")
    verifyNoInteractions(mockBrowserService)
  }

  @Test
  fun listenerIgnoresUnrecognizedLinks() {
    val listener = BenchmarkLinkListener(projectRule.project)
    listener.hyperlinkClicked("qwerty")
    listener.hyperlinkClicked("asdf")
    listener.hyperlinkClicked("ftp://abc.def")
    listener.hyperlinkClicked("vnc://192.168.1.100")
    verifyNoInteractions(mockBrowserService)
    verifyNoInteractions(mockEditorService)
  }
}

private class FakePerfettoLoader {
  lateinit var capturedFile: File
    private set

  var capturedQuery: String? = null
    private set

  var callCount = 0
    private set

  fun load(file: File, query: String?) {
    callCount++
    this.capturedFile = file
    this.capturedQuery = query
  }
}
