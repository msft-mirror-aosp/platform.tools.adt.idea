load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:jvm_import.bzl", "jvm_import")

# managed by go/iml_to_build
jvm_import(
    name = "cli_commands_proto",
    jars = ["//tools/adt/idea/cli-server/command-handler/proto:cli_commands_proto.jar"],
    visibility = ["//visibility:public"],
)

# managed by go/iml_to_build
iml_module(
    name = "cli-server.command-handler",
    srcs = ["src"],
    iml_files = ["cli-server.command-handler.iml"],
    resources = ["resources"],
    test_class = "com.android.tools.idea.cli.handlers.CliCommandHandlerTestSuite",
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:test-framework",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
    ],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
    exports = ["//tools/adt/idea/cli-server/command-handler:cli_commands_proto"],
    # do not sort: must match IML order
    deps = [
        "//tools/adt/idea/cli-server/server:cli-server.server",
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/cli-server/command-handler:cli_commands_proto",
    ],
)
