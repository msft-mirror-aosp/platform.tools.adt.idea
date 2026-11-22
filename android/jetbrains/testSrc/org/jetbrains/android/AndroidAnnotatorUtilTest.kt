/*
 * Copyright (C) 2020 The Android Open Source Project
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
package org.jetbrains.android

import com.android.ide.common.resources.ResourceResolver
import com.android.resources.ResourceType
import com.android.tools.configurations.Configuration
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.android.tools.idea.ui.resourcechooser.common.ResourcePickerSources
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.TestActionEvent
import java.awt.Color
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

@RunsInEdt
class AndroidAnnotatorUtilTest {
  @get:Rule val projectRule = AndroidProjectRule.withSdk().onEdt()

  private val project
    get() = projectRule.project

  @Test
  fun testPickupColorResourceInJavaFile() {
    val element = createJavaElement("R.color.color1")

    val task = AndroidAnnotatorUtil.SetAttributeConsumer(element, ResourceType.COLOR)

    task.testJavaConsumer("@color/color2" to "R.color.color2", "@android:color/color3" to "android.R.color.color3")
  }

  @Test
  fun testSetColorInKotlinFile() {
    val element = createKotlinElement("R.color.color1")

    val task = AndroidAnnotatorUtil.SetAttributeConsumer(element, ResourceType.COLOR)

    task.testKotlinConsumer("@color/color2" to "R.color.color2", "@android:color/color3" to "android.R.color.color3")

    // Verify that the current element in the consumer is the PsiElement that corresponds to the name only
    assertEquals("color3", task.element.text)
  }

  @Test
  fun testSetColorToXmlTag() {
    val xmlTag = XmlElementFactory.getInstance(project).createTagFromText("<color name=\"xxx\">#000000</color>")
    val task = AndroidAnnotatorUtil.SetAttributeConsumer(xmlTag, ResourceType.COLOR)
    task.consume("#FFFFFF")
    assertEquals("<color name=\"xxx\">#FFFFFF</color>", xmlTag.text)

    task.consume("@color/color1")
    assertEquals("<color name=\"xxx\">@color/color1</color>", xmlTag.text)

    task.consume("@android:color/color2")
    assertEquals("<color name=\"xxx\">@android:color/color2</color>", xmlTag.text)
  }

  @Test
  fun testSetColorToXmlAttribute() {
    val xmlAttribute = XmlElementFactory.getInstance(project).createXmlAttribute("android:background", "#000000")

    run {
      val task = AndroidAnnotatorUtil.SetAttributeConsumer(xmlAttribute.valueElement!!, ResourceType.COLOR)
      task.consume("#FFFFFF")
      assertEquals("#FFFFFF", xmlAttribute.valueElement!!.value)
    }

    // The xml attribute value element is replaced when new value is set, thus we need to recreate a new task for different test cases.

    run {
      val task = AndroidAnnotatorUtil.SetAttributeConsumer(xmlAttribute.valueElement!!, ResourceType.COLOR)
      task.consume("@color/color1")
      assertEquals("@color/color1", xmlAttribute.valueElement!!.value)
    }

    run {
      val task = AndroidAnnotatorUtil.SetAttributeConsumer(xmlAttribute.valueElement!!, ResourceType.COLOR)
      task.consume("@android:color/color2")
      assertEquals("@android:color/color2", xmlAttribute.valueElement!!.value)
    }
  }

  @Test
  fun testPickupDrawableResourceInJavaFile() {
    val element = createJavaElement("R.drawable.drawable1")

    val task = AndroidAnnotatorUtil.SetAttributeConsumer(element, ResourceType.DRAWABLE)

    task.testJavaConsumer("@drawable/drawable2" to "R.drawable.drawable2", "@android:drawable/drawable3" to "android.R.drawable.drawable3")
  }

  @Test
  fun testSetDrawableInKotlinFile() {
    val element = createKotlinElement("R.drawable.drawable1")

    val task = AndroidAnnotatorUtil.SetAttributeConsumer(element, ResourceType.DRAWABLE)

    task.testKotlinConsumer(
      "@drawable/drawable2" to "R.drawable.drawable2",
      "@android:drawable/drawable3" to "android.R.drawable.drawable3",
    )

    // Verify that the current element in the consumer is the PsiElement that corresponds to the name only
    assertEquals("drawable3", task.element.text)
  }

  @Test
  fun testColorRendererClickActionResolvesResourceResolverOffEdtAndOpensPickerOnEdt() {
    val colorsFile =
      projectRule.fixture.addFileToProject(
        "res/values/colors.xml",
        """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
          <color name="colorPrimary">#112233</color>
        </resources>
        """
          .trimIndent(),
      )
    projectRule.fixture.openFileInEditor(colorsFile.virtualFile)
    val colorTag = PsiTreeUtil.findChildrenOfType(colorsFile, XmlTag::class.java).first { it.name == "color" }
    val facet = AndroidFacet.getInstance(projectRule.fixture.module)!!

    val invocation = clickColorRenderer(colorTag, colorsFile, color = null, hasCustomColor = true, facet = facet)

    assertThat(invocation.pickConfigOnEdt).isFalse()
    assertThat(invocation.resolverOnEdt).isFalse()
    assertThat(invocation.openPickerOnEdt).isTrue()
    assertThat(invocation.color).isEqualTo(Color(0x11, 0x22, 0x33))
    assertThat(invocation.facet).isSameAs(facet)
    assertThat(invocation.contextFile).isEqualTo(colorsFile.virtualFile)
    assertThat(invocation.resolvedResolver).isNotNull()
    assertThat(invocation.resolver).isSameAs(invocation.resolvedResolver)
    assertThat(invocation.sources)
      .containsExactly(
        ResourcePickerSources.PROJECT,
        ResourcePickerSources.ANDROID,
        ResourcePickerSources.LIBRARY,
        ResourcePickerSources.THEME_ATTR,
      )
      .inOrder()
  }

  @Test
  fun testColorRendererClickActionOmitsThemeAttrForJavaFile() {
    val javaFile =
      projectRule.fixture.addFileToProject(
        "src/com/example/ColorSample.java",
        """
        package com.example;
        class ColorSample {}
        """
          .trimIndent(),
      )
    projectRule.fixture.openFileInEditor(javaFile.virtualFile)
    val facet = AndroidFacet.getInstance(projectRule.fixture.module)!!

    val invocation = clickColorRenderer(javaFile, javaFile, color = Color.RED, hasCustomColor = false, facet = facet)

    assertThat(invocation.pickConfigOnEdt).isFalse()
    assertThat(invocation.resolverOnEdt).isFalse()
    assertThat(invocation.openPickerOnEdt).isTrue()
    assertThat(invocation.color).isEqualTo(Color.RED)
    assertThat(invocation.resolvedResolver).isNotNull()
    assertThat(invocation.resolver).isSameAs(invocation.resolvedResolver)
    assertThat(invocation.sources)
      .containsExactly(ResourcePickerSources.PROJECT, ResourcePickerSources.ANDROID, ResourcePickerSources.LIBRARY)
      .inOrder()
  }

  private data class ColorPickerInvocation(
    val pickConfigOnEdt: Boolean?,
    val resolverOnEdt: Boolean?,
    val resolvedResolver: ResourceResolver?,
    val openPickerOnEdt: Boolean?,
    val color: Color?,
    val facet: AndroidFacet?,
    val contextFile: VirtualFile?,
    val resolver: ResourceResolver?,
    val sources: List<ResourcePickerSources>?,
  )

  private fun clickColorRenderer(
    element: PsiElement,
    psiFile: PsiFile,
    color: Color?,
    hasCustomColor: Boolean,
    facet: AndroidFacet,
  ): ColorPickerInvocation {
    val pickConfigOnEdt = AtomicReference<Boolean?>()
    val resolverOnEdt = AtomicReference<Boolean?>()
    val resolvedResolver = AtomicReference<ResourceResolver?>()
    val openPickerOnEdt = AtomicReference<Boolean?>()
    val opened = AtomicBoolean(false)
    var capturedColor: Color? = null
    var capturedFacet: AndroidFacet? = null
    var capturedContextFile: VirtualFile? = null
    var capturedResolver: ResourceResolver? = null
    var capturedSources: List<ResourcePickerSources>? = null

    val renderer =
      object : AndroidAnnotatorUtil.ColorRenderer(element, color, ResourceResolver.create(emptyMap(), null), null, hasCustomColor, facet) {
        override fun pickConfiguration(file: PsiFile, facet: AndroidFacet): Configuration? {
          pickConfigOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
          val configuration = super.pickConfiguration(file, facet) ?: return null
          val spyConfig = spy(configuration)
          doAnswer { invocation ->
              resolverOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
              (invocation.callRealMethod() as ResourceResolver).also(resolvedResolver::set)
            }
            .whenever(spyConfig)
            .resourceResolver
          return spyConfig
        }

        override fun openColorPicker(
          currentColor: Color?,
          facet: AndroidFacet,
          contextFile: VirtualFile?,
          resourceResolver: ResourceResolver,
          pickerSources: List<ResourcePickerSources>,
        ) {
          openPickerOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
          capturedColor = currentColor
          capturedFacet = facet
          capturedContextFile = contextFile
          capturedResolver = resourceResolver
          capturedSources = pickerSources
          opened.set(true)
        }
      }

    val dataContext =
      SimpleDataContext.builder().add(CommonDataKeys.PSI_FILE, psiFile).add(CommonDataKeys.EDITOR, projectRule.fixture.editor).build()
    renderer.clickAction!!.actionPerformed(TestActionEvent.createTestEvent(dataContext))

    waitWithEventsDispatching("Color picker was not opened", opened::get, 10)

    return ColorPickerInvocation(
      pickConfigOnEdt = pickConfigOnEdt.get(),
      resolverOnEdt = resolverOnEdt.get(),
      resolvedResolver = resolvedResolver.get(),
      openPickerOnEdt = openPickerOnEdt.get(),
      color = capturedColor,
      facet = capturedFacet,
      contextFile = capturedContextFile,
      resolver = capturedResolver,
      sources = capturedSources,
    )
  }

  private fun createJavaElement(text: String) = PsiElementFactory.getInstance(project).createExpressionFromText(text, null)

  private fun createKotlinElement(text: String) = (KtPsiFactory(project, false).createExpression(text) as PsiElement).lastChild

  /** Test the [AndroidAnnotatorUtil.SetAttributeConsumer] for multiple changes when the [PsiElement] in the consumer is in a Java file. */
  private fun AndroidAnnotatorUtil.SetAttributeConsumer.testJavaConsumer(vararg resourceAttributeAndExpected: Pair<String, String>) {
    for ((resource, expectedResult) in resourceAttributeAndExpected) {
      consume(resource)
      assertEquals(expectedResult, element.text)
    }
  }

  /**
   * Test the [AndroidAnnotatorUtil.SetAttributeConsumer] for multiple changes when the [PsiElement] in the consumer is in a Kotlin file.
   */
  private fun AndroidAnnotatorUtil.SetAttributeConsumer.testKotlinConsumer(vararg resourceAttributeAndExpected: Pair<String, String>) {
    for ((resource, expectedResult) in resourceAttributeAndExpected) {
      consume(resource)
      // For Kotlin, test against the parent, which is the full resource reference: namespace.R.resource_type.resource_name
      assertEquals(expectedResult, element.parent.text)
    }
  }
}
