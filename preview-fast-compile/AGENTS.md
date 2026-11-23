# Preview Fast Compile Agent Guide

This module (`tools/adt/idea/preview-fast-compile`) defines shared Fast Preview compilation result models (`CompilationResult`) used by both IDE Fast Preview (`android/src/com/android/tools/idea/editors/fast/`) and non-IDE targets (`tools.preview-fast-compile`).

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../designer/AGENTS.md](../designer/AGENTS.md). Keep dual-targeted `tools.preview-fast-compile` free of `@intellij//:intellij-sdk`, `intellij-core`, and `com.android.tools.idea.*` IDE plugin dependencies (`U1`).
