# Fast Preview Manager Agent Guide

This package hosts `FastPreviewManager` and IDE Fast Preview live compilation integration.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../../../../../../../../designer/AGENTS.md](../../../../../../../../designer/AGENTS.md). Pay strict attention to `ModuleClassLoader` reload isolation (`U1`), off-EDT compilation (`U3`), and deterministic regression tests (`U5`).
