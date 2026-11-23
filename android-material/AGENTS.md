# Android Material Assets Agent Guide

This module (`tools/adt/idea/android-material`) manages Material icon and symbol catalogs, caches, and downloads.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../designer/AGENTS.md](../designer/AGENTS.md). Pay particular attention to normalized cache keys (`U2`) and off-EDT `@Slow` resource/network lookups (`U3`).
