load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.profilersAndroid.gradle",
    srcs = ["src"],
    iml_files = ["intellij.android.profilersAndroid.gradle.iml"],
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/project-system:intellij.android.projectSystem",
        "//tools/adt/idea/project-system-gradle:intellij.android.projectSystem.gradle",
        "//tools/adt/idea/project-system-gradle-models:intellij.android.projectSystem.gradle.models",
        "//tools/adt/idea/profilers-android:intellij.android.profilersAndroid",
        "//tools/base/sdk-common:studio.android.sdktools.sdk-common",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.profilersAndroid.gradle.tests",
    iml_files = ["intellij.android.profilersAndroid.gradle.tests.iml"],
    test_class = "com.android.tools.idea.profilers.ProfilersAndroidGradleTestSuite",
    test_data = [
        "//prebuilts/studio/sdk:platforms/latest",
    ],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/profilers-android/gradle:intellij.android.profilersAndroid.gradle",
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/profilers-android:intellij.android.profilersAndroid",
        "//tools/adt/idea/project-system:intellij.android.projectSystem",
        "//tools/adt/idea/project-system-gradle:intellij.android.projectSystem.gradle",
        "//tools/adt/idea/project-system-gradle-models:intellij.android.projectSystem.gradle.models",
        "//tools/adt/idea/android-test-framework:intellij.android.testFramework",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
        "//tools/base/testutils:studio.android.sdktools.testutils",
    ],
    test_friends = ["//tools/adt/idea/profilers-android/gradle:intellij.android.profilersAndroid.gradle"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
