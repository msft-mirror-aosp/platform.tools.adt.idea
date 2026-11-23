# Designer Performance Tests Agent Guide

This module (`tools/adt/idea/designer-perf-tests`) hosts UI Tools rendering and designer performance benchmarks.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../designer/AGENTS.md](../designer/AGENTS.md). Pay particular attention to `RenderTask`/`RenderResult` disposal (`U2`) and leak-free deterministic test fixtures (`U5`).
