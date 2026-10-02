load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "codenavigation",
    srcs = ["src"],
    iml_files = ["codenavigation.iml"],
    lint_baseline = "lint_baseline.xml",
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/native-symbolizer",
        "//tools/adt/idea/android:intellij.android.core",
        "//tools/analytics-library/tracker:analytics-tracker",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/adt/idea/analytics",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "codenavigation.tests",
    iml_files = ["codenavigation.tests.iml"],
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/codenavigation",
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/native-symbolizer",
        "//tools/adt/idea/android:intellij.android.core",
        "//tools/analytics-library/tracker:analytics-tracker",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/adt/idea/analytics",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:mockito",
    ],
    test_friends = ["//tools/adt/idea/codenavigation"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
