load("@bazel_skylib//rules:expand_template.bzl", "expand_template")
load("//tools/adt/idea/studio:version.bzl", "STUDIO_MICRO_PATCH")
load("//tools/base/bazel:utils.bzl", "dir_archive")

filegroup(
    name = "uninstaller",
    srcs = ["win/builds/uninstall.exe"],
    visibility = ["//visibility:public"],
)

expand_template(
    name = "setup_android_studio_nsi",
    out = "win/setup_android_studio.nsi",
    substitutions = {
        "{VERSION_MICRO_PATCH}": STUDIO_MICRO_PATCH,
    },
    template = "win/setup_android_studio.nsi.template",
)

dir_archive(
    name = "android-studio-bundle-data",
    dir = "tools/adt/idea/native/installer/win",
    files = glob(
        include = ["win/**"],
        exclude = [
            "win/version",
            "win/setup_android_studio.nsi.template",
        ],
    ) + [":setup_android_studio_nsi"],
    stamp = "version",
    visibility = ["//visibility:public"],
)
