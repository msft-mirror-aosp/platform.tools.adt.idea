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
package com.android.tools.idea.compose.preview.scene

import com.android.ide.common.rendering.api.ViewInfo
import com.android.mockito.kotlin.whenever
import com.android.tools.idea.compose.preview.ComposeViewInfo
import com.android.tools.idea.compose.preview.InteractivePreviewNavigationController
import com.android.tools.idea.compose.preview.PxBounds
import com.android.tools.idea.compose.preview.SourceLocationImpl
import com.android.tools.idea.compose.preview.TestComposePreviewManager
import com.android.tools.idea.compose.preview.parseViewInfo
import com.android.tools.idea.preview.analytics.InteractiveNopTracker
import com.android.tools.idea.preview.modes.PreviewMode
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.PreviewDisplaySettings
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.android.tools.rendering.RenderResult
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class FakeComposeViewAdapter {
  @Suppress("unused", "PrivatePropertyName") // This property is called via reflection
  private val FakeOnBackPressedDispatcherOwner =
    object : Any() {
      // We can perform back navigation
      fun onBackPressCompleted() {}
    }
}

class InteractivePreviewBackNavigationUpdaterTest {
  lateinit var myInteractivePreviewNavigationController: InteractivePreviewNavigationController

  @Before
  fun setUp() {
    myInteractivePreviewNavigationController =
      InteractivePreviewNavigationController({ InteractiveNopTracker() }, fpsUpdater = MutableSharedFlow())
  }

  @After
  fun tearDown() {
    // Clear all cached ClassLoader entries after every test to keep tests isolated
    InteractivePreviewBackNavigationUpdater.resetForTest()
  }

  val composable =
    SingleComposePreviewElementInstance(
      "composableMethodName",
      PreviewDisplaySettings(
        name = "a name",
        baseName = "BaseName",
        parameterName = "ParameterName",
        group = null,
        showDecoration = false,
        background = PreviewDisplaySettings.Background.None,
        organizationGroup = "organizationGroup",
      ),
      null,
      null,
      PreviewConfiguration.cleanAndGet(),
    )

  @Test
  fun `check backDispatcher from ComposeViewAdapter is only set in interactive mode`() {
    val previewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Default()) }
    val layoutlibSceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(FakeComposeViewAdapter()) }
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = previewManager,
      layoutlibSceneManager = layoutlibSceneManagerMock,
      interactivePreviewNavigationController = myInteractivePreviewNavigationController,
    )
    assertThat(myInteractivePreviewNavigationController.canPerformBackNavigation()).isFalse()

    previewManager.setMode(PreviewMode.Interactive(composable))

    InteractivePreviewBackNavigationUpdater.update(
      previewManager = previewManager,
      layoutlibSceneManager = layoutlibSceneManagerMock,
      interactivePreviewNavigationController = myInteractivePreviewNavigationController,
    )
    assertThat(myInteractivePreviewNavigationController.canPerformBackNavigation()).isTrue()
  }

  @Test
  fun `check updateObjects is called with no NavDisplay in code`() {
    val previewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Interactive(composable)) }

    runWithMockedParseViewInfo(viewInfoChildName = "Text") { layoutlibSceneManagerMock, controllerMock ->
      InteractivePreviewBackNavigationUpdater.update(
        previewManager = previewManager,
        layoutlibSceneManager = layoutlibSceneManagerMock,
        interactivePreviewNavigationController = controllerMock,
      )

      // We expect updateObject method is calling with false [hasNavDisplay].
      verify(controllerMock)
        .updateObjects(
          currentNavigationEventDispatcherOwnerObj = anyOrNull(),
          currentComposeViewAdapterObj = any(),
          hasNavDisplay = eq(false),
        )
    }
  }

  @Test
  fun `check updateObjects is called with NavDisplay in code`() {
    val previewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Interactive(composable)) }

    runWithMockedParseViewInfo(viewInfoChildName = "NavDisplay") { layoutlibSceneManagerMock, controllerMock ->
      InteractivePreviewBackNavigationUpdater.update(
        previewManager = previewManager,
        layoutlibSceneManager = layoutlibSceneManagerMock,
        interactivePreviewNavigationController = controllerMock,
      )

      // We expect updateObject method is calling with true [hasNavDisplay].
      verify(controllerMock)
        .updateObjects(
          currentNavigationEventDispatcherOwnerObj = anyOrNull(),
          currentComposeViewAdapterObj = any(),
          hasNavDisplay = eq(true),
        )
    }
  }

  /** Helper to run a test block with a mocked `parseViewInfo` returning a view tree containing a child with the given name. */
  private fun runWithMockedParseViewInfo(
    viewInfoChildName: String,
    block: (LayoutlibSceneManager, InteractivePreviewNavigationController) -> Unit,
  ) {
    val layoutlibSceneManagerMock = createLayoutLibManager()
    val controllerMock = mock<InteractivePreviewNavigationController>()

    val sourceLocation = SourceLocationImpl("file", 1, 1)
    val childInfo = ComposeViewInfo(sourceLocation, PxBounds(0, 0, 0, 0), emptyList(), viewInfoChildName)
    val rootInfo = ComposeViewInfo(sourceLocation, PxBounds(0, 0, 0, 0), listOf(childInfo), "Root")

    // Mock parseViewInfo using Class.forName because ComposeViewInfoParserKt is not on the test classpath.
    // This is needed because the local ComposeViewAdapter package mismatch causes findComposeViewAdapter to return null.
    val parserClass = Class.forName("com.android.tools.idea.compose.preview.ComposeViewInfoParserKt")
    Mockito.mockStatic(parserClass).use { mockParser ->
      mockParser.whenever<List<ComposeViewInfo>> { parseViewInfo(any(), any()) }.thenReturn(listOf(rootInfo))

      block(layoutlibSceneManagerMock, controllerMock)
    }
  }

  /**
   * Creates a mocked [LayoutlibSceneManager] that simulates a render result returning a mocked [ViewInfo].
   *
   * @return a mocked [LayoutlibSceneManager] instance.
   */
  private fun createLayoutLibManager(): LayoutlibSceneManager =
    mock<LayoutlibSceneManager>().apply {

      // Create a fake [ComposeViewAdapter] with the specified navigation display state
      val fakeComposeViewAdapter = FakeComposeViewAdapter()

      val fakeViewInfo = ViewInfo("androidx.compose.ui.tooling.ComposeViewAdapter", null, 0, 0, 0, 0, fakeComposeViewAdapter, null, null)

      // Mock the render result to return the fake [ViewInfo] as its root view
      val renderResultMock = mock<RenderResult>().apply { whenever(rootViews).thenReturn(ImmutableList.of(fakeViewInfo)) }

      // Set up the layoutlib scene manager mock to return the fake view adapter and render result
      whenever(viewObject).thenReturn(fakeComposeViewAdapter)
      whenever(renderResult).thenReturn(renderResultMock)
    }

  @Test
  fun `setNavigationEventDispatcherOwner is cleared when not in interactive mode`() {
    // Register a dispatcher owner loaded by an isolated preview ClassLoader
    val previewClassLoader = createIsolatedClassLoader()
    val dispatcherOwner = newInstanceFromClassLoader(previewClassLoader)
    val viewAdapter = newInstanceFromClassLoader(previewClassLoader)
    val sceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(viewAdapter) }
    InteractivePreviewBackNavigationUpdater.setNavigationEventDispatcherOwner(dispatcherOwner)

    // Verify interactive mode resolves the registered dispatcher owner
    val interactivePreviewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Interactive(composable)) }
    val initialControllerMock = mock<InteractivePreviewNavigationController>()
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = interactivePreviewManager,
      layoutlibSceneManager = sceneManagerMock,
      interactivePreviewNavigationController = initialControllerMock,
    )
    verify(initialControllerMock)
      .updateObjects(
        currentNavigationEventDispatcherOwnerObj = eq(dispatcherOwner),
        currentComposeViewAdapterObj = eq(viewAdapter),
        hasNavDisplay = eq(false),
      )

    // Switch to non-interactive mode and run update() to clear that ClassLoader's dispatcher owner
    val defaultPreviewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Default()) }
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = defaultPreviewManager,
      layoutlibSceneManager = sceneManagerMock,
      interactivePreviewNavigationController = myInteractivePreviewNavigationController,
    )

    // Verify a subsequent interactive update() no longer resolves the cleared dispatcher owner
    val subsequentControllerMock = mock<InteractivePreviewNavigationController>()
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = interactivePreviewManager,
      layoutlibSceneManager = sceneManagerMock,
      interactivePreviewNavigationController = subsequentControllerMock,
    )
    verify(subsequentControllerMock)
      .updateObjects(
        currentNavigationEventDispatcherOwnerObj = eq(null),
        currentComposeViewAdapterObj = eq(viewAdapter),
        hasNavDisplay = eq(false),
      )
  }

  @Test
  fun `non-interactive update with null viewObject is a no-op and preserves active ClassLoader owners`() {
    // Register a dispatcher owner for an active preview ClassLoader
    val activeClassLoader = createIsolatedClassLoader()
    val activeDispatcherOwner = newInstanceFromClassLoader(activeClassLoader)
    val activeViewAdapter = newInstanceFromClassLoader(activeClassLoader)
    InteractivePreviewBackNavigationUpdater.setNavigationEventDispatcherOwner(activeDispatcherOwner)

    // Run a non-interactive update() on a scene whose viewObject is null and verify the controller is untouched
    val nullViewSceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(null) }
    val defaultModeControllerMock = mock<InteractivePreviewNavigationController>()
    val defaultPreviewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Default()) }
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = defaultPreviewManager,
      layoutlibSceneManager = nullViewSceneManagerMock,
      interactivePreviewNavigationController = defaultModeControllerMock,
    )
    verifyNoInteractions(defaultModeControllerMock)

    // Verify the active ClassLoader's dispatcher owner is still resolved after the non-interactive null-viewObject update
    val interactivePreviewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Interactive(composable)) }
    val activeSceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(activeViewAdapter) }
    val activeControllerMock = mock<InteractivePreviewNavigationController>()
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = interactivePreviewManager,
      layoutlibSceneManager = activeSceneManagerMock,
      interactivePreviewNavigationController = activeControllerMock,
    )
    verify(activeControllerMock)
      .updateObjects(
        currentNavigationEventDispatcherOwnerObj = eq(activeDispatcherOwner),
        currentComposeViewAdapterObj = eq(activeViewAdapter),
        hasNavDisplay = eq(false),
      )
  }

  @Test
  fun `interactive update with null viewObject is a no-op and preserves active ClassLoader owners`() {
    // Register a dispatcher owner for an active preview ClassLoader
    val activeClassLoader = createIsolatedClassLoader()
    val activeDispatcherOwner = newInstanceFromClassLoader(activeClassLoader)
    val activeViewAdapter = newInstanceFromClassLoader(activeClassLoader)
    InteractivePreviewBackNavigationUpdater.setNavigationEventDispatcherOwner(activeDispatcherOwner)

    // Run an interactive update() on a scene whose viewObject is null and verify the controller is untouched
    val nullViewSceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(null) }
    val interactiveModeControllerMock = mock<InteractivePreviewNavigationController>()
    val interactivePreviewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Interactive(composable)) }
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = interactivePreviewManager,
      layoutlibSceneManager = nullViewSceneManagerMock,
      interactivePreviewNavigationController = interactiveModeControllerMock,
    )
    verifyNoInteractions(interactiveModeControllerMock)

    // Verify the active ClassLoader's dispatcher owner is still resolved after the interactive null-viewObject update
    val activeSceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(activeViewAdapter) }
    val activeControllerMock = mock<InteractivePreviewNavigationController>()
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = interactivePreviewManager,
      layoutlibSceneManager = activeSceneManagerMock,
      interactivePreviewNavigationController = activeControllerMock,
    )
    verify(activeControllerMock)
      .updateObjects(
        currentNavigationEventDispatcherOwnerObj = eq(activeDispatcherOwner),
        currentComposeViewAdapterObj = eq(activeViewAdapter),
        hasNavDisplay = eq(false),
      )
  }

  @Test
  fun `update resolves dispatcher owner matching ComposeViewAdapter ClassLoader across concurrent previews`() {
    // Create two isolated classloaders to simulate two split editor tabs rendering concurrently
    val firstTabClassLoader = createIsolatedClassLoader()
    val secondTabClassLoader = createIsolatedClassLoader()
    val firstTabDispatcherOwner = newInstanceFromClassLoader(firstTabClassLoader)
    val firstTabViewAdapter = newInstanceFromClassLoader(firstTabClassLoader)
    val secondTabDispatcherOwner = newInstanceFromClassLoader(secondTabClassLoader)
    val secondTabViewAdapter = newInstanceFromClassLoader(secondTabClassLoader)

    // First tab registers its dispatcher owner during render, then second tab registers its own owner before first tab's update()
    InteractivePreviewBackNavigationUpdater.setNavigationEventDispatcherOwner(firstTabDispatcherOwner)
    InteractivePreviewBackNavigationUpdater.setNavigationEventDispatcherOwner(secondTabDispatcherOwner)

    // Set up scene managers and controllers for each split editor tab
    val previewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Interactive(composable)) }
    val firstTabSceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(firstTabViewAdapter) }
    val secondTabSceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(secondTabViewAdapter) }
    val firstTabControllerMock = mock<InteractivePreviewNavigationController>()
    val secondTabControllerMock = mock<InteractivePreviewNavigationController>()

    // Verify first tab's update() resolves firstTabDispatcherOwner rather than secondTabDispatcherOwner
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = previewManager,
      layoutlibSceneManager = firstTabSceneManagerMock,
      interactivePreviewNavigationController = firstTabControllerMock,
    )
    verify(firstTabControllerMock)
      .updateObjects(
        currentNavigationEventDispatcherOwnerObj = eq(firstTabDispatcherOwner),
        currentComposeViewAdapterObj = eq(firstTabViewAdapter),
        hasNavDisplay = eq(false),
      )

    // Verify second tab's update() resolves secondTabDispatcherOwner
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = previewManager,
      layoutlibSceneManager = secondTabSceneManagerMock,
      interactivePreviewNavigationController = secondTabControllerMock,
    )
    verify(secondTabControllerMock)
      .updateObjects(
        currentNavigationEventDispatcherOwnerObj = eq(secondTabDispatcherOwner),
        currentComposeViewAdapterObj = eq(secondTabViewAdapter),
        hasNavDisplay = eq(false),
      )
  }

  @Test
  fun `non-interactive update in one ClassLoader does not clear another ClassLoader owner`() {
    // Create two isolated classloaders representing an active interactive tab and a non-interactive tab
    val interactiveTabClassLoader = createIsolatedClassLoader()
    val defaultTabClassLoader = createIsolatedClassLoader()
    val interactiveTabOwner = newInstanceFromClassLoader(interactiveTabClassLoader)
    val interactiveTabViewAdapter = newInstanceFromClassLoader(interactiveTabClassLoader)
    val defaultTabOwner = newInstanceFromClassLoader(defaultTabClassLoader)
    val defaultTabViewAdapter = newInstanceFromClassLoader(defaultTabClassLoader)

    // Register dispatcher owners for both tabs
    InteractivePreviewBackNavigationUpdater.setNavigationEventDispatcherOwner(interactiveTabOwner)
    InteractivePreviewBackNavigationUpdater.setNavigationEventDispatcherOwner(defaultTabOwner)

    // Run a non-interactive update on the second tab
    val defaultPreviewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Default()) }
    val defaultTabSceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(defaultTabViewAdapter) }
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = defaultPreviewManager,
      layoutlibSceneManager = defaultTabSceneManagerMock,
      interactivePreviewNavigationController = myInteractivePreviewNavigationController,
    )

    // Verify the interactive tab's owner is still resolved by its ClassLoader
    val interactivePreviewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Interactive(composable)) }
    val interactiveTabSceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(interactiveTabViewAdapter) }
    val interactiveTabControllerMock = mock<InteractivePreviewNavigationController>()
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = interactivePreviewManager,
      layoutlibSceneManager = interactiveTabSceneManagerMock,
      interactivePreviewNavigationController = interactiveTabControllerMock,
    )
    verify(interactiveTabControllerMock)
      .updateObjects(
        currentNavigationEventDispatcherOwnerObj = eq(interactiveTabOwner),
        currentComposeViewAdapterObj = eq(interactiveTabViewAdapter),
        hasNavDisplay = eq(false),
      )

    // Verify the non-interactive tab's ClassLoader owner was cleared by its non-interactive update()
    val defaultTabControllerMock = mock<InteractivePreviewNavigationController>()
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = interactivePreviewManager,
      layoutlibSceneManager = defaultTabSceneManagerMock,
      interactivePreviewNavigationController = defaultTabControllerMock,
    )
    verify(defaultTabControllerMock)
      .updateObjects(
        currentNavigationEventDispatcherOwnerObj = eq(null),
        currentComposeViewAdapterObj = eq(defaultTabViewAdapter),
        hasNavDisplay = eq(false),
      )
  }

  @Test
  fun `controller clear removes its own ClassLoader owner without clearing another ClassLoader owner`() {
    // Create two isolated classloaders representing two active interactive preview tabs
    val firstTabClassLoader = createIsolatedClassLoader()
    val secondTabClassLoader = createIsolatedClassLoader()
    val firstTabOwner = newInstanceFromClassLoader(firstTabClassLoader)
    val firstTabViewAdapter = newInstanceFromClassLoader(firstTabClassLoader)
    val secondTabOwner = newInstanceFromClassLoader(secondTabClassLoader)
    val secondTabViewAdapter = newInstanceFromClassLoader(secondTabClassLoader)

    // Register both owners and initialize a real controller on the first tab
    InteractivePreviewBackNavigationUpdater.setNavigationEventDispatcherOwner(firstTabOwner)
    InteractivePreviewBackNavigationUpdater.setNavigationEventDispatcherOwner(secondTabOwner)
    val interactivePreviewManager = TestComposePreviewManager().apply { setMode(PreviewMode.Interactive(composable)) }
    val firstTabSceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(firstTabViewAdapter) }
    val secondTabSceneManagerMock = mock<LayoutlibSceneManager>().apply { whenever(viewObject).thenReturn(secondTabViewAdapter) }
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = interactivePreviewManager,
      layoutlibSceneManager = firstTabSceneManagerMock,
      interactivePreviewNavigationController = myInteractivePreviewNavigationController,
    )

    // Stop interactive mode on the first tab via controller.clear()
    myInteractivePreviewNavigationController.clear()

    // Verify the first tab's ClassLoader owner was removed from the updater
    val firstTabControllerMock = mock<InteractivePreviewNavigationController>()
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = interactivePreviewManager,
      layoutlibSceneManager = firstTabSceneManagerMock,
      interactivePreviewNavigationController = firstTabControllerMock,
    )
    verify(firstTabControllerMock)
      .updateObjects(
        currentNavigationEventDispatcherOwnerObj = eq(null),
        currentComposeViewAdapterObj = eq(firstTabViewAdapter),
        hasNavDisplay = eq(false),
      )

    // Verify the second tab's ClassLoader owner remains intact
    val secondTabControllerMock = mock<InteractivePreviewNavigationController>()
    InteractivePreviewBackNavigationUpdater.update(
      previewManager = interactivePreviewManager,
      layoutlibSceneManager = secondTabSceneManagerMock,
      interactivePreviewNavigationController = secondTabControllerMock,
    )
    verify(secondTabControllerMock)
      .updateObjects(
        currentNavigationEventDispatcherOwnerObj = eq(secondTabOwner),
        currentComposeViewAdapterObj = eq(secondTabViewAdapter),
        hasNavDisplay = eq(false),
      )
  }

  /** Creates an isolated [ClassLoader] that defines [FakeComposeViewAdapter] independently from the parent classloader. */
  private fun createIsolatedClassLoader(): ClassLoader {
    val adapterClassName = FakeComposeViewAdapter::class.java.name
    val parentClassLoader = FakeComposeViewAdapter::class.java.classLoader
    return object : ClassLoader(parentClassLoader) {
      override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith(adapterClassName)) {
          // Return the already-loaded class first to avoid re-reading .class bytes on repeated lookups
          findLoadedClass(name)?.let { loadedClass ->
            return loadedClass
          }
          // Define FakeComposeViewAdapter and its inner classes directly inside this isolated ClassLoader
          val classResourcePath = name.replace('.', '/') + ".class"
          val classBytes =
            checkNotNull(parentClassLoader.getResourceAsStream(classResourcePath)).use {
              it.readAllBytes()
            }
          return defineClass(name, classBytes, 0, classBytes.size)
        }
        return super.loadClass(name, resolve)
      }
    }
  }

  /** Instantiates a new [FakeComposeViewAdapter] loaded by [isolatedClassLoader]. */
  private fun newInstanceFromClassLoader(isolatedClassLoader: ClassLoader): Any =
    isolatedClassLoader.loadClass(FakeComposeViewAdapter::class.java.name).getDeclaredConstructor().newInstance()
}
