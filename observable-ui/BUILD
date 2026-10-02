load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.observable.ui",
    srcs = ["src"],
    iml_files = ["intellij.android.observable.ui.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui",
        "//tools/adt/idea/observable:intellij.android.observable",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.observable.ui.tests",
    iml_files = ["intellij.android.observable.ui.tests.iml"],
    test_class = "com.android.tools.idea.observable.ui.ObservableUiTestSuite",
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/observable-ui:intellij.android.observable.ui",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui",
        "//tools/adt/idea/observable:intellij.android.observable",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/observable:intellij.android.observable.tests",
    ],
    test_friends = ["//tools/adt/idea/observable-ui:intellij.android.observable.ui"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
