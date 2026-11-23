# Studio Rendering & Classloading Transforms Agent Guide

This package hosts IDE-side rendering integration (`StudioRenderService`, `DrawableRenderer`, `GutterIconCache`) and classloading transforms (`ThreadLocalTrackingTransform`, `ThreadControllingTransform`, `ViewTreeLifecycleTransform`).

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../../../../../../../designer/AGENTS.md](../../../../../../../designer/AGENTS.md). Pay strict attention to `ModuleClassLoader` / `Layoutlib Render Thread` isolation (`U1`), `RenderTask` / `RenderResult` / `Graphics2D` disposal (`U2`), and deterministic unit tests for bytecode transforms (`U5`).
