load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.transport",
    srcs = ["src"],
    iml_files = ["intellij.android.transport.iml"],
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/base/annotations:studio.android.sdktools.android-annotations",
        "//tools/base/ddmlib-studio:studio.android.sdktools.ddmlib-studio",
        "//tools/base/sdklib:studio.android.sdktools.sdklib",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//tools/adt/idea/android-adb:intellij.android.adb",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/android:intellij.android.core",
        "//tools/adt/idea/transport-database:intellij.android.transportDatabase",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/analytics-library/tracker:analytics-tracker",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui",
        "//tools/adt/idea/adt-ui-model:intellij.android.adt.ui.model",
        "//tools/adt/idea/artwork:intellij.android.artwork",
        "//tools/adt/idea/.idea/libraries:studio-grpc",
        "//tools/adt/idea/.idea/libraries:studio-proto",
        "//tools/adt/idea/.idea/libraries:transport-proto",
        "//tools/adt/idea/analytics",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.transport.tests",
    iml_files = ["intellij.android.transport.tests.iml"],
    test_class = "com.android.tools.idea.transport.AndroidTransportTestSuite",
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "//tools/adt/idea/android-transport:intellij.android.transport",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/android:intellij.android.core",
        "//tools/adt/idea/transport-database:intellij.android.transportDatabase",
        "//tools/adt/idea/adt-ui-model:intellij.android.adt.ui.model",
        "//tools/adt/idea/.idea/libraries:studio-grpc",
        "//tools/adt/idea/.idea/libraries:studio-proto",
        "//tools/adt/idea/.idea/libraries:transport-proto",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
        "//tools/adt/idea/gradle-declarative-lang-ide:intellij.android.gradle.declarative.lang.ide",
        "//tools/adt/idea/.idea/libraries:mockito-kotlin",
        "//tools/adt/idea/adt-ui-model:intellij.android.adt.ui.model.tests",
        "//tools/adt/idea/transport-database:intellij.android.transportDatabase.tests",
    ],
    test_friends = ["//tools/adt/idea/android-transport:intellij.android.transport"],
    test_srcs = ["testSrc"],
    test_tags = [],
    test_timeout = "long",
    visibility = ["//visibility:public"],
)
