# Resource Manager Agent Guide

This package implements the Swing-based Android Studio Resource Manager tool window (see [README.md](README.md)).

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../../../../../../../../designer/AGENTS.md](../../../../../../../../designer/AGENTS.md). Pay particular attention to `Graphics2D` / thumbnail buffer disposal and cache keys (`U2`), off-EDT `@Slow` resource lookups and post-suspension validity checks (`U3`), and Swing `AccessibleContext` / keyboard accessibility (apply `U4` only if Compose Desktop / Jewel controls are added).
