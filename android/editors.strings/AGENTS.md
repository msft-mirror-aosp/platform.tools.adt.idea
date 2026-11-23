# Translations Editor Tests Agent Guide

Split test module (`tools/adt/idea/android/editors.strings`) covering the Translations Editor (`android/src/com/android/tools/idea/editors/strings`).

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../../designer/AGENTS.md](../../designer/AGENTS.md). Pay particular attention to off-EDT `@Slow` resource lookups (`U3`) and leak-free deterministic test cleanup (`U5`).
