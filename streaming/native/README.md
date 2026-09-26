When `ImageConverter.c` is changed, the prebuilt libimage_converter native libraries has to be updated for all platforms. To update the libimage_converter library for the current platform, run
```
bazel build //tools/adt/idea/streaming/native:update_libimage_converter
```

**Note:** The Mac Arm version of the library (`tools/adt/idea/streaming/native/mac_arm/libimage_converter.dylib`) cannot be built using the above method yet. The current version was obtained from an Emulator build (https://android-build.googleplex.com/builds/branches/aosp-emu-master-dev/grid).

When `VideoDecoderLinux.cc`, `VideoDecoderMac.mm`, or `VideoDecoderWin.cc` is changed, the prebuilt native library under `prebuilts/tools/{linux-x86_64,darwin-arm64,darwin-x86_64,windows-x86_64}/streaming` has to be updated. To update the library for both Mac architectures (ARM64 and x86_64) on a Mac host, run
```
bazel run --platforms=//tools/base/bazel/platforms:mac-arm64 //tools/adt/idea/streaming/native:update_libvideo_decoder
bazel run --platforms=//tools/base/bazel/platforms:mac-x86_64 //tools/adt/idea/streaming/native:update_libvideo_decoder
```
To update only for the current host platform (Linux, macOS, or Windows), run
```
bazel run //tools/adt/idea/streaming/native:update_libvideo_decoder
```


