load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:jvm_import.bzl", "jvm_import")
load("//tools/base/bazel:proto.bzl", "java_proto_library")

java_proto_library(
    name = "server-flag-test-proto",
    srcs = glob(["proto/test/*.proto"]),
    visibility = ["//visibility:public"],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.server-flags",
    srcs = ["src"],
    iml_files = ["intellij.android.server-flags.iml"],
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.server-flags.tests",
    iml_files = ["intellij.android.server-flags.tests.iml"],
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/server-flags:intellij.android.server-flags",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/server-flags:libserver-flag-test-proto",
    ],
    test_friends = ["//tools/adt/idea/server-flags:intellij.android.server-flags"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)

# managed by go/iml_to_build
jvm_import(
    name = "libserver-flag-test-proto",
    jars = ["//tools/adt/idea/server-flags:libserver-flag-test-proto.jar"],
    visibility = ["//visibility:public"],
)
