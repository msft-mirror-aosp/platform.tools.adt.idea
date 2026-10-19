load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.core.editing.metrics",
    srcs = ["src"],
    iml_files = ["intellij.android.core.editing.metrics.iml"],
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "@intellij//:org.jetbrains.kotlin",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/analytics-library/tracker:analytics-tracker",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.core.editing.metrics.tests",
    iml_files = ["intellij.android.core.editing.metrics.tests.iml"],
    test_class = "com.android.tools.idea.editing.metrics.EditingMetricsTestSuite",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/android/editing/metrics:intellij.android.core.editing.metrics",
        "@intellij//:intellij-sdk",
        "@intellij//:org.jetbrains.kotlin",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/analytics-library/tracker:analytics-tracker",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:jetbrains.kotlinx.coroutines.test",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework",
        "//tools/analytics-library/testing:android.sdktools.analytics-testing",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/adt/idea/.idea/libraries:mockito-kotlin",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
    ],
    test_friends = ["//tools/adt/idea/android/editing/metrics:intellij.android.core.editing.metrics"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
