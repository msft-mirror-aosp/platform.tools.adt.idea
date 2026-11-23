# Wear Preview Elements Agent Guide

This module (`tools/adt/idea/wear-preview-elements`) defines Wear Tile preview element models shared between IDE designers and headless `standalone-render` (Watch Face Format support lives in `wear-dwf`).

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../designer/AGENTS.md](../designer/AGENTS.md). Keep dual-targeted `tools.wear-preview-elements` free of `@intellij//:intellij-sdk`, `intellij-core`, and `com.android.tools.idea.*` IDE plugin dependencies (`U1`).
