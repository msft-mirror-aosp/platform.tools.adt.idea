load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.profilers.atrace",
    # do not sort: must match IML order
    srcs = [
        "src/core/common/src/main/kotlin",
        "src/core/model/src/main/kotlin",
    ],
    iml_files = ["intellij.android.profilers.atrace.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.profilers.atrace.tests",
    iml_files = ["intellij.android.profilers.atrace.tests.iml"],
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/profilers-atrace:intellij.android.profilers.atrace",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_friends = ["//tools/adt/idea/profilers-atrace:intellij.android.profilers.atrace"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
