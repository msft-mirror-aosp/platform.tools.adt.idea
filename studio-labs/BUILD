load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.studio-labs",
    srcs = ["src"],
    iml_files = ["intellij.android.studio-labs.iml"],
    kotlin_use_compose = True,
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/android:intellij.android.core",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui",
        "//tools/adt/idea/artwork:intellij.android.artwork",
        "//tools/analytics-library/tracker:analytics-tracker",
        "//tools/adt/idea/adt-ui-compose:intellij.android.adt.ui.compose",
        "@intellij//:intellij-sdk",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.studio-labs.tests",
    iml_files = ["intellij.android.studio-labs.tests.iml"],
    kotlin_use_compose = True,
    test_class = "com.android.tools.studio.labs.StudioLabsSuite",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/studio-labs:intellij.android.studio-labs",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/artwork:intellij.android.artwork",
        "//tools/adt/idea/adt-ui-compose:intellij.android.adt.ui.compose",
        "@intellij//:intellij-sdk",
        "@intellij//:test-framework",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:truth-java8-extension",
        "//tools/analytics-library/testing:android.sdktools.analytics-testing",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/adt-ui-compose:intellij.android.adt.ui.compose.tests",
    ],
    test_friends = ["//tools/adt/idea/studio-labs:intellij.android.studio-labs"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
