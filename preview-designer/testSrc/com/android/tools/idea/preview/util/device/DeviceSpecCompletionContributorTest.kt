/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.idea.preview.util.device

import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.Sdks
import com.android.tools.idea.testing.caret
import com.android.tools.preview.config.REFERENCE_DESKTOP_SPEC
import com.android.tools.preview.config.REFERENCE_FOLDABLE_SPEC
import com.android.tools.preview.config.REFERENCE_PHONE_SPEC
import com.android.tools.preview.config.REFERENCE_TABLET_SPEC
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.runInEdtAndWait
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

internal class DeviceSpecCompletionContributorTest {
  @get:Rule val rule = AndroidProjectRule.inMemory()

  val fixture
    get() = rule.fixture

  @Before
  fun setup() {
    val ep = ApplicationManager.getApplication().extensionArea.getExtensionPoint<CompletionContributorEP>(CompletionContributor.EP.name)
    ep.registerExtension(
      CompletionContributorEP(DeviceSpecLanguage.id, DeviceSpecCompletionContributor::class.java.name, ep.pluginDescriptor),
      fixture.testRootDisposable,
    )
    stubPreviewDeviceSpec("expand,maxHeight,maxWidth,scrollTo")
    runInEdtAndWait {
      // Sdk needed for Devices
      Sdks.addLatestAndroidSdk(fixture.testRootDisposable, rule.module)
    }
  }

  private fun stubPreviewDeviceSpec(supportedParameters: String?) {
    runInEdtAndWait {
      ApplicationManager.getApplication().runWriteAction {
        fixture.findFileInTempDir("src/androidx/compose/ui/tooling/PreviewDeviceSpec.kt")?.delete(this)
      }
    }
    if (supportedParameters != null) {
      fixture.addFileToProject(
        "src/androidx/compose/ui/tooling/PreviewDeviceSpec.kt",
        // language=kotlin
        """
        package androidx.compose.ui.tooling

        object PreviewDeviceSpec {
          const val SUPPORTED_PARAMETERS = "$supportedParameters"
        }
        """
          .trimIndent(),
      )
    }
  }

  @Test
  fun providedDeviceInId() {
    fixture.completeDeviceSpec("id:pixel_10$caret")

    assertEquals(5, fixture.lookupElementStrings!!.size)
    assertEquals("pixel_10", fixture.lookupElementStrings!![0])
    assertEquals("pixel_10_pro", fixture.lookupElementStrings!![1])
    assertEquals("pixel_10_pro_fold", fixture.lookupElementStrings!![2])
    assertEquals("pixel_10_pro_xl", fixture.lookupElementStrings!![3])
    assertEquals("pixel_10a", fixture.lookupElementStrings!![4])
  }

  @Test
  fun providedDeviceInParent() {
    fixture.completeDeviceSpec("spec:parent=pixel_10$caret")

    assertEquals(5, fixture.lookupElementStrings!!.size)
    assertEquals("pixel_10", fixture.lookupElementStrings!![0])
    assertEquals("pixel_10_pro", fixture.lookupElementStrings!![1])
    assertEquals("pixel_10_pro_fold", fixture.lookupElementStrings!![2])
    assertEquals("pixel_10_pro_xl", fixture.lookupElementStrings!![3])
    assertEquals("pixel_10a", fixture.lookupElementStrings!![4])
  }

  @Test
  fun providedOrientationValues() {
    fixture.completeDeviceSpec("spec:orientation=$caret")

    assertEquals(2, fixture.lookupElementStrings!!.size)
    assertEquals("landscape", fixture.lookupElementStrings!![0])
    assertEquals("portrait", fixture.lookupElementStrings!![1])
  }

  @Test
  fun providedNavigationValues() {
    fixture.completeDeviceSpec("spec:navigation=$caret")

    assertEquals(2, fixture.lookupElementStrings!!.size)
    assertEquals("buttons", fixture.lookupElementStrings!![0])
    assertEquals("gesture", fixture.lookupElementStrings!![1])
  }

  @Test
  fun providedCutoutValues() {
    fixture.completeDeviceSpec("spec:cutout=$caret")

    assertEquals(5, fixture.lookupElementStrings!!.size)
    assertEquals("corner", fixture.lookupElementStrings!![0])
    assertEquals("double", fixture.lookupElementStrings!![1])
    assertEquals("none", fixture.lookupElementStrings!![2])
    assertEquals("punch_hole", fixture.lookupElementStrings!![3])
    assertEquals("tall", fixture.lookupElementStrings!![4])
  }

  @Test
  fun providedWindowPostureValues() {
    fixture.completeDeviceSpec("spec:windowPosture=$caret")

    assertEquals(3, fixture.lookupElementStrings!!.size)
    assertEquals("book", fixture.lookupElementStrings!![0])
    assertEquals("flat", fixture.lookupElementStrings!![1])
    assertEquals("tabletop", fixture.lookupElementStrings!![2])
  }

  @Test
  fun providedPointerPrecisionValues() {
    fixture.completeDeviceSpec("spec:pointerPrecision=$caret")

    assertEquals(4, fixture.lookupElementStrings!!.size)
    assertEquals("blunt", fixture.lookupElementStrings!![0])
    assertEquals("coarse", fixture.lookupElementStrings!![1])
    assertEquals("fine", fixture.lookupElementStrings!![2])
    assertEquals("none", fixture.lookupElementStrings!![3])
  }

  @Test
  fun providedKeyboardKindValues() {
    fixture.completeDeviceSpec("spec:keyboardKind=$caret")

    assertEquals(3, fixture.lookupElementStrings!!.size)
    assertEquals("none", fixture.lookupElementStrings!![0])
    assertEquals("physical", fixture.lookupElementStrings!![1])
    assertEquals("virtual", fixture.lookupElementStrings!![2])
  }

  @Test
  fun providedViewingDistanceValues() {
    fixture.completeDeviceSpec("spec:viewingDistance=$caret")

    assertEquals(3, fixture.lookupElementStrings!!.size)
    assertEquals("far", fixture.lookupElementStrings!![0])
    assertEquals("medium", fixture.lookupElementStrings!![1])
    assertEquals("near", fixture.lookupElementStrings!![2])
  }

  @Test
  fun providedHasCameraValues() {
    fixture.completeDeviceSpec("spec:hasCamera=$caret")

    assertEquals(2, fixture.lookupElementStrings!!.size)
    assertEquals("false", fixture.lookupElementStrings!![0])
    assertEquals("true", fixture.lookupElementStrings!![1])
  }

  @Test
  fun providedHasMicrophoneValues() {
    fixture.completeDeviceSpec("spec:hasMicrophone=$caret")

    assertEquals(2, fixture.lookupElementStrings!!.size)
    assertEquals("false", fixture.lookupElementStrings!![0])
    assertEquals("true", fixture.lookupElementStrings!![1])
  }

  @Test
  fun providedExpandValues() {
    fixture.completeDeviceSpec("spec:expand=$caret")

    assertEquals(2, fixture.lookupElementStrings!!.size)
    assertEquals("horizontal", fixture.lookupElementStrings!![0])
    assertEquals("vertical", fixture.lookupElementStrings!![1])
  }

  @Test
  fun providedScrollToValues() {
    fixture.completeDeviceSpec("spec:scrollTo=$caret")
    assertEquals(listOf("end"), fixture.lookupElementStrings)

    fixture.completeDeviceSpec("spec:parent=pixel_5,scrollTo=$caret")
    assertEquals(listOf("end"), fixture.lookupElementStrings)
  }

  @Test
  fun nothingProvided() {
    // Currently, not supported in name
    fixture.completeDeviceSpec("name:Nexus 7$caret")
    fixture.checkResult("name:Nexus 7")
    assertEquals(0, fixture.lookupElementStrings!!.size)

    // No such thing as id=<device_id>
    fixture.completeDeviceSpec("spec:id=Nexus 7$caret")
    fixture.checkResult("spec:id=Nexus 7")
    assertEquals(0, fixture.lookupElementStrings!!.size)

    // No such thing as parent:<device_id>
    fixture.completeDeviceSpec("parent:Nexus 7$caret")
    fixture.checkResult("parent:Nexus 7")
    assertEquals(0, fixture.lookupElementStrings!!.size)
  }

  @Test
  fun prefixCompletion() {
    // Blank, should provide all possible options
    fixture.completeDeviceSpec(caret)
    assertEquals(6, fixture.lookupElementStrings!!.size)
    assertEquals("id:pixel_5", fixture.lookupElementStrings!![0])
    assertEquals("spec:", fixture.lookupElementStrings!![1]) // Driven by Live Template
    assertEquals(REFERENCE_PHONE_SPEC, fixture.lookupElementStrings!![2])

    assertEquals(REFERENCE_FOLDABLE_SPEC, fixture.lookupElementStrings!![3])
    assertEquals(REFERENCE_TABLET_SPEC, fixture.lookupElementStrings!![4])
    assertEquals(REFERENCE_DESKTOP_SPEC, fixture.lookupElementStrings!![5])

    // 'pix' should only match the default device (pixel_5)
    fixture.completeDeviceSpec("pix$caret")
    assertEquals(1, fixture.lookupElementStrings!!.size)
    assertEquals("id:pixel_5", fixture.lookupElementStrings!![0])

    // completion for 'id' prefix
    fixture.completeDeviceSpec("id$caret") // Note that 'id' also matches 'width' in the full 'spec:...' definition
    assertEquals(5, fixture.lookupElementStrings!!.size)
    assertEquals("id:pixel_5", fixture.lookupElementStrings!![0])
    assertEquals(REFERENCE_PHONE_SPEC, fixture.lookupElementStrings!![1])

    assertEquals(REFERENCE_FOLDABLE_SPEC, fixture.lookupElementStrings!![2])
    assertEquals(REFERENCE_TABLET_SPEC, fixture.lookupElementStrings!![3])
    assertEquals(REFERENCE_DESKTOP_SPEC, fixture.lookupElementStrings!![4])

    // completion for 'spec' prefix
    fixture.completeDeviceSpec("spe$caret")
    assertEquals(5, fixture.lookupElementStrings!!.size)
    assertEquals("spec:", fixture.lookupElementStrings!![0]) // Driven by Live Template
    assertEquals(REFERENCE_PHONE_SPEC, fixture.lookupElementStrings!![1])

    assertEquals(REFERENCE_FOLDABLE_SPEC, fixture.lookupElementStrings!![2])
    assertEquals(REFERENCE_TABLET_SPEC, fixture.lookupElementStrings!![3])
    assertEquals(REFERENCE_DESKTOP_SPEC, fixture.lookupElementStrings!![4])
  }

  @Test
  fun parameterCompletion() {
    fixture.completeDeviceSpec("spec:$caret")
    assertEquals(19, fixture.lookupElementStrings!!.size)
    assertEquals("chinSize", fixture.lookupElementStrings!![0])
    assertEquals("cutout", fixture.lookupElementStrings!![1])
    assertEquals("dpi", fixture.lookupElementStrings!![2])
    assertEquals("expand", fixture.lookupElementStrings!![3])
    assertEquals("hasCamera", fixture.lookupElementStrings!![4])
    assertEquals("hasMicrophone", fixture.lookupElementStrings!![5])
    assertEquals("height", fixture.lookupElementStrings!![6])
    assertEquals("isRound", fixture.lookupElementStrings!![7])
    assertEquals("keyboardKind", fixture.lookupElementStrings!![8])
    assertEquals("maxHeight", fixture.lookupElementStrings!![9])
    assertEquals("maxWidth", fixture.lookupElementStrings!![10])
    assertEquals("navigation", fixture.lookupElementStrings!![11])
    assertEquals("orientation", fixture.lookupElementStrings!![12])
    assertEquals("parent", fixture.lookupElementStrings!![13])
    assertEquals("pointerPrecision", fixture.lookupElementStrings!![14])
    assertEquals("scrollTo", fixture.lookupElementStrings!![15])
    assertEquals("viewingDistance", fixture.lookupElementStrings!![16])
    assertEquals("width", fixture.lookupElementStrings!![17])
    assertEquals("windowPosture", fixture.lookupElementStrings!![18])

    fixture.completeDeviceSpec("spec:orientation=portrait,$caret")
    assertEquals(18, fixture.lookupElementStrings!!.size)
    assertEquals("chinSize", fixture.lookupElementStrings!![0])
    assertEquals("cutout", fixture.lookupElementStrings!![1])
    assertEquals("dpi", fixture.lookupElementStrings!![2])
    assertEquals("expand", fixture.lookupElementStrings!![3])
    assertEquals("hasCamera", fixture.lookupElementStrings!![4])
    assertEquals("hasMicrophone", fixture.lookupElementStrings!![5])
    assertEquals("height", fixture.lookupElementStrings!![6])
    assertEquals("isRound", fixture.lookupElementStrings!![7])
    assertEquals("keyboardKind", fixture.lookupElementStrings!![8])
    assertEquals("maxHeight", fixture.lookupElementStrings!![9])
    assertEquals("maxWidth", fixture.lookupElementStrings!![10])
    assertEquals("navigation", fixture.lookupElementStrings!![11])
    assertEquals("parent", fixture.lookupElementStrings!![12])
    assertEquals("pointerPrecision", fixture.lookupElementStrings!![13])
    assertEquals("scrollTo", fixture.lookupElementStrings!![14])
    assertEquals("viewingDistance", fixture.lookupElementStrings!![15])
    assertEquals("width", fixture.lookupElementStrings!![16])
    assertEquals("windowPosture", fixture.lookupElementStrings!![17])

    fixture.completeDeviceSpec("spec:parent=pixel_5,$caret")
    assertEquals(12, fixture.lookupElementStrings!!.size)
    assertEquals("expand", fixture.lookupElementStrings!![0])
    assertEquals("hasCamera", fixture.lookupElementStrings!![1])
    assertEquals("hasMicrophone", fixture.lookupElementStrings!![2])
    assertEquals("keyboardKind", fixture.lookupElementStrings!![3])
    assertEquals("maxHeight", fixture.lookupElementStrings!![4])
    assertEquals("maxWidth", fixture.lookupElementStrings!![5])
    assertEquals("navigation", fixture.lookupElementStrings!![6])
    assertEquals("orientation", fixture.lookupElementStrings!![7])
    assertEquals("pointerPrecision", fixture.lookupElementStrings!![8])
    assertEquals("scrollTo", fixture.lookupElementStrings!![9])
    assertEquals("viewingDistance", fixture.lookupElementStrings!![10])
    assertEquals("windowPosture", fixture.lookupElementStrings!![11])

    fixture.completeDeviceSpec("spec:width=1080px,$caret")
    assertEquals(17, fixture.lookupElementStrings!!.size)
    assertEquals("chinSize", fixture.lookupElementStrings!![0])
    assertEquals("cutout", fixture.lookupElementStrings!![1])
    assertEquals("dpi", fixture.lookupElementStrings!![2])
    assertEquals("expand", fixture.lookupElementStrings!![3])
    assertEquals("hasCamera", fixture.lookupElementStrings!![4])
    assertEquals("hasMicrophone", fixture.lookupElementStrings!![5])
    assertEquals("height", fixture.lookupElementStrings!![6])
    assertEquals("isRound", fixture.lookupElementStrings!![7])
    assertEquals("keyboardKind", fixture.lookupElementStrings!![8])
    assertEquals("maxHeight", fixture.lookupElementStrings!![9])
    assertEquals("maxWidth", fixture.lookupElementStrings!![10])
    assertEquals("navigation", fixture.lookupElementStrings!![11])
    assertEquals("orientation", fixture.lookupElementStrings!![12])
    assertEquals("pointerPrecision", fixture.lookupElementStrings!![13])
    assertEquals("scrollTo", fixture.lookupElementStrings!![14])
    assertEquals("viewingDistance", fixture.lookupElementStrings!![15])
    assertEquals("windowPosture", fixture.lookupElementStrings!![16])

    // 'heigh' also matches 'maxHeight'
    fixture.completeDeviceSpec("spec:width=1080px,heigh$caret")
    assertEquals(listOf("height", "maxHeight"), fixture.lookupElementStrings)
    fixture.selectLookupItem("height")
    fixture.checkResult("spec:width=1080px,height=891px")

    fixture.completeDeviceSpec("spec:width=1080dp,heigh$caret")
    fixture.selectLookupItem("height")
    fixture.checkResult("spec:width=1080dp,height=891dp")

    fixture.completeDeviceSpec("spec:width=1080dp,isRoun$caret")
    fixture.checkResult("spec:width=1080dp,isRound=false")

    fixture.completeDeviceSpec("spec:width=1080dp,chinSiz$caret")
    fixture.checkResult("spec:width=1080dp,chinSize=0dp")

    fixture.completeDeviceSpec("spec:width=1080dp,cuto$caret")
    fixture.checkResult("spec:width=1080dp,cutout=none")

    fixture.completeDeviceSpec("spec:parent=pixel_5,orient$caret")
    fixture.checkResult("spec:parent=pixel_5,orientation=portrait")

    fixture.completeDeviceSpec("spec:parent=pixel_5,navi$caret")
    fixture.checkResult("spec:parent=pixel_5,navigation=gesture")

    fixture.completeDeviceSpec("spec:orientation=portrait,par$caret")
    fixture.checkResult("spec:orientation=portrait,parent=pixel_5")

    fixture.completeDeviceSpec("spec:width=1080dp,windowPostur$caret")
    fixture.checkResult("spec:width=1080dp,windowPosture=flat")

    fixture.completeDeviceSpec("spec:width=1080dp,pointerPrecisio$caret")
    fixture.checkResult("spec:width=1080dp,pointerPrecision=coarse")

    fixture.completeDeviceSpec("spec:width=1080dp,keyboardKin$caret")
    fixture.checkResult("spec:width=1080dp,keyboardKind=virtual")

    fixture.completeDeviceSpec("spec:width=1080dp,hasCamer$caret")
    fixture.checkResult("spec:width=1080dp,hasCamera=true")

    fixture.completeDeviceSpec("spec:width=1080dp,hasMicrophon$caret")
    fixture.checkResult("spec:width=1080dp,hasMicrophone=true")

    fixture.completeDeviceSpec("spec:width=1080dp,viewingDistanc$caret")
    fixture.checkResult("spec:width=1080dp,viewingDistance=near")

    fixture.completeDeviceSpec("spec:width=1080dp,expan$caret")
    fixture.checkResult("spec:width=1080dp,expand=vertical")

    fixture.completeDeviceSpec("spec:width=1080px,maxHeigh$caret")
    fixture.checkResult("spec:width=1080px,maxHeight=4096px")

    fixture.completeDeviceSpec("spec:width=1080dp,maxWidt$caret")
    fixture.checkResult("spec:width=1080dp,maxWidth=4096dp")

    fixture.completeDeviceSpec("spec:parent=pixel_5,expan$caret")
    fixture.checkResult("spec:parent=pixel_5,expand=vertical")

    fixture.completeDeviceSpec("spec:parent=pixel_5,maxWidth=2000px,maxHeigh$caret")
    fixture.checkResult("spec:parent=pixel_5,maxWidth=2000px,maxHeight=4096px")

    fixture.completeDeviceSpec("spec:width=1080dp,scroll$caret")
    fixture.checkResult("spec:width=1080dp,scrollTo=end")

    fixture.completeDeviceSpec("spec:parent=pixel_5,scroll$caret")
    fixture.checkResult("spec:parent=pixel_5,scrollTo=end")

    // Nothing else to complete when using `parent`
    fixture.completeDeviceSpec(
      "spec:orientation=portrait,navigation=gesture,parent=pixel_5,windowPosture=flat,pointerPrecision=coarse,keyboardKind=virtual,hasCamera=true,hasMicrophone=true,viewingDistance=near,expand=vertical,maxHeight=1000dp,maxWidth=1000dp,scrollTo=end,$caret"
    )
    fixture.checkResult(
      "spec:orientation=portrait,navigation=gesture,parent=pixel_5,windowPosture=flat,pointerPrecision=coarse,keyboardKind=virtual,hasCamera=true,hasMicrophone=true,viewingDistance=near,expand=vertical,maxHeight=1000dp,maxWidth=1000dp,scrollTo=end,"
    )
    assertEquals(0, fixture.lookupElementStrings!!.size)

    // No parameters starting with 'spe'
    fixture.completeDeviceSpec("spec:width=300dp,spe$caret")
    assertEquals(0, fixture.lookupElementStrings!!.size)
  }

  @Test
  fun expandParametersNotProvidedWhenUnsupportedByPreviewDeviceSpec() {
    stubPreviewDeviceSpec(null)

    fixture.completeDeviceSpec("spec:$caret")
    assertEquals(
      listOf(
        "chinSize",
        "cutout",
        "dpi",
        "hasCamera",
        "hasMicrophone",
        "height",
        "isRound",
        "keyboardKind",
        "navigation",
        "orientation",
        "parent",
        "pointerPrecision",
        "viewingDistance",
        "width",
        "windowPosture",
      ),
      fixture.lookupElementStrings,
    )

    fixture.completeDeviceSpec("spec:parent=pixel_5,$caret")
    assertEquals(
      listOf(
        "hasCamera",
        "hasMicrophone",
        "keyboardKind",
        "navigation",
        "orientation",
        "pointerPrecision",
        "viewingDistance",
        "windowPosture",
      ),
      fixture.lookupElementStrings,
    )

    fixture.completeDeviceSpec("spec:expand=$caret")
    assertEquals(emptyList<String>(), fixture.lookupElementStrings)

    fixture.completeDeviceSpec("spec:scrollTo=$caret")
    assertEquals(emptyList<String>(), fixture.lookupElementStrings)
  }
}

private fun CodeInsightTestFixture.completeDeviceSpec(text: String) {
  configureByText(DeviceSpecFileType, text)
  completeBasic()
}

/** Selects the lookup element with the given [lookupString] when the completion shows more than one element. */
private fun CodeInsightTestFixture.selectLookupItem(lookupString: String) = runInEdtAndWait {
  lookup.currentItem = lookupElements!!.first { it.lookupString == lookupString }
  finishLookup(Lookup.NORMAL_SELECT_CHAR)
}
