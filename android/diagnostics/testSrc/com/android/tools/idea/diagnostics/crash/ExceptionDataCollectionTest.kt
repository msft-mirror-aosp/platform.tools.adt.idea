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
package com.android.tools.idea.diagnostics.crash

import com.android.tools.idea.diagnostics.ExceptionTestUtils
import com.android.tools.idea.serverflags.protos.Date
import com.android.tools.idea.serverflags.protos.ExceptionAction
import com.android.tools.idea.serverflags.protos.ExceptionConfiguration
import com.android.tools.idea.serverflags.protos.ExceptionFilter
import com.android.tools.idea.serverflags.protos.ExceptionSeverity
import com.android.tools.idea.serverflags.protos.LogFilter
import com.android.tools.idea.serverflags.protos.MessageFilter
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.awaitLogQueueProcessed
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.replaceService
import java.util.Calendar
import java.util.GregorianCalendar

internal class ExceptionDataCollectionTest : LightPlatformTestCase() {

  class ExceptionDataConfigurationMock : ExceptionDataConfiguration {
    override fun getConfigurations(): Map<String, ExceptionConfiguration> {
      val cal = GregorianCalendar()
      return mapOf(
        "test_1" to
          ExceptionConfiguration.newBuilder()
            .setExpirationDate(Date.newBuilder().setYear(cal.get(Calendar.YEAR) + 1).setMonth(12).setDay(31).build())
            .setExceptionFilter(ExceptionFilter.newBuilder().setSignature(ex1Sig).build())
            .setAction(ExceptionAction.newBuilder().setRequiresConfirmation(false).setIncludeExceptionMessage(false).build())
            .build(),
        "test_2" to
          ExceptionConfiguration.newBuilder()
            .setExpirationDate(Date.newBuilder().setYear(cal.get(Calendar.YEAR) + 1).setMonth(12).setDay(31).build())
            .setExceptionFilter(ExceptionFilter.newBuilder().setSignature(ex2Sig).build())
            .setAction(ExceptionAction.newBuilder().setRequiresConfirmation(true).setIncludeExceptionMessage(true).build())
            .build(),
        "test_3" to
          ExceptionConfiguration.newBuilder()
            .setExpirationDate(Date.newBuilder().setYear(cal.get(Calendar.YEAR) + 1).setMonth(12).setDay(31).build())
            .setExceptionFilter(ExceptionFilter.newBuilder().setSignature(ex3Sig).build())
            .setAction(
              ExceptionAction.newBuilder()
                .setRequiresConfirmation(true)
                .setIncludeExceptionMessage(true)
                .setLogFilter(
                  LogFilter.newBuilder()
                    .setMaxMessageCount(5)
                    .addMessageFilter(
                      MessageFilter.newBuilder()
                        .setSeverity(ExceptionSeverity.INFO)
                        .setLoggerCategory(exceptionDataCollectionTestLoggerName)
                    )
                )
                .build()
            )
            .build(),
      )
    }
  }

  private var fixtureDisposable =
    object : Disposable {
      override fun dispose() = Unit
    }

  lateinit var service: ExceptionDataCollection

  override fun setUp() {
    super.setUp()
    ApplicationManager.getApplication()
      .replaceService(ExceptionDataConfiguration::class.java, ExceptionDataConfigurationMock(), fixtureDisposable)
    ApplicationManager.getApplication().replaceService(ExceptionDataCollection::class.java, ExceptionDataCollection(), fixtureDisposable)
    service = ExceptionDataCollection.getInstance()
  }

  override fun tearDown() {
    Disposer.dispose(fixtureDisposable)
    service.unregisterLogAppenders()
    super.tearDown()
  }

  fun testGetExceptionUploadFields() {
    val uploadFieldsEx1 = service.getExceptionUploadFields(ex1, forceExceptionMessage = false, includeLogs = false)
    assertThat(uploadFieldsEx1.description.replace("\r\n", "\n")).isEqualTo(ex1DescriptionWithoutMessage)
    assertThat(uploadFieldsEx1.logs.size).isEqualTo(0)
    val uploadFieldsEx2 = service.getExceptionUploadFields(ex2, forceExceptionMessage = false, includeLogs = false)
    assertThat(uploadFieldsEx2.description.replace("\r\n", "\n")).isEqualTo(ex2Description)
    assertThat(uploadFieldsEx2.logs.size).isEqualTo(0)
  }

  fun testGetExceptionUploadFields_trackedExceptionMessagePreservedWhenWrapped() {
    val icce = IncompatibleClassChangeError("class A has interface B as super class")
    val wrapped = RuntimeException("wrapper exception", icce)
    val uploadFields = service.getExceptionUploadFields(wrapped, forceExceptionMessage = false, includeLogs = false)
    assertThat(uploadFields.description).contains("class A has interface B as super class")
    assertThat(uploadFields.description).doesNotContain("<elided>")
  }

  fun testGetExceptionUploadFields_trackedExceptionMessagePreservedUnwrapped() {
    val icce = IncompatibleClassChangeError("class A has interface B as super class")
    val uploadFields = service.getExceptionUploadFields(icce, forceExceptionMessage = false, includeLogs = false)
    assertThat(uploadFields.description).contains("class A has interface B as super class")
    assertThat(uploadFields.description).doesNotContain("<elided>")
  }

  fun testGetExceptionUploadFields_untrackedExceptionMessageElidedWhenWrapped() {
    val root = IllegalArgumentException("sensitive argument info")
    val wrapped = RuntimeException("wrapper exception", root)
    val uploadFields = service.getExceptionUploadFields(wrapped, forceExceptionMessage = false, includeLogs = false)
    assertThat(uploadFields.description).contains("java.lang.IllegalArgumentException: <elided>")
    assertThat(uploadFields.description).doesNotContain("sensitive argument info")
  }

  fun testGetExceptionUploadFields_untrackedExceptionMessageElidedWhenWrappedInTrackedException() {
    val root = IllegalArgumentException("sensitive argument info")
    val wrapped = java.lang.reflect.InvocationTargetException(root, "reflection error")
    val uploadFields = service.getExceptionUploadFields(wrapped, forceExceptionMessage = false, includeLogs = false)
    assertThat(uploadFields.description).contains("java.lang.IllegalArgumentException: <elided>")
    assertThat(uploadFields.description).doesNotContain("sensitive argument info")
  }

  fun testRegisteringAppenders() {
    val registeredAppenders = service.registeredAppenders
    assertThat(registeredAppenders).hasSize(1)
    assertThat(registeredAppenders[0].logger.name).isEqualTo("#com.android.tools.idea.diagnostics.crash.ExceptionDataCollectionTest")
  }

  fun testLogCollection() {
    // val log4jLogger = LogManager.getLogger(exceptionDataCollectionTestLoggerName)
    val logger = java.util.logging.LogManager.getLogManager().getLogger(exceptionDataCollectionTestLoggerName)
    with(exceptionDataCollectionTestLogger) {
      for (i in 1..5) {
        trace("trace message #$i")
        debug("debug message #$i")
        info("info message #$i")
        warn("warn message #$i")
        awaitLogQueueProcessed() // the next line is synchronous
        logger.severe("severe message #$i") // use logger directly
      }
    }
    val exceptionUploadFields = service.getExceptionUploadFields(ex3, forceExceptionMessage = false, includeLogs = true)
    var result = exceptionUploadFields.logs["test_3"]!!
    // remove time information
    result = result.replace(Regex("^\\[[ 0-9]+\\]", RegexOption.MULTILINE), "[<time>]")
    assertThat(result.trimEnd())
      .isEqualTo(
        """
        [<time>] W [sh.ExceptionDataCollectionTest] warn message #4
        [<time>] S [sh.ExceptionDataCollectionTest] severe message #4
        [<time>] I [sh.ExceptionDataCollectionTest] info message #5
        [<time>] W [sh.ExceptionDataCollectionTest] warn message #5
        [<time>] S [sh.ExceptionDataCollectionTest] severe message #5
        """
          .trimIndent()
      )
  }

  fun testCalculateSignature() {
    val sig1 = ExceptionDataCollection.calculateSignature(ex1)
    assertThat(sig1).isEqualTo(ex1Sig)

    val sig2 = ExceptionDataCollection.calculateSignature(ex2)
    assertThat(sig2).isEqualTo(ex2Sig)

    val sig3 = ExceptionDataCollection.calculateSignature(ex3)
    assertThat(sig3).isEqualTo(ex3Sig)

    val sig4 = ExceptionDataCollection.calculateSignature(ex4)
    assertThat(sig4).isEqualTo(ex4Sig)

    val sig5 = ExceptionDataCollection.calculateSignature(ex5)
    assertThat(sig5).isEqualTo(ex5Sig)
  }

  fun testCalculateDynamicSignature() {
    val sig1 = ExceptionDataCollection.calculateDynamicSignature(ex1)
    assertThat(sig1).isEqualTo(ex1Sig)

    val sig2 = ExceptionDataCollection.calculateDynamicSignature(ex2)
    assertThat(sig2).isEqualTo(ex2DynamicSig)

    val sig3 = ExceptionDataCollection.calculateDynamicSignature(ex3)
    assertThat(sig3).isEqualTo(ex3Sig)

    val sig4 = ExceptionDataCollection.calculateDynamicSignature(ex4)
    assertThat(sig4).isEqualTo(ex4DynamicSig)

    val sig5 = ExceptionDataCollection.calculateDynamicSignature(ex5)
    assertThat(sig5).isEqualTo(ex5DynamicSig)

    val sigStudioOwnedFirst = ExceptionDataCollection.calculateDynamicSignature(exStudioOwnedFirst)
    assertThat(sigStudioOwnedFirst).isEqualTo(exStudioOwnedFirstSig)

    val sigStudioOwnedDeep = ExceptionDataCollection.calculateDynamicSignature(exStudioOwnedDeep)
    assertThat(sigStudioOwnedDeep).isEqualTo(exStudioOwnedDeepSig)

    val sigNoStudioOwned = ExceptionDataCollection.calculateDynamicSignature(exNoStudioOwned)
    assertThat(sigNoStudioOwned).isEqualTo(exNoStudioOwnedSig)

    val sigStudioOwnedAtLine10 = ExceptionDataCollection.calculateDynamicSignature(exStudioOwnedAtLine10)
    assertThat(sigStudioOwnedAtLine10).isEqualTo(exStudioOwnedAtLine10Sig)

    val sig17FramesNoStudioOwned = ExceptionDataCollection.calculateDynamicSignature(ex17FramesNoStudioOwned)
    assertThat(sig17FramesNoStudioOwned).isEqualTo(ex17FramesNoStudioOwnedSig)
  }

  fun testCalculateSignatueMissingStack() {
    val sig3 = ExceptionDataCollection.calculateSignature(exNoStack)
    assertThat(sig3).isEqualTo(exNoStackSig)
    val dynamicSig = ExceptionDataCollection.calculateDynamicSignature(exNoStack)
    assertThat(dynamicSig).isEqualTo(exNoStackSig)
  }

  fun testGetDescription() {
    val messageRemoved = service.getDescription(ex1, stripMessage = true, includeFullStack = true)
    assertThat(messageRemoved.replace("\r\n", "\n")).isEqualTo(ex1DescriptionWithoutMessage)
    val withMessage = service.getDescription(ex1, stripMessage = false, includeFullStack = true)
    assertThat(withMessage.replace("\r\n", "\n")).isEqualTo(ex1Description)
  }

  fun testRequiresConfirmation() {
    assertThat(service.requiresConfirmation(ex1)).isFalse()
    assertThat(service.requiresConfirmation(ex2)).isTrue()
  }

  companion object {
    val exceptionDataCollectionTestLoggerName = "#" + ExceptionDataCollectionTest::class.java.name

    val exceptionDataCollectionTestLogger = Logger.getInstance(exceptionDataCollectionTestLoggerName)

    const val ex1Description =
      "java.lang.Exception: exception text 123456789\n" +
        "\tat com.intellij.diagnostic.DropAnErrorAction.actionPerformed(dropErrorActions.kt:25)\n"
    const val ex1DescriptionWithoutMessage =
      "java.lang.Exception: <elided>\n" + "\tat com.intellij.diagnostic.DropAnErrorAction.actionPerformed(dropErrorActions.kt:25)\n"
    val ex1 = ExceptionTestUtils.createExceptionFromDesc(ex1Description)
    const val ex1Sig = "java.lang.Exception at com.intellij.diagnostic.DropAnErrorAction.actionPerformed-19432f88"

    const val ex2Description =
      "java.lang.Exception: random exception text -7184021398473263170\n" +
        "\tat com.intellij.diagnostic.DropAnErrorAction.actionPerformed(dropErrorActions.kt:25)\n" +
        "\tat com.intellij.openapi.actionSystem.ex.ActionUtil.lambda\$performActionDumbAware\$5(ActionUtil.java:273)\n" +
        "\tat com.intellij.util.SlowOperations.lambda\$allowSlowOperations\$0(SlowOperations.java:77)\n" +
        "\tat com.intellij.util.SlowOperations.allowSlowOperations(SlowOperations.java:68)\n" +
        "\tat com.intellij.util.SlowOperations.allowSlowOperations(SlowOperations.java:76)\n" +
        "\tat com.intellij.openapi.actionSystem.ex.ActionUtil.performActionDumbAware(ActionUtil.java:273)\n" +
        "\tat com.intellij.ide.actions.GotoActionAction.lambda\$performAction\$2(GotoActionAction.java:108)\n" +
        "\tat java.desktop/java.awt.EventDispatchThread.run(EventDispatchThread.java:90)\n"
    val ex2 = ExceptionTestUtils.createExceptionFromDesc(ex2Description)
    const val ex2Sig = "java.lang.Exception at com.intellij.diagnostic.DropAnErrorAction.actionPerformed-2f166b9f"
    const val ex2DynamicSig = "java.lang.Exception at com.intellij.diagnostic.DropAnErrorAction.actionPerformed-2f166b9f"

    const val exNoStackDescription = "java.lang.Exception: sample message\n"
    val exNoStack = ExceptionTestUtils.createExceptionFromDesc(exNoStackDescription)
    const val exNoStackSig = "MissingCrashedThreadStack"

    const val ex3Description = "java.lang.Exception: sample message\n" + "\tat com.android.SomeClass.someMethod(FileName.java:100)\n"

    val ex3 = ExceptionTestUtils.createExceptionFromDesc(ex3Description)
    val ex3Sig = "java.lang.Exception at com.android.SomeClass.someMethod-d3f18885"

    const val ex4Description =
      "java.lang.OutOfMemoryError: <elided>\n" +
        "\tat java.desktop/sun.java2d.metal.MTLSurfaceData.initSurfaceNow(Unknown Source)\n" +
        "\tat java.desktop/sun.java2d.metal.MTLSurfaceData\$1.run(Unknown Source)\n" +
        "\tat java.desktop/sun.java2d.metal.MTLRenderQueue\$QueueFlusher.run(Unknown Source)\n" +
        "\tat java.base/java.lang.Thread.run(Unknown Source)\n"
    val ex4 = ExceptionTestUtils.createExceptionFromDesc(ex4Description)
    val ex4Sig = "java.lang.OutOfMemoryError at java.desktop/sun.java2d.metal.MTLSurfaceData.initSurfaceNow-8edba189"
    val ex4DynamicSig = "java.lang.OutOfMemoryError at java.desktop/sun.java2d.metal.MTLSurfaceData.initSurfaceNow-8edba189"

    const val ex5Description =
      "java.lang.RuntimeException: AWT-EventQueue-0\" RUNNABLE Frozen for 12secs\n" +
        "\tat app/jcef/org.cef.callback.CefSchemeRegistrar_N.N_AddCustomScheme(Native Method)\n" +
        "\tat app/jcef/org.cef.callback.CefSchemeRegistrar_N.addCustomScheme(CefSchemeRegistrar_N.java:13)\n" +
        "\tat com.intellij.ui.jcef.JBCefSourceSchemeHandlerFactory.registerCustomScheme(JBCefSourceSchemeHandlerFactory.java:16)\n" +
        "\tat com.intellij.ui.jcef.JBCefApp\$MyCefAppHandler.onRegisterCustomSchemes(JBCefApp.java:566)\n" +
        "\tat app/jcef/org.cef.CefApp.N_Initialize(Native Method)\n" +
        "\tat app/jcef/org.cef.CefApp\$3.lambda\$run\$0(CefApp.java:452)\n" +
        "\tat app/jcef/org.cef.CefApp\$3\$\$Lambda\$5988/0x0000000101c23900.call(Unknown Source)\n"
    val ex5 = ExceptionTestUtils.createExceptionFromDesc(ex5Description)
    val ex5Sig = "java.lang.RuntimeException at app/jcef/org.cef.callback.CefSchemeRegistrar_N.N_AddCustomScheme-7b65b85f"
    val ex5DynamicSig = "java.lang.RuntimeException at app/jcef/org.cef.callback.CefSchemeRegistrar_N.N_AddCustomScheme-7b65b85f"

    const val exStudioOwnedFirstDescription =
      "java.lang.Exception: Studio owned top frame\n" +
        "\tat com.android.tools.idea.MyClass.myMethod(MyClass.kt:10)\n" +
        "\tat com.intellij.openapi.Foo.bar(Foo.java:20)\n" +
        "\tat com.intellij.openapi.Baz.qux(Baz.java:30)\n"
    val exStudioOwnedFirst = ExceptionTestUtils.createExceptionFromDesc(exStudioOwnedFirstDescription)
    const val exStudioOwnedFirstSig = "java.lang.Exception at com.android.tools.idea.MyClass.myMethod-a8ac5175"

    const val exStudioOwnedDeepDescription =
      "java.lang.Exception: Studio owned frame deep in stack\n" +
        "\tat com.intellij.openapi.Action.perform(Action.java:10)\n" +
        "\tat com.intellij.util.Slow.allow(Slow.java:20)\n" +
        "\tat org.jetbrains.kotlin.psi.KtElement.getText(KtElement.kt:30)\n" +
        "\tat com.android.tools.idea.res.ResourceRepository.getResources(ResourceRepository.java:40)\n" +
        "\tat java.desktop/java.awt.EventDispatchThread.run(EventDispatchThread.java:50)\n" +
        "\tat java.base/java.lang.Thread.run(Thread.java:60)\n"
    val exStudioOwnedDeep = ExceptionTestUtils.createExceptionFromDesc(exStudioOwnedDeepDescription)
    const val exStudioOwnedDeepSig = "java.lang.Exception at com.intellij.openapi.Action.perform-294e2da9"

    const val exNoStudioOwnedDescription =
      "java.lang.Exception: No studio owned frames\n" +
        "\tat com.intellij.openapi.Action.perform(Action.java:10)\n" +
        "\tat com.intellij.util.Slow.allow(Slow.java:20)\n" +
        "\tat java.desktop/java.awt.EventDispatchThread.run(EventDispatchThread.java:30)\n" +
        "\tat java.base/java.lang.Thread.run(Thread.java:40)\n"
    val exNoStudioOwned = ExceptionTestUtils.createExceptionFromDesc(exNoStudioOwnedDescription)
    const val exNoStudioOwnedSig = "java.lang.Exception at com.intellij.openapi.Action.perform-29c19fb5"

    const val exStudioOwnedAtLine10Description =
      "java.lang.Exception: Studio owned frame at 10th stack frame\n" +
        "\tat com.intellij.openapi.Action1.step1(Action1.java:10)\n" +
        "\tat com.intellij.openapi.Action2.step2(Action2.java:20)\n" +
        "\tat com.intellij.openapi.Action3.step3(Action3.java:30)\n" +
        "\tat com.intellij.openapi.Action4.step4(Action4.java:40)\n" +
        "\tat com.intellij.openapi.Action5.step5(Action5.java:50)\n" +
        "\tat com.intellij.openapi.Action6.step6(Action6.java:60)\n" +
        "\tat com.intellij.openapi.Action7.step7(Action7.java:70)\n" +
        "\tat com.intellij.openapi.Action8.step8(Action8.java:80)\n" +
        "\tat com.intellij.openapi.Action9.step9(Action9.java:90)\n" +
        "\tat com.android.tools.idea.DeepClass.androidMethod(DeepClass.java:100)\n" +
        "\tat java.desktop/java.awt.EventDispatchThread.pumpEvents(EventDispatchThread.java:110)\n" +
        "\tat java.desktop/java.awt.EventDispatchThread.pumpEventsForHierarchy(EventDispatchThread.java:120)\n" +
        "\tat java.desktop/java.awt.EventDispatchThread.pumpEventsForFilter(EventDispatchThread.java:130)\n" +
        "\tat java.desktop/java.awt.EventDispatchThread.run(EventDispatchThread.java:140)\n" +
        "\tat java.base/java.lang.Thread.run(Thread.java:150)\n"
    val exStudioOwnedAtLine10 = ExceptionTestUtils.createExceptionFromDesc(exStudioOwnedAtLine10Description)
    const val exStudioOwnedAtLine10Sig = "java.lang.Exception at com.intellij.openapi.Action1.step1-386e1d0e"

    const val ex17FramesNoStudioOwnedDescription =
      "java.lang.Exception: 17 frames without studio owned\n" +
        "\tat com.intellij.openapi.Frame1.method1(Frame1.java:1)\n" +
        "\tat com.intellij.openapi.Frame2.method2(Frame2.java:2)\n" +
        "\tat com.intellij.openapi.Frame3.method3(Frame3.java:3)\n" +
        "\tat com.intellij.openapi.Frame4.method4(Frame4.java:4)\n" +
        "\tat com.intellij.openapi.Frame5.method5(Frame5.java:5)\n" +
        "\tat com.intellij.openapi.Frame6.method6(Frame6.java:6)\n" +
        "\tat com.intellij.openapi.Frame7.method7(Frame7.java:7)\n" +
        "\tat com.intellij.openapi.Frame8.method8(Frame8.java:8)\n" +
        "\tat com.intellij.openapi.Frame9.method9(Frame9.java:9)\n" +
        "\tat com.intellij.openapi.Frame10.method10(Frame10.java:10)\n" +
        "\tat com.intellij.openapi.Frame11.method11(Frame11.java:11)\n" +
        "\tat com.intellij.openapi.Frame12.method12(Frame12.java:12)\n" +
        "\tat com.intellij.openapi.Frame13.method13(Frame13.java:13)\n" +
        "\tat com.intellij.openapi.Frame14.method14(Frame14.java:14)\n" +
        "\tat com.intellij.openapi.Frame15.method15(Frame15.java:15)\n" +
        "\tat com.intellij.openapi.Frame16.method16(Frame16.java:16)\n" +
        "\tat com.intellij.openapi.Frame17.method17(Frame17.java:17)\n"
    val ex17FramesNoStudioOwned = ExceptionTestUtils.createExceptionFromDesc(ex17FramesNoStudioOwnedDescription)
    const val ex17FramesNoStudioOwnedSig = "java.lang.Exception at com.intellij.openapi.Frame1.method1-869be3d9"
  }
}
