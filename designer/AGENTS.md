# Designer Agent Guide

This document describes how an AI agent can interact with the Layout Editor (Designer).

## Key Entry Points

### 1. Structural Changes and Undo
Structural changes to the layout (adding/removing/reordering components) MUST be wrapped in an `NlWriteCommandAction`.
- Use `NlWriteCommandActionUtil.run()` to perform atomic updates.
- **Example**:
    ```java
    NlWriteCommandActionUtil.run(component, "Add Widget", () -> {
        model.addComponent(newComponent, parent, null);
    });
    ```

### 2. Scene vs Model
- **NlModel**: Represents the "Source of Truth" (the XML). Use this for data-driven changes (attributes, hierarchy).
- **Scene**: Represents the "Interaction Layer". Use `SceneManager` to calculate layout positions or handle constraints without touching the XML immediately.
- **`NlComponentBackend`**: ([`NlComponentBackend.kt`](src/com/android/tools/idea/common/model/NlComponentBackend.kt)) Agents should use the backend to perform low-level XML operations. `NlComponent.tag` provides the underlying `XmlTag`.

### 3. View Modification
- **Attributes**: Use `NlComponent.setAttribute()` for simple changes. This maintains the internal cache.
- **Transactions**: For multiple attribute changes, use `AttributesTransaction` ([`AttributesTransaction.java`](src/com/android/tools/idea/common/model/AttributesTransaction.java)) to batch PSI events and improve performance.
- **Clean-up**: Always call `backend.reformatAndRearrange()` after significant XML edits to maintain file style and consistent indentation.

## Interaction Handling
- **`InteractionHandler`**: ([`InteractionHandler.java`](src/com/android/tools/idea/uibuilder/surface/InteractionHandler.java)) The entry point for mouse/keyboard events. Agents can simulate user input by invoking `createInteractionOn()` for specific ViewHandlers.

## Important Considerations for Agents
- **Dumb Mode**: Many operations (like finding a View's handler based on its FQN) require indices. Check `DumbService.isDumb(project)` before performing actions that depend on symbol resolution.
- **Asynchronous Rendering**: Previews are rendered in-process using Layoutlib. Use `RenderService.getLastRenderResult()` to check if the current view is up-to-date. If `renderResult.isValid()` returns false, the image may be stale.

## Examples and Testing
- **Integration Tests**: `com.android.tools.idea.uibuilder.surface.NlDesignSurfaceTest` shows how to mock user interactions (clicks, drags) on the surface.
- **Constraint Layout**: `com.android.tools.idea.uibuilder.handlers.constraint.ConstraintLayoutHandlerTest` demonstrates how to test complex auto-connect logic.

### How to run tests
Use Bazel for the core designer tests:
```bash
bazel test //tools/adt/idea/designer:intellij.android.designer.tests_tests
```

## Related Documentation
- [Layout Editor Architecture](docs/architecture.md)

## Code Review Invariants (UI Tools)

Enforce these diff-scoped invariants on added or modified (`+`) lines across UI
Tools IDE modules (`designer`, `compose-designer`, `preview-designer`,
`preview-elements`, `preview-fast-compile`, `rendering`, `render-resources`,
`layoutlib`, `layoutlib-loader`, `layout-ui`, `android-material`,
`design-plugin`, `designer-perf-tests`, `wear-designer`,
`wear-preview-elements`, `wear-whs`, `glance-designer`, `nav/editor`, split
UI Tools test modules `android/rendering`, `android/editors.fast`,
`android/editors.strings`, `android/ui.resourcemanager`,
`android/ui.designer.overlays`, and UI Tools
packages under `android/src/`; see
[../layout-inspector/GEMINI.md](../layout-inspector/GEMINI.md) for Layout
Inspector-specific invariants), layering on foundational Studio EDT/lock,
coroutine, retention, and `Disposable` rules. Note: legacy UI Tools tests
still under `android/testSrc/com/android/tools/idea/{editors/fonts,fonts,rendering,ui/resourcemanager}` and
`android/jetbrains/testSrc/org/jetbrains/android/uipreview/`, shared test
helpers in `android-test-framework/testSrc/com/android/tools/idea/editors/fast`
(depended on by `android/editors.fast/BUILD`), and configuration code under
`android/src/com/android/tools/idea/configurations/` sit outside per-package
`AGENTS.md` / `**/testSrc/**` discovery for standalone CLs (CLs that also
touch paired UI Tools `android/src/` packages inherit `U1`–`U5`):

### U1. Host IDE vs. Guest Runtime & `ModuleClassLoader` Isolation
- **Rule:** Cross-classloader communication between IDE host classes and
  user/guest preview classes loaded inside `ModuleClassLoader` must use
  reflection (`ViewLoader`, `ComposeViewAdapter` / AndroidX `ComposableInvoker`
  bridges), ASM bytecode transforms, or JDK/primitive bridge contracts—never
  direct casts across classloaders or uncleaned host `ThreadLocal` state on the
  `Layoutlib Render Thread` (which pins reloaded `ModuleClassLoader`s across
  Fast Preview updates). On the `Layoutlib Render Thread`, never call
  `runBlocking` for work that dispatches onto `Dispatchers.Main` (host EDT or
  Layoutlib's `LayoutlibDispatcherFactory` inside `ModuleClassLoader`,
  `ComposeViewAdapter`, or `RenderSessionCleaner`). Dual-targeted `tools.*`
  modules consumed by headless `standalone-render` or non-IDE targets
  (`rendering`, `render-resources`, `layoutlib`, `layoutlib-loader`,
  `preview-elements`, `wear-preview-elements`, `preview-fast-compile`) must
  never depend on `@intellij//:intellij-sdk` or `com.android.tools.idea.*` IDE
  plugin targets/source imports outside `com.android.tools.idea.layoutlib.*`
  (`tools.layoutlib-loader`) in their `tools.*` targets or shared sources;
  only `tools.rendering` and `tools.render-resources` may link headless
  `//prebuilts/tools/common/lint-psi/intellij-core`, while the other 5
  `tools.*` targets must remain free of both `@intellij//:intellij-sdk` and
  `intellij-core`.
- **Why:** Direct cross-classloader references throw `ClassCastException` /
  `LinkageError` or leak reloaded `ModuleClassLoader` instances, `runBlocking`
  on the single-threaded `Layoutlib Render Thread` deadlocks coroutines
  scheduled onto `Dispatchers.Main` via `LayoutlibDispatcherFactory`, and IDE
  dependencies in dual-targeted `tools.*` libraries break headless CLI and CI
  rendering.
- **Exceptions:** Host-side IDE coordinators outside the render thread may use
  IntelliJ SDK APIs provided values crossing into `ModuleClassLoader` stick to
  JDK/bridge types.

### U2. `RenderTask`, `RenderResult`, `Graphics2D` & Preview Image Buffer Lifecycle
- **Rule:** Dispose every `RenderTask` (via `task.disposeAsync()`), every
  returned `RenderResult` via `Disposer.dispose(result)` (never raw
  `result.dispose()`, which bypasses child `Disposable`s registered on
  `result`), every batch/in-memory preview render session, and every
  `Graphics2D` created via `BufferedImage.createGraphics()` (`g2d.dispose()`)
  in `finally` / `whenComplete` blocks across success, timeout, cancellation,
  and exception paths (`RenderTask.disposeAsync()` does not dispose
  already-returned `RenderResult` image buffers). Key preview, icon, and glyph caches on **all
  resolved rendering parameters** (optical size `opsz`, variation axes, density
  / HiDPI scale, and theme—never raw unnormalized caller strings), defensively
  isolate mutable `ByteArray` / `BufferedImage` backing buffers, and release
  decoded bitmaps when the hosting preview surface hides or disposes.
- **Anti-pattern:**
  ```kotlin
  val task = builder.build().await() ?: return null
  val raw = task.render().await().renderedImage.copy ?: return null // Leaks RenderTask & RenderResult!
  val g = out.createGraphics()
  g.drawImage(raw, 0, 0, w, h, null) // Graphics2D never disposed!
  ```
- **Preferred pattern:**
  ```kotlin
  val task = builder.build().await() ?: return null
  try {
    val result = task.render().await()
    try {
      val raw = result.renderedImage.copy ?: return null
      val g = out.createGraphics()
      try {
        g.drawImage(raw, 0, 0, w, h, null)
      } finally {
        g.dispose()
      }
    } finally {
      Disposer.dispose(result)
    }
  } finally {
    task.disposeAsync()
  }
  ```
- **Exceptions:** Shared `RenderTask`s or `RenderResult`s whose ownership is
  explicitly transferred to a `LayoutlibSceneManager` or `Disposable` container
  that guarantees `task.disposeAsync()` / `Disposer.dispose(result)` across
  error and replacement paths.

### U3. Off-EDT `@Slow` Resource Lookups, Post-Suspension Validity & Multiplatform `@Preview`
- **Rule:** Never call `@Slow` rendering or resource APIs
  (`ConfigurationManager.getConfiguration`, `ConfigurationManager.getDevices`,
  `StudioResourceRepositoryManager.getAppResources`,
  `ResourceNotificationManager.getCurrentVersion`, `NlModel.Builder.build()`)
  on the Swing EDT. After suspending on background/IO threads before UI actions
  (such as `AbstractPreviewNavigationHandler.navigateTo`,
  `NavigationHandler.navigateTo`, or preview click handlers), re-check
  validity on EDT before opening editors or mutating UI (e.g.,
  `!sceneView.sceneManager.model.project.isDisposed && navigatable.canNavigate()`,
  or `OpenFileDescriptor.file.isValid` / PSI `isValid` in suspending callers). In `@Preview` and `@PreviewParameter`
  discovery, support both AndroidX and KMP (`commonMain`) annotations
  (`COMPOSE_PREVIEW_ANNOTATION_FQN` / `MULTIPLATFORM_PREVIEW_ANNOTATION_FQN`
  and `COMPOSE_PREVIEW_PARAMETER_ANNOTATION_FQN` /
  `MULTIPLATFORM_PREVIEW_PARAMETER_ANNOTATION_FQN`) unless explicitly
  Android-only, and handle both explicit and wildcard (`*`) imports. When
  creating synthetic Kotlin source `LightVirtualFile`s for in-memory preview
  compilation (distinct from XML layout adapters such as
  `ComposeAdapterLightVirtualFile` / `InMemoryLayoutVirtualFile`), pass
  `KotlinFileType.INSTANCE` rather than inheriting a context file's `fileType`.
- **Exceptions:** Explicitly Android-only XML layout tools (such as 9-patch or
  manifest theme editors) that do not process `@Composable` functions.

### U4. Jewel / Compose Desktop Accessibility & Theme Tokens
- **Rule:** Interactive Compose Desktop / Jewel controls in preview pickers,
  canvas toolbars, and diff viewers (`Modifier.clickable`, icon actions, toggle
  chips) must set explicit semantics (`role = Role.Button` or matching `Role`),
  unique non-colliding `contentDescription` labels across multi-preview grids,
  and theme tokens rather than hardcoded light-mode colors.
- **Exceptions:** Decorative icons inside a parent container that already
  provides a complete accessible label and `Role`, either via
  `mergeDescendants = true` or implicitly merging clickable/button containers
  such as Jewel `Button` / `OutlinedButton` (e.g., `NavigationControlsPanel.kt`).

### U5. Anti-Fragile UI Tools Regression Testing
- **Rule:** Accompany bug fixes (`Bug:` / `Fixes:`) and behavioral changes in
  preview rendering, Fast Preview transforms, `ModuleClassLoader`, or Layout
  Inspector protocols with targeted, deterministic unit tests when a testable
  seam exists. Avoid fragile deep mocks of internal Compose runtime structures
  (`LayoutNode`, `CompositionData`, slot tables) or `FileEditorManager`
  internals; prefer real `@Composable` / PSI fixtures (`AndroidProjectRule`),
  virtual coroutine time (`runTest`, `advanceUntilIdle` instead of
  `Thread.sleep`), and `finally`-guarded `PsiTestUtil.removeSourceRoot(...)`
  cleanup so shared `ProjectRule` suites, `Bleak`, and
  `LastInIdeaTestSuite#checkForLeaks` do not leak.
- **Exceptions:** Docs/OWNERS/config-only CLs, UI copy tweaks already covered by
  snapshot tests, or device-specific bugs whose commit message explains why a
  unit test is infeasible.
