load("//tools/base/bazel:bazel.bzl", "iml_module")

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.app-quality-insights.play-findings.ide",
    srcs = ["src"],
    iml_files = ["intellij.android.app-quality-insights.play-findings.ide.iml"],
    resources = ["resources"],
    visibility = ["//visibility:public"],
    # do not sort: must match IML order
    deps = [
        "//tools/adt/idea/app-quality-insights/play-findings/client:intellij.android.app-quality-insights.play-findings.client",
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "@intellij//:org.jetbrains.kotlin",
        "//tools/adt/idea/artwork:intellij.android.artwork",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/app-quality-insights/api:intellij.android.app-quality-insights.api",
        "//tools/adt/idea/app-quality-insights/ui:intellij.android.app-quality-insights.ui",
        "//tools/adt/idea/app-quality-insights/ide:intellij.android.app-quality-insights.ide",
        "//tools/adt/idea/app-quality-insights/client:intellij.android.app-quality-insights.client",
        "//tools/vendor/google/login:google-login",
    ],
)

# managed by go/iml_to_build
iml_module(
    name = "intellij.android.app-quality-insights.play-findings.ide.tests",
    iml_files = ["intellij.android.app-quality-insights.play-findings.ide.tests.iml"],
    test_class = "com.android.tools.idea.play.findings.PlayFindingsIdeTestSuite",
    # do not sort: must match IML order
    test_deps = [
        "//tools/adt/idea/app-quality-insights/play-findings/client:intellij.android.app-quality-insights.play-findings.client",
        "//tools/adt/idea/app-quality-insights/play-findings/ide:intellij.android.app-quality-insights.play-findings.ide",
        "@intellij//:intellij-sdk",
        "@intellij//:com.intellij.java",
        "@intellij//:org.jetbrains.kotlin",
        "//tools/adt/idea/artwork:intellij.android.artwork",
        "//tools/base/flags:studio.android.sdktools.flags",
        "//tools/adt/idea/android-common:intellij.android.common",
        "//tools/adt/idea/app-quality-insights/api:intellij.android.app-quality-insights.api",
        "//tools/adt/idea/app-quality-insights/ui:intellij.android.app-quality-insights.ui",
        "//tools/adt/idea/app-quality-insights/ide:intellij.android.app-quality-insights.ide",
        "//tools/adt/idea/app-quality-insights/client:intellij.android.app-quality-insights.client",
        "//tools/vendor/google/login:google-login",
        "//tools/adt/idea/.idea/libraries:junit4",
        "//tools/adt/idea/.idea/libraries:truth",
        "//tools/adt/idea/.idea/libraries:mockito",
        "//tools/adt/idea/.idea/libraries:mockito-kotlin",
        "//tools/base/testutils:studio.android.sdktools.testutils",
        "//tools/adt/idea/adt-testutils:intellij.android.adt.testutils",
        "//tools/adt/idea/gradle-declarative-lang-ide:intellij.android.gradle.declarative.lang.ide",
        "//tools/adt/idea/.idea/libraries:jetbrains.kotlinx.coroutines.test",
    ],
    test_friends = ["//tools/adt/idea/app-quality-insights/play-findings/ide:intellij.android.app-quality-insights.play-findings.ide"],
    test_srcs = ["testSrc"],
    visibility = ["//visibility:public"],
)
