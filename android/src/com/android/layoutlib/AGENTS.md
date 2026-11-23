# Android Layoutlib Reflection Tracking Package Agent Guide

This package (`android/src/com/android/layoutlib`) hosts ASM bytecode-transform replacement classes (`reflection/TrackingThread.kt`, `reflection/TrackingThreadLocal.kt`) injected for `java.lang.Thread` and `java.lang.ThreadLocal` to track preview threads and prevent `ModuleClassLoader` leaks. IDE-side Layoutlib bridge helpers live in `android/src/com/android/tools/idea/layoutlib/` and `layoutlib/`.

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../../../../../designer/AGENTS.md](../../../../../designer/AGENTS.md). Pay strict attention to `ModuleClassLoader` isolation and render-thread leak prevention (`U1`).
