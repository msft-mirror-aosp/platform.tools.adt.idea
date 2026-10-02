load("@rules_java//java:defs.bzl", "java_test")
load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "memory-usage",
    srcs = ["src/main/java"],
    iml_files = ["memory-usage.iml"],
    jvm_target = "8",
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:com.intellij.java",
        "@intellij//:intellij-sdk",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "memory-usage.tests",
    enable_tests = False,
    iml_files = ["memory-usage.tests.iml"],
    jvm_target = "8",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/memory-usage",
        "//tools/adt/idea/.idea/libraries:junit4",
    ],
    test_friends = ["//tools/adt/idea/memory-usage"],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
)

java_test(
    name = "tools.memory-usage_tests",
    size = "small",
    data = ["//tools/adt/idea/memory-usage/src/main/native:liblightweight_heap_traverse"],
    jvm_flags = ["-agentpath:$(location //tools/adt/idea/memory-usage/src/main/native:liblightweight_heap_traverse)"],
    test_class = "com.android.tools.memory.usage.LightweightHeapTraverseTest",
    runtime_deps = [
        ":memory-usage.tests_testlib",
        "//tools/base/annotations",
        "//tools/base/common:tools.common",
        "//tools/base/testutils:tools.testutils",
        "@maven//:junit.junit",
    ],
)
