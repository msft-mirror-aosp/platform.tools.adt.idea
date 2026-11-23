# Render Resources Agent Guide

This module (`tools/adt/idea/render-resources`) provides resource resolution, configuration management, and font/asset lookup shared between Android Studio and headless `standalone-render`.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../designer/AGENTS.md](../designer/AGENTS.md). Dual-targeted `tools.render-resources` may link headless `//prebuilts/tools/common/lint-psi/intellij-core`, but must never depend on `@intellij//:intellij-sdk` or `com.android.tools.idea.*` IDE plugin dependencies outside `com.android.tools.idea.layoutlib.*` (`tools.layoutlib-loader`) (`U1`).
