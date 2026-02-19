load("@rules_android//android:rules.bzl", "android_library")
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

kt_jvm_library(
    name = "external_lib",
    srcs = ["ExternalLib.kt"],
    visibility = ["//visibility:public"],
)

android_library(
    name = "external_resource",
    manifest = "AndroidManifest.xml",
    resource_files = ["res/drawable/foo.xml"],
    visibility = ["//visibility:public"],
)
