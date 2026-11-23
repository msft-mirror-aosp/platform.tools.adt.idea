# Resource Manager Tests Agent Guide

Split test module (`tools/adt/idea/android/ui.resourcemanager`) covering Resource Manager behavior.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../../designer/AGENTS.md](../../designer/AGENTS.md). Pay particular attention to off-EDT resource lookups (`U3`) and leak-free test cleanup (`U5`).
