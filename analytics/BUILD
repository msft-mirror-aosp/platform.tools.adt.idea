load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "analytics",
    srcs = ["src"],
    iml_files = ["analytics.iml"],
    kotlin_use_compose = True,
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/analytics-library/tracker:analytics-tracker",
        "//tools/adt/idea/project-system:intellij.android.projectSystem",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/adt/idea/jps-model:intellij.android.jps.model",
        "//tools/base/ddmlib-studio:studio.android.sdktools.ddmlib-studio",
        "//tools/analytics-library/shared:analytics-shared",
        "//tools/base/adblib:studio.android.sdktools.adblib",
        "//tools/adt/idea/adt-ui-compose:intellij.android.adt.ui.compose",
        "//tools/adt/idea/artwork-compose:intellij.android.artwork-compose",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "analytics.tests",
    iml_files = ["analytics.tests.iml"],
    kotlin_use_compose = True,
    test_class = "com.android.analytics.AnalyticsTestSuiteBase",
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/analytics",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:jetbrains.kotlinx.coroutines.test",
        "//tools/adt/idea/analytics-utils:intellij.android.analytics-utils",
        "//tools/adt/idea/adt-ui-compose:intellij.android.adt.ui.compose",
        "//tools/adt/idea/adt-ui-compose:intellij.android.adt.ui.compose.tests",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
        "//tools/base/testutils:studio.android.sdktools.testutils",
    ],
    test_friends = ["//tools/adt/idea/analytics"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
