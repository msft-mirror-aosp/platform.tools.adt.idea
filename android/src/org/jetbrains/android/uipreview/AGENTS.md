# Studio UI Preview & ModuleClassLoader Agent Guide

This package hosts `StudioModuleClassLoader`, `StudioModuleClassLoaderManager`, `ModuleClassLoaderHatchery`, and `StudioRenderSandbox`.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../../../../../../designer/AGENTS.md](../../../../../../designer/AGENTS.md). Pay strict attention to `ModuleClassLoader` isolation and leak prevention (`U1`) and deterministic classloader regression tests (`U5`).
