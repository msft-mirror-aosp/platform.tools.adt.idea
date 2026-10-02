load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.layout-ui",
    srcs = ["src/main/java"],
    iml_files = ["intellij.android.layout-ui.iml"],
    lint_baseline = "lint_baseline.xml",
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui",
        "//tools/adt/idea/adt-ui-model:intellij.android.adt.ui.model",
        "//tools/adt/idea/artwork:intellij.android.artwork",
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/base/flags:studio.android.sdktools.flags",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.layout-ui.tests",
    iml_files = ["intellij.android.layout-ui.tests.iml"],
    test_class = "com.android.tools.LayoutUiTestSuite",
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/layout-ui:intellij.android.layout-ui",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui",
        "//tools/adt/idea/adt-ui-model:intellij.android.adt.ui.model",
        "//tools/adt/idea/artwork:intellij.android.artwork",
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:kotlin-test",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/.idea/libraries:mockito-kotlin",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui.tests",
    ],
    test_friends = ["//tools/adt/idea/layout-ui:intellij.android.layout-ui"],
    test_resources = ["testResources"],
    test_srcs = ["src/test/java"],
    visibility = ["//visibility:public"],
)
