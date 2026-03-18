load("@rules_java//java:defs.bzl", "java_library")
load("//tools/adt/idea/emulator-snapshots:emulator_jvm_flags.bzl", "emulator_test_jvm_flags")
load("//tools/adt/idea/emulator-snapshots:emulator_quickboot_snapshot.bzl", "emulator_quickboot_snapshot")
load("//tools/base/bazel:bazel.bzl", "iml_module")

java_library(
    name = "emulator_quickboot_snapshot_generator_lib",
    testonly = True,
    srcs = ["testSrc/com/android/tools/idea/EmulatorQuickbootSnapshotGenerator.java"],
    visibility = ["//visibility:public"],
    deps = [
        "//tools/emulator/testlib",
    ],
)

emulator_quickboot_snapshot(
    name = "emulator_quickboot_api_30_snapshot",
    jvm_flags = emulator_test_jvm_flags("@system_image_android-30_default_x86_64//:x86_64-android-30-images"),
    system_image = "@system_image_android-30_default_x86_64//:x86_64-android-30-images",
    visibility = ["//visibility:public"],
)

emulator_quickboot_snapshot(
    name = "emulator_quickboot_api_31_snapshot",
    jvm_flags = emulator_test_jvm_flags("//prebuilts/studio/sdk:system_image_android-31_default"),
    system_image = "//prebuilts/studio/sdk:system_image_android-31_default",
    visibility = ["//visibility:public"],
)

emulator_quickboot_snapshot(
    name = "emulator_quickboot_api_33_snapshot",
    jvm_flags = emulator_test_jvm_flags("//prebuilts/studio/sdk:system_image_android-33_default"),
    system_image = "//prebuilts/studio/sdk:system_image_android-33_default",
    visibility = ["//visibility:public"],
)

emulator_quickboot_snapshot(
    name = "emulator_quickboot_api_33_atd_snapshot",
    jvm_flags = emulator_test_jvm_flags("@system_image_android-33_aosp_atd_x86_64//:x86_64-android-33AospAtd-images"),
    system_image = "@system_image_android-33_aosp_atd_x86_64//:x86_64-android-33AospAtd-images",
    visibility = ["//visibility:public"],
)

emulator_quickboot_snapshot(
    name = "emulator_quickboot_api_35_snapshot",
    jvm_flags = emulator_test_jvm_flags("@system_image_android-35_default_x86_64//:x86_64-android-35-images"),
    system_image = "@system_image_android-35_default_x86_64//:x86_64-android-35-images",
    visibility = ["//visibility:public"],
)

# managed by go/iml_to_build
iml_module(
    name = "emulator-snapshots",
    enable_tests = False,
    iml_files = ["emulator-snapshots.iml"],
    test_deps = ["//tools/emulator/testlib:android.sdktools.testlib"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
    deps = ["@intellij//:intellij-sdk"],
)
