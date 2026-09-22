When `ImageConverter.c` is changed, the prebuilt libimage_converter native libraries has to be updated for all platforms. To update the libimage_converter library for the current platform, run
```
bazel build //tools/adt/idea/streaming/native:update_libimage_converter
```

**Note:** The Mac Arm version of the library (`tools/adt/idea/streaming/native/mac_arm/libimage_converter.dylib`) cannot be built using the above method yet. The current version was obtained from an Emulator build (https://android-build.googleplex.com/builds/branches/aosp-emu-master-dev/grid).

When `VideoDecoderMac.mm` is changed, the prebuilt libvideo_decoder native library under `prebuilts/tools/darwin-{arm64,x86_64}/streaming` has to be updated for macOS. To update the libvideo_decoder library for both Mac architectures (ARM64 and x86_64), run
```
bazel run --platforms=//tools/base/bazel/platforms:mac-arm64 //tools/adt/idea/streaming/native:update_libvideo_decoder
bazel run --platforms=//tools/base/bazel/platforms:mac-x86_64 //tools/adt/idea/streaming/native:update_libvideo_decoder
```
Or to update only for the current Mac architecture, run
```
bazel run //tools/adt/idea/streaming/native:update_libvideo_decoder
```

