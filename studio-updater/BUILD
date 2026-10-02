load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:coverage.bzl", "coverage_java_test")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

# The updater ships as a standalone deploy jar running outside the IDE in a separate process.
# Thus we keep dependencies to a minimum.
kotlin_library(
    name = "studio-updater",
    srcs = glob([
        "src/**/*.java",
        "src/**/*.kt",
    ]),
    resource_strip_prefix = "tools/adt/idea/studio-updater/resources",
    resources = glob(["resources/**"]),
    visibility = ["//tools/adt/idea/studio:__pkg__"],
    deps = [
        "//tools/analytics-library/tracker:tools.analytics-tracker",
        "//tools/base/common:tools.common",
        "@intellij//:updater",
    ],
)

# managed by go/iml_to_build
iml_module(
    # Note: this iml_module target is for local development purposes only.
    name = "intellij.android.updater.studio-updater",
    srcs = ["src"],
    iml_files = ["intellij.android.updater.studio-updater.iml"],
    jvm_target = "21",  # Must be compatible with the oldest Studio version for which we still offer patches.
    resources = ["resources"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:updater",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/analytics-library/shared:analytics-shared",
        "//tools/analytics-library/tracker:analytics-tracker",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.updater.studio-updater.tests",
    iml_files = ["intellij.android.updater.studio-updater.tests.iml"],
    jvm_target = "21",
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:updater",
        "//tools/adt/idea/studio-updater:intellij.android.updater.studio-updater",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/analytics-library/shared:analytics-shared",
        "//tools/analytics-library/tracker:analytics-tracker",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/adt/idea/.idea/libraries:opentest4j",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "@intellij//:test-framework",
    ],
    test_friends = ["//tools/adt/idea/studio-updater:intellij.android.updater.studio-updater"],
    test_srcs = ["testSrc"],
    test_tags = ["manual"],  # Tested via the integration_test target below
    visibility = ["//visibility:public"],
)

coverage_java_test(
    name = "integration_test",
    data = ["//tools/adt/idea/studio:updater_deploy.jar"],
    tags = ["noci:studio-win"],  # b/77288863
    test_class = "com.android.studio.updater.StudioPatchUpdaterIntegrationTest",
    runtime_deps = [":intellij.android.updater.studio-updater.tests_testlib"],
)
