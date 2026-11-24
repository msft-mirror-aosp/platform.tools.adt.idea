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
package com.android.tools.idea.streaming.emulator.actions

import com.android.emulator.control.Camera
import com.android.emulator.control.CameraList
import com.android.emulator.control.Environment
import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.testutils.waitForCondition
import com.android.tools.adtui.actions.createTestEvent
import com.android.tools.adtui.actions.executeAction
import com.android.tools.adtui.actions.updateAndGetActionPresentation
import com.android.tools.idea.avd.EnvironmentImage
import com.android.tools.idea.avd.EnvironmentsUpdater
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.protobuf.TextFormat.shortDebugString
import com.android.tools.idea.streaming.emulator.EMULATOR_CONTROLLER_KEY
import com.android.tools.idea.streaming.emulator.EmulatorConfiguration
import com.android.tools.idea.streaming.emulator.EmulatorController
import com.android.tools.idea.streaming.emulator.FakeEmulator
import com.android.tools.idea.streaming.emulator.FakeEmulatorRule
import com.android.tools.idea.streaming.emulator.RunningEmulatorCatalog
import com.android.tools.idea.testing.disposable
import com.android.tools.idea.testing.file.registerFakeFileChooserFactory
import com.android.tools.idea.testing.flags.overrideForTest
import com.google.common.truth.Truth.assertThat
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataSnapshotProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.io.FileUtilRt.toSystemIndependentName
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TemporaryDirectory
import com.intellij.testFramework.replaceService
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import java.util.zip.Deflater
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertFailsWith
import kotlin.text.Charsets.US_ASCII
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunsInEdt
class EmulatorEnvironmentActionTest {

  private val projectRule = ProjectRule()
  private val emulatorRule = FakeEmulatorRule()
  @get:Rule val rule = RuleChain(projectRule, emulatorRule, EdtRule())
  @get:Rule val tempDirRule = TemporaryDirectory()

  val testRootDisposable
    get() = projectRule.disposable

  private val emulator by lazy {
    val avdFolder = FakeEmulator.createDisplayGlassesAvd(emulatorRule.avdRoot)
    emulatorRule.newEmulator(avdFolder)
  }
  private val emulatorController by lazy {
    emulator.start()
    val catalog = RunningEmulatorCatalog.getInstance()
    val controller = runBlocking { catalog.updateNow().await() }.first()
    waitForCondition(5.seconds) { controller.connectionState == EmulatorController.ConnectionState.CONNECTED }
    controller
  }
  private val dataSnapshotProvider by lazy { DataSnapshotProvider { sink -> sink[EMULATOR_CONTROLLER_KEY] = emulatorController } }

  @Before
  fun setUp() {
    StudioFlags.EMBEDDED_EMULATOR_3D_SCENE_ENVIRONMENT.overrideForTest(true, testRootDisposable)
    StudioFlags.EMBEDDED_EMULATOR_VIDEO_ENVIRONMENT.overrideForTest(true, testRootDisposable)
    StudioFlags.EMBEDDED_EMULATOR_360_VIDEO_ENVIRONMENT.overrideForTest(true, testRootDisposable)
  }

  @Test
  fun testDarknessEnvironment() {
    val action = ActionManager.getInstance().getAction("android.emulator.environment.darkness")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request)).isEqualTo("environment { key: \"scene.mode\" value: \"color:#000000\" }")
  }

  @Test
  fun testBuiltInEnvironments() {
    val environmentsUpdater = mock<EnvironmentsUpdater>()
    val list =
      listOf(
        EnvironmentImage(Path.of("/Sdk/environments/outdoor-nature-bright.jpg"), "Outdoor Nature Bright", false),
        EnvironmentImage(Path.of("/Sdk/environments/indoor-study-dark.jpg"), "Indoor Study Dark", true),
        EnvironmentImage(Path.of("/Sdk/environments/outdoor-city-bright.jpg"), "Outdoor City Bright", false),
      )
    runBlocking { whenever(environmentsUpdater.getEnvironments()).thenReturn(list) }
    ApplicationManager.getApplication().replaceService(EnvironmentsUpdater::class.java, environmentsUpdater, testRootDisposable)

    val group = ActionManager.getInstance().getAction("android.emulator.environments") as EmulatorEnvironmentActionGroup
    val event = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)
    val children = group.getChildren(event)

    val environments = listOf("indoor-study-dark", "outdoor-city-bright", "outdoor-nature-bright")
    for (i in environments.indices) {
      val action = children[i]
      executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

      val call = emulator.getNextGrpcCall(2.seconds)
      assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
      assertThat(shortDebugString(call.request))
        .isEqualTo("environment { key: \"scene.mode\" value: \"imagefile:/Sdk/environments/${environments[i]}.jpg\" }")
    }
  }

  @Test
  fun testCustomEnvironment() {
    val imageFile = mock<VirtualFile>()
    whenever(imageFile.path).thenReturn("/tmp/test_image.png")
    testRootDisposable.registerFakeFileChooserFactory(imageFile)

    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request)).isEqualTo("environment { key: \"scene.mode\" value: \"imagefile:${imageFile.path}\" }")
  }

  @Test
  fun testCustom3dEnvironment() {
    val objFileContent =
      """
      # Wavefront OBJ file
      v 0.0 0.0 0.0
      v 1.0 0.0 0.0
      v 0.0 1.0 0.0
      f 1 2 3
      """
        .trimIndent()
    val objPath = tempDirRule.newPath("test_scene.obj")
    Files.write(objPath, objFileContent.toByteArray(Charsets.UTF_8))

    val objFile = mock<VirtualFile>()
    whenever(objFile.path).thenReturn(objPath.toString())
    testRootDisposable.registerFakeFileChooserFactory(objFile)

    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"mesh3d:${objPath.systemIndependentString}\" }")
  }

  @Test
  fun testCustom3dEnvironment_invalidFile() {
    val objFileContent =
      """
      This is not a valid Wavefront OBJ file
      random text
      """
        .trimIndent()
    val objPath = tempDirRule.newPath("test_scene_invalid.obj")
    Files.write(objPath, objFileContent.toByteArray(Charsets.UTF_8))

    val objFile = mock<VirtualFile>()
    whenever(objFile.path).thenReturn(objPath.toString())
    testRootDisposable.registerFakeFileChooserFactory(objFile)

    var dialogShownMessage: String? = null
    TestDialogManager.setTestDialog(
      { message ->
        dialogShownMessage = message
        0 // OK
      },
      testRootDisposable,
    )
    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    assertFailsWith<TimeoutException> { emulator.getNextGrpcCall(1.seconds) }
    assertThat(dialogShownMessage).isEqualTo("The selected file is not a valid Wavefront 3D scene file.")
  }

  @Test
  fun testCustom3dEnvironment_binaryFile() {
    val binaryContent = byteArrayOf(0x4C, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) // Looks like Intel 386 COFF header
    val objPath = tempDirRule.newPath("test_scene_binary.obj")
    Files.write(objPath, binaryContent)

    val objFile = mock<VirtualFile>()
    whenever(objFile.path).thenReturn(objPath.toString())
    testRootDisposable.registerFakeFileChooserFactory(objFile)

    var dialogShownMessage: String? = null
    TestDialogManager.setTestDialog(
      { message ->
        dialogShownMessage = message
        0 // OK
      },
      testRootDisposable,
    )
    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    assertFailsWith<TimeoutException> { emulator.getNextGrpcCall(1.seconds) }
    assertThat(dialogShownMessage).isEqualTo("The selected file is not a valid Wavefront 3D scene file.")
  }

  @Test
  fun testRecentCustomEnvironment() {
    val filePath = "/tmp/recent_image.png"
    val action = EmulatorEnvironmentAction.RecentCustom(Path.of(filePath))
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request)).isEqualTo("environment { key: \"scene.mode\" value: \"imagefile:$filePath\" }")
  }

  @Test
  fun testRecentCustom3dEnvironment() {
    val filePath = "/tmp/recent_scene.obj"
    val action = EmulatorEnvironmentAction.RecentCustom(Path.of(filePath))
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request)).isEqualTo("environment { key: \"scene.mode\" value: \"mesh3d:$filePath\" }")
  }

  @Test
  fun testCustomVideoEnvironment() {
    val videoFile = mock<VirtualFile>()
    whenever(videoFile.path).thenReturn("/tmp/test_video.mp4")
    testRootDisposable.registerFakeFileChooserFactory(videoFile)

    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request)).isEqualTo("environment { key: \"scene.mode\" value: \"videofile:${videoFile.path}\" }")
  }

  @Test
  fun testCustomVideoEnvironment_featureFlagOff() {
    StudioFlags.EMBEDDED_EMULATOR_VIDEO_ENVIRONMENT.overrideForTest(false, testRootDisposable)
    val videoFile = mock<VirtualFile>()
    whenever(videoFile.path).thenReturn("/tmp/test_video.mp4")
    testRootDisposable.registerFakeFileChooserFactory(videoFile)

    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request)).isEqualTo("environment { key: \"scene.mode\" value: \"imagefile:${videoFile.path}\" }")
  }

  @Test
  fun testRecentCustomVideoEnvironment() {
    val filePath = "/tmp/recent_video.mp4"
    val action = EmulatorEnvironmentAction.RecentCustom(Path.of(filePath))
    assertThat(action.templatePresentation.text).isEqualTo("<html>recent_video.mp4&ensp;<font color=\"gray\">video</font></html>")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request)).isEqualTo("environment { key: \"scene.mode\" value: \"videofile:$filePath\" }")
  }

  @Test
  fun testCustom360Mp4VideoEnvironment() {
    val mp4Path = createMock360Mp4File("test_360.mp4")
    val videoFile = mock<VirtualFile>()
    whenever(videoFile.path).thenReturn(mp4Path.toString())
    testRootDisposable.registerFakeFileChooserFactory(videoFile)

    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"video360:${mp4Path.systemIndependentString}\" }")

    waitForCondition(2.seconds) { EmulatorEnvironmentAction.getRecentFile(mp4Path) != null }
    val recentEntry = EmulatorEnvironmentAction.getRecentFile(mp4Path)!!
    assertThat(recentEntry.mode).isEqualTo("video360")
  }

  @Test
  fun testCustom360WebmVideoEnvironment() {
    val webmPath = createMock360WebmFile("test_360.webm")
    val videoFile = mock<VirtualFile>()
    whenever(videoFile.path).thenReturn(webmPath.toString())
    testRootDisposable.registerFakeFileChooserFactory(videoFile)

    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"video360:${webmPath.systemIndependentString}\" }")
  }

  @Test
  fun testCustom360VideoEnvironment_360VideoFlagOff() {
    StudioFlags.EMBEDDED_EMULATOR_360_VIDEO_ENVIRONMENT.overrideForTest(false, testRootDisposable)
    val mp4Path = createMock360Mp4File("test_360_flag_off.mp4")
    val videoFile = mock<VirtualFile>()
    whenever(videoFile.path).thenReturn(mp4Path.toString())
    testRootDisposable.registerFakeFileChooserFactory(videoFile)

    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"videofile:${mp4Path.systemIndependentString}\" }")
  }

  @Test
  fun testCustom360VideoEnvironment_videoFlagOff() {
    StudioFlags.EMBEDDED_EMULATOR_VIDEO_ENVIRONMENT.overrideForTest(false, testRootDisposable)
    val mp4Path = createMock360Mp4File("test_video_flag_off.mp4")
    val videoFile = mock<VirtualFile>()
    whenever(videoFile.path).thenReturn(mp4Path.toString())
    testRootDisposable.registerFakeFileChooserFactory(videoFile)

    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"imagefile:${mp4Path.systemIndependentString}\" }")
  }

  @Test
  fun testRecentCustom360VideoEnvironment_360VideoFlagOff() {
    StudioFlags.EMBEDDED_EMULATOR_360_VIDEO_ENVIRONMENT.overrideForTest(false, testRootDisposable)
    val mp4Path = createMock360Mp4File("recent_360_flag_off.mp4")
    PropertiesComponent.getInstance().setValue("EmulatorEnvironmentAction.recentFiles", "")

    val action = EmulatorEnvironmentAction.RecentCustom(mp4Path)
    assertThat(action.templatePresentation.text).isEqualTo("<html>${mp4Path.fileName}&ensp;<font color=\"gray\">video</font></html>")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"videofile:${mp4Path.systemIndependentString}\" }")
  }

  @Test
  fun testRecentCustom360VideoEnvironment() {
    val mp4Path = createMock360Mp4File("recent_360.mp4")
    PropertiesComponent.getInstance().setValue("EmulatorEnvironmentAction.recentFiles", "")

    val action = EmulatorEnvironmentAction.RecentCustom(mp4Path)
    assertThat(action.templatePresentation.text).isEqualTo("<html>${mp4Path.fileName}&ensp;<font color=\"gray\">360° video</font></html>")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"video360:${mp4Path.systemIndependentString}\" }")

    val event = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) { Toggleable.isSelected(updateAndGetActionPresentation(action, event)) }

    // Active 360 video environment should be excluded from Recent Custom Environments submenu.
    val recentGroup = EmulatorRecentEnvironmentsActionGroup()
    assertThat(recentGroup.getChildren(event)).isEmpty()
  }

  private fun createMock360Mp4File(fileName: String): Path {
    fun box(type: String, vararg children: ByteArray): ByteArray {
      val payloadSize = children.sumOf { it.size }
      val buf = ByteBuffer.allocate(8 + payloadSize).order(ByteOrder.BIG_ENDIAN)
      buf.putInt(8 + payloadSize)
      buf.put(type.toByteArray(US_ASCII))
      for (child in children) {
        buf.put(child)
      }
      return buf.array()
    }
    val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(US_ASCII))
    val hdlr =
      box("hdlr", ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(0).putInt(0).put("vide".toByteArray(US_ASCII)).array())
    val sv3d = box("sv3d", box("proj", box("equi", ByteArray(20))))
    val avc1 = box("avc1", ByteArray(78), sv3d)
    val stsd = box("stsd", ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(0).putInt(1).array(), avc1)
    val moov = box("moov", box("trak", box("mdia", hdlr, box("minf", box("stbl", stsd)))))
    val file = tempDirRule.newPath(fileName)
    Files.write(file, ftyp + moov)
    return file
  }

  private fun createMock360WebmFile(fileName: String): Path {
    fun ebml(id: ByteArray, vararg payloads: ByteArray): ByteArray {
      val size = payloads.sumOf { it.size }
      val out = ByteArrayOutputStream()
      out.write(id)
      out.write(0x80 or size)
      for (p in payloads) out.write(p)
      return out.toByteArray()
    }
    val header =
      ebml(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), ebml(byteArrayOf(0x42, 0x82.toByte()), "webm".toByteArray(US_ASCII)))
    val projType = ebml(byteArrayOf(0x76, 0x71), byteArrayOf(1))
    val proj = ebml(byteArrayOf(0x76, 0x70), projType)
    val video = ebml(byteArrayOf(0xE0.toByte()), proj)
    val track = ebml(byteArrayOf(0xAE.toByte()), video)
    val tracks = ebml(byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B), track)
    val segment = ebml(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), tracks)
    val file = tempDirRule.newPath(fileName)
    Files.write(file, header + segment)
    return file
  }

  @Test
  fun testRecentEnvironmentsExcludesVideoWhenFlagOff() {
    val file1 = tempDirRule.newPath("file1.png")
    val file2 = tempDirRule.newPath("file2.mp4")
    Files.createFile(file1)
    Files.createFile(file2)

    val properties = PropertiesComponent.getInstance()
    properties.setValue("EmulatorEnvironmentAction.recentFiles", "")
    EmulatorEnvironmentAction.addRecentFile(file1.toString())
    EmulatorEnvironmentAction.addRecentFile(file2.toString())

    val group = EmulatorRecentEnvironmentsActionGroup()
    val event = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)

    // With flag ON, both should be shown
    StudioFlags.EMBEDDED_EMULATOR_VIDEO_ENVIRONMENT.overrideForTest(true, testRootDisposable)
    var children = group.getChildren(event)
    assertThat(children.size).isEqualTo(2)

    // With flag OFF, file2 (.mp4) should be filtered out
    StudioFlags.EMBEDDED_EMULATOR_VIDEO_ENVIRONMENT.overrideForTest(false, testRootDisposable)
    children = group.getChildren(event)
    assertThat(children.size).isEqualTo(1)
    assertThat(children[0].templatePresentation.text).isEqualTo("<html>${file1.fileName}&ensp;<font color=\"gray\">photo</font></html>")
  }

  @Test
  fun testRecentEnvironmentsExcludes3dWhenFlagOff() {
    val file1 = tempDirRule.newPath("file1.png")
    val file2 = tempDirRule.newPath("file2.obj")
    Files.createFile(file1)
    Files.createFile(file2)

    val properties = PropertiesComponent.getInstance()
    properties.setValue("EmulatorEnvironmentAction.recentFiles", "")
    EmulatorEnvironmentAction.addRecentFile(file1.toString())
    EmulatorEnvironmentAction.addRecentFile(file2.toString())

    val group = EmulatorRecentEnvironmentsActionGroup()
    val event = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)

    // With flag ON, both should be shown
    StudioFlags.EMBEDDED_EMULATOR_3D_SCENE_ENVIRONMENT.overrideForTest(true, testRootDisposable)
    var children = group.getChildren(event)
    assertThat(children.size).isEqualTo(2)

    // With flag OFF, file2 (.obj) should be filtered out
    StudioFlags.EMBEDDED_EMULATOR_3D_SCENE_ENVIRONMENT.overrideForTest(false, testRootDisposable)
    children = group.getChildren(event)
    assertThat(children.size).isEqualTo(1)
    assertThat(children[0].templatePresentation.text).isEqualTo("<html>${file1.fileName}&ensp;<font color=\"gray\">photo</font></html>")
  }

  @Test
  fun testActionGroupIncludesRecentFiles() {
    val dir = Files.createDirectories(tempDirRule.newPath())
    val file1 = dir.resolve("file1.png")
    Files.createFile(file1)
    val file2 = dir.resolve("file2.png")
    Files.createFile(file2)
    // Setup recent files
    val properties = PropertiesComponent.getInstance()
    properties.setValue("EmulatorEnvironmentAction.recentFiles", "${file1.toAbsolutePath()}\n${file2.toAbsolutePath()}")

    val group = ActionManager.getInstance().getAction("android.emulator.environments") as EmulatorEnvironmentActionGroup
    val children = group.getChildren(null)

    // Expect original children + Separators + Recent Environments submenu
    assertThat(children.size).isEqualTo(10)
    assertThat(children[4]).isInstanceOf(Separator::class.java)
    val recentGroup = children[6] as DefaultActionGroup
    assertThat(children[8]).isInstanceOf(Separator::class.java)
    assertThat(recentGroup.templatePresentation.text).isEqualTo("Recent Custom Environments")
    val recentChildren = recentGroup.getChildren(null)
    assertThat(recentChildren.size).isEqualTo(2)
    assertThat((recentChildren[0] as EmulatorEnvironmentAction.RecentCustom).filePath).isEqualTo(file1.toAbsolutePath())
    assertThat((recentChildren[1] as EmulatorEnvironmentAction.RecentCustom).filePath).isEqualTo(file2.toAbsolutePath())
  }

  @Test
  fun testRecentCustom360ImageCandidate_userConfirms() {
    val w = 200
    val h = 100
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val v = (128 + 127 * sin(2 * PI * x / w)).toInt().coerceIn(0, 255)
        val color = (v shl 16) or (v shl 8) or v
        img.setRGB(x, y, color)
      }
    }
    val tempFile = tempDirRule.newPath("equirectangular_candidate.png")
    ImageIO.write(img, "png", tempFile.toFile())

    TestDialogManager.setTestDialog(TestDialog.YES, testRootDisposable)
    val action = EmulatorEnvironmentAction.RecentCustom(tempFile)
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"image360:${tempFile.systemIndependentString}\" }")
  }

  @Test
  fun testRecentCustom360ImageCandidate_userDeclines() {
    val w = 200
    val h = 100
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val v = (128 + 127 * sin(2 * PI * x / w)).toInt().coerceIn(0, 255)
        val color = (v shl 16) or (v shl 8) or v
        img.setRGB(x, y, color)
      }
    }
    val tempFile = tempDirRule.newPath("equirectangular_candidate.png")
    ImageIO.write(img, "png", tempFile.toFile())

    TestDialogManager.setTestDialog(TestDialog.NO, testRootDisposable)
    val action = EmulatorEnvironmentAction.RecentCustom(tempFile)
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"imagefile:${tempFile.systemIndependentString}\" }")
  }

  @Test
  fun testRecentEnvironmentsExcludesCurrent() {
    val file1 = tempDirRule.newPath("file1.png")
    val file2 = tempDirRule.newPath("file2.png")
    Files.createFile(file1)
    Files.createFile(file2)

    val properties = PropertiesComponent.getInstance()
    properties.setValue("EmulatorEnvironmentAction.recentFiles", "")
    EmulatorEnvironmentAction.addRecentFile(file1.toString())
    EmulatorEnvironmentAction.addRecentFile(file2.toString())

    val group = EmulatorRecentEnvironmentsActionGroup()
    val event = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)

    // 1. Initially (no environment), both should be shown
    assertThat(updateAndGetActionPresentation(group, event).isVisible).isTrue()
    var children = group.getChildren(event)
    assertThat(children.size).isEqualTo(2)
    assertThat(children[0].templatePresentation.text).isEqualTo("<html>${file2.fileName}&ensp;<font color=\"gray\">photo</font></html>")
    assertThat(children[1].templatePresentation.text).isEqualTo("<html>${file1.fileName}&ensp;<font color=\"gray\">photo</font></html>")

    // 2. Set active environment to file2
    val recentAction2 = EmulatorEnvironmentAction.RecentCustom(file2)
    executeAction(recentAction2, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) {
      recentAction2.doesMatchEnvironment(
        EnvironmentTracker.forEmulator(emulatorController)?.environment ?: Environment.getDefaultInstance()
      )
    }

    // Now file2 should be filtered out
    assertThat(updateAndGetActionPresentation(group, event).isVisible).isTrue()
    children = group.getChildren(event)
    assertThat(children.size).isEqualTo(1)
    assertThat(children[0].templatePresentation.text).isEqualTo("<html>${file1.fileName}&ensp;<font color=\"gray\">photo</font></html>")

    // 3. Set active environment to file1
    val recentAction1 = EmulatorEnvironmentAction.RecentCustom(file1)
    executeAction(recentAction1, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) {
      recentAction1.doesMatchEnvironment(
        EnvironmentTracker.forEmulator(emulatorController)?.environment ?: Environment.getDefaultInstance()
      )
    }

    // Now file1 should be filtered out, showing only file2
    assertThat(updateAndGetActionPresentation(group, event).isVisible).isTrue()
    children = group.getChildren(event)
    assertThat(children.size).isEqualTo(1)
    assertThat(children[0].templatePresentation.text).isEqualTo("<html>${file2.fileName}&ensp;<font color=\"gray\">photo</font></html>")

    // 4. Set environment to empty (None)
    val action = ActionManager.getInstance().getAction("android.emulator.environment.darkness")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) {
      val env = EnvironmentTracker.forEmulator(emulatorController)?.environment ?: Environment.getDefaultInstance()
      env.environmentMap["scene.mode"] == "color:#000000"
    }

    // Both should be shown again
    assertThat(updateAndGetActionPresentation(group, event).isVisible).isTrue()
    children = group.getChildren(event)
    assertThat(children.size).isEqualTo(2)

    // 5. Test with only one recent file
    properties.setValue("EmulatorEnvironmentAction.recentFiles", "")
    EmulatorEnvironmentAction.addRecentFile(file1.toString())

    // Active environment is empty, so file1 is visible
    assertThat(updateAndGetActionPresentation(group, event).isVisible).isTrue()
    children = group.getChildren(event)
    assertThat(children.size).isEqualTo(1)

    // Select file1
    executeAction(recentAction1, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) {
      recentAction1.doesMatchEnvironment(
        EnvironmentTracker.forEmulator(emulatorController)?.environment ?: Environment.getDefaultInstance()
      )
    }

    // Since file1 is active, and it's the only recent file, the group should be hidden.
    assertThat(updateAndGetActionPresentation(group, event).isVisible).isFalse()
    children = group.getChildren(event)
    assertThat(children.size).isEqualTo(0)
  }

  @Test
  fun testActionGroupDoesNotIncludeTitleWhenEmpty() {
    // Clear recent files
    val properties = PropertiesComponent.getInstance()
    properties.setValue("EmulatorEnvironmentAction.recentFiles", null)

    val group = ActionManager.getInstance().getAction("android.emulator.environments") as EmulatorEnvironmentActionGroup
    val children = group.getChildren(null)
    val recentGroup = children[6] as DefaultActionGroup

    val event = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)
    assertThat(updateAndGetActionPresentation(recentGroup, event).isVisible).isFalse()
  }

  @Test
  fun testActionGroupFiltersNonExistentFiles() {
    val dir = Files.createDirectories(tempDirRule.newPath())
    val file1 = dir.resolve("file1.png")
    Files.createFile(file1)
    val file2Path = "/tmp/non_existent_file.png"
    // Setup recent files
    val properties = PropertiesComponent.getInstance()
    properties.setValue("EmulatorEnvironmentAction.recentFiles", "${file1.toAbsolutePath()}\n$file2Path")

    val group = ActionManager.getInstance().getAction("android.emulator.environments") as EmulatorEnvironmentActionGroup
    val children = group.getChildren(null)

    // Expect original children + Separators + Recent Environments submenu containing 1 file (file1)
    assertThat(children.size).isEqualTo(10)
    assertThat(children[4]).isInstanceOf(Separator::class.java)
    val recentGroup = children[6] as DefaultActionGroup
    assertThat(children[8]).isInstanceOf(Separator::class.java)
    assertThat(recentGroup.templatePresentation.text).isEqualTo("Recent Custom Environments")
    val recentChildren = recentGroup.getChildren(null)
    assertThat(recentChildren.size).isEqualTo(1)
    assertThat((recentChildren[0] as EmulatorEnvironmentAction.RecentCustom).filePath).isEqualTo(file1.toAbsolutePath())
  }

  @Test
  fun testCameraEnvironment() {
    val action = EmulatorEnvironmentAction.Camera("FaceTime HD Camera", "camera_id_123")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request)).isEqualTo("environment { key: \"scene.mode\" value: \"webcam:camera_id_123\" }")
  }

  @Test
  fun testActionGroupIncludesCameras() {
    val camera1 = Camera.newBuilder().setDisplayName("Camera 1").setId("id1").build()
    val camera2 = Camera.newBuilder().setDisplayName("Camera 2").setId("id2").build()
    emulator.hostCameras = CameraList.newBuilder().addCameras(camera1).addCameras(camera2).build()

    val group = ActionManager.getInstance().getAction("android.emulator.environments") as EmulatorEnvironmentActionGroup
    val event = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)
    val children = group.getChildren(event)

    // Expect original children (6) + Separators + Cameras submenu
    assertThat(children.size).isEqualTo(10)
    assertThat(children[4]).isInstanceOf(Separator::class.java)
    assertThat(children[8]).isInstanceOf(Separator::class.java)
    val camerasGroup = children[7] as DefaultActionGroup
    assertThat(camerasGroup.templatePresentation.text).isEqualTo("Camera")
    val cameraChildren = camerasGroup.getChildren(event)
    assertThat(cameraChildren.size).isEqualTo(2)

    val cameraAction1 = cameraChildren[0] as EmulatorEnvironmentAction.Camera
    assertThat(cameraAction1.cameraName).isEqualTo("Camera 1")
    assertThat(cameraAction1.cameraId).isEqualTo("id1")

    val cameraAction2 = cameraChildren[1] as EmulatorEnvironmentAction.Camera
    assertThat(cameraAction2.cameraName).isEqualTo("Camera 2")
    assertThat(cameraAction2.cameraId).isEqualTo("id2")
  }

  @Test
  fun testActionGroupDoesNotIncludeCamerasWhenEmpty() {
    emulator.hostCameras = CameraList.getDefaultInstance()

    val group = ActionManager.getInstance().getAction("android.emulator.environments") as EmulatorEnvironmentActionGroup
    val event = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)
    val children = group.getChildren(event)
    val camerasGroup = children[7] as DefaultActionGroup

    assertThat(updateAndGetActionPresentation(camerasGroup, event).isVisible).isFalse()
  }

  @Test
  fun testBuiltInImageActionPresentationText() {
    val group = ActionManager.getInstance().getAction("android.emulator.environments") as EmulatorEnvironmentActionGroup
    val event = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)
    val children = group.getChildren(event)

    val indoorAction = children.firstOrNull() as? EmulatorEnvironmentAction.BuiltInImage
    assertThat(indoorAction).isNotNull()
    assertThat(indoorAction!!.environmentPath.fileName.toString()).isEqualTo("study-360.jpg")
    assertThat(indoorAction.templatePresentation.text).isEqualTo("<html>Study&ensp;<font color=\"gray\">photo</font></html>")
    assertThat(indoorAction.templatePresentation.description).isEqualTo("Select Study environment")
  }

  @Test
  fun testCustom360Environment() {
    val xml =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" GPano:ProjectionType="equirectangular" />
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val jpeg = createMockJpegWithXml(xml)

    val imageFile = mock<VirtualFile>()
    whenever(imageFile.path).thenReturn(jpeg.toString())
    testRootDisposable.registerFakeFileChooserFactory(imageFile)

    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"image360:${jpeg.systemIndependentString}\" }")
  }

  @Test
  fun testRecentCustom360Environment() {
    val xml =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" GPano:ProjectionType="equirectangular" />
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val jpeg = createMockJpegWithXml(xml)

    val action = EmulatorEnvironmentAction.RecentCustom(jpeg)
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"image360:${jpeg.systemIndependentString}\" }")
  }

  private fun createMockJpegWithXml(xml: String): Path {
    val xmlBytes = xml.toByteArray()
    val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
    val payloadLength = xmpHeader.size + xmlBytes.size
    val totalLength = payloadLength + 2

    val jpeg = ByteArray(2 + 4 + payloadLength + 2)
    jpeg[0] = 0xFF.toByte()
    jpeg[1] = 0xD8.toByte() // SOI

    jpeg[2] = 0xFF.toByte()
    jpeg[3] = 0xE1.toByte() // APP1
    jpeg[4] = ((totalLength shr 8) and 0xFF).toByte()
    jpeg[5] = (totalLength and 0xFF).toByte()

    System.arraycopy(xmpHeader, 0, jpeg, 6, xmpHeader.size)
    System.arraycopy(xmlBytes, 0, jpeg, 6 + xmpHeader.size, xmlBytes.size)

    val eoiIdx = 6 + payloadLength
    jpeg[eoiIdx] = 0xFF.toByte()
    jpeg[eoiIdx + 1] = 0xD9.toByte() // EOI

    val file = tempDirRule.newPath("test_image.jpg")
    Files.write(file, jpeg)
    return file
  }

  @Test
  fun testCustomPng360Environment() {
    val xml =
      """
      <x:xmpmeta xmlns:x="adobe:ns:meta/">
       <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" GPano:ProjectionType="equirectangular" />
       </rdf:RDF>
      </x:xmpmeta>
      """
        .trimIndent()
    val pngPath = createMockPngWithXml(xml)

    val imageFile = mock<VirtualFile>()
    whenever(imageFile.path).thenReturn(pngPath.toString())
    testRootDisposable.registerFakeFileChooserFactory(imageFile)

    val action = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"image360:${pngPath.systemIndependentString}\" }")
  }

  private fun createMockPngWithXml(xml: String, compressed: Boolean = false): Path {
    val stream = ByteArrayOutputStream()
    // Signature
    stream.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

    // IHDR
    writePngChunk(stream, "IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0))

    // iTXt
    val chunkStream = ByteArrayOutputStream()
    chunkStream.write("XML:com.adobe.xmp\u0000".toByteArray())
    if (compressed) {
      chunkStream.write(1) // compression flag
      chunkStream.write(0) // compression method
      chunkStream.write(0) // language tag null
      chunkStream.write(0) // translated keyword null
      val deflatedBytes = ByteArray(1024)
      val compressedStream = ByteArrayOutputStream()
      Deflater().use { deflater ->
        deflater.setInput(xml.toByteArray())
        deflater.finish()
        while (!deflater.finished()) {
          val count = deflater.deflate(deflatedBytes)
          compressedStream.write(deflatedBytes, 0, count)
        }
      }
      chunkStream.write(compressedStream.toByteArray())
    } else {
      chunkStream.write(0) // compression flag
      chunkStream.write(0) // compression method
      chunkStream.write(0) // language tag null
      chunkStream.write(0) // translated keyword null
      chunkStream.write(xml.toByteArray())
    }
    writePngChunk(stream, "iTXt", chunkStream.toByteArray())

    // IEND
    writePngChunk(stream, "IEND", ByteArray(0))

    val file = tempDirRule.newPath("test_image.png")
    Files.write(file, stream.toByteArray())
    return file
  }

  private fun writePngChunk(stream: ByteArrayOutputStream, type: String, data: ByteArray) {
    val length = data.size
    stream.write((length shr 24) and 0xFF)
    stream.write((length shr 16) and 0xFF)
    stream.write((length shr 8) and 0xFF)
    stream.write(length and 0xFF)
    stream.write(type.toByteArray(US_ASCII))
    stream.write(data)
    // CRC (dummy)
    stream.write(byteArrayOf(0, 0, 0, 0))
  }

  @Test
  fun testEnvironmentToggleState() {
    val emptyAction = ActionManager.getInstance().getAction("android.emulator.environment.darkness")
    val emptyEvent = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)

    val cameraAction = EmulatorEnvironmentAction.Camera("FaceTime HD Camera", "camera_id_123")
    val cameraEvent = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)

    val builtInAction =
      EmulatorEnvironmentAction.BuiltInImage(Path.of("/Sdk/environments/outdoor-nature-bright.jpg"), "Outdoor Nature Bright")
    val builtInEvent = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)

    val recentAction = EmulatorEnvironmentAction.RecentCustom(Path.of("/tmp/recent_image.png"))
    val recentEvent = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)

    assertThat(Toggleable.isSelected(updateAndGetActionPresentation(cameraAction, cameraEvent))).isFalse()

    // 2. Set to camera environment
    executeAction(cameraAction, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) { Toggleable.isSelected(updateAndGetActionPresentation(cameraAction, cameraEvent)) }
    assertThat(Toggleable.isSelected(updateAndGetActionPresentation(emptyAction, emptyEvent))).isFalse()

    // 3. Set to built-in image environment
    executeAction(builtInAction, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) { Toggleable.isSelected(updateAndGetActionPresentation(builtInAction, builtInEvent)) }
    assertThat(Toggleable.isSelected(updateAndGetActionPresentation(cameraAction, cameraEvent))).isFalse()

    // 4. Set to recent custom environment
    executeAction(recentAction, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) { Toggleable.isSelected(updateAndGetActionPresentation(recentAction, recentEvent)) }
    assertThat(Toggleable.isSelected(updateAndGetActionPresentation(builtInAction, builtInEvent))).isFalse()
  }

  @Test
  fun testCustomEnvironmentToggleState() {
    val customAction = ActionManager.getInstance().getAction("android.emulator.environment.custom")
    val customEvent = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)

    val builtInAction =
      EmulatorEnvironmentAction.BuiltInImage(Path.of("/Sdk/environments/outdoor-nature-bright.jpg"), "Outdoor Nature Bright")
    val builtInEvent = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)

    // Setup mock EnvironmentsUpdater for custom path checking
    val environmentsUpdater = mock<EnvironmentsUpdater>()
    val list = listOf(EnvironmentImage(Path.of("/Sdk/environments/outdoor-nature-bright.jpg"), "Outdoor Nature Bright", false))
    runBlocking { whenever(environmentsUpdater.getEnvironments()).thenReturn(list) }
    ApplicationManager.getApplication().replaceService(EnvironmentsUpdater::class.java, environmentsUpdater, testRootDisposable)

    // 1. Set environment to built-in image (outdoor-nature-bright)
    executeAction(builtInAction, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) { Toggleable.isSelected(updateAndGetActionPresentation(builtInAction, builtInEvent)) }
    // Check that customAction is NOT selected
    assertThat(Toggleable.isSelected(updateAndGetActionPresentation(customAction, customEvent))).isFalse()

    // 2. Set environment to custom image
    val customImagePath = "/tmp/my_custom_image.jpg"
    val recentAction = EmulatorEnvironmentAction.RecentCustom(Path.of(customImagePath))
    val recentEvent = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)
    executeAction(recentAction, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) { Toggleable.isSelected(updateAndGetActionPresentation(recentAction, recentEvent)) }

    // Now builtInAction should be deselected
    assertThat(Toggleable.isSelected(updateAndGetActionPresentation(builtInAction, builtInEvent))).isFalse()

    // And customAction should be SELECTED!
    assertThat(Toggleable.isSelected(updateAndGetActionPresentation(customAction, customEvent))).isTrue()

    // 3. Set environment to custom 3D scene
    val custom3dPath = "/tmp/my_custom_scene.obj"
    val recentAction3d = EmulatorEnvironmentAction.RecentCustom(Path.of(custom3dPath))
    val recentEvent3d = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)
    executeAction(recentAction3d, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) { Toggleable.isSelected(updateAndGetActionPresentation(recentAction3d, recentEvent3d)) }

    // customAction should STILL be SELECTED!
    assertThat(Toggleable.isSelected(updateAndGetActionPresentation(customAction, customEvent))).isTrue()
  }

  @Test
  fun testCameraActionGroupToggleState() {
    val camera1 = Camera.newBuilder().setDisplayName("Camera 1").setId("id1").build()
    emulator.hostCameras = CameraList.newBuilder().addCameras(camera1).build()

    val group = EmulatorCameraActionGroup()
    val event = createTestEvent(project = projectRule.project, extra = dataSnapshotProvider)

    // 1. Initial state (empty environment in emulator)
    emulator.environment.clear()
    assertThat(Toggleable.isSelected(updateAndGetActionPresentation(group, event))).isFalse()

    // 2. Set to camera environment
    val cameraAction = EmulatorEnvironmentAction.Camera("Camera 1", "id1")
    executeAction(cameraAction, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) { Toggleable.isSelected(updateAndGetActionPresentation(group, event)) }

    // 3. Set to built-in image environment
    val builtInAction =
      EmulatorEnvironmentAction.BuiltInImage(Path.of("/Sdk/environments/outdoor-nature-bright.jpg"), "Outdoor Nature Bright")
    executeAction(builtInAction, project = projectRule.project, extra = dataSnapshotProvider)
    waitForCondition(5.seconds) { !Toggleable.isSelected(updateAndGetActionPresentation(group, event)) }
  }

  @Test
  fun testUpdateWhenDisconnected() {
    val disconnectedController = mock<EmulatorController>()
    whenever(disconnectedController.connectionState).thenReturn(EmulatorController.ConnectionState.DISCONNECTED)
    whenever(disconnectedController.getUserData<Any>(any())).thenReturn(null)
    whenever(disconnectedController.putUserDataIfAbsent<Any>(any(), any())).thenAnswer { it.arguments[1] }
    val mockConfig = mock<EmulatorConfiguration>()
    whenever(mockConfig.deviceType).thenReturn(DeviceType.AI_GLASSES)
    whenever(disconnectedController.emulatorConfig).thenReturn(mockConfig)

    val action = ActionManager.getInstance().getAction("android.emulator.environment.darkness")
    val event =
      createTestEvent(
        project = projectRule.project,
        extra = DataSnapshotProvider { sink -> sink[EMULATOR_CONTROLLER_KEY] = disconnectedController },
      )

    // This should not throw an exception!
    assertThat(updateAndGetActionPresentation(action, event).isEnabled).isFalse()
  }

  @Test
  fun testRecentFileMetadataStorage() {
    val tempFile = tempDirRule.newPath("test_metadata.png")
    Files.write(tempFile, byteArrayOf(1, 2, 3))
    val fileTime = Files.getLastModifiedTime(tempFile).toMillis()
    val fileSize = Files.size(tempFile)

    EmulatorEnvironmentAction.addRecentFile(RecentFile(tempFile.systemIndependentString, fileTime, fileSize, "image360"))

    val recentFiles = EmulatorEnvironmentAction.getRecentFiles()
    assertThat(recentFiles).isNotEmpty()
    val entry = recentFiles.first()
    assertThat(entry.path).isEqualTo(tempFile.systemIndependentString)
    assertThat(entry.timestamp).isEqualTo(fileTime)
    assertThat(entry.size).isEqualTo(fileSize)
    assertThat(entry.mode).isEqualTo("image360")
  }

  @Test
  fun testRecentFileDeserializationOldFormat() {
    val properties = PropertiesComponent.getInstance()
    properties.setValue("EmulatorEnvironmentAction.recentFiles", "/tmp/old_file.png")

    val recentFiles = EmulatorEnvironmentAction.getRecentFiles()
    assertThat(recentFiles).hasSize(1)
    val entry = recentFiles.first()
    assertThat(entry.path).isEqualTo("/tmp/old_file.png")
    assertThat(entry.timestamp).isEqualTo(0L)
    assertThat(entry.size).isEqualTo(0L)
    assertThat(entry.mode).isEmpty()
  }

  @Test
  fun testRecentCustom360ImageCandidate_reusePrevious360Mode_withoutAskingConfirmation() {
    val w = 200
    val h = 100
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val v = (128 + 127 * sin(2 * PI * x / w)).toInt().coerceIn(0, 255)
        val color = (v shl 16) or (v shl 8) or v
        img.setRGB(x, y, color)
      }
    }
    val tempFile = tempDirRule.newPath("equirectangular_reuse360.png")
    ImageIO.write(img, "png", tempFile.toFile())

    val timestamp = Files.getLastModifiedTime(tempFile).toMillis()
    val size = Files.size(tempFile)

    // Store in recent files as image360
    EmulatorEnvironmentAction.addRecentFile(RecentFile(tempFile.systemIndependentString, timestamp, size, "image360"))

    // Set dialog to throw exception if called (dialog should NOT be called!)
    TestDialogManager.setTestDialog({ message -> error("Dialog should not be shown when reusing mode: $message") }, testRootDisposable)
    val action = EmulatorEnvironmentAction.RecentCustom(tempFile)
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"image360:${tempFile.systemIndependentString}\" }")
  }

  @Test
  fun testRecentCustom360ImageCandidate_reusePreviousFlatMode_withoutAskingConfirmation() {
    val w = 200
    val h = 100
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val v = (128 + 127 * sin(2 * PI * x / w)).toInt().coerceIn(0, 255)
        val color = (v shl 16) or (v shl 8) or v
        img.setRGB(x, y, color)
      }
    }
    val tempFile = tempDirRule.newPath("equirectangular_reuseFlat.png")
    ImageIO.write(img, "png", tempFile.toFile())

    val timestamp = Files.getLastModifiedTime(tempFile).toMillis()
    val size = Files.size(tempFile)

    // Store in recent files as imagefile
    EmulatorEnvironmentAction.addRecentFile(RecentFile(tempFile.systemIndependentString, timestamp, size, "imagefile"))

    // Set dialog to throw exception if called (dialog should NOT be called!)
    TestDialogManager.setTestDialog({ message -> error("Dialog should not be shown when reusing mode: $message") }, testRootDisposable)
    val action = EmulatorEnvironmentAction.RecentCustom(tempFile)
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"imagefile:${tempFile.systemIndependentString}\" }")
  }

  @Test
  fun testRecentCustom360ImageCandidate_modifiedFile_asksConfirmation() {
    val w = 200
    val h = 100
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val v = (128 + 127 * sin(2 * PI * x / w)).toInt().coerceIn(0, 255)
        val color = (v shl 16) or (v shl 8) or v
        img.setRGB(x, y, color)
      }
    }
    val tempFile = tempDirRule.newPath("equirectangular_modified.png")
    ImageIO.write(img, "png", tempFile.toFile())

    val size = Files.size(tempFile)

    // Store in recent files with an OLD/DIFFERENT timestamp (12345L)
    EmulatorEnvironmentAction.addRecentFile(RecentFile(tempFile.systemIndependentString, 12345L, size, "imagefile"))

    var dialogShown = false
    TestDialogManager.setTestDialog(
      {
        dialogShown = true
        0 // YES
      },
      testRootDisposable,
    )
    val action = EmulatorEnvironmentAction.RecentCustom(tempFile)
    executeAction(action, project = projectRule.project, extra = dataSnapshotProvider)

    val call = emulator.getNextGrpcCall(2.seconds)
    assertThat(call.methodName).isEqualTo("android.emulation.control.EmulatorController/setEnvironment")
    assertThat(shortDebugString(call.request))
      .isEqualTo("environment { key: \"scene.mode\" value: \"image360:${tempFile.systemIndependentString}\" }")
    assertThat(dialogShown).isTrue()
  }
}

private val Path.systemIndependentString: String
  get() = toSystemIndependentName(toString())
