load("//tools/base/bazel:proto.bzl", "kotlin_proto_library")

package(default_visibility = ["//visibility:public"])

kotlin_proto_library(
    name = "play_findings_proto",
    srcs = glob(["**/*.proto"]),
    proto_deps = ["//tools/base/bazel:grpc-common-java_proto_srcs"],
    proto_java_runtime_library = [
        "@intellij//:intellij-sdk",
        "@intellij//:intellij.libraries.grpc",
    ],
    visibility = ["//visibility:public"],
)
