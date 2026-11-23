# Layoutlib Loader Agent Guide

This module (`tools/adt/idea/layoutlib-loader`) loads the Layoutlib `Bridge` and native rendering libraries for IDE and headless `standalone-render` sessions.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../designer/AGENTS.md](../designer/AGENTS.md). Keep dual-targeted `tools.layoutlib-loader` free of `@intellij//:intellij-sdk`, `intellij-core`, and other `com.android.tools.idea.*` IDE plugin dependencies (`U1`). Keep `layoutlib-loader/src/` Java-only: `tools.layoutlib-loader` is a `java_library` (`srcs = glob(["src/**/*.java"])`), so adding `.kt` files compiles in `intellij.android.layoutlib-loader` while silently excluding them from `tools.layoutlib-loader`.
