# Fast Preview Tests Agent Guide

Split test module (`tools/adt/idea/android/editors.fast`) covering Fast Preview compilation and reload behavior.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../../designer/AGENTS.md](../../designer/AGENTS.md). Pay particular attention to `ModuleClassLoader` isolation (`U1`) and deterministic coroutine tests (`U5`).
