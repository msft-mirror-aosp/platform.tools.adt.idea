load("//tools/base/bazel:bazel.bzl", "iml_module")
load("//tools/base/bazel:jvm_import.bzl", "jvm_import")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.profilers.ui",
    srcs = ["src"],
    iml_files = ["intellij.android.profilers.ui.iml"],
    kotlin_use_compose = True,
    lint_baseline = "lint_baseline.xml",
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/profilers:intellij.android.profilers",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui",
        "//tools/adt/idea/adt-ui-compose:intellij.android.adt.ui.compose",
        "//tools/adt/idea/adt-ui-model:intellij.android.adt.ui.model",
        "//tools/base/perflib:studio.android.sdktools.perflib",
        "//tools/adt/idea/artwork:intellij.android.artwork",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/adt/idea/.idea/libraries:studio-proto",
        "//tools/adt/idea/.idea/libraries:transport-proto",
        "//tools/adt/idea/.idea/libraries:studio-grpc",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/inspectors-common/ui:intellij.android.inspectors-common.ui",
        "//tools/adt/idea/inspectors-common/api:intellij.android.inspectors-common.api",
        "//tools/adt/idea/codenavigation",
        "//tools/adt/idea/.idea/libraries:perfetto-proto",
        "//tools/adt/idea/artwork-compose:intellij.android.artwork-compose",
        "//tools/base/leakcanarylib:studio.android.sdktools.leakcanarylib",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.profilers.ui.tests",
    iml_files = ["intellij.android.profilers.ui.tests.iml"],
    kotlin_use_compose = True,
    test_class = "com.android.tools.profilers.ProfilersUiTestSuite",
    test_data = glob(["testData/**"]),
    # do not sort: must match IML order
    test_deps = [
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "//tools/adt/idea/profilers-ui:intellij.android.profilers.ui",
        "//tools/adt/idea/profilers:intellij.android.profilers",
        "//tools/adt/idea/adt-ui:intellij.android.adt.ui",
        "//tools/adt/idea/adt-ui-compose:intellij.android.adt.ui.compose",
        "//tools/adt/idea/adt-ui-model:intellij.android.adt.ui.model",
        "//tools/base/perflib:studio.android.sdktools.perflib",
        "//tools/adt/idea/artwork:intellij.android.artwork",
        "//tools/base/common:studio.android.sdktools.common",
        "//tools/adt/idea/.idea/libraries:studio-proto",
        "//tools/adt/idea/.idea/libraries:transport-proto",
        "//tools/adt/idea/.idea/libraries:studio-grpc",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/inspectors-common/ui:intellij.android.inspectors-common.ui",
        "//tools/adt/idea/inspectors-common/api:intellij.android.inspectors-common.api",
        "//tools/adt/idea/codenavigation",
        "//tools/adt/idea/.idea/libraries:perfetto-proto",
        "//tools/adt/idea/artwork-compose:intellij.android.artwork-compose",
        "//tools/base/leakcanarylib:studio.android.sdktools.leakcanarylib",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/android-transport:intellij.android.transport",
        "//tools/base/perf-logger:studio.perf-logger",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
        "//tools/adt/idea/.idea/libraries:studio-analytics-proto",
        "//tools/adt/idea/profilers-ui:traceprocessor-proto",
        "//tools/adt/idea/.idea/libraries:mockito-kotlin",
        "//tools/adt/idea/adt-ui-compose:intellij.android.adt.ui.compose.tests",
    ],
    test_friends = ["//tools/adt/idea/profilers-ui:intellij.android.profilers.ui"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)

# managed by go/iml_to_build
jvm_import(
    name = "traceprocessor-proto",
    jars = ["//tools/base/profiler/native/trace_processor_daemon:traceprocessor_protos.jar"],
    visibility = ["//visibility:public"],
)
