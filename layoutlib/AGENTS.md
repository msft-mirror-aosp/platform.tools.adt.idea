# Layoutlib Integration Agent Guide

This module (`tools/adt/idea/layoutlib`) instantiates Layoutlib's `com.android.layoutlib.bridge.Bridge` in `LayoutlibProvider.getLibrary()` and supplies `LayoutlibClassLoader` (which dynamically generates `android.os.Build` via ASM from `_Original_Build`) as the framework `ClassLoader` to `LayoutLibrary.load(...)` for Android Studio and headless `standalone-render`.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../designer/AGENTS.md](../designer/AGENTS.md). Keep dual-targeted `tools.layoutlib` free of `@intellij//:intellij-sdk`, `intellij-core`, and `com.android.tools.idea.*` IDE plugin dependencies outside `com.android.tools.idea.layoutlib.*` (`tools.layoutlib-loader`) (`U1`).
