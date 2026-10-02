load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.inspectors-common.ui",
    srcs = ["src"],
    iml_files = ["intellij.android.inspectors-common.ui.iml"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/adt-ui-model:intellij.android.adt.ui.model",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/inspectors-common/api:intellij.android.inspectors-common.api",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui",
        "//tools/adt/idea/codenavigation",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.inspectors-common.ui.tests",
    iml_files = ["intellij.android.inspectors-common.ui.tests.iml"],
    test_class = "com.android.tools.inspectors.common.ui.InspectorsCommonUiTestSuite",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/inspectors-common/ui:intellij.android.inspectors-common.ui",
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/adt-ui-model:intellij.android.adt.ui.model",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/inspectors-common/api:intellij.android.inspectors-common.api",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui",
        "//tools/adt/idea/codenavigation",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
        "//tools/adt/idea/.idea/libraries:kotlin-test",
        "@intellij//:com.intellij.modules.json",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui.tests",
    ],
    test_friends = ["//tools/adt/idea/inspectors-common/ui:intellij.android.inspectors-common.ui"],
    test_resources = ["resources"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
