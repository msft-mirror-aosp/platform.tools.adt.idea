load("@rules_cc//cc:defs.bzl", "cc_binary", "cc_library", "objc_library")
load("@rules_shell//shell:sh_binary.bzl", "sh_binary")
load("//tools/adt/idea/studio:studio.bzl", "studio_data")

config_setting(
    name = "macos",
    constraint_values = [
        "@platforms//cpu:x86_64",
        "@platforms//os:macos",
    ],
)

config_setting(
    name = "macos_arm",
    constraint_values = [
        "@platforms//cpu:arm64",
        "@platforms//os:macos",
    ],
)

studio_data(
    name = "native_lib",
    files = [
        "//prebuilts/tools:streaming-bundle",
    ],
    files_linux = glob(["linux/*"]),
    files_mac = glob(["mac/*"]),
    files_mac_arm = glob(["mac_arm/*"]),
    files_win = glob(["win/*"]),
    mappings = {
        "tools/adt/idea/streaming/native/linux/": "native/",
        "tools/adt/idea/streaming/native/mac/": "native/",
        "tools/adt/idea/streaming/native/mac_arm/": "native/",
        "tools/adt/idea/streaming/native/win/": "native/",
    },
    visibility = ["//visibility:public"],
)

cc_library(
    name = "image_converter",
    srcs = ["ImageConverter.c"],
    copts = ["-O2"] + select({
        "@platforms//cpu:x86_64": ["-mmovbe"],
        "//conditions:default": [],
    }),
    defines = ["NDEBUG"],
    linkstatic = True,
    deps = ["//prebuilts/studio/jdk:jni_headers"],
    alwayslink = True,
)

cc_binary(
    name = "libimage_converter.so",
    linkopts = ["-Wl,--strip-all"],
    linkshared = True,
    stamp = 0,
    target_compatible_with = ["@platforms//os:linux"],
    visibility = ["//visibility:public"],
    deps = [
        ":image_converter",
    ],
)

cc_binary(
    name = "libimage_converter.dylib",
    linkshared = True,
    stamp = 0,
    target_compatible_with = ["@platforms//os:macos"],
    visibility = ["//visibility:public"],
    deps = [
        ":image_converter",
    ],
)

cc_binary(
    name = "image_converter.dll",
    linkshared = True,
    stamp = 0,
    target_compatible_with = ["@platforms//os:windows"],
    visibility = ["//visibility:public"],
    deps = [
        ":image_converter",
    ],
)

filegroup(
    name = "libimage_converter",
    srcs = select({
        "@platforms//os:linux": [":libimage_converter.so"],
        "@platforms//os:macos": [":libimage_converter.dylib"],
        "@platforms//os:windows": [":image_converter.dll"],
    }),
    visibility = ["//visibility:public"],
)

filegroup(
    name = "libimage_converter.prebuilt",
    srcs = select({
        "@platforms//os:linux": glob(["linux/*"]),
        ":macos": glob(["mac/*"]),
        ":macos_arm": glob(["mac_arm/*"]),
        "@platforms//os:windows": glob(["win/*"]),
    }),
    visibility = ["//visibility:public"],
)

# bazel run :update_libimage_converter
sh_binary(
    name = "update_libimage_converter",
    srcs = ["//tools/base/bazel/shell:workspace-cp.sh"],
    args = [
        "$(execpath libimage_converter)",
    ] + select({
        "@platforms//os:linux": ["tools/adt/idea/streaming/native/linux"],
        ":macos": ["tools/adt/idea/streaming/native/mac"],
        ":macos_arm": ["tools/adt/idea/streaming/native/mac_arm"],
        "@platforms//os:windows": ["tools/adt/idea/streaming/native/win"],
    }),
    data = [":libimage_converter"],
)

objc_library(
    name = "video_decoder_mac",
    srcs = ["VideoDecoderMac.mm"],
    cxxopts = [
        "-O2",
        "-std=c++17",
    ],
    defines = ["NDEBUG"],
    sdk_frameworks = [
        "VideoToolbox",
        "CoreMedia",
        "CoreVideo",
        "CoreFoundation",
    ],
    target_compatible_with = ["@platforms//os:macos"],
    deps = ["//prebuilts/studio/jdk:jni_headers"],
    alwayslink = True,
)

cc_binary(
    name = "libvideo_decoder.dylib",
    linkshared = True,
    stamp = 0,
    target_compatible_with = ["@platforms//os:macos"],
    visibility = ["//visibility:public"],
    deps = [
        ":video_decoder_mac",
    ],
)

cc_library(
    name = "video_decoder_win",
    srcs = ["VideoDecoderWin.cc"],
    copts = [
        "/O2",
        "/std:c++20",
    ],
    defines = ["NDEBUG"],
    linkopts = [
        "/DEFAULTLIB:mfplat.lib",
        "/DEFAULTLIB:mfuuid.lib",
        "/DEFAULTLIB:ole32.lib",
        "/DEFAULTLIB:d3d11.lib",
    ],
    linkstatic = True,
    target_compatible_with = ["@platforms//os:windows"],
    deps = ["//prebuilts/studio/jdk:jni_headers"],
    alwayslink = True,
)

cc_binary(
    name = "video_decoder.dll",
    linkshared = True,
    stamp = 0,
    target_compatible_with = ["@platforms//os:windows"],
    visibility = ["//visibility:public"],
    deps = [
        ":video_decoder_win",
    ],
)

filegroup(
    name = "libvideo_decoder",
    srcs = select({
        "@platforms//os:macos": [":libvideo_decoder.dylib"],
        "@platforms//os:windows": [":video_decoder.dll"],
    }),
    target_compatible_with = select({
        "@platforms//os:linux": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
    visibility = ["//visibility:public"],
)

filegroup(
    name = "libvideo_decoder.prebuilt",
    srcs = ["//prebuilts/tools:libvideo_decoder.prebuilt"],
    visibility = ["//visibility:public"],
)

# bazel run :update_libvideo_decoder
sh_binary(
    name = "update_libvideo_decoder",
    srcs = ["//tools/base/bazel/shell:workspace-cp.sh"],
    args = [
        "-f",
        "$(execpath :libvideo_decoder)",
    ] + select({
        ":macos": ["prebuilts/tools/darwin-x86_64/streaming"],
        ":macos_arm": ["prebuilts/tools/darwin-arm64/streaming"],
        "@platforms//os:windows": ["prebuilts/tools/windows-x86_64/streaming"],
        "//conditions:default": [],
    }),
    data = [":libvideo_decoder"],
    target_compatible_with = select({
        "@platforms//os:linux": ["@platforms//:incompatible"],
        "//conditions:default": [],
    }),
)
