/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.screenshottest.run

import com.android.flags.junit.FlagRule
import com.android.screenshottest.action.withNonDebuggableBuildTypes
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.testing.TestProjectPaths
import com.android.tools.idea.testing.onEdt
import com.android.utils.FileUtils
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil.findFileByIoFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.RunsInEdt
import java.io.File
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ScreenshotTestRunLineMarkerContributorTest {
  @get:Rule val flagRule = FlagRule(StudioFlags.ENABLE_SCREENSHOT_TESTING, true)

  @get:Rule val projectRule = AndroidGradleProjectRule().onEdt()

  private val contributor = ScreenshotTestRunLineMarkerContributor()
  private var file: PsiFile? = null

  private val ACTION_ID = "com.android.screenshottest.action.UpdateReferenceImagesAction"
  private var originalAction: AnAction? = null

  val updateReferenceImagesAction: AnAction =
    object : AnAction("Add/Update Reference Images...") {
      override fun actionPerformed(e: AnActionEvent) {}
    }

  private val SRC_FILE_HEADER =
    """
    package com.example.runlinemarker;

    import androidx.compose.runtime.Composable
    import androidx.compose.ui.tooling.preview.Preview
    import com.android.tools.screenshot.PreviewTest

    """
      .trimIndent()

  @Before
  fun setup() {
    projectRule.loadProject(TestProjectPaths.SIMPLE_APP_WITH_SCREENSHOT_TEST)
    stubComposeAnnotation()
    stubPreviewAnnotation()
    stubPreviewTestAnnotation()
    val actionManager = ActionManager.getInstance() as ActionManagerImpl
    originalAction = actionManager.getAction(ACTION_ID)
    if (originalAction != null) {
      actionManager.unregisterAction(ACTION_ID)
    }
    actionManager.registerAction(ACTION_ID, updateReferenceImagesAction)
  }

  @After
  fun tearDown() {
    val actionManager = ActionManager.getInstance() as ActionManagerImpl
    actionManager.unregisterAction(ACTION_ID)
    originalAction?.let { actionManager.registerAction(ACTION_ID, it) }
  }

  @Test
  @RunsInEdt
  fun testStudioFlagDisabled() {
    StudioFlags.ENABLE_SCREENSHOT_TESTING.override(false)
    val cFile =
      createRelativeFilewithContent(
        "app/src/screenshotTest/java/com/example/runlinemarker/PreviewScreenshotTest.kt",
        """
        $SRC_FILE_HEADER
        class PreviewScreenshotTest {
           @PreviewTest
           @Preview(showBackground = true)
           @Composable
           fun GreetingAndroid() {
               println("Hello")
           }
        
           @PreviewTest
           @Preview(showBackground = true)
           @Composable
           fun GreetingPreview() {
               println("Hi")
           }
        }
      """
          .trimIndent(),
      )
    val virtualFile = findFileByIoFile(cFile, true)
    val screenshotDir = virtualFile!!.parent.parent.parent.parent.parent
    PsiTestUtil.addSourceRoot(projectRule.fixture.module, screenshotDir!!, true)
    file = virtualFile.toPsiFile(projectRule.project)
    val function1 = file!!.findFunctionIdentifier("GreetingAndroid")
    val function2 = file!!.findFunctionIdentifier("GreetingPreview")
    val classElement = file!!.findClassIdentifier("PreviewScreenshotTest")
    assertNull(contributor.getSlowInfo(function1))
    assertNull(contributor.getSlowInfo(function2))
    assertNull(contributor.getSlowInfo(classElement))
  }

  @Test
  @RunsInEdt
  fun testRunLineMarkerContributor() {
    val cFile =
      createRelativeFilewithContent(
        "app/src/screenshotTest/java/com/example/runlinemarker/PreviewScreenshotTest.kt",
        """
        $SRC_FILE_HEADER
        class PreviewScreenshotTest {
           @PreviewTest
           @Preview(showBackground = true)
           @Composable
           fun GreetingAndroid() {
               println("Hello")
           }
        
           @PreviewTest
           @Preview(showBackground = true)
           @Composable
           fun GreetingPreview() {
               println("Hi")
           }
        }
      """
          .trimIndent(),
      )
    val virtualFile = findFileByIoFile(cFile, true)
    val screenshotDir = virtualFile!!.parent.parent.parent.parent.parent
    PsiTestUtil.addSourceRoot(projectRule.fixture.module, screenshotDir!!, true)
    file = virtualFile.toPsiFile(projectRule.project)
    val function1 = file!!.findFunctionIdentifier("GreetingAndroid")
    val function2 = file!!.findFunctionIdentifier("GreetingPreview")
    val classElement = file!!.findClassIdentifier("PreviewScreenshotTest")
    val fun1Info = contributor.getSlowInfo(function1)
    val fun2Info = contributor.getSlowInfo(function2)
    val classInfo = contributor.getSlowInfo(classElement)
    val lastAction1 = fun1Info!!.actions.lastOrNull()
    val lastAction2 = fun2Info!!.actions.lastOrNull()

    assertNotNull(fun1Info)
    assertNotNull(fun2Info)
    assertNotNull(classInfo)
    assertEquals(updateReferenceImagesAction, lastAction1)
    assertEquals(updateReferenceImagesAction, lastAction2)
    assertEquals("Add/Update Reference Images...", lastAction1!!.templateText)
    assertEquals("Add/Update Reference Images...", lastAction2!!.templateText)
  }

  @Test
  @RunsInEdt
  fun testRunLineMarkerContributorNoPreviewTest() {
    val cFile =
      createRelativeFilewithContent(
        "app/src/screenshotTest/java/com/example/runlinemarker/PreviewScreenshotTest.kt",
        """
        $SRC_FILE_HEADER
        class PreviewScreenshotTest {
           @Preview(showBackground = true)
           @Composable
           fun GreetingAndroid() {
               println("Hello")
           }
        
           @Preview(showBackground = true)
           @Composable
           fun GreetingPreview() {
               println("Hi")
           }
        }
      """
          .trimIndent(),
      )
    val virtualFile = findFileByIoFile(cFile, true)
    val screenshotDir = virtualFile!!.parent.parent.parent.parent.parent
    PsiTestUtil.addSourceRoot(projectRule.fixture.module, screenshotDir!!, true)
    file = virtualFile.toPsiFile(projectRule.project)
    val function1 = file!!.findFunctionIdentifier("GreetingAndroid")
    val function2 = file!!.findFunctionIdentifier("GreetingPreview")
    val classElement = file!!.findClassIdentifier("PreviewScreenshotTest")
    val fun1Info = contributor.getSlowInfo(function1)
    val fun2Info = contributor.getSlowInfo(function2)
    val classInfo = contributor.getSlowInfo(classElement)
    assertNull(fun1Info)
    assertNull(fun2Info)
    assertNull(classInfo)
  }

  @Test
  @RunsInEdt
  fun testRunLineMarkerContributorOnlyPreviewTest() {
    val cFile =
      createRelativeFilewithContent(
        "app/src/screenshotTest/java/com/example/runlinemarker/PreviewScreenshotTest.kt",
        """
        $SRC_FILE_HEADER
        class PreviewScreenshotTest {
           @PreviewTest
           @Composable
           fun GreetingAndroid() {
               println("Hello")
           }
        
           @PreviewTest
           @Composable
           fun GreetingPreview() {
               println("Hi")
           }
        }
      """
          .trimIndent(),
      )
    val virtualFile = findFileByIoFile(cFile, true)
    val screenshotDir = virtualFile!!.parent.parent.parent.parent.parent
    PsiTestUtil.addSourceRoot(projectRule.fixture.module, screenshotDir!!, true)
    file = virtualFile.toPsiFile(projectRule.project)
    val function1 = file!!.findFunctionIdentifier("GreetingAndroid")
    val function2 = file!!.findFunctionIdentifier("GreetingPreview")
    val classElement = file!!.findClassIdentifier("PreviewScreenshotTest")
    val fun1Info = contributor.getSlowInfo(function1)
    val fun2Info = contributor.getSlowInfo(function2)
    val classInfo = contributor.getSlowInfo(classElement)
    val lastAction1 = fun1Info!!.actions.lastOrNull()
    val lastAction2 = fun2Info!!.actions.lastOrNull()

    assertNotNull(fun1Info)
    assertNotNull(fun2Info)
    assertNotNull(classInfo)
    assertEquals(updateReferenceImagesAction, lastAction1)
    assertEquals(updateReferenceImagesAction, lastAction2)
    assertEquals("Add/Update Reference Images...", lastAction1!!.templateText)
    assertEquals("Add/Update Reference Images...", lastAction2!!.templateText)
  }

  @Test
  @RunsInEdt
  fun testRunLineMarkerContributorMultiPreview() {
    val cFile =
      createRelativeFilewithContent(
        "app/src/screenshotTest/java/com/example/runlinemarker/PreviewScreenshotTest.kt",
        """
        $SRC_FILE_HEADER
        class PreviewScreenshotTest {
           @PreviewTest
           @MultiPreview(showBackground = true)
           @Composable
           fun GreetingAndroid() {
               println("Hello")
           }
        }
        
        @Preview(name = "with background", showBackground = true)
        @Preview(name = "without background", showBackground = false)
        annotation class MultiPreview
      """
          .trimIndent(),
      )
    val virtualFile = findFileByIoFile(cFile, true)
    val screenshotDir = virtualFile!!.parent.parent.parent.parent.parent
    PsiTestUtil.addSourceRoot(projectRule.fixture.module, screenshotDir!!, true)
    file = virtualFile.toPsiFile(projectRule.project)
    val function1 = file!!.findFunctionIdentifier("GreetingAndroid")
    val classElement = file!!.findClassIdentifier("PreviewScreenshotTest")
    val fun1Info = contributor.getSlowInfo(function1)
    val classInfo = contributor.getSlowInfo(classElement)
    val lastAction1 = fun1Info!!.actions.lastOrNull()

    assertNotNull(fun1Info)
    assertNotNull(classInfo)
    assertEquals(updateReferenceImagesAction, lastAction1)
    assertEquals("Add/Update Reference Images...", lastAction1!!.templateText)
  }

  @Test
  @RunsInEdt
  fun testRunLineMarkerContributorNoPreviewMethod() {
    val cFile =
      createRelativeFilewithContent(
        "app/src/screenshotTest/java/com/example/runlinemarker/PreviewScreenshotTest.kt",
        """
        $SRC_FILE_HEADER
        class PreviewScreenshotTest {
           @Composable
           fun GreetingAndroid() {
               println("Hello")
           }

           @Composable
           fun GreetingPreview() {
               println("Hi")
           }
        }
      """
          .trimIndent(),
      )
    val virtualFile = findFileByIoFile(cFile, true)
    val screenshotDir = virtualFile!!.parent.parent.parent.parent.parent
    PsiTestUtil.addSourceRoot(projectRule.fixture.module, screenshotDir!!, true)
    file = virtualFile.toPsiFile(projectRule.project)
    val function1 = file!!.findFunctionIdentifier("GreetingAndroid")
    val function2 = file!!.findFunctionIdentifier("GreetingPreview")
    val classElement = file!!.findClassIdentifier("PreviewScreenshotTest")
    assertNull(contributor.getSlowInfo(function1))
    assertNull(contributor.getSlowInfo(function2))
    assertNull(contributor.getSlowInfo(classElement))
  }

  @Test
  @RunsInEdt
  fun testRunLineMarkerContributorNoMethod() {
    val cFile =
      createRelativeFilewithContent(
        "app/src/screenshotTest/java/com/example/runlinemarker/PreviewScreenshotTest.kt",
        """
        $SRC_FILE_HEADER
        class PreviewScreenshotTest {
        }
      """
          .trimIndent(),
      )
    val virtualFile = findFileByIoFile(cFile, true)
    val screenshotDir = virtualFile!!.parent.parent.parent.parent.parent
    PsiTestUtil.addSourceRoot(projectRule.fixture.module, screenshotDir!!, true)
    file = virtualFile.toPsiFile(projectRule.project)
    val classElement = file!!.findClassIdentifier("PreviewScreenshotTest")
    assertNull(contributor.getSlowInfo(classElement))
  }

  @Test
  @RunsInEdt
  fun testGutterIconAppearance() {
    val cFile =
      createRelativeFilewithContent(
        "app/src/screenshotTest/java/com/example/runlinemarker/IconsTest.kt",
        """
            $SRC_FILE_HEADER
            class IconsTest {
                @PreviewTest
                @Composable
                fun TestMethod() {
                    println("Test")
                }
            }
        """
          .trimIndent(),
      )
    val virtualFile = findFileByIoFile(cFile, true)
    val screenshotDir = virtualFile!!.parent.parent.parent.parent.parent
    PsiTestUtil.addSourceRoot(projectRule.fixture.module, screenshotDir!!, true)
    file = virtualFile.toPsiFile(projectRule.project)

    val classIdentifier = file!!.findClassIdentifier("IconsTest")
    val methodIdentifier = file!!.findFunctionIdentifier("TestMethod")

    val classInfo = contributor.getSlowInfo(classIdentifier)
    val methodInfo = contributor.getSlowInfo(methodIdentifier)

    assertNotNull(classInfo)
    assertNotNull(methodInfo)

    // Verify: Double green triangle (Run_run) for class, single (Run) for method
    assertEquals(AllIcons.RunConfigurations.TestState.Run_run, classInfo!!.icon)
    assertEquals(AllIcons.RunConfigurations.TestState.Run, methodInfo!!.icon)
  }

  @Test
  @RunsInEdt
  fun testRunLineMarkerContributorTopLevelFunctions() {
    val cFile =
      createRelativeFilewithContent(
        "app/src/screenshotTest/java/com/example/runlinemarker/TopLevelScreenshotTest.kt",
        """
        $SRC_FILE_HEADER
        @PreviewTest
        @Preview(showBackground = true)
        @Composable
        fun TopLevelPreviewTestWithPreview() {
            println("Hello")
        }

        @PreviewTest
        @Composable
        fun TopLevelOnlyPreviewTest() {
            println("Hi")
        }

        @Preview(showBackground = true)
        @Composable
        fun TopLevelWithoutPreviewTest() {
            println("Not a screenshot test")
        }
        """
          .trimIndent(),
      )
    val virtualFile = findFileByIoFile(cFile, true)!!
    val screenshotDir = projectRule.fixture.tempDirFixture.findOrCreateDir("app/src/screenshotTest")
    PsiTestUtil.addSourceRoot(projectRule.fixture.module, screenshotDir, true)
    file = virtualFile.toPsiFile(projectRule.project)

    val topLevelWithPreview = file!!.findFunctionIdentifier("TopLevelPreviewTestWithPreview")
    val topLevelOnlyPreviewTest = file!!.findFunctionIdentifier("TopLevelOnlyPreviewTest")
    val topLevelWithoutPreviewTest = file!!.findFunctionIdentifier("TopLevelWithoutPreviewTest")

    val info1 = contributor.getSlowInfo(topLevelWithPreview)
    val info2 = contributor.getSlowInfo(topLevelOnlyPreviewTest)
    val info3 = contributor.getSlowInfo(topLevelWithoutPreviewTest)

    assertNull(contributor.getInfo(topLevelWithPreview))
    assertNull(contributor.getSlowInfo(topLevelWithPreview.parent))

    assertNotNull(info1)
    assertEquals(AllIcons.RunConfigurations.TestState.Run, info1!!.icon)
    assertEquals("Run screenshot tests", info1.tooltipProvider.apply(topLevelWithPreview))
    assertEquals(updateReferenceImagesAction, info1.actions.lastOrNull())
    assertEquals("Add/Update Reference Images...", info1.actions.lastOrNull()!!.templateText)

    assertNotNull(info2)
    assertEquals(AllIcons.RunConfigurations.TestState.Run, info2!!.icon)
    assertEquals(updateReferenceImagesAction, info2.actions.lastOrNull())
    assertEquals("Add/Update Reference Images...", info2.actions.lastOrNull()!!.templateText)

    assertNull(info3)
  }

  @Test
  @RunsInEdt
  fun testRunLineMarkerContributorNonDebuggableVariant() {
    val cFile =
      createRelativeFilewithContent(
        "app/src/screenshotTest/java/com/example/runlinemarker/PreviewScreenshotTest.kt",
        """
        $SRC_FILE_HEADER
        @PreviewTest
        @Preview(showBackground = true)
        @Composable
        fun TopLevelPreview() {
            println("Top level")
        }

        class PreviewScreenshotTest {
           @PreviewTest
           @Preview(showBackground = true)
           @Composable
           fun GreetingAndroid() {
               println("Hello")
           }
        }
        """
          .trimIndent(),
      )
    val virtualFile = findFileByIoFile(cFile, true)!!
    val screenshotDir = projectRule.fixture.tempDirFixture.findOrCreateDir("app/src/screenshotTest")
    PsiTestUtil.addSourceRoot(projectRule.fixture.module, screenshotDir, true)
    file = virtualFile.toPsiFile(projectRule.project)

    val topLevelFunction = file!!.findFunctionIdentifier("TopLevelPreview")
    val memberFunction = file!!.findFunctionIdentifier("GreetingAndroid")
    val classElement = file!!.findClassIdentifier("PreviewScreenshotTest")

    // Verify markers are present when the variant is debuggable
    assertNotNull(contributor.getSlowInfo(topLevelFunction))
    assertNotNull(contributor.getSlowInfo(memberFunction))
    assertNotNull(contributor.getSlowInfo(classElement))

    withNonDebuggableBuildTypes(projectRule.project) {
      val module = ModuleUtilCore.findModuleForPsiElement(classElement)!!
      assertFalse(module.getModuleSystem().isDebuggable)

      assertNull(contributor.getSlowInfo(topLevelFunction))
      assertNull(contributor.getSlowInfo(memberFunction))
      assertNull(contributor.getSlowInfo(classElement))
    }

    // Verify markers are restored once debuggable build types are restored
    assertNotNull(contributor.getSlowInfo(topLevelFunction))
    assertNotNull(contributor.getSlowInfo(memberFunction))
    assertNotNull(contributor.getSlowInfo(classElement))
  }

  private fun PsiFile.findFunctionIdentifier(name: String): PsiElement {
    val function = PsiTreeUtil.findChildrenOfType(this, KtNamedFunction::class.java).first { it.name == name }
    return PsiTreeUtil.getChildrenOfType(function, LeafPsiElement::class.java)?.first {
      it.node.elementType == KtTokens.IDENTIFIER
    }!!
  }

  private fun PsiFile.findClassIdentifier(name: String): PsiElement {
    val function = PsiTreeUtil.findChildrenOfType(this, KtClass::class.java).first { it.name == name }
    return PsiTreeUtil.getChildrenOfType(function, LeafPsiElement::class.java)?.first {
      it.node.elementType == KtTokens.IDENTIFIER
    }!!
  }

  private fun stubPreviewTestAnnotation() {
    createRelativeFilewithContent(
      "app/src/screenshotTest/java/com/android/testing/screenshot/PreviewTest.kt",
      """
      package com.android.tools.screenshot

      @MustBeDocumented
      @Retention(AnnotationRetention.BINARY)
      @Target(
          AnnotationTarget.FUNCTION
      )
      annotation class PreviewTest {
      }
          
      """
        .trimIndent(),
    )
  }

  private fun stubComposeAnnotation() {
    createRelativeFilewithContent(
      "app/src/screenshotTest/java/androidx/compose/runtime/Composable.kt",
      """
      package androidx.compose.runtime
      @Target(
          AnnotationTarget.FUNCTION,
          AnnotationTarget.TYPE_USAGE,
          AnnotationTarget.TYPE,
          AnnotationTarget.TYPE_PARAMETER,
          AnnotationTarget.PROPERTY_GETTER
      )
      annotation class Composable
      """
        .trimIndent(),
    )
  }

  private fun stubPreviewAnnotation() {
    createRelativeFilewithContent(
      "app/src/screenshotTest/java/androidx/compose/ui/tooling/preview/Preview.kt",
      """
    package androidx.compose.ui.tooling.preview

    import kotlin.reflect.KClass

    object Devices {
        const val DEFAULT = ""

        const val NEXUS_7 = "id:Nexus 7"
        const val NEXUS_10 = "name:Nexus 10"
    }


    @Repeatable
    annotation class Preview(
      val name: String = "",
      val group: String = "",
      val apiLevel: Int = -1,
      val theme: String = "",
      val widthDp: Int = -1,
      val heightDp: Int = -1,
      val locale: String = "",
      val fontScale: Float = 1f,
      val showDecoration: Boolean = false,
      val showBackground: Boolean = false,
      val backgroundColor: Long = 0,
      val uiMode: Int = 0,
      val device: String = ""
    )

    interface PreviewParameterProvider<T> {
        val values: Sequence<T>
        val count get() = values.count()
    }

    annotation class PreviewParameter(
        val provider: KClass<out PreviewParameterProvider<*>>,
        val limit: Int = Int.MAX_VALUE
    )
    """,
    )
  }

  private fun createRelativeFilewithContent(relativePath: String, content: String): File {
    val newFile = File(projectRule.project.basePath, FileUtils.toSystemDependentPath(relativePath))
    FileUtil.createIfDoesntExist(newFile)
    newFile.writeText(content)
    return newFile
  }
}
