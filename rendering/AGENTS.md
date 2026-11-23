# Rendering & ModuleClassLoader Agent Guide

This module (`tools/adt/idea/rendering`) hosts `RenderService`, `RenderTask`,
`RenderExecutor`, `ModuleClassLoader`, bytecode transforms
(`MethodInterceptTransform`, `CooperativeInterruptTransform`,
`RenderActionAllocationLimiterTransform`, `RepackageTransform`,
`ViewMethodWrapperTransform`), and deferred image disposal utilities
(`imagepool/RecyclableImageDisposer.kt`, `ImagePoolUtil.kt`).

## Related Documentation
- [Rendering Architecture](docs/architecture.md)
- [Class Loading & ModuleClassLoader](docs/classloading.md)
- [Shared UI Tools Review Invariants](../designer/AGENTS.md)

## Code Review Invariants

Read and enforce all mandatory UI Tools code review invariants (`U1`–`U5`) in
[../designer/AGENTS.md](../designer/AGENTS.md). Dual-targeted `tools.rendering`
may link headless `//prebuilts/tools/common/lint-psi/intellij-core`, but must
never depend on `@intellij//:intellij-sdk` or `com.android.tools.idea.*` IDE
plugin dependencies outside `com.android.tools.idea.layoutlib.*`
(`tools.layoutlib-loader`) so headless `standalone-render` continues to build
and run (`U1`).
