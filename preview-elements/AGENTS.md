# Preview Elements Agent Guide

This module (`tools/adt/idea/preview-elements`) defines `@Preview` element models, parameter providers, and multipreview descriptors shared across IDE designers and headless `standalone-render`.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../designer/AGENTS.md](../designer/AGENTS.md). Keep dual-targeted `tools.preview-elements` free of `@intellij//:intellij-sdk`, `intellij-core`, and `com.android.tools.idea.*` IDE plugin dependencies (`U1`).
