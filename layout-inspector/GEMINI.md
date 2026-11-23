# Project Memory
Update this file with any surprising or unexpected project-specific details discovered during work. This serves as a long-term memory to avoid rediscovering the same issues in future sessions.

Keep this file up to date as information changes/goes out of date.

# General information
- **bazel location**: `studio-main/tools/base/bazel/bazel`
- **how to build the module**: `bazel build //tools/adt/idea/layout-inspector:intellij.android.layout-inspector`
- **how to run all tests**: `bazel test //tools/adt/idea/layout-inspector:intellij.android.layout-inspector.tests_tests`
- **how to run a specific test**: `bazel test //tools/adt/idea/layout-inspector:intellij.android.layout-inspector.tests_tests --test_filter=MyTest`
  - **useful flags**: --test_output=errors: display the output of failed tests only.

## Related Documentation
- [Layout Inspector Architecture](docs/architecture.md)
- [Shared UI Tools Review Invariants](../designer/AGENTS.md)

## Code Review Invariants

Enforce these diff-scoped invariants on added or modified (`+`) lines in
`layout-inspector` (layering on foundational Studio EDT/lock, coroutine,
retention, and `Disposable` rules; see `U2` and `U5` in
[../designer/AGENTS.md](../designer/AGENTS.md) for general UI Tools buffer and
testing principles):

- **View tree locking (`ViewNode.readAccess` / `writeAccess`):** Guard reads
  and mutations of the `ViewNode` hierarchy with `ViewNode.readAccess` /
  `ViewNode.writeAccess`, and never invoke UI callbacks or blocking work while
  holding `ViewNode.writeAccess` (dispatch EDT listeners asynchronously via
  `invokeLater` or UI coroutine dispatchers).
- **Threading (`@Slow` off EDT, `@UiThread` for UI):** Keep device transport,
  bitmap/image decoding, `@Slow` resource/property lookups (`ResourceLookup`,
  property resolution), and non-trivial `InspectorModel` tree updates off the
  Swing EDT (the lightweight `DisconnectedClient` model clear via `invokeLater`
  in `LayoutInspector.kt` is an explicit exception). Keep Swing UI mutations on
  `@UiThread` / EDT.
- **Buffer & `Disposable` lifecycle:** Register stateful listeners,
  connections, and models with `Disposer.register(parentDisposable, ...)`,
  dispose `Graphics2D` handles created via `Graphics.create()` or `BufferedImage.createGraphics()` in `finally` blocks,
  and bound retained snapshot/bitmap buffers across inspection sessions.
- **Deterministic tests:** Accompany protocol, tree-merging, and recomposition
  state fixes with deterministic unit tests using
  `TestLayoutInspectorModelBuilder.kt` DSL helpers (`model(...)`,
  `viewWindow(...)`) and virtual coroutine time (`runTest`) rather than
  `Thread.sleep`.
