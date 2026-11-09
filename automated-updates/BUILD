load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.automated-updates",
    srcs = ["src"],
    iml_files = ["intellij.android.automated-updates.iml"],
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/android:intellij.android.core",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//tools/analytics-library/tracker:analytics-tracker",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.automated-updates.tests",
    iml_files = ["intellij.android.automated-updates.tests.iml"],
    test_class = "com.android.tools.idea.automatedupdates.AutomatedUpdatesTestSuite",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/automated-updates:intellij.android.automated-updates",
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:kotlin-test",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
        "@intellij//:test-framework",
    ],
    test_friends = ["//tools/adt/idea/automated-updates:intellij.android.automated-updates"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
