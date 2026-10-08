load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.observable",
    srcs = ["src"],
    iml_files = ["intellij.android.observable.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/base/common:studio.android.sdktools.common",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.observable.tests",
    iml_files = ["intellij.android.observable.tests.iml"],
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/observable:intellij.android.observable",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
    ],
    test_friends = ["//tools/adt/idea/observable:intellij.android.observable"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
