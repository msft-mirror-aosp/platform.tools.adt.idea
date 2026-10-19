load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.wizard.model",
    srcs = ["src"],
    iml_files = ["intellij.android.wizard.model.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    exports = ["//tools/adt/idea/observable:intellij.android.observable"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
        "//tools/adt/idea/observable:intellij.android.observable",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.wizard.model.tests",
    iml_files = ["intellij.android.wizard.model.tests.iml"],
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/wizard-model:intellij.android.wizard.model",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/observable:intellij.android.observable.tests",
    ],
    test_friends = ["//tools/adt/idea/wizard-model:intellij.android.wizard.model"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
