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
package com.android.tools.idea.fonts

import com.android.SdkConstants
import com.android.ide.common.fonts.FontDetail
import com.android.ide.common.fonts.FontFamily
import com.android.ide.common.fonts.FontSource
import com.android.resources.ResourceFolderType
import com.android.testutils.TestUtils
import com.android.testutils.waitForCondition
import com.android.tools.adtui.swing.FakeUi
import com.android.tools.adtui.swing.HeadlessDialogRule
import com.android.tools.adtui.swing.createModalDialogAndInteractWithIt
import com.android.tools.fonts.DownloadableFontCacheService
import com.android.tools.fonts.DownloadableFontCacheServiceImpl
import com.android.tools.fonts.FontDownloader
import com.android.tools.idea.res.StudioResourceRepositoryManager
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.replaceService
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import java.io.File
import java.util.function.Supplier
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JRadioButton
import javax.swing.JTextField
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.android.dom.manifest.Manifest
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.android.facet.ResourceFolderManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MoreFontsDialogTest {
  private val projectRule = AndroidProjectRule.onDisk()
  private val headlessDialogRule = HeadlessDialogRule()

  @get:Rule val chain = RuleChain(projectRule, headlessDialogRule)

  private lateinit var facet: AndroidFacet
  private lateinit var tempFontPath: File

  @Before
  fun setUp() {
    facet = AndroidFacet.getInstance(projectRule.module)!!
    tempFontPath = FileUtil.createTempDirectory("more_fonts_dialog_test", "fonts")
    val mockFontService =
      object :
        DownloadableFontCacheServiceImpl(
          FontDownloader.NOOP_FONT_DOWNLOADER,
          Supplier<File> { tempFontPath },
        ) {
        override fun getRelativeCachedMenuFile(family: FontFamily): String? = null

        override fun getRelativeFontFile(font: FontDetail): String? = null
      }
    ApplicationManager.getApplication()
      .replaceService(
        DownloadableFontCacheService::class.java,
        mockFontService,
        projectRule.testRootDisposable,
      )

    projectRule.fixture.testDataPath = TestUtils.resolveWorkspacePath("tools/adt/idea/android/testData").toString()
    projectRule.fixture.copyFileToProject(
      SdkConstants.FN_ANDROID_MANIFEST_XML,
      SdkConstants.FN_ANDROID_MANIFEST_XML,
    )
    projectRule.fixture.addFileToProject("res/values/strings.xml", "<resources/>")
    StudioResourceRepositoryManager.getProjectResources(facet)
  }

  @Test
  fun testCreateDownloadableFontFromDialog() {
    val dialog = invokeAndWaitIfNeeded { MoreFontsDialog(facet, null, false) }
    try {
      val rootPanel = invokeAndWaitIfNeeded { getCenterPanel(dialog) }
      val fontList = invokeAndWaitIfNeeded { findFontList(rootPanel) }
      waitForFontListPopulated(fontList)

      // Select Abel font family
      invokeAndWaitIfNeeded { selectFontFamily(fontList, "Abel") }

      val fontDetailList = invokeAndWaitIfNeeded { findFontDetailList(rootPanel) }
      waitForDetailListPopulated(fontDetailList)
      invokeAndWaitIfNeeded {
        assertThat(fontDetailList.model.size).isAtLeast(1)

        val fontNameEditor = findFontNameEditor(rootPanel)
        assertThat(fontNameEditor.isVisible).isTrue()
        assertThat(fontNameEditor.text).isEqualTo("abel")

        val downloadableRadio = findRadioButton(rootPanel, "Create downloadable font")
        assertThat(downloadableRadio.isVisible).isTrue()
        assertThat(downloadableRadio.isSelected).isTrue()

        triggerDoOKAction(dialog)

        assertThat(dialog.resultingFont).isEqualTo("@font/abel")
      }
    } finally {
      invokeAndWaitIfNeeded { disposeDialog(dialog) }
    }

    waitForCondition(10.seconds) { ResourceFolderManager.getInstance(facet).folders.isNotEmpty() }

    assertThat(getFontFileContent("abel.xml"))
      .isEqualTo(
        """
        <?xml version="1.0" encoding="utf-8"?>
        <font-family xmlns:app="http://schemas.android.com/apk/res-auto"
                app:fontProviderAuthority="com.google.android.gms.fonts"
                app:fontProviderPackage="com.google.android.gms"
                app:fontProviderQuery="Abel"
                app:fontProviderCerts="@array/com_google_android_gms_fonts_certs">
        </font-family>
        """
          .trimIndent() + "\n"
      )

    assertThat(getValuesFileContent("font_certs.xml"))
      .isEqualTo(
        """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <array name="com_google_android_gms_fonts_certs">
                <item>@array/com_google_android_gms_fonts_certs_dev</item>
                <item>@array/com_google_android_gms_fonts_certs_prod</item>
            </array>
            <string-array name="com_google_android_gms_fonts_certs_dev">
                <item>
                    MIIEqDCCA5CgAwIBAgIJANWFuGx90071MA0GCSqGSIb3DQEBBAUAMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTAeFw0wODA0MTUyMzM2NTZaFw0zNTA5MDEyMzM2NTZaMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBANbOLggKv+IxTdGNs8/TGFy0PTP6DHThvbbR24kT9ixcOd9W+EaBPWW+wPPKQmsHxajtWjmQwWfna8mZuSeJS48LIgAZlKkpFeVyxW0qMBujb8X8ETrWy550NaFtI6t9+u7hZeTfHwqNvacKhp1RbE6dBRGWynwMVX8XW8N1+UjFaq6GCJukT4qmpN2afb8sCjUigq0GuMwYXrFVee74bQgLHWGJwPmvmLHC69EH6kWr22ijx4OKXlSIx2xT1AsSHee70w5iDBiK4aph27yH3TxkXy9V89TDdexAcKk/cVHYNnDBapcavl7y0RiQ4biu8ymM8Ga/nmzhRKya6G0cGw8CAQOjgfwwgfkwHQYDVR0OBBYEFI0cxb6VTEM8YYY6FbBMvAPyT+CyMIHJBgNVHSMEgcEwgb6AFI0cxb6VTEM8YYY6FbBMvAPyT+CyoYGapIGXMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbYIJANWFuGx90071MAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEEBQADggEBABnTDPEF+3iSP0wNfdIjIz1AlnrPzgAIHVvXxunW7SBrDhEglQZBbKJEk5kT0mtKoOD1JMrSu1xuTKEBahWRbqHsXclaXjoBADb0kkjVEJu/Lh5hgYZnOjvlba8Ld7HCKePCVePoTJBdI4fvugnL8TsgK05aIskyY0hKI9L8KfqfGTl1lzOv2KoWD0KWwtAWPoGChZxmQ+nBli+gwYMzM1vAkP+aayLe0a1EQimlOalO762r0GXO0ks+UeXde2Z4e+8S/pf7pITEI/tP+MxJTALw9QUWEv9lKTk+jkbqxbsh8nfBUapfKqYn0eidpwq2AzVp3juYl7//fKnaPhJD9gs=
                </item>
            </string-array>
            <string-array name="com_google_android_gms_fonts_certs_prod">
                <item>
                    MIIEQzCCAyugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDAeFw0wODA4MjEyMzEzMzRaFw0zNjAxMDcyMzEzMzRaMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBAKtWLgDYO6IIrgqWbxJOKdoR8qtW0I9Y4sypEwPpt1TTcvZApxsdyxMJZ2JORland2qSGT2y5b+3JKkedxiLDmpHpDsz2WCbdxgxRczfey5YZnTJ4VZbH0xqWVW/8lGmPav5xVwnIiJS6HXk+BVKZF+JcWjAsb/GEuq/eFdpuzSqeYTcfi6idkyugwfYwXFU1+5fZKUaRKYCwkkFQVfcAs1fXA5V+++FGfvjJ/CxURaSxaBvGdGDhfXE28LWuT9ozCl5xw4Yq5OGazvV24mZVSoOO0yZ31j7kYvtwYK6NeADwbSxDdJEqO4k//0zOHKrUiGYXtqw/A0LFFtqoZKFjnkCAQOjgdkwgdYwHQYDVR0OBBYEFMd9jMIhF1Ylmn/Tgt9r45jk14alMIGmBgNVHSMEgZ4wgZuAFMd9jMIhF1Ylmn/Tgt9r45jk14aloXikdjB0MQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEUMBIGA1UEChMLR29vZ2xlIEluYy4xEDAOBgNVBAsTB0FuZHJvaWQxEDAOBgNVBAMTB0FuZHJvaWSCCQDC4IdGZEowjTAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEBBAUAA4IBAQBt0lLO74UwLDYKqs6Tm8/yzKkEu116FmH4rkaymUIE0P9KaMftGlMexFlaYjzmB2OxZyl6euNXEsQH8gjwyxCUKRJNexBiGcCEyj6z+a1fuHHvkiaai+KL8W1EyNmgjmyy8AW7P+LLlkR+ho5zEHatRbM/YAnqGcFh5iZBqpknHf1SKMXFh4dd239FJ1jWYfbMDMy3NS5CTMQ2XFI1MvcyUTdZPErjQfTbQe3aDQsQcafEQPD+nqActifKZ0Np0IS9L9kR/wbNvyz6ENwPiTrjV2KRkEjH78ZMcUQXg0L3BYHJ3lc69Vs5Ddf9uUGGMYldX3WfMBEmh/9iFBDAaTCK
                </item>
            </string-array>
        </resources>
        """
          .trimIndent() + "\n"
      )

    assertThat(getValuesFileContent("preloaded_fonts.xml"))
      .isEqualTo(
        """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <array name="preloaded_fonts" translatable="false">
                <item>@font/abel</item>
            </array>
        </resources>
        """
          .trimIndent() + "\n"
      )

    assertThat(
        ApplicationManager.getApplication().runReadAction<String> {
          Manifest.getMainManifest(facet)!!.xmlTag!!.text
        }
      )
      .isEqualTo(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="p1.p2">
            <application android:icon="@drawable/icon">
                <meta-data
                    android:name="preloaded_fonts"
                    android:resource="@array/preloaded_fonts" />
            </application>
        </manifest>
        """
          .trimIndent()
      )
  }

  @Test
  fun testInitialFontSelectionAndSearchFiltering() {
    val dialog = invokeAndWaitIfNeeded { MoreFontsDialog(facet, "Abel", false) }
    try {
      val rootPanel = invokeAndWaitIfNeeded { getCenterPanel(dialog) }
      val fontList = invokeAndWaitIfNeeded { findFontList(rootPanel) }
      waitForFontListPopulated(fontList)

      // When opened with "Abel", Abel should be selected initially
      invokeAndWaitIfNeeded {
        val selectedFamily = fontList.selectedValue
        assertThat(selectedFamily).isNotNull()
        assertThat(selectedFamily?.name).isEqualTo("Abel")
      }

      val fontDetailList = invokeAndWaitIfNeeded { findFontDetailList(rootPanel) }
      waitForDetailListPopulated(fontDetailList)

      invokeAndWaitIfNeeded {
        val fontNameEditor = findFontNameEditor(rootPanel)
        assertThat(fontNameEditor.isVisible).isTrue()
        assertThat(fontNameEditor.text).isEqualTo("abel")

        // Search filtering test
        val searchField = findSearchField(rootPanel)
        searchField.text = "Roboto"
        UIUtil.dispatchAllInvocationEvents()

        val filteredSize = fontList.model.size
        assertThat(filteredSize).isAtLeast(1)
        for (i in 0 until filteredSize) {
          val family = fontList.model.getElementAt(i)
          if (family.fontSource != FontSource.HEADER) {
            assertThat(family.name).contains("Roboto")
          }
        }

        // Clear search filter
        searchField.text = ""
        UIUtil.dispatchAllInvocationEvents()
        assertThat(fontList.model.size).isGreaterThan(filteredSize)
      }
    } finally {
      invokeAndWaitIfNeeded { disposeDialog(dialog) }
    }
  }

  @Test
  fun testSelectFontToAddToProjectRadio() {
    val dialog = invokeAndWaitIfNeeded { MoreFontsDialog(facet, null, false) }
    try {
      val rootPanel = invokeAndWaitIfNeeded { getCenterPanel(dialog) }
      val fontList = invokeAndWaitIfNeeded { findFontList(rootPanel) }
      waitForFontListPopulated(fontList)

      invokeAndWaitIfNeeded { selectFontFamily(fontList, "Abel") }

      val fontDetailList = invokeAndWaitIfNeeded { findFontDetailList(rootPanel) }
      waitForDetailListPopulated(fontDetailList)

      invokeAndWaitIfNeeded {
        val downloadableRadio = findRadioButton(rootPanel, "Create downloadable font")
        val addToProjectRadio = findRadioButton(rootPanel, "Add font to project")
        assertThat(downloadableRadio.isSelected).isTrue()
        assertThat(addToProjectRadio.isSelected).isFalse()

        // Toggle to "Add font to project"
        addToProjectRadio.isSelected = true
        assertThat(addToProjectRadio.isSelected).isTrue()
        assertThat(downloadableRadio.isSelected).isFalse()
      }
    } finally {
      invokeAndWaitIfNeeded { disposeDialog(dialog) }
    }
  }

  @Test
  fun testModalDialogLoadsFontsAndSelectsInitialSystemFont() {
    invokeAndWaitIfNeeded {
      val dialog = MoreFontsDialog(facet, "sans-serif", true)
      createModalDialogAndInteractWithIt({ dialog.show() }) {
        val ui = FakeUi(dialog.contentPane)
        @Suppress("UNCHECKED_CAST") val fontList = ui.findComponent<JBList<*>> { it.name == "Font list" } as JBList<FontFamily>
        @Suppress("UNCHECKED_CAST") val detailList = ui.findComponent<JBList<*>> { it !== fontList } as JBList<FontDetail>

        waitForCondition(5.seconds) {
          fontList.emptyText.text == "No fonts available" && fontList.selectedValue?.name == "sans-serif" && detailList.model.size > 0
        }

        assertThat(fontList.selectedValue.name).isEqualTo("sans-serif")
        assertThat(detailList.selectedValue).isNotNull()
        assertThat(dialog.isOKActionEnabled).isTrue()
        dialog.clickDefaultButton()
      }
      assertThat(dialog.isOK).isTrue()
      assertThat(dialog.resultingFont).isEqualTo("sans-serif")
    }
  }

  @Test
  fun testModalDialogSelectsInitialProjectFont() {
    projectRule.fixture.copyFileToProject("fonts/customfont.ttf", "res/font/customfont.ttf")
    invokeAndWaitIfNeeded {
      val dialog = MoreFontsDialog(facet, "@font/customfont", true)
      createModalDialogAndInteractWithIt({ dialog.show() }) {
        val ui = FakeUi(dialog.contentPane)
        @Suppress("UNCHECKED_CAST") val fontList = ui.findComponent<JBList<*>> { it.name == "Font list" } as JBList<FontFamily>
        @Suppress("UNCHECKED_CAST") val detailList = ui.findComponent<JBList<*>> { it !== fontList } as JBList<FontDetail>

        waitForCondition(5.seconds) {
          fontList.emptyText.text == "No fonts available" && fontList.selectedValue?.name == "customfont" && detailList.model.size > 0
        }

        assertThat(fontList.selectedValue.fontSource).isEqualTo(FontSource.PROJECT)
        assertThat(fontList.selectedValue.name).isEqualTo("customfont")
        assertThat(detailList.selectedValue).isNotNull()
        dialog.clickDefaultButton()
      }
      assertThat(dialog.isOK).isTrue()
      assertThat(dialog.resultingFont).isEqualTo("@font/customfont")
    }
  }

  @Test
  fun testModalDialogWithoutExistingFonts() {
    invokeAndWaitIfNeeded {
      val dialog = MoreFontsDialog(facet, null, false)
      createModalDialogAndInteractWithIt({ dialog.show() }) {
        val ui = FakeUi(dialog.contentPane)
        @Suppress("UNCHECKED_CAST") val fontList = ui.findComponent<JBList<*>> { it.name == "Font list" } as JBList<FontFamily>

        waitForCondition(5.seconds) {
          fontList.emptyText.text == "No fonts available" && fontList.model.size > 0
        }

        val elements = (0 until fontList.model.size).map { fontList.model.getElementAt(it) }
        assertThat(elements.first().name).isEqualTo("Downloadable")
        assertThat(elements.none { it.fontSource == FontSource.PROJECT }).isTrue()
        assertThat(elements.none { it.fontSource == FontSource.SYSTEM }).isTrue()
        assertThat(elements.any { it.name == "Abel" }).isTrue()
        dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
      }
    }
  }

  @After
  fun tearDown() {
    invokeAndWaitIfNeeded { UIUtil.dispatchAllInvocationEvents() }
  }

  private fun disposeDialog(dialog: MoreFontsDialog) {
    try {
      val modelField = MoreFontsDialog::class.java.getDeclaredField("myModel")
      modelField.isAccessible = true
      val model = modelField.get(dialog)
      if (model != null) {
        val listenerField = model.javaClass.getDeclaredField("myRepopulateListener")
        listenerField.isAccessible = true
        listenerField.set(model, null)

        val loadedIndexField = model.javaClass.getDeclaredField("myLoadedFontIndex")
        loadedIndexField.isAccessible = true
        loadedIndexField.setInt(model, Int.MAX_VALUE)

        val clearMethod = model.javaClass.getMethod("clear")
        clearMethod.invoke(model)
      }

      val lastSelectedFontField = MoreFontsDialog::class.java.getDeclaredField("myLastSelectedFont")
      lastSelectedFontField.isAccessible = true
      lastSelectedFontField.set(dialog, null)

      val detailModelField = MoreFontsDialog::class.java.getDeclaredField("myDetailModel")
      detailModelField.isAccessible = true
      val detailModel = detailModelField.get(dialog) as? DefaultListModel<*>
      detailModel?.clear()
    } catch (e: ReflectiveOperationException) {
      throw AssertionError("Failed to clean up MoreFontsDialog fields via reflection", e)
    } finally {
      Disposer.dispose(dialog.disposable)
      UIUtil.dispatchAllInvocationEvents()
    }
  }

  private fun getCenterPanel(dialog: MoreFontsDialog): JComponent {
    val method = MoreFontsDialog::class.java.getDeclaredMethod("createCenterPanel")
    method.isAccessible = true
    return method.invoke(dialog) as JComponent
  }

  private fun selectFontFamily(fontList: JBList<FontFamily>, familyName: String) {
    for (i in 0 until fontList.model.size) {
      val family = fontList.model.getElementAt(i)
      if (family.name == familyName) {
        fontList.selectedIndex = i
        break
      }
    }
    UIUtil.dispatchAllInvocationEvents()
  }

  private fun waitForFontListPopulated(fontList: JBList<FontFamily>) {
    waitForCondition(10.seconds) {
      invokeAndWaitIfNeeded {
        UIUtil.dispatchAllInvocationEvents()
        fontList.model.size > 0
      }
    }
  }

  private fun waitForDetailListPopulated(detailList: JBList<FontDetail>) {
    waitForCondition(10.seconds) {
      invokeAndWaitIfNeeded {
        UIUtil.dispatchAllInvocationEvents()
        detailList.model.size > 0
      }
    }
  }

  private fun triggerDoOKAction(dialog: MoreFontsDialog) {
    val method = MoreFontsDialog::class.java.getDeclaredMethod("doOKAction")
    method.isAccessible = true
    method.invoke(dialog)
    UIUtil.dispatchAllInvocationEvents()
  }

  @Suppress("UNCHECKED_CAST")
  private fun findFontList(parent: JComponent): JBList<FontFamily> {
    val lists = UIUtil.findComponentsOfType(parent, JBList::class.java)
    return lists.first { it.name == "Font list" } as JBList<FontFamily>
  }

  @Suppress("UNCHECKED_CAST")
  private fun findFontDetailList(parent: JComponent): JBList<FontDetail> {
    val lists = UIUtil.findComponentsOfType(parent, JBList::class.java)
    return lists.first { it.name != "Font list" } as JBList<FontDetail>
  }

  private fun findFontNameEditor(parent: JComponent): JTextField {
    val fields = UIUtil.findComponentsOfType(parent, JTextField::class.java)
    return fields.first { it !is SearchTextField && it.parent !is SearchTextField }
  }

  private fun findSearchField(parent: JComponent): SearchTextField {
    return requireNotNull(UIUtil.findComponentOfType(parent, SearchTextField::class.java))
  }

  private fun findRadioButton(parent: JComponent, text: String): JRadioButton {
    val buttons = UIUtil.findComponentsOfType(parent, JRadioButton::class.java)
    return buttons.first { it.text == text }
  }

  private fun getFontFileContent(fontFileName: String): String {
    return StringUtil.convertLineSeparators(FontTestUtils.getResourceFileContent(facet, ResourceFolderType.FONT, fontFileName))
  }

  private fun getValuesFileContent(valuesFileName: String): String {
    return StringUtil.convertLineSeparators(FontTestUtils.getResourceFileContent(facet, ResourceFolderType.VALUES, valuesFileName))
  }
}
