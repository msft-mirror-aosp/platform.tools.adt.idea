load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.tracer",
    srcs = ["src"],
    iml_files = ["intellij.android.tracer.iml"],
    kotlin_use_compose = True,
    resources = ["resources"],
    test_class = "com.android.tools.idea.tracer.AndroidxTracerTestSuite",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:jetbrains.kotlinx.coroutines.test",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework",
        "//tools/adt/idea/adt-ui-compose:intellij.android.adt.ui.compose.tests",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
    ],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
    exports = ["//prebuilts/tools/common/m2:androidx-tracing-desktop"],
    # do not sort: must match IML order
    deps = [
        "//tools/adt/idea/adt-ui-compose:intellij.android.adt.ui.compose",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//tools/adt/idea/android-common:intellij.android.common",
        "@intellij//:intellij-sdk",
        "//prebuilts/tools/common/m2:androidx-tracing-desktop",
    ],
)
