/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <dlfcn.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr uint32_t kCodecTypeAV1 = 0x61763031;   // 'av01'
constexpr uint32_t kCodecTypeAVC = 0x61766331;   // 'avc1'
constexpr uint32_t kCodecTypeHEVC = 0x68766331;  // 'hvc1'
constexpr uint32_t kCodecTypeVP8 = 0x76703038;   // 'vp08'
constexpr uint32_t kCodecTypeVP9 = 0x76703039;   // 'vp09'

constexpr jint DECODE_INVALID_FRAME = -2;
constexpr jint DECODE_ERROR = -1;
constexpr jint DECODE_NO_FRAME = 0;
constexpr jint DECODE_FRAME_PRODUCED = 1;

constexpr int SWS_BILINEAR = 2;
constexpr size_t AV_INPUT_BUFFER_PADDING_SIZE = 64;
constexpr int32_t kMaxFrameDimension = 16384;

// H.264 (AVC) NAL unit constants.
constexpr uint8_t AVC_NAL_UNIT_TYPE_MASK = 0x1F;
constexpr uint8_t AVC_NAL_SLICE_MIN = 1;
constexpr uint8_t AVC_NAL_SLICE_MAX = 5;
constexpr uint8_t AVC_NAL_SEI = 6;
constexpr uint8_t AVC_NAL_SPS = 7;
constexpr uint8_t AVC_NAL_PPS = 8;
constexpr uint8_t AVC_NAL_AUD = 9;
constexpr uint8_t AVC_NAL_FILLER = 12;

// H.265 (HEVC) NAL unit constants.
constexpr uint8_t HEVC_NAL_UNIT_TYPE_MASK = 0x3F;
constexpr uint8_t HEVC_NAL_VCL_MAX = 31;
constexpr uint8_t HEVC_NAL_VPS = 32;
constexpr uint8_t HEVC_NAL_SPS = 33;
constexpr uint8_t HEVC_NAL_PPS = 34;
constexpr uint8_t HEVC_NAL_AUD = 35;
constexpr uint8_t HEVC_NAL_FILLER = 38;
constexpr uint8_t HEVC_NAL_PREFIX_SEI = 39;
constexpr uint8_t HEVC_NAL_SUFFIX_SEI = 40;

// AV1 OBU type constants.
constexpr uint8_t AV1_OBU_SEQUENCE_HEADER = 1;
constexpr uint8_t AV1_OBU_TEMPORAL_DELIMITER = 2;
constexpr uint8_t AV1_OBU_FRAME_HEADER = 3;
constexpr uint8_t AV1_OBU_TILE_GROUP = 4;
constexpr uint8_t AV1_OBU_METADATA = 5;
constexpr uint8_t AV1_OBU_FRAME = 6;
constexpr uint8_t AV1_OBU_REDUNDANT_FRAME_HEADER = 7;
constexpr uint8_t AV1_OBU_TILE_LIST = 8;
constexpr uint8_t AV1_OBU_PADDING = 15;

// Prefix of FFmpeg's AVFrame struct, which has remained ABI-stable across FFmpeg 3.x - 9.x.
struct AvFramePrefix {
  uint8_t* data[8];
  int linesize[8];
  uint8_t** extended_data;
  int width;
  int height;
  int nb_samples;
  int format;
};

struct NalUnit {
  const uint8_t* data;
  size_t size;
  uint8_t type;
};

class BitReader {
public:
  BitReader(const uint8_t* data, size_t size)
      : data_(data), totalBits_(size * 8), bitPos_(0) {}

  bool HasBits(size_t count) const {
    return count <= totalBits_ && bitPos_ <= totalBits_ - count;
  }

  uint32_t ReadBits(size_t count) {
    if (count == 0) {
      return 0;
    }
    if (!HasBits(count) || count > 32) {
      overflow_ = true;
      return 0;
    }
    uint32_t value = 0;
    for (size_t i = 0; i < count; ++i) {
      size_t byteIdx = bitPos_ >> 3;
      size_t bitIdx = 7 - (bitPos_ & 7);
      value = (value << 1) | ((data_[byteIdx] >> bitIdx) & 1);
      ++bitPos_;
    }
    return value;
  }

  bool ReadBit() {
    return ReadBits(1) != 0;
  }

  bool HasOverflow() const { return overflow_; }

private:
  const uint8_t* data_;
  size_t totalBits_;
  size_t bitPos_;
  bool overflow_ = false;
};

bool ReadLeb128(const uint8_t* data, size_t size, size_t* outValue, size_t* outBytesRead) {
  uint64_t value = 0;
  for (size_t i = 0; i < 8 && i < size; ++i) {
    uint8_t byte = data[i];
    value |= static_cast<uint64_t>(byte & 0x7F) << (i * 7);
    if ((byte & 0x80) == 0) {
      if (value > UINT32_MAX) {
        return false;
      }
      *outValue = static_cast<size_t>(value);
      *outBytesRead = i + 1;
      return true;
    }
  }
  return false;
}

void AppendLeb128(std::vector<uint8_t>* dest, size_t value) {
  do {
    uint8_t byte = static_cast<uint8_t>(value & 0x7F);
    value >>= 7;
    if (value != 0) {
      byte |= 0x80;
    }
    dest->push_back(byte);
  } while (value != 0);
}

void AppendObuWithSizeField(
    std::vector<uint8_t>* dest,
    const uint8_t* obuStart,
    size_t headerLen,
    size_t payloadSize,
    bool hasSizeField) {
  if (hasSizeField) {
    dest->insert(dest->end(), obuStart, obuStart + headerLen + payloadSize);
    return;
  }
  dest->push_back(static_cast<uint8_t>(obuStart[0] | 0x02));  // Set obu_has_size_field = 1.
  if (headerLen > 1) {
    dest->push_back(obuStart[1]);  // Copy obu_extension_header byte.
  }
  AppendLeb128(dest, payloadSize);
  const uint8_t* payloadStart = obuStart + headerLen;
  dest->insert(dest->end(), payloadStart, payloadStart + payloadSize);
}

std::vector<NalUnit> ParseAnnexBNalUnits(const uint8_t* data, size_t size, bool isHevc) {
  std::vector<NalUnit> units;
  if (size < 4) {
    return units;
  }

  std::vector<size_t> startCodeOffsets;
  for (size_t i = 0; i + 2 < size; ++i) {
    if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
      if (i > 0 && data[i - 1] == 0) {
        startCodeOffsets.push_back(i - 1);
      } else {
        startCodeOffsets.push_back(i);
      }
      i += 2;
    }
  }

  size_t minHeaderBytes = isHevc ? 2 : 1;
  for (size_t i = 0; i < startCodeOffsets.size(); ++i) {
    size_t start = startCodeOffsets[i];
    size_t prefixLen = (data[start] == 0 && data[start + 1] == 0 && data[start + 2] == 0) ? 4 : 3;
    size_t payloadStart = start + prefixLen;

    size_t payloadEnd = (i + 1 < startCodeOffsets.size()) ? startCodeOffsets[i + 1] : size;
    while (payloadEnd > payloadStart && data[payloadEnd - 1] == 0) {
      --payloadEnd;
    }

    if (payloadEnd >= payloadStart + minHeaderBytes) {
      uint8_t nalType = isHevc
          ? ((data[payloadStart] >> 1) & HEVC_NAL_UNIT_TYPE_MASK)
          : (data[payloadStart] & AVC_NAL_UNIT_TYPE_MASK);
      units.push_back({data + payloadStart, payloadEnd - payloadStart, nalType});
    }
  }

  return units;
}

struct Vp9PacketInfo {
  bool hasConfig = false;
  bool showFrame = false;
  int32_t width = 0;
  int32_t height = 0;
};

bool SkipVp9ColorConfig(BitReader* reader, uint8_t profile) {
  if (profile >= 2) {
    reader->ReadBit();  // ten_or_twelve_bit
  }
  uint8_t colorSpace = static_cast<uint8_t>(reader->ReadBits(3));
  if (colorSpace != 7) {  // Not CS_RGB
    reader->ReadBit();    // color_range
    if (profile == 1 || profile == 3) {
      reader->ReadBits(2);  // subsampling_x, subsampling_y
      if (reader->ReadBit()) {
        return false;  // reserved_zero
      }
    }
  } else {
    if (profile == 1 || profile == 3) {
      if (reader->ReadBit()) {
        return false;  // reserved_zero
      }
    } else {
      return false;
    }
  }
  return !reader->HasOverflow();
}

bool ParseVp9SingleFrameHeader(const uint8_t* data, size_t size, Vp9PacketInfo* info) {
  BitReader reader(data, size);
  if (reader.ReadBits(2) != 2) {  // frame_marker must be 2
    return false;
  }
  uint32_t profileLow = reader.ReadBits(1);
  uint32_t profileHigh = reader.ReadBits(1);
  uint8_t profile = static_cast<uint8_t>((profileHigh << 1) | profileLow);
  if (profile == 3 && reader.ReadBits(1) != 0) {  // reserved_zero
    return false;
  }
  if (reader.ReadBit()) {  // show_existing_frame
    reader.ReadBits(3);    // frame_to_show_map_idx
    info->hasConfig = false;
    info->showFrame = true;
    return !reader.HasOverflow();
  }

  bool isKeyFrame = (reader.ReadBits(1) == 0);
  bool showFrame = reader.ReadBit();
  bool errorResilientMode = reader.ReadBit();
  info->showFrame = showFrame;

  if (isKeyFrame) {
    if (reader.ReadBits(24) != 0x498342) {  // frame_sync_code
      return false;
    }
    if (!SkipVp9ColorConfig(&reader, profile)) {
      return false;
    }
    int32_t width = static_cast<int32_t>(reader.ReadBits(16)) + 1;
    int32_t height = static_cast<int32_t>(reader.ReadBits(16)) + 1;
    if (reader.HasOverflow() || width <= 0 || height <= 0) {
      return false;
    }
    info->hasConfig = true;
    info->width = width;
    info->height = height;
    return true;
  }

  bool intraOnly = !showFrame ? reader.ReadBit() : false;
  if (!errorResilientMode) {
    reader.ReadBits(2);  // reset_frame_context
  }

  if (intraOnly) {
    if (reader.ReadBits(24) != 0x498342) {  // frame_sync_code
      return false;
    }
    if (profile > 0 && !SkipVp9ColorConfig(&reader, profile)) {
      return false;
    }
    reader.ReadBits(8);  // refresh_frame_flags
    int32_t width = static_cast<int32_t>(reader.ReadBits(16)) + 1;
    int32_t height = static_cast<int32_t>(reader.ReadBits(16)) + 1;
    if (reader.HasOverflow() || width <= 0 || height <= 0) {
      return false;
    }
    info->hasConfig = true;
    info->width = width;
    info->height = height;
    return true;
  }

  info->hasConfig = false;
  return !reader.HasOverflow();
}

bool ParseVp9Packet(const uint8_t* data, size_t size, Vp9PacketInfo* info) {
  if (size == 0) {
    return false;
  }
  uint8_t marker = data[size - 1];
  if ((marker & 0xE0) == 0xC0) {
    size_t bytesPerFrameSize = ((marker >> 3) & 0x03) + 1;
    size_t frameCount = (marker & 0x07) + 1;
    size_t indexSize = 2 + frameCount * bytesPerFrameSize;
    if (size >= indexSize && data[size - indexSize] == marker) {
      const uint8_t* indexPtr = data + size - indexSize + 1;
      size_t offset = 0;
      bool validSuperframe = true;
      Vp9PacketInfo combinedInfo;
      for (size_t i = 0; i < frameCount; ++i) {
        size_t frameSize = 0;
        for (size_t b = 0; b < bytesPerFrameSize; ++b) {
          frameSize |= static_cast<size_t>(*indexPtr++) << (b * 8);
        }
        if (frameSize == 0 || offset + frameSize > size - indexSize) {
          validSuperframe = false;
          break;
        }
        Vp9PacketInfo subInfo;
        if (!ParseVp9SingleFrameHeader(data + offset, frameSize, &subInfo)) {
          return false;
        }
        if (subInfo.hasConfig) {
          combinedInfo.hasConfig = true;
          combinedInfo.width = subInfo.width;
          combinedInfo.height = subInfo.height;
        }
        if (subInfo.showFrame) {
          combinedInfo.showFrame = true;
        }
        offset += frameSize;
      }
      if (validSuperframe && offset == size - indexSize) {
        *info = combinedInfo;
        return true;
      }
    }
  }
  return ParseVp9SingleFrameHeader(data, size, info);
}

struct FfmpegSoNames {
  const char* avutilSoName;
  const char* swresampleSoName;
  const char* swscaleSoName;
  const char* avcodecSoName;
};

constexpr FfmpegSoNames kFfmpegSoNames[] = {
    {"libavutil.so.61", "libswresample.so.7", "libswscale.so.10", "libavcodec.so.63"},  // FFmpeg 9.x
    {"libavutil.so.60", "libswresample.so.6", "libswscale.so.9", "libavcodec.so.62"},  // FFmpeg 8.x
    {"libavutil.so.59", "libswresample.so.5", "libswscale.so.8", "libavcodec.so.61"},  // FFmpeg 7.x
    {"libavutil.so.58", "libswresample.so.4", "libswscale.so.7", "libavcodec.so.60"},  // FFmpeg 6.x
    {"libavutil.so.57", "libswresample.so.4", "libswscale.so.6", "libavcodec.so.59"},  // FFmpeg 5.x
    {"libavutil.so.56", "libswresample.so.3", "libswscale.so.5", "libavcodec.so.58"},  // FFmpeg 4.x
    {"libavutil.so", "libswresample.so", "libswscale.so", "libavcodec.so"},
};

constexpr const char* kSystemLibDirs[] = {
#if defined(__x86_64__)
    "/usr/lib/x86_64-linux-gnu",
    "/lib/x86_64-linux-gnu",
#elif defined(__aarch64__)
    "/usr/lib/aarch64-linux-gnu",
    "/lib/aarch64-linux-gnu",
#endif
    "/usr/lib64",
    "/lib64",
    "/usr/lib",
};

bool IsExpectedSystemFile(const void* sym, const char* expectedPath) {
  if (sym == nullptr || expectedPath == nullptr) {
    return false;
  }
  Dl_info dlInfo = {};
  if (dladdr(sym, &dlInfo) == 0 || dlInfo.dli_fname == nullptr) {
    return false;
  }
  struct stat actualStat = {};
  struct stat expectedStat = {};
  if (stat(dlInfo.dli_fname, &actualStat) != 0 || stat(expectedPath, &expectedStat) != 0) {
    return false;
  }
  return actualStat.st_dev == expectedStat.st_dev && actualStat.st_ino == expectedStat.st_ino;
}

class SystemFfmpegApi {
public:
  static const SystemFfmpegApi& Instance() {
    static const SystemFfmpegApi instance;
    return instance;
  }

  bool IsAvailable() const { return available_; }
  int BgraPixFmt() const { return bgraPixFmt_; }

  std::vector<const void*> FindDecoders(uint32_t codecType) const {
    std::vector<const void*> decoders;
    if (!available_) {
      return decoders;
    }
    auto addByNames = [&](std::initializer_list<const char*> names) {
      for (const char* name : names) {
        if (const void* codec = avcodec_find_decoder_by_name(name)) {
          decoders.push_back(codec);
        }
      }
    };
    switch (codecType) {
      case kCodecTypeAVC:
        addByNames({"h264", "libopenh264"});
        break;
      case kCodecTypeHEVC:
        addByNames({"hevc", "libde265"});
        break;
      case kCodecTypeVP8:
        addByNames({"vp8", "libvpx"});
        break;
      case kCodecTypeVP9:
        addByNames({"vp9", "libvpx-vp9"});
        break;
      case kCodecTypeAV1:
        // Note: FFmpeg's built-in "av1" decoder is a hardware-only stub that requires
        // hw_device_ctx and fails at decode time with ENOSYS when opened without one.
        addByNames({"libdav1d", "libaom-av1"});
        break;
      default:
        break;
    }
    return decoders;
  }

  // libavcodec functions
  const void* (*avcodec_find_decoder_by_name)(const char* name) = nullptr;
  void* (*avcodec_alloc_context3)(const void* codec) = nullptr;
  void (*avcodec_free_context)(void** avctx) = nullptr;
  int (*avcodec_open2)(void* avctx, const void* codec, void** options) = nullptr;
  int (*avcodec_send_packet)(void* avctx, const void* avpkt) = nullptr;
  int (*avcodec_receive_frame)(void* avctx, void* frame) = nullptr;
  void (*avcodec_flush_buffers)(void* avctx) = nullptr;
  void* (*av_packet_alloc)() = nullptr;
  void (*av_packet_free)(void** pkt) = nullptr;
  void (*av_packet_unref)(void* pkt) = nullptr;
  int (*av_packet_from_data)(void* pkt, uint8_t* data, int size) = nullptr;

  // libavutil functions
  void* (*av_frame_alloc)() = nullptr;
  void (*av_frame_free)(void** frame) = nullptr;
  void (*av_frame_unref)(void* frame) = nullptr;
  void* (*av_malloc)(size_t size) = nullptr;
  void (*av_free)(void* ptr) = nullptr;
  int (*av_dict_set)(void** pm, const char* key, const char* value, int flags) = nullptr;
  void (*av_dict_free)(void** pm) = nullptr;
  void (*av_log_set_level)(int level) = nullptr;
  int (*av_get_pix_fmt)(const char* name) = nullptr;

  // libswscale functions
  void* (*sws_getCachedContext)(void* context, int srcW, int srcH, int srcFormat,
                                int dstW, int dstH, int dstFormat, int flags,
                                void* srcFilter, void* dstFilter, const double* param) = nullptr;
  void (*sws_freeContext)(void* swsContext) = nullptr;
  int (*sws_scale)(void* c, const uint8_t* const srcSlice[], const int srcStride[],
                   int srcSliceY, int srcSliceH, uint8_t* const dst[], const int dstStride[]) = nullptr;

private:
  SystemFfmpegApi() {
    for (const auto& soNames : kFfmpegSoNames) {
      for (const char* dir : kSystemLibDirs) {
        std::string prefix = std::string(dir) + "/";
        std::string avutilPath = prefix + soNames.avutilSoName;
        std::string swresamplePath = prefix + soNames.swresampleSoName;
        std::string swscalePath = prefix + soNames.swscaleSoName;
        std::string avcodecPath = prefix + soNames.avcodecSoName;

        if (access(avutilPath.c_str(), R_OK) != 0 ||
            access(swscalePath.c_str(), R_OK) != 0 ||
            access(avcodecPath.c_str(), R_OK) != 0) {
          continue;
        }

        if (TryLoadLibraries(avutilPath.c_str(), swresamplePath.c_str(), swscalePath.c_str(), avcodecPath.c_str())) {
          return;
        }
      }
    }
  }

  bool TryLoadLibraries(
      const char* avutilPath, const char* swresamplePath, const char* swscalePath, const char* avcodecPath) {
    void* avutil = nullptr;
    void* swresample = nullptr;
    void* swscale = nullptr;
    void* avcodec = nullptr;

    auto closeHandles = [&]() {
      if (avcodec != nullptr) dlclose(avcodec);
      if (swscale != nullptr) dlclose(swscale);
      if (swresample != nullptr) dlclose(swresample);
      if (avutil != nullptr) dlclose(avutil);
      avutil = nullptr;
      swresample = nullptr;
      swscale = nullptr;
      avcodec = nullptr;
    };

    // First attempt loading in a new linker namespace (LM_ID_NEWLM) to avoid any symbol or SONAME
    // collision with JavaCPP's bundled FFmpeg libraries loaded in LM_ID_BASE.
    // Note: glibc's dlmopen rejects RTLD_GLOBAL with EINVAL; objects loaded into the same secondary
    // namespace automatically resolve DT_NEEDED symbols from each other with RTLD_LOCAL.
    avutil = dlmopen(LM_ID_NEWLM, avutilPath, RTLD_LAZY | RTLD_LOCAL);
    if (avutil != nullptr) {
      Lmid_t lmid = LM_ID_BASE;
      if (dlinfo(avutil, RTLD_DI_LMID, &lmid) == 0) {
        if (access(swresamplePath, R_OK) == 0) {
          swresample = dlmopen(lmid, swresamplePath, RTLD_LAZY | RTLD_LOCAL);
        }
        swscale = dlmopen(lmid, swscalePath, RTLD_LAZY | RTLD_LOCAL);
        avcodec = dlmopen(lmid, avcodecPath, RTLD_LAZY | RTLD_LOCAL);
      }
      if (swscale == nullptr || avcodec == nullptr) {
        closeHandles();
      }
    }

    // Fall back to standard dlopen with RTLD_LOCAL | RTLD_DEEPBIND if dlmopen could not resolve
    // all transitive dependencies (e.g. due to glibc's per-namespace static TLS surplus limit).
    if (avutil == nullptr || swscale == nullptr || avcodec == nullptr) {
      int flags = RTLD_LAZY | RTLD_LOCAL;
#ifdef RTLD_DEEPBIND
      flags |= RTLD_DEEPBIND;
#endif
      avutil = dlopen(avutilPath, flags);
      if (avutil != nullptr && access(swresamplePath, R_OK) == 0) {
        swresample = dlopen(swresamplePath, flags);
      }
      swscale = avutil ? dlopen(swscalePath, flags) : nullptr;
      avcodec = swscale ? dlopen(avcodecPath, flags) : nullptr;
      if (avutil == nullptr || swscale == nullptr || avcodec == nullptr) {
        closeHandles();
        return false;
      }
    }

    auto fn_avcodec_find_decoder_by_name =
        reinterpret_cast<decltype(avcodec_find_decoder_by_name)>(dlsym(avcodec, "avcodec_find_decoder_by_name"));
    auto fn_avcodec_alloc_context3 = reinterpret_cast<decltype(avcodec_alloc_context3)>(dlsym(avcodec, "avcodec_alloc_context3"));
    auto fn_avcodec_free_context = reinterpret_cast<decltype(avcodec_free_context)>(dlsym(avcodec, "avcodec_free_context"));
    auto fn_avcodec_open2 = reinterpret_cast<decltype(avcodec_open2)>(dlsym(avcodec, "avcodec_open2"));
    auto fn_avcodec_send_packet = reinterpret_cast<decltype(avcodec_send_packet)>(dlsym(avcodec, "avcodec_send_packet"));
    auto fn_avcodec_receive_frame = reinterpret_cast<decltype(avcodec_receive_frame)>(dlsym(avcodec, "avcodec_receive_frame"));
    auto fn_avcodec_flush_buffers = reinterpret_cast<decltype(avcodec_flush_buffers)>(dlsym(avcodec, "avcodec_flush_buffers"));
    auto fn_av_packet_alloc = reinterpret_cast<decltype(av_packet_alloc)>(dlsym(avcodec, "av_packet_alloc"));
    auto fn_av_packet_free = reinterpret_cast<decltype(av_packet_free)>(dlsym(avcodec, "av_packet_free"));
    auto fn_av_packet_unref = reinterpret_cast<decltype(av_packet_unref)>(dlsym(avcodec, "av_packet_unref"));
    auto fn_av_packet_from_data = reinterpret_cast<decltype(av_packet_from_data)>(dlsym(avcodec, "av_packet_from_data"));

    auto fn_av_frame_alloc = reinterpret_cast<decltype(av_frame_alloc)>(dlsym(avutil, "av_frame_alloc"));
    auto fn_av_frame_free = reinterpret_cast<decltype(av_frame_free)>(dlsym(avutil, "av_frame_free"));
    auto fn_av_frame_unref = reinterpret_cast<decltype(av_frame_unref)>(dlsym(avutil, "av_frame_unref"));
    auto fn_av_malloc = reinterpret_cast<decltype(av_malloc)>(dlsym(avutil, "av_malloc"));
    auto fn_av_free = reinterpret_cast<decltype(av_free)>(dlsym(avutil, "av_free"));
    auto fn_av_dict_set = reinterpret_cast<decltype(av_dict_set)>(dlsym(avutil, "av_dict_set"));
    auto fn_av_dict_free = reinterpret_cast<decltype(av_dict_free)>(dlsym(avutil, "av_dict_free"));
    auto fn_av_log_set_level = reinterpret_cast<decltype(av_log_set_level)>(dlsym(avutil, "av_log_set_level"));
    auto fn_av_get_pix_fmt = reinterpret_cast<decltype(av_get_pix_fmt)>(dlsym(avutil, "av_get_pix_fmt"));

    auto fn_sws_getCachedContext = reinterpret_cast<decltype(sws_getCachedContext)>(dlsym(swscale, "sws_getCachedContext"));
    auto fn_sws_freeContext = reinterpret_cast<decltype(sws_freeContext)>(dlsym(swscale, "sws_freeContext"));
    auto fn_sws_scale = reinterpret_cast<decltype(sws_scale)>(dlsym(swscale, "sws_scale"));

    if (!fn_avcodec_find_decoder_by_name || !fn_avcodec_alloc_context3 || !fn_avcodec_free_context ||
        !fn_avcodec_open2 || !fn_avcodec_send_packet || !fn_avcodec_receive_frame || !fn_avcodec_flush_buffers ||
        !fn_av_packet_alloc || !fn_av_packet_free || !fn_av_packet_unref || !fn_av_packet_from_data ||
        !fn_av_frame_alloc || !fn_av_frame_free || !fn_av_frame_unref || !fn_av_malloc || !fn_av_free ||
        !fn_av_dict_set || !fn_av_dict_free || !fn_av_get_pix_fmt ||
        !fn_sws_getCachedContext || !fn_sws_freeContext || !fn_sws_scale) {
      closeHandles();
      return false;
    }

    // Verify that every resolved symbol belongs to the exact system library file on disk (matching
    // st_dev and st_ino) and that avcodec and swscale bound their DT_NEEDED libavutil dependency
    // to that exact same libavutil instance.
    if (!IsExpectedSystemFile(reinterpret_cast<const void*>(fn_avcodec_find_decoder_by_name), avcodecPath) ||
        !IsExpectedSystemFile(reinterpret_cast<const void*>(fn_av_frame_alloc), avutilPath) ||
        !IsExpectedSystemFile(reinterpret_cast<const void*>(fn_sws_scale), swscalePath) ||
        dlsym(avcodec, "av_frame_alloc") != reinterpret_cast<void*>(fn_av_frame_alloc) ||
        dlsym(swscale, "av_frame_alloc") != reinterpret_cast<void*>(fn_av_frame_alloc)) {
      closeHandles();
      return false;
    }

    int bgraPixFmt = fn_av_get_pix_fmt("bgra");
    if (bgraPixFmt < 0) {
      closeHandles();
      return false;
    }

    if (fn_av_log_set_level != nullptr) {
      fn_av_log_set_level(-8);  // AV_LOG_QUIET
    }

    avcodec_find_decoder_by_name = fn_avcodec_find_decoder_by_name;
    avcodec_alloc_context3 = fn_avcodec_alloc_context3;
    avcodec_free_context = fn_avcodec_free_context;
    avcodec_open2 = fn_avcodec_open2;
    avcodec_send_packet = fn_avcodec_send_packet;
    avcodec_receive_frame = fn_avcodec_receive_frame;
    avcodec_flush_buffers = fn_avcodec_flush_buffers;
    av_packet_alloc = fn_av_packet_alloc;
    av_packet_free = fn_av_packet_free;
    av_packet_unref = fn_av_packet_unref;
    av_packet_from_data = fn_av_packet_from_data;

    av_frame_alloc = fn_av_frame_alloc;
    av_frame_free = fn_av_frame_free;
    av_frame_unref = fn_av_frame_unref;
    av_malloc = fn_av_malloc;
    av_free = fn_av_free;
    av_dict_set = fn_av_dict_set;
    av_dict_free = fn_av_dict_free;
    av_log_set_level = fn_av_log_set_level;
    av_get_pix_fmt = fn_av_get_pix_fmt;

    sws_getCachedContext = fn_sws_getCachedContext;
    sws_freeContext = fn_sws_freeContext;
    sws_scale = fn_sws_scale;

    avutilHandle_ = avutil;
    swresampleHandle_ = swresample;
    swscaleHandle_ = swscale;
    avcodecHandle_ = avcodec;
    bgraPixFmt_ = bgraPixFmt;
    available_ = true;
    return true;
  }

  void* avutilHandle_ = nullptr;
  void* swresampleHandle_ = nullptr;
  void* swscaleHandle_ = nullptr;
  void* avcodecHandle_ = nullptr;
  int bgraPixFmt_ = -1;
  bool available_ = false;
};

// GStreamer fallback API for systems where GStreamer plugins (e.g. openh264dec / vah264dec)
// are installed without system FFmpeg.
class SystemGstApi {
public:
  static const SystemGstApi& Instance() {
    static const SystemGstApi instance;
    return instance;
  }

  bool IsAvailable() const { return available_; }

  std::vector<const char*> FindDecoderElementNames(uint32_t codecType) const {
    std::vector<const char*> result;
    if (!available_) {
      return result;
    }
    auto addAvailable = [&](std::initializer_list<const char*> names) {
      for (const char* name : names) {
        if (HasElement(name)) {
          result.push_back(name);
        }
      }
    };
    switch (codecType) {
      case kCodecTypeAVC:
        if (!HasElement("h264parse")) return result;
        addAvailable({"openh264dec", "vah264dec", "vaapih264dec", "nvh264dec"});
        break;
      case kCodecTypeHEVC:
        if (!HasElement("h265parse")) return result;
        addAvailable({"de265dec", "vah265dec", "vaapih265dec", "nvh265dec"});
        break;
      case kCodecTypeVP8:
        addAvailable({"vp8dec", "avdec_vp8", "vavp8dec", "vaapivp8dec", "nvvp8dec"});
        break;
      case kCodecTypeVP9:
        addAvailable({"vp9dec", "avdec_vp9", "vavp9dec", "vaapivp9dec", "nvvp9dec"});
        break;
      case kCodecTypeAV1:
        addAvailable({"dav1ddec", "av1dec", "avdec_av1", "vaav1dec", "vaapiav1dec", "nvav1dec"});
        break;
      default:
        break;
    }
    return result;
  }

  bool HasElement(const char* name) const {
    if (!available_ || !gst_element_factory_find || !gst_object_unref) {
      return false;
    }
    void* factory = gst_element_factory_find(name);
    if (factory == nullptr) {
      return false;
    }
    gst_object_unref(factory);
    return true;
  }

  struct GstMapInfo {
    void* memory;
    int flags;
    uint8_t* data;
    size_t size;
    size_t maxsize;
    void* user_data[4];
    void* _gst_reserved[4];
  };

  struct GstVideoMetaPrefix {
    int metaFlags;
    const void* metaInfo;
    void* buffer;
    int frameFlags;
    int format;
    unsigned int id;
    unsigned int width;
    unsigned int height;
    unsigned int n_planes;
    size_t offset[4];
    int stride[4];
  };

  int (*gst_init_check)(int* argc, char*** argv, void** err) = nullptr;
  void* (*gst_element_factory_find)(const char* name) = nullptr;
  void* (*gst_parse_launch)(const char* pipeline_description, void** error) = nullptr;
  void* (*gst_bin_get_by_name)(void* bin, const char* name) = nullptr;
  int (*gst_element_set_state)(void* element, int state) = nullptr;
  void* (*gst_element_get_bus)(void* element) = nullptr;
  void* (*gst_bus_pop_filtered)(void* bus, int types) = nullptr;
  void (*gst_object_unref)(void* object) = nullptr;
  void* (*gst_buffer_new_allocate)(void* allocator, size_t size, void* params) = nullptr;
  size_t (*gst_buffer_fill)(void* buffer, size_t offset, const void* src, size_t size) = nullptr;
  int (*gst_app_src_push_buffer)(void* appsrc, void* buffer) = nullptr;
  int (*gst_app_src_end_of_stream)(void* appsrc) = nullptr;
  void* (*gst_app_sink_try_pull_sample)(void* appsink, uint64_t timeout) = nullptr;
  void* (*gst_sample_get_buffer)(void* sample) = nullptr;
  void* (*gst_sample_get_caps)(void* sample) = nullptr;
  void* (*gst_caps_get_structure)(const void* caps, unsigned int index) = nullptr;
  int (*gst_structure_get_int)(const void* structure, const char* fieldname, int* value) = nullptr;
  int (*gst_buffer_map)(void* buffer, GstMapInfo* info, int flags) = nullptr;
  void (*gst_buffer_unmap)(void* buffer, GstMapInfo* info) = nullptr;
  void (*gst_mini_object_unref)(void* mini_object) = nullptr;
  GstVideoMetaPrefix* (*gst_buffer_get_video_meta)(void* buffer) = nullptr;

private:
  SystemGstApi() {
    void* gst = dlopen("libgstreamer-1.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    void* app = gst ? dlopen("libgstapp-1.0.so.0", RTLD_LAZY | RTLD_LOCAL) : nullptr;
    if (!gst || !app) {
      if (app) dlclose(app);
      if (gst) dlclose(gst);
      return;
    }
    void* video = dlopen("libgstvideo-1.0.so.0", RTLD_LAZY | RTLD_LOCAL);

    auto fn_gst_init_check = reinterpret_cast<decltype(gst_init_check)>(dlsym(gst, "gst_init_check"));
    auto fn_gst_element_factory_find = reinterpret_cast<decltype(gst_element_factory_find)>(dlsym(gst, "gst_element_factory_find"));
    auto fn_gst_parse_launch = reinterpret_cast<decltype(gst_parse_launch)>(dlsym(gst, "gst_parse_launch"));
    auto fn_gst_bin_get_by_name = reinterpret_cast<decltype(gst_bin_get_by_name)>(dlsym(gst, "gst_bin_get_by_name"));
    auto fn_gst_element_set_state = reinterpret_cast<decltype(gst_element_set_state)>(dlsym(gst, "gst_element_set_state"));
    auto fn_gst_element_get_bus = reinterpret_cast<decltype(gst_element_get_bus)>(dlsym(gst, "gst_element_get_bus"));
    auto fn_gst_bus_pop_filtered = reinterpret_cast<decltype(gst_bus_pop_filtered)>(dlsym(gst, "gst_bus_pop_filtered"));
    auto fn_gst_object_unref = reinterpret_cast<decltype(gst_object_unref)>(dlsym(gst, "gst_object_unref"));
    auto fn_gst_buffer_new_allocate = reinterpret_cast<decltype(gst_buffer_new_allocate)>(dlsym(gst, "gst_buffer_new_allocate"));
    auto fn_gst_buffer_fill = reinterpret_cast<decltype(gst_buffer_fill)>(dlsym(gst, "gst_buffer_fill"));
    auto fn_gst_sample_get_buffer = reinterpret_cast<decltype(gst_sample_get_buffer)>(dlsym(gst, "gst_sample_get_buffer"));
    auto fn_gst_sample_get_caps = reinterpret_cast<decltype(gst_sample_get_caps)>(dlsym(gst, "gst_sample_get_caps"));
    auto fn_gst_caps_get_structure = reinterpret_cast<decltype(gst_caps_get_structure)>(dlsym(gst, "gst_caps_get_structure"));
    auto fn_gst_structure_get_int = reinterpret_cast<decltype(gst_structure_get_int)>(dlsym(gst, "gst_structure_get_int"));
    auto fn_gst_buffer_map = reinterpret_cast<decltype(gst_buffer_map)>(dlsym(gst, "gst_buffer_map"));
    auto fn_gst_buffer_unmap = reinterpret_cast<decltype(gst_buffer_unmap)>(dlsym(gst, "gst_buffer_unmap"));
    auto fn_gst_mini_object_unref = reinterpret_cast<decltype(gst_mini_object_unref)>(dlsym(gst, "gst_mini_object_unref"));

    auto fn_gst_app_src_push_buffer = reinterpret_cast<decltype(gst_app_src_push_buffer)>(dlsym(app, "gst_app_src_push_buffer"));
    auto fn_gst_app_src_end_of_stream = reinterpret_cast<decltype(gst_app_src_end_of_stream)>(dlsym(app, "gst_app_src_end_of_stream"));
    auto fn_gst_app_sink_try_pull_sample = reinterpret_cast<decltype(gst_app_sink_try_pull_sample)>(dlsym(app, "gst_app_sink_try_pull_sample"));

    auto fn_gst_buffer_get_video_meta =
        video ? reinterpret_cast<decltype(gst_buffer_get_video_meta)>(dlsym(video, "gst_buffer_get_video_meta")) : nullptr;

    if (!fn_gst_init_check || !fn_gst_element_factory_find || !fn_gst_parse_launch || !fn_gst_bin_get_by_name ||
        !fn_gst_element_set_state || !fn_gst_element_get_bus || !fn_gst_bus_pop_filtered || !fn_gst_object_unref ||
        !fn_gst_buffer_new_allocate || !fn_gst_buffer_fill || !fn_gst_sample_get_buffer || !fn_gst_sample_get_caps ||
        !fn_gst_caps_get_structure || !fn_gst_structure_get_int || !fn_gst_buffer_map || !fn_gst_buffer_unmap ||
        !fn_gst_mini_object_unref || !fn_gst_app_src_push_buffer || !fn_gst_app_src_end_of_stream ||
        !fn_gst_app_sink_try_pull_sample) {
      if (video) dlclose(video);
      dlclose(app);
      dlclose(gst);
      return;
    }

    if (!fn_gst_init_check(nullptr, nullptr, nullptr)) {
      if (video) dlclose(video);
      dlclose(app);
      dlclose(gst);
      return;
    }

    void* vcFactory = fn_gst_element_factory_find("videoconvert");
    if (vcFactory == nullptr) {
      if (video) dlclose(video);
      dlclose(app);
      dlclose(gst);
      return;
    }
    fn_gst_object_unref(vcFactory);

    gst_init_check = fn_gst_init_check;
    gst_element_factory_find = fn_gst_element_factory_find;
    gst_parse_launch = fn_gst_parse_launch;
    gst_bin_get_by_name = fn_gst_bin_get_by_name;
    gst_element_set_state = fn_gst_element_set_state;
    gst_element_get_bus = fn_gst_element_get_bus;
    gst_bus_pop_filtered = fn_gst_bus_pop_filtered;
    gst_object_unref = fn_gst_object_unref;
    gst_buffer_new_allocate = fn_gst_buffer_new_allocate;
    gst_buffer_fill = fn_gst_buffer_fill;
    gst_sample_get_buffer = fn_gst_sample_get_buffer;
    gst_sample_get_caps = fn_gst_sample_get_caps;
    gst_caps_get_structure = fn_gst_caps_get_structure;
    gst_structure_get_int = fn_gst_structure_get_int;
    gst_buffer_map = fn_gst_buffer_map;
    gst_buffer_unmap = fn_gst_buffer_unmap;
    gst_mini_object_unref = fn_gst_mini_object_unref;
    gst_app_src_push_buffer = fn_gst_app_src_push_buffer;
    gst_app_src_end_of_stream = fn_gst_app_src_end_of_stream;
    gst_app_sink_try_pull_sample = fn_gst_app_sink_try_pull_sample;
    gst_buffer_get_video_meta = fn_gst_buffer_get_video_meta;

    gstHandle_ = gst;
    appHandle_ = app;
    videoHandle_ = video;
    available_ = true;
  }

  void* gstHandle_ = nullptr;
  void* appHandle_ = nullptr;
  void* videoHandle_ = nullptr;
  bool available_ = false;
};

class LinuxVideoDecoder {
public:
  explicit LinuxVideoDecoder(uint32_t codecType) : codecType_(codecType) {}

  ~LinuxVideoDecoder() {
    std::lock_guard<std::mutex> lock(decodeMutex_);
    DestroyFfmpegDecoder();
    DestroyGstPipeline();
  }

  bool Initialize() {
    const auto& ffmpeg = SystemFfmpegApi::Instance();
    ffmpegDecoders_ = ffmpeg.FindDecoders(codecType_);
    for (size_t i = 0; i < ffmpegDecoders_.size(); ++i) {
      codec_ = ffmpegDecoders_[i];
      if (InitFfmpegDecoder()) {
        ffmpegDecoderIndex_ = i;
        useFfmpeg_ = true;
        return true;
      }
    }

    const auto& gst = SystemGstApi::Instance();
    gstDecoderNames_ = gst.FindDecoderElementNames(codecType_);
    for (size_t i = 0; i < gstDecoderNames_.size(); ++i) {
      gstDecoderName_ = gstDecoderNames_[i];
      if (InitGstPipeline()) {
        gstDecoderIndex_ = i;
        useGst_ = true;
        return true;
      }
    }

    return false;
  }

  jint Decode(
      const uint8_t* packetData, size_t packetSize, uint8_t* outputPixels, size_t outputCapacity, int32_t* outWidth, int32_t* outHeight) {
    std::lock_guard<std::mutex> lock(decodeMutex_);
    if (packetSize == 0) {
      return DECODE_NO_FRAME;
    }

    PreparedPacket prepared;
    PacketKind kind = InspectAndPreparePacket(packetData, packetSize, &prepared);
    if (kind == PacketKind::kInvalid) {
      return FailureResult();
    }

    if (useFfmpeg_) {
      return DecodeFfmpeg(kind, prepared, outputPixels, outputCapacity, outWidth, outHeight);
    }
    if (useGst_) {
      return DecodeGst(kind, prepared, outputPixels, outputCapacity, outWidth, outHeight);
    }
    return DECODE_ERROR;
  }

private:
  enum class PacketKind {
    kInvalid,
    kConfigOnly,
    kFrame,
  };

  struct PreparedPacket {
    const uint8_t* data = nullptr;
    size_t size = 0;
    std::vector<uint8_t> storage;
    bool configUpdated = false;
    bool packetHasAllConfig = false;
    bool endsWithAud = false;
    bool expectsOutputFrame = true;
  };

  jint FailureResult() const {
    return hasDecodedFrames_ ? DECODE_INVALID_FRAME : DECODE_ERROR;
  }

  PacketKind InspectAndPreparePacket(const uint8_t* packetData, size_t packetSize, PreparedPacket* out) {
    out->data = packetData;
    out->size = packetSize;
    out->configUpdated = false;
    out->packetHasAllConfig = false;
    out->endsWithAud = false;
    out->expectsOutputFrame = true;

    if (codecType_ == kCodecTypeAVC || codecType_ == kCodecTypeHEVC) {
      bool isHevc = (codecType_ == kCodecTypeHEVC);
      std::vector<NalUnit> units = ParseAnnexBNalUnits(packetData, packetSize, isHevc);
      if (units.empty()) {
        return PacketKind::kInvalid;
      }

      bool hasParameterSets = false;
      bool hasNonVcl = false;
      bool hasSlice = false;
      bool packetHasVps = false;
      bool packetHasSps = false;
      bool packetHasPps = false;

      for (const auto& unit : units) {
        if ((unit.data[0] & 0x80) != 0) {  // forbidden_zero_bit must be 0
          return PacketKind::kInvalid;
        }
        if (isHevc) {
          if ((unit.data[1] & 0x07) == 0) {  // nuh_temporal_id_plus1 must be > 0
            return PacketKind::kInvalid;
          }
          if (unit.type == HEVC_NAL_VPS) {
            hasParameterSets = true;
            packetHasVps = true;
            if (vps_.size() != unit.size || std::memcmp(vps_.data(), unit.data, unit.size) != 0) {
              vps_.assign(unit.data, unit.data + unit.size);
              out->configUpdated = true;
            }
          } else if (unit.type == HEVC_NAL_SPS) {
            hasParameterSets = true;
            packetHasSps = true;
            if (sps_.size() != unit.size || std::memcmp(sps_.data(), unit.data, unit.size) != 0) {
              sps_.assign(unit.data, unit.data + unit.size);
              out->configUpdated = true;
            }
          } else if (unit.type == HEVC_NAL_PPS) {
            hasParameterSets = true;
            packetHasPps = true;
            if (pps_.size() != unit.size || std::memcmp(pps_.data(), unit.data, unit.size) != 0) {
              pps_.assign(unit.data, unit.data + unit.size);
              out->configUpdated = true;
            }
          } else if (unit.type <= HEVC_NAL_VCL_MAX) {
            hasSlice = true;
          } else if (unit.type == HEVC_NAL_AUD || unit.type == HEVC_NAL_FILLER ||
                     unit.type == HEVC_NAL_PREFIX_SEI || unit.type == HEVC_NAL_SUFFIX_SEI) {
            hasNonVcl = true;
          }
        } else {
          if (unit.type == 0 || unit.type > 23) {
            return PacketKind::kInvalid;
          }
          if (unit.type == AVC_NAL_SPS) {
            hasParameterSets = true;
            packetHasSps = true;
            if (sps_.size() != unit.size || std::memcmp(sps_.data(), unit.data, unit.size) != 0) {
              sps_.assign(unit.data, unit.data + unit.size);
              out->configUpdated = true;
            }
          } else if (unit.type == AVC_NAL_PPS) {
            hasParameterSets = true;
            packetHasPps = true;
            if (pps_.size() != unit.size || std::memcmp(pps_.data(), unit.data, unit.size) != 0) {
              pps_.assign(unit.data, unit.data + unit.size);
              out->configUpdated = true;
            }
          } else if (unit.type >= AVC_NAL_SLICE_MIN && unit.type <= AVC_NAL_SLICE_MAX) {
            hasSlice = true;
          } else if (unit.type == AVC_NAL_SEI || unit.type == AVC_NAL_AUD || unit.type == AVC_NAL_FILLER) {
            hasNonVcl = true;
          }
        }
      }

      if (out->configUpdated) {
        ffmpegSentConfig_ = false;
        gstSentConfig_ = false;
      }

      if (!hasSlice) {
        return (hasParameterSets || hasNonVcl) ? PacketKind::kConfigOnly : PacketKind::kInvalid;
      }
      if (sps_.empty() || pps_.empty() || (isHevc && vps_.empty())) {
        return PacketKind::kInvalid;
      }

      out->packetHasAllConfig = packetHasSps && packetHasPps && (!isHevc || packetHasVps);
      out->endsWithAud = (units.back().type == (isHevc ? HEVC_NAL_AUD : AVC_NAL_AUD));
      return PacketKind::kFrame;
    }

    if (codecType_ == kCodecTypeAV1) {
      bool hasSequenceHeader = false;
      bool hasFrameData = false;
      bool hasNonFrameObu = false;
      size_t offset = 0;

      while (offset < packetSize) {
        uint8_t header = packetData[offset];
        if ((header & 0x80) != 0) {  // obu_forbidden_bit must be 0
          return PacketKind::kInvalid;
        }
        uint8_t obuType = (header >> 3) & 0x0F;
        if (obuType == 0 || (obuType > AV1_OBU_TILE_LIST && obuType != AV1_OBU_PADDING)) {
          return PacketKind::kInvalid;
        }
        bool hasExtension = (header & 0x04) != 0;
        bool hasSizeField = (header & 0x02) != 0;
        size_t headerLen = 1 + (hasExtension ? 1 : 0);
        if (offset + headerLen > packetSize) {
          return PacketKind::kInvalid;
        }

        size_t payloadSize = 0;
        if (hasSizeField) {
          size_t lebBytes = 0;
          if (!ReadLeb128(packetData + offset + headerLen, packetSize - offset - headerLen, &payloadSize, &lebBytes)) {
            return PacketKind::kInvalid;
          }
          headerLen += lebBytes;
          if (offset + headerLen + payloadSize > packetSize) {
            return PacketKind::kInvalid;
          }
        } else {
          payloadSize = packetSize - offset - headerLen;
        }

        size_t totalObuSize = headerLen + payloadSize;
        const uint8_t* obuStart = packetData + offset;

        if (obuType == AV1_OBU_SEQUENCE_HEADER) {
          if (payloadSize == 0) {
            return PacketKind::kInvalid;
          }
          hasSequenceHeader = true;
          std::vector<uint8_t> normalizedSeq;
          AppendObuWithSizeField(&normalizedSeq, obuStart, headerLen, payloadSize, hasSizeField);
          if (seqHeader_ != normalizedSeq) {
            seqHeader_ = std::move(normalizedSeq);
            out->configUpdated = true;
          }
        } else if (obuType == AV1_OBU_FRAME || obuType == AV1_OBU_TILE_GROUP || obuType == AV1_OBU_FRAME_HEADER) {
          if (payloadSize == 0) {
            return PacketKind::kInvalid;
          }
          if (obuType == AV1_OBU_FRAME || obuType == AV1_OBU_TILE_GROUP) {
            hasFrameData = true;
          } else {
            hasNonFrameObu = true;
          }
        } else if (obuType == AV1_OBU_TEMPORAL_DELIMITER || obuType == AV1_OBU_METADATA ||
                   obuType == AV1_OBU_REDUNDANT_FRAME_HEADER || obuType == AV1_OBU_PADDING) {
          hasNonFrameObu = true;
        }

        AppendObuWithSizeField(&out->storage, obuStart, headerLen, payloadSize, hasSizeField);
        offset += totalObuSize;
      }

      if (out->configUpdated) {
        ffmpegSentConfig_ = false;
        gstSentConfig_ = false;
      }

      if (!hasFrameData) {
        return (hasSequenceHeader || hasNonFrameObu) ? PacketKind::kConfigOnly : PacketKind::kInvalid;
      }
      if (seqHeader_.empty()) {
        return PacketKind::kInvalid;
      }

      out->packetHasAllConfig = hasSequenceHeader;
      out->data = out->storage.data();
      out->size = out->storage.size();
      return PacketKind::kFrame;
    }

    if (codecType_ == kCodecTypeVP8) {
      if (packetSize < 3) {
        return PacketKind::kInvalid;
      }
      bool isKeyFrame = (packetData[0] & 0x01) == 0;
      bool showFrame = (packetData[0] & 0x10) != 0;
      size_t firstPartSize = (static_cast<size_t>(packetData[0]) >> 5) |
                             (static_cast<size_t>(packetData[1]) << 3) |
                             (static_cast<size_t>(packetData[2]) << 11);
      size_t headerLen = isKeyFrame ? 10 : 3;
      if (packetSize < headerLen || firstPartSize == 0 || headerLen + firstPartSize > packetSize) {
        return PacketKind::kInvalid;
      }
      if (isKeyFrame) {
        if (packetData[3] != 0x9d || packetData[4] != 0x01 || packetData[5] != 0x2a) {
          return PacketKind::kInvalid;
        }
        int32_t width = (packetData[6] | (packetData[7] << 8)) & 0x3FFF;
        int32_t height = (packetData[8] | (packetData[9] << 8)) & 0x3FFF;
        if (width <= 0 || height <= 0) {
          return PacketKind::kInvalid;
        }
        if (width != currentWidth_ || height != currentHeight_) {
          currentWidth_ = width;
          currentHeight_ = height;
          out->configUpdated = true;
        }
        hasCodecConfig_ = true;
        out->packetHasAllConfig = true;
      } else if (!hasCodecConfig_) {
        return PacketKind::kInvalid;
      }
      out->expectsOutputFrame = showFrame;
      return PacketKind::kFrame;
    }

    if (codecType_ == kCodecTypeVP9) {
      Vp9PacketInfo info;
      if (!ParseVp9Packet(packetData, packetSize, &info)) {
        return PacketKind::kInvalid;
      }
      if (info.hasConfig) {
        if (info.width != currentWidth_ || info.height != currentHeight_) {
          currentWidth_ = info.width;
          currentHeight_ = info.height;
          out->configUpdated = true;
        }
        hasCodecConfig_ = true;
        out->packetHasAllConfig = true;
      } else if (!hasCodecConfig_) {
        return PacketKind::kInvalid;
      }
      out->expectsOutputFrame = info.showFrame;
      return PacketKind::kFrame;
    }

    return PacketKind::kInvalid;
  }

  std::vector<uint8_t> BuildPacketWithConfigAndAud(const PreparedPacket& prepared, bool needConfig, bool appendAud) const {
    static const uint8_t kStartCode[4] = {0x00, 0x00, 0x00, 0x01};
    static const uint8_t kAvcAud[6] = {0x00, 0x00, 0x00, 0x01, 0x09, 0xF0};
    static const uint8_t kHevcAud[7] = {0x00, 0x00, 0x00, 0x01, 0x46, 0x01, 0x50};

    std::vector<uint8_t> buf;
    if (needConfig && !prepared.packetHasAllConfig) {
      if (codecType_ == kCodecTypeAVC || codecType_ == kCodecTypeHEVC) {
        if (codecType_ == kCodecTypeHEVC && !vps_.empty()) {
          buf.insert(buf.end(), kStartCode, kStartCode + sizeof(kStartCode));
          buf.insert(buf.end(), vps_.begin(), vps_.end());
        }
        if (!sps_.empty()) {
          buf.insert(buf.end(), kStartCode, kStartCode + sizeof(kStartCode));
          buf.insert(buf.end(), sps_.begin(), sps_.end());
        }
        if (!pps_.empty()) {
          buf.insert(buf.end(), kStartCode, kStartCode + sizeof(kStartCode));
          buf.insert(buf.end(), pps_.begin(), pps_.end());
        }
      } else if (codecType_ == kCodecTypeAV1 && !seqHeader_.empty()) {
        buf.insert(buf.end(), seqHeader_.begin(), seqHeader_.end());
      }
    }

    buf.insert(buf.end(), prepared.data, prepared.data + prepared.size);

    if (appendAud && !prepared.endsWithAud) {
      if (codecType_ == kCodecTypeAVC) {
        buf.insert(buf.end(), kAvcAud, kAvcAud + sizeof(kAvcAud));
      } else if (codecType_ == kCodecTypeHEVC) {
        buf.insert(buf.end(), kHevcAud, kHevcAud + sizeof(kHevcAud));
      }
    }
    return buf;
  }

  bool InitFfmpegDecoder() {
    const auto& api = SystemFfmpegApi::Instance();
    codecCtx_ = api.avcodec_alloc_context3(codec_);
    if (codecCtx_ == nullptr) {
      return false;
    }

    void* opts = nullptr;
    api.av_dict_set(&opts, "threads", "1", 0);
    api.av_dict_set(&opts, "flags", "+low_delay", 0);
    // Setting strict=experimental (-2) alongside +low_delay instructs FFmpeg's h264/hevc decoders
    // to output frames immediately even when SPS VUI specifies num_reorder_frames > 0.
    api.av_dict_set(&opts, "strict", "experimental", 0);
    int ret = api.avcodec_open2(codecCtx_, codec_, &opts);
    api.av_dict_free(&opts);
    if (ret < 0) {
      api.avcodec_free_context(&codecCtx_);
      codecCtx_ = nullptr;
      return false;
    }

    frame_ = api.av_frame_alloc();
    pkt_ = api.av_packet_alloc();
    if (frame_ == nullptr || pkt_ == nullptr) {
      DestroyFfmpegDecoder();
      return false;
    }
    ffmpegSentConfig_ = false;
    return true;
  }

  void DestroyFfmpegDecoder() {
    if (swsCtx_ == nullptr && pkt_ == nullptr && frame_ == nullptr && codecCtx_ == nullptr) {
      return;
    }
    const auto& api = SystemFfmpegApi::Instance();
    if (!api.IsAvailable()) {
      return;
    }
    if (swsCtx_ != nullptr) {
      api.sws_freeContext(swsCtx_);
      swsCtx_ = nullptr;
    }
    if (pkt_ != nullptr) {
      api.av_packet_free(&pkt_);
      pkt_ = nullptr;
    }
    if (frame_ != nullptr) {
      api.av_frame_free(&frame_);
      frame_ = nullptr;
    }
    if (codecCtx_ != nullptr) {
      api.avcodec_free_context(&codecCtx_);
      codecCtx_ = nullptr;
    }
    ffmpegSentConfig_ = false;
  }

  bool SendRawPacketToFfmpeg(const uint8_t* data, size_t size) {
    if (size == 0 || size > static_cast<size_t>(INT32_MAX) - AV_INPUT_BUFFER_PADDING_SIZE) {
      return false;
    }
    const auto& api = SystemFfmpegApi::Instance();
    auto* buf = static_cast<uint8_t*>(api.av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE));
    if (buf == nullptr) {
      return false;
    }
    std::memcpy(buf, data, size);
    std::memset(buf + size, 0, AV_INPUT_BUFFER_PADDING_SIZE);

    if (api.av_packet_from_data(pkt_, buf, static_cast<int>(size)) < 0) {
      api.av_free(buf);
      return false;
    }

    int sendRet = api.avcodec_send_packet(codecCtx_, pkt_);
    api.av_packet_unref(pkt_);
    return sendRet >= 0;
  }

  bool SendPacketAndReceiveFrame(const PreparedPacket& prepared, bool* outNoFrameExpected) {
    *outNoFrameExpected = false;
    const auto& api = SystemFfmpegApi::Instance();

    bool needConfig = !ffmpegSentConfig_ && !prepared.packetHasAllConfig &&
                      (codecType_ == kCodecTypeAVC || codecType_ == kCodecTypeHEVC || codecType_ == kCodecTypeAV1);
    const uint8_t* sendData = prepared.data;
    size_t sendSize = prepared.size;
    std::vector<uint8_t> combined;
    if (needConfig) {
      combined = BuildPacketWithConfigAndAud(prepared, /*needConfig=*/true, /*appendAud=*/false);
      sendData = combined.data();
      sendSize = combined.size();
    }

    if (!SendRawPacketToFfmpeg(sendData, sendSize)) {
      return false;
    }
    ffmpegSentConfig_ = true;

    int recvRet = api.avcodec_receive_frame(codecCtx_, frame_);
    if (recvRet != 0) {
      if (!prepared.expectsOutputFrame) {
        *outNoFrameExpected = true;
        return false;
      }
      // Drain buffered frame if the decoder still held it back (e.g. external wrapper decoders).
      // Mark ffmpegSentConfig_ = false so cached parameter sets are re-supplied on the next packet.
      api.avcodec_send_packet(codecCtx_, nullptr);
      recvRet = api.avcodec_receive_frame(codecCtx_, frame_);
      api.avcodec_flush_buffers(codecCtx_);
      ffmpegSentConfig_ = false;
    }
    return recvRet == 0;
  }

  jint DecodeFfmpeg(PacketKind kind, const PreparedPacket& prepared,
                    uint8_t* outputPixels, size_t outputCapacity, int32_t* outWidth, int32_t* outHeight) {
    const auto& api = SystemFfmpegApi::Instance();
    if (kind == PacketKind::kConfigOnly) {
      if (SendRawPacketToFfmpeg(prepared.data, prepared.size)) {
        ffmpegSentConfig_ = true;
      }
      return DECODE_NO_FRAME;
    }

    bool noFrameExpected = false;
    if (!SendPacketAndReceiveFrame(prepared, &noFrameExpected)) {
      if (noFrameExpected) {
        return DECODE_NO_FRAME;
      }
      // Only re-initialize the decoder context if the packet carried a new configuration/keyframe
      // or if no frames have been decoded yet (in which case we can also try fallback decoders).
      if (prepared.configUpdated || prepared.packetHasAllConfig) {
        DestroyFfmpegDecoder();
        bool decoded = InitFfmpegDecoder() && SendPacketAndReceiveFrame(prepared, &noFrameExpected);
        if (!decoded && !hasDecodedFrames_) {
          for (size_t i = ffmpegDecoderIndex_ + 1; i < ffmpegDecoders_.size(); ++i) {
            codec_ = ffmpegDecoders_[i];
            DestroyFfmpegDecoder();
            if (InitFfmpegDecoder() && SendPacketAndReceiveFrame(prepared, &noFrameExpected)) {
              ffmpegDecoderIndex_ = i;
              decoded = true;
              break;
            }
          }
        }
        if (!decoded) {
          if (noFrameExpected) {
            return DECODE_NO_FRAME;
          }
          return FailureResult();
        }
      } else {
        return FailureResult();
      }
    }

    const auto* framePrefix = reinterpret_cast<const AvFramePrefix*>(frame_);
    int width = framePrefix->width;
    int height = framePrefix->height;
    int format = framePrefix->format;

    if (width <= 0 || height <= 0 || width > kMaxFrameDimension || height > kMaxFrameDimension || format < 0) {
      api.av_frame_unref(frame_);
      return FailureResult();
    }

    size_t dstRowBytes = static_cast<size_t>(width) * 4;
    size_t totalBytes = dstRowBytes * static_cast<size_t>(height);
    if (totalBytes > outputCapacity) {
      api.av_frame_unref(frame_);
      return FailureResult();
    }

    swsCtx_ = api.sws_getCachedContext(
        swsCtx_, width, height, format, width, height, api.BgraPixFmt(), SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (swsCtx_ == nullptr) {
      api.av_frame_unref(frame_);
      return FailureResult();
    }

    uint8_t* dstData[4] = {outputPixels, nullptr, nullptr, nullptr};
    int dstLinesize[4] = {static_cast<int>(dstRowBytes), 0, 0, 0};

    int scaledSliceH = api.sws_scale(swsCtx_, framePrefix->data, framePrefix->linesize, 0, height, dstData, dstLinesize);
    api.av_frame_unref(frame_);
    if (scaledSliceH <= 0) {
      return FailureResult();
    }

    *outWidth = width;
    *outHeight = height;
    hasDecodedFrames_ = true;
    return DECODE_FRAME_PRODUCED;
  }

  bool InitGstPipeline() {
    const auto& gst = SystemGstApi::Instance();
    std::string capsStr;
    std::string parserStr;
    switch (codecType_) {
      case kCodecTypeAVC:
        capsStr = "video/x-h264,stream-format=(string)byte-stream,alignment=(string)au";
        parserStr = "h264parse ! ";
        break;
      case kCodecTypeHEVC:
        capsStr = "video/x-h265,stream-format=(string)byte-stream,alignment=(string)au";
        parserStr = "h265parse ! ";
        break;
      case kCodecTypeVP8:
        capsStr = "video/x-vp8";
        break;
      case kCodecTypeVP9:
        capsStr = "video/x-vp9";
        break;
      case kCodecTypeAV1:
        capsStr = "video/x-av1,stream-format=(string)obu-stream,alignment=(string)tu";
        if (gst.HasElement("av1parse")) {
          parserStr = "av1parse ! ";
        }
        break;
      default:
        return false;
    }

    std::string desc = "appsrc name=src is-live=true format=time caps=\"" + capsStr + "\" ! " +
                       parserStr + gstDecoderName_ +
                       " ! videoconvert ! video/x-raw,format=BGRA ! "
                       "appsink name=sink sync=false max-buffers=1 drop=true";

    gstPipeline_ = gst.gst_parse_launch(desc.c_str(), nullptr);
    if (gstPipeline_ == nullptr) {
      return false;
    }

    gstAppSrc_ = gst.gst_bin_get_by_name(gstPipeline_, "src");
    gstAppSink_ = gst.gst_bin_get_by_name(gstPipeline_, "sink");
    if (gstAppSrc_ == nullptr || gstAppSink_ == nullptr) {
      DestroyGstPipeline();
      return false;
    }

    constexpr int GST_STATE_PLAYING = 4;
    if (gst.gst_element_set_state(gstPipeline_, GST_STATE_PLAYING) == 0) {
      DestroyGstPipeline();
      return false;
    }
    gstSentConfig_ = false;
    return true;
  }

  void DestroyGstPipeline() {
    if (gstPipeline_ == nullptr && gstAppSrc_ == nullptr && gstAppSink_ == nullptr) {
      return;
    }
    const auto& gst = SystemGstApi::Instance();
    if (!gst.IsAvailable()) {
      return;
    }
    constexpr int GST_STATE_NULL = 1;
    if (gstPipeline_ != nullptr) {
      gst.gst_element_set_state(gstPipeline_, GST_STATE_NULL);
    }
    if (gstAppSrc_ != nullptr) {
      gst.gst_object_unref(gstAppSrc_);
      gstAppSrc_ = nullptr;
    }
    if (gstAppSink_ != nullptr) {
      gst.gst_object_unref(gstAppSink_);
      gstAppSink_ = nullptr;
    }
    if (gstPipeline_ != nullptr) {
      gst.gst_object_unref(gstPipeline_);
      gstPipeline_ = nullptr;
    }
    gstSentConfig_ = false;
  }

  bool HasGstBusError() const {
    const auto& gst = SystemGstApi::Instance();
    if (gstPipeline_ == nullptr || !gst.gst_element_get_bus || !gst.gst_bus_pop_filtered) {
      return false;
    }
    void* bus = gst.gst_element_get_bus(gstPipeline_);
    if (bus == nullptr) {
      return false;
    }
    constexpr int GST_MESSAGE_ERROR = 2;
    bool hasError = false;
    while (void* msg = gst.gst_bus_pop_filtered(bus, GST_MESSAGE_ERROR)) {
      hasError = true;
      gst.gst_mini_object_unref(msg);
    }
    gst.gst_object_unref(bus);
    return hasError;
  }

  void* PushAndPullGstSample(const PreparedPacket& prepared) {
    const auto& gst = SystemGstApi::Instance();

    // Discard any stale sample or bus error left over from previous operations.
    while (void* stale = gst.gst_app_sink_try_pull_sample(gstAppSink_, 0)) {
      gst.gst_mini_object_unref(stale);
    }
    (void)HasGstBusError();

    bool needConfig = !gstSentConfig_ && !prepared.packetHasAllConfig &&
                      (codecType_ == kCodecTypeAVC || codecType_ == kCodecTypeHEVC || codecType_ == kCodecTypeAV1);
    bool appendAud = (codecType_ == kCodecTypeAVC || codecType_ == kCodecTypeHEVC) && !prepared.endsWithAud;
    const uint8_t* sendData = prepared.data;
    size_t sendSize = prepared.size;
    std::vector<uint8_t> combined;
    if (needConfig || appendAud) {
      combined = BuildPacketWithConfigAndAud(prepared, needConfig, appendAud);
      sendData = combined.data();
      sendSize = combined.size();
    }

    void* gstBuf = gst.gst_buffer_new_allocate(nullptr, sendSize, nullptr);
    if (gstBuf == nullptr) {
      return nullptr;
    }
    gst.gst_buffer_fill(gstBuf, 0, sendData, sendSize);
    if (gst.gst_app_src_push_buffer(gstAppSrc_, gstBuf) != 0) {
      return nullptr;
    }
    gstSentConfig_ = true;

    constexpr uint64_t kInitialTimeoutNs = 50000000ULL;  // 50ms
    void* sample = gst.gst_app_sink_try_pull_sample(gstAppSink_, kInitialTimeoutNs);
    if (sample == nullptr && prepared.expectsOutputFrame && !HasGstBusError()) {
      // Drain buffered frame if the GStreamer decoder held it in its reorder queue.
      gst.gst_app_src_end_of_stream(gstAppSrc_);
      sample = gst.gst_app_sink_try_pull_sample(gstAppSink_, kInitialTimeoutNs);
      constexpr int GST_STATE_READY = 2;
      constexpr int GST_STATE_PLAYING = 4;
      gst.gst_element_set_state(gstPipeline_, GST_STATE_READY);
      gst.gst_element_set_state(gstPipeline_, GST_STATE_PLAYING);
      gstSentConfig_ = false;
    }
    return sample;
  }

  jint DecodeGst(PacketKind kind, const PreparedPacket& prepared,
                 uint8_t* outputPixels, size_t outputCapacity, int32_t* outWidth, int32_t* outHeight) {
    if (kind == PacketKind::kConfigOnly) {
      return DECODE_NO_FRAME;
    }

    const auto& gst = SystemGstApi::Instance();
    void* sample = PushAndPullGstSample(prepared);
    if (sample == nullptr) {
      if (!prepared.expectsOutputFrame && !HasGstBusError()) {
        return DECODE_NO_FRAME;
      }
      if (!hasDecodedFrames_) {
        for (size_t i = gstDecoderIndex_ + 1; i < gstDecoderNames_.size(); ++i) {
          gstDecoderName_ = gstDecoderNames_[i];
          DestroyGstPipeline();
          if (InitGstPipeline()) {
            sample = PushAndPullGstSample(prepared);
            if (sample != nullptr) {
              gstDecoderIndex_ = i;
              break;
            }
          }
        }
      }
      if (sample == nullptr) {
        return FailureResult();
      }
    }

    void* caps = gst.gst_sample_get_caps(sample);
    void* buf = gst.gst_sample_get_buffer(sample);
    int width = 0;
    int height = 0;
    if (caps != nullptr) {
      void* structure = gst.gst_caps_get_structure(caps, 0);
      if (structure != nullptr) {
        gst.gst_structure_get_int(structure, "width", &width);
        gst.gst_structure_get_int(structure, "height", &height);
      }
    }

    jint result = FailureResult();
    if (buf != nullptr && width > 0 && height > 0 && width <= kMaxFrameDimension && height <= kMaxFrameDimension) {
      size_t dstRowBytes = static_cast<size_t>(width) * 4;
      size_t totalBytes = dstRowBytes * static_cast<size_t>(height);
      SystemGstApi::GstMapInfo mapInfo = {};
      constexpr int GST_MAP_READ = 1;
      if (totalBytes <= outputCapacity && gst.gst_buffer_map(buf, &mapInfo, GST_MAP_READ)) {
        size_t srcOffset = 0;
        size_t srcStride = dstRowBytes;
        if (gst.gst_buffer_get_video_meta != nullptr) {
          if (const auto* meta = gst.gst_buffer_get_video_meta(buf)) {
            if (meta->stride[0] > 0 && static_cast<size_t>(meta->stride[0]) >= dstRowBytes) {
              srcStride = static_cast<size_t>(meta->stride[0]);
              srcOffset = meta->offset[0];
            }
          }
        } else if (mapInfo.size > totalBytes && mapInfo.size % static_cast<size_t>(height) == 0) {
          size_t inferredStride = mapInfo.size / static_cast<size_t>(height);
          if (inferredStride >= dstRowBytes) {
            srcStride = inferredStride;
          }
        }

        size_t neededSrcBytes = srcOffset + srcStride * static_cast<size_t>(height - 1) + dstRowBytes;
        if (mapInfo.data != nullptr && neededSrcBytes <= mapInfo.size) {
          const uint8_t* srcBase = mapInfo.data + srcOffset;
          if (srcStride == dstRowBytes) {
            std::memcpy(outputPixels, srcBase, totalBytes);
          } else {
            for (int y = 0; y < height; ++y) {
              std::memcpy(outputPixels + static_cast<size_t>(y) * dstRowBytes, srcBase + static_cast<size_t>(y) * srcStride, dstRowBytes);
            }
          }
          *outWidth = width;
          *outHeight = height;
          hasDecodedFrames_ = true;
          result = DECODE_FRAME_PRODUCED;
        }
        gst.gst_buffer_unmap(buf, &mapInfo);
      }
    }

    gst.gst_mini_object_unref(sample);
    return result;
  }

  const uint32_t codecType_;
  std::mutex decodeMutex_;
  bool hasDecodedFrames_ = false;
  bool hasCodecConfig_ = false;
  int32_t currentWidth_ = 0;
  int32_t currentHeight_ = 0;

  std::vector<uint8_t> vps_;
  std::vector<uint8_t> sps_;
  std::vector<uint8_t> pps_;
  std::vector<uint8_t> seqHeader_;

  bool useFfmpeg_ = false;
  bool ffmpegSentConfig_ = false;
  std::vector<const void*> ffmpegDecoders_;
  size_t ffmpegDecoderIndex_ = 0;
  const void* codec_ = nullptr;
  void* codecCtx_ = nullptr;
  void* frame_ = nullptr;
  void* pkt_ = nullptr;
  void* swsCtx_ = nullptr;

  bool useGst_ = false;
  bool gstSentConfig_ = false;
  std::vector<const char*> gstDecoderNames_;
  size_t gstDecoderIndex_ = 0;
  const char* gstDecoderName_ = nullptr;
  void* gstPipeline_ = nullptr;
  void* gstAppSrc_ = nullptr;
  void* gstAppSink_ = nullptr;
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_android_tools_idea_streaming_device_OsVideoDecoder_createNativeDecoder(JNIEnv* /*env*/, jclass /*clazz*/, jint codecType) {
  uint32_t type = static_cast<uint32_t>(codecType);
  if (type != kCodecTypeAV1 && type != kCodecTypeAVC && type != kCodecTypeHEVC && type != kCodecTypeVP8 && type != kCodecTypeVP9) {
    return 0;
  }
  auto* decoder = new LinuxVideoDecoder(type);
  if (!decoder->Initialize()) {
    delete decoder;
    return 0;
  }
  return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT jint JNICALL
Java_com_android_tools_idea_streaming_device_OsVideoDecoder_decodeFrame(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jobject packetBuffer, jint packetOffset, jint packetSize, jobject outputPixelBuffer,
    jint outputCapacity, jintArray outDimensions) {
  auto* decoder = reinterpret_cast<LinuxVideoDecoder*>(handle);
  if (decoder == nullptr || packetBuffer == nullptr || outputPixelBuffer == nullptr ||
      outDimensions == nullptr || env->GetArrayLength(outDimensions) < 2) {
    return DECODE_ERROR;
  }

  jlong packetCapacity = env->GetDirectBufferCapacity(packetBuffer);
  jlong outputPixelCapacity = env->GetDirectBufferCapacity(outputPixelBuffer);
  if (packetOffset < 0 || packetSize < 0 || outputCapacity < 0 ||
      static_cast<jlong>(packetOffset) + packetSize > packetCapacity || outputCapacity > outputPixelCapacity) {
    return DECODE_ERROR;
  }

  const auto* packetBytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(packetBuffer));
  auto* outputBytes = static_cast<uint8_t*>(env->GetDirectBufferAddress(outputPixelBuffer));
  if (packetBytes == nullptr || outputBytes == nullptr) {
    return DECODE_ERROR;
  }

  int32_t width = 0;
  int32_t height = 0;
  jint result = decoder->Decode(
      packetBytes + packetOffset, static_cast<size_t>(packetSize), outputBytes, static_cast<size_t>(outputCapacity), &width, &height);

  if (result == DECODE_FRAME_PRODUCED) {
    jint dims[2] = {width, height};
    env->SetIntArrayRegion(outDimensions, 0, 2, dims);
  }

  return result;
}

JNIEXPORT void JNICALL
Java_com_android_tools_idea_streaming_device_OsVideoDecoder_destroyNativeDecoder(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  auto* decoder = reinterpret_cast<LinuxVideoDecoder*>(handle);
  delete decoder;
}

}  // extern "C"
