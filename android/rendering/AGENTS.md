# Android Rendering Tests Agent Guide

Split test module (`tools/adt/idea/android/rendering`) covering Studio rendering, `RenderService`, and `RenderTask` behavior.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../../designer/AGENTS.md](../../designer/AGENTS.md). Pay particular attention to `RenderTask`/`RenderResult` disposal (`U2`) and anti-fragile test cleanup (`U5`).
