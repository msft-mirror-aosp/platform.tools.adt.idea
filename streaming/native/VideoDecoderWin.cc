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

#include <jni.h>
#include <windows.h>
#include <strmif.h>
#include <codecapi.h>
#include <d3d11.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <vector>

#ifdef _MSC_VER
#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "d3d11.lib")
#endif

#ifdef __MINGW32__
#include <new>
void* operator new(size_t size) {
  void* p = std::malloc(size ? size : 1);
  if (!p) std::abort();
  return p;
}
void* operator new[](size_t size) {
  return ::operator new(size);
}
void operator delete(void* p) noexcept {
  std::free(p);
}
void operator delete[](void* p) noexcept {
  std::free(p);
}
void operator delete(void* p, size_t) noexcept {
  std::free(p);
}
void operator delete[](void* p, size_t) noexcept {
  std::free(p);
}
namespace std {
void __throw_length_error(const char*) {
  std::abort();
}
void __throw_bad_alloc() {
  std::abort();
}
void __throw_bad_array_new_length() {
  std::abort();
}
}  // namespace std
#endif

namespace {

class SrwLockGuard {
public:
  explicit SrwLockGuard(SRWLOCK* lock) : lock_(lock) {
    AcquireSRWLockExclusive(lock_);
  }
  ~SrwLockGuard() {
    ReleaseSRWLockExclusive(lock_);
  }
  SrwLockGuard(const SrwLockGuard&) = delete;
  SrwLockGuard& operator=(const SrwLockGuard&) = delete;

private:
  SRWLOCK* lock_;
};

// Ensures COM is initialized on the calling thread for the duration of a scope
// and balanced with CoUninitialize on the exact same thread.
class ScopedComInitializer {
public:
  ScopedComInitializer() {
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (SUCCEEDED(hr)) {
      initialized_ = true;
      valid_ = true;
    } else if (hr == RPC_E_CHANGED_MODE) {
      valid_ = true;
    }
  }
  ~ScopedComInitializer() {
    if (initialized_) {
      CoUninitialize();
    }
  }
  bool IsValid() const { return valid_; }
  ScopedComInitializer(const ScopedComInitializer&) = delete;
  ScopedComInitializer& operator=(const ScopedComInitializer&) = delete;

private:
  bool initialized_ = false;
  bool valid_ = false;
};

constexpr uint32_t kCodecTypeAV1 = 0x61763031;  // 'av01'
constexpr uint32_t kCodecTypeAVC = 0x61766331;  // 'avc1'
constexpr uint32_t kCodecTypeHEVC = 0x68766331; // 'hvc1'
constexpr uint32_t kCodecTypeVP8 = 0x76703038;  // 'vp08'
constexpr uint32_t kCodecTypeVP9 = 0x76703039;  // 'vp09'

constexpr jint DECODE_ERROR = -1;
constexpr jint DECODE_NO_FRAME = 0;
constexpr jint DECODE_FRAME_PRODUCED = 1;

// H.264 (AVC) NAL unit constants.
constexpr uint8_t AVC_NAL_UNIT_TYPE_MASK = 0x1F;
constexpr uint8_t AVC_NAL_SLICE_NON_IDR = 1;
constexpr uint8_t AVC_NAL_SLICE_IDR = 5;
constexpr uint8_t AVC_NAL_SPS = 7;
constexpr uint8_t AVC_NAL_PPS = 8;
constexpr uint8_t AVC_NAL_AUD = 9;

// H.265 (HEVC) NAL unit constants.
constexpr uint8_t HEVC_NAL_UNIT_TYPE_MASK = 0x3F;
constexpr uint8_t HEVC_NAL_MAX_VCL = 31;
constexpr uint8_t HEVC_NAL_VPS = 32;
constexpr uint8_t HEVC_NAL_SPS = 33;
constexpr uint8_t HEVC_NAL_PPS = 34;
constexpr uint8_t HEVC_NAL_AUD = 35;

// AV1 OBU type constants.
constexpr uint8_t AV1_OBU_SEQUENCE_HEADER = 1;
constexpr uint8_t AV1_OBU_TILE_GROUP = 4;
constexpr uint8_t AV1_OBU_FRAME = 6;

constexpr GUID MakeFourccSubtype(uint32_t fourcc) {
  return { fourcc, 0x0000, 0x0010, { 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71 } };
}

// Media Foundation video format GUIDs.
constexpr GUID kSubTypeH264 = MakeFourccSubtype(0x34363248); // 'H264'
constexpr GUID kSubTypeHEVC = MakeFourccSubtype(0x43564548); // 'HEVC'
constexpr GUID kSubTypeVP80 = MakeFourccSubtype(0x30385056); // 'VP80'
constexpr GUID kSubTypeVP90 = MakeFourccSubtype(0x30395056); // 'VP90'
constexpr GUID kSubTypeAV1 = MakeFourccSubtype(0x31305641);  // 'AV01'

constexpr GUID kSubTypeNV12 = MakeFourccSubtype(0x3231564E); // 'NV12'
constexpr GUID kSubTypeIYUV = MakeFourccSubtype(0x56555949); // 'IYUV'
constexpr GUID kSubTypeYV12 = MakeFourccSubtype(0x32315659); // 'YV12'
constexpr GUID kSubTypeYUY2 = MakeFourccSubtype(0x32595559); // 'YUY2'
constexpr GUID kSubTypeARGB32 = MakeFourccSubtype(21);       // D3DFMT_A8R8G8B8
constexpr GUID kSubTypeRGB32 = MakeFourccSubtype(22);        // D3DFMT_X8R8G8B8

// IID_ICodecAPI: {901db4c7-31ce-41a2-85dc-8fa0bf41b8da}
constexpr GUID kIidICodecAPI = {
  0x901db4c7, 0x31ce, 0x41a2, { 0x85, 0xdc, 0x8f, 0xa0, 0xbf, 0x41, 0xb8, 0xda }
};

// IID_IMF2DBuffer2: {33ae5ea6-4316-436f-8ddd-d73d22f829ec}
constexpr GUID kIidIMF2DBuffer2 = {
  0x33ae5ea6, 0x4316, 0x436f, { 0x8d, 0xdd, 0xd7, 0x3d, 0x22, 0xf8, 0x29, 0xec }
};

// CODECAPI_AVLowLatencyMode / MF_LOW_LATENCY: {9c27891a-ed7a-40e1-88e8-b22727a024ee}
constexpr GUID kCodecApiAvLowLatencyMode = {
  0x9c27891a, 0xed7a, 0x40e1, { 0x88, 0xe8, 0xb2, 0x27, 0x27, 0xa0, 0x24, 0xee }
};

// MF_SA_D3D11_AWARE: {206b4fc8-fcf9-4c51-afe3-9764369e33a0}
constexpr GUID kMfSaD3D11Aware = {
  0x206b4fc8, 0xfcf9, 0x4c51, { 0xaf, 0xe3, 0x97, 0x64, 0x36, 0x9e, 0x33, 0xa0 }
};

struct NalUnit {
  const uint8_t* data;
  size_t size;
  uint8_t type;
};

struct BufferChunk {
  const uint8_t* data;
  size_t size;
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

  uint32_t ReadUvlc() {
    size_t leadingZeros = 0;
    while (!ReadBit()) {
      if (overflow_) {
        return 0;
      }
      ++leadingZeros;
      if (leadingZeros >= 32) {
        overflow_ = true;
        return 0;
      }
    }
    if (leadingZeros == 0) {
      return 0;
    }
    uint32_t value = ReadBits(leadingZeros);
    if (overflow_) {
      return 0;
    }
    return value + (1u << leadingZeros) - 1;
  }

  int32_t ReadSvlc() {
    uint32_t codeNum = ReadUvlc();
    if (overflow_ || codeNum == 0) {
      return 0;
    }
    int32_t val = static_cast<int32_t>((codeNum >> 1) + (codeNum & 1));
    return (codeNum & 1) != 0 ? val : -val;
  }

  bool HasOverflow() const { return overflow_; }

private:
  const uint8_t* data_;
  size_t totalBits_;
  size_t bitPos_;
  bool overflow_ = false;
};

std::vector<uint8_t> UnescapeRbsp(const uint8_t* data, size_t size) {
  std::vector<uint8_t> rbsp;
  rbsp.reserve(size);
  size_t zeroCount = 0;
  for (size_t i = 0; i < size; ++i) {
    uint8_t b = data[i];
    if (zeroCount == 2 && b == 0x03) {
      zeroCount = 0;
      continue;
    }
    rbsp.push_back(b);
    if (b == 0) {
      ++zeroCount;
    } else {
      zeroCount = 0;
    }
  }
  return rbsp;
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
      units.push_back({ data + payloadStart, payloadEnd - payloadStart, nalType });
    }
  }

  return units;
}

void SkipAvcScalingList(BitReader* reader, size_t sizeOfScalingList) {
  int32_t lastScale = 8;
  int32_t nextScale = 8;
  for (size_t j = 0; j < sizeOfScalingList; ++j) {
    if (nextScale != 0) {
      int32_t deltaScale = reader->ReadSvlc();
      nextScale = (lastScale + deltaScale + 256) % 256;
    }
    lastScale = (nextScale == 0) ? lastScale : nextScale;
  }
}

bool ParseAvcSpsDimensions(const uint8_t* nalData, size_t nalSize, int32_t* outWidth, int32_t* outHeight) {
  if (nalSize < 4) {
    return false;
  }
  std::vector<uint8_t> rbsp = UnescapeRbsp(nalData + 1, nalSize - 1);
  BitReader reader(rbsp.data(), rbsp.size());

  uint32_t profileIdc = reader.ReadBits(8);
  reader.ReadBits(8); // constraint_set flags
  reader.ReadBits(8); // level_idc
  reader.ReadUvlc();  // seq_parameter_set_id

  uint32_t chromaFormatIdc = 1;
  if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 ||
      profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 ||
      profileIdc == 128 || profileIdc == 138 || profileIdc == 139 || profileIdc == 134 || profileIdc == 135) {
    chromaFormatIdc = reader.ReadUvlc();
    if (chromaFormatIdc > 3) {
      return false;
    }
    if (chromaFormatIdc == 3) {
      reader.ReadBit(); // separate_colour_plane_flag
    }
    reader.ReadUvlc(); // bit_depth_luma_minus8
    reader.ReadUvlc(); // bit_depth_chroma_minus8
    reader.ReadBit();  // qpprime_y_zero_transform_bypass_flag
    if (reader.ReadBit()) { // seq_scaling_matrix_present_flag
      size_t count = (chromaFormatIdc != 3) ? 8 : 12;
      for (size_t i = 0; i < count; ++i) {
        if (reader.ReadBit()) {
          SkipAvcScalingList(&reader, i < 6 ? 16 : 64);
        }
      }
    }
  }

  reader.ReadUvlc(); // log2_max_frame_num_minus4
  uint32_t picOrderCntType = reader.ReadUvlc();
  if (picOrderCntType > 2) {
    return false;
  }
  if (picOrderCntType == 0) {
    reader.ReadUvlc(); // log2_max_pic_order_cnt_lsb_minus4
  } else if (picOrderCntType == 1) {
    reader.ReadBit();  // delta_pic_order_always_zero_flag
    reader.ReadSvlc(); // offset_for_non_ref_pic
    reader.ReadSvlc(); // offset_for_top_to_bottom_field
    uint32_t numRefFramesInPicOrderCntCycle = reader.ReadUvlc();
    if (numRefFramesInPicOrderCntCycle > 255) {
      return false;
    }
    for (uint32_t i = 0; i < numRefFramesInPicOrderCntCycle && !reader.HasOverflow(); ++i) {
      reader.ReadSvlc();
    }
  }

  reader.ReadUvlc(); // max_num_ref_frames
  reader.ReadBit();  // gaps_in_frame_num_value_allowed_flag
  uint32_t picWidthInMbsMinus1 = reader.ReadUvlc();
  uint32_t picHeightInMapUnitsMinus1 = reader.ReadUvlc();
  bool frameMbsOnlyFlag = reader.ReadBit();
  if (!frameMbsOnlyFlag) {
    reader.ReadBit(); // mb_adaptive_frame_field_flag
  }
  reader.ReadBit(); // direct_8x8_inference_flag

  uint32_t cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
  if (reader.ReadBit()) { // frame_cropping_flag
    cropLeft = reader.ReadUvlc();
    cropRight = reader.ReadUvlc();
    cropTop = reader.ReadUvlc();
    cropBottom = reader.ReadUvlc();
  }

  if (reader.HasOverflow() || picWidthInMbsMinus1 > 1024 || picHeightInMapUnitsMinus1 > 1024) {
    return false;
  }

  int64_t cropUnitX = (chromaFormatIdc == 0 || chromaFormatIdc == 3) ? 1 : 2;
  int64_t cropUnitY = (chromaFormatIdc == 1 ? 2 : 1) * (frameMbsOnlyFlag ? 1 : 2);
  int64_t width = (static_cast<int64_t>(picWidthInMbsMinus1) + 1) * 16 -
                  (static_cast<int64_t>(cropLeft) + cropRight) * cropUnitX;
  int64_t height = (2 - (frameMbsOnlyFlag ? 1 : 0)) * (static_cast<int64_t>(picHeightInMapUnitsMinus1) + 1) * 16 -
                   (static_cast<int64_t>(cropTop) + cropBottom) * cropUnitY;
  if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
    return false;
  }
  *outWidth = static_cast<int32_t>(width);
  *outHeight = static_cast<int32_t>(height);
  return true;
}

bool ParseHevcSpsDimensions(const uint8_t* nalData, size_t nalSize, int32_t* outWidth, int32_t* outHeight) {
  if (nalSize < 15) {
    return false;
  }
  std::vector<uint8_t> rbsp = UnescapeRbsp(nalData + 2, nalSize - 2);
  BitReader reader(rbsp.data(), rbsp.size());

  reader.ReadBits(4); // sps_video_parameter_set_id
  uint32_t maxSubLayersMinus1 = reader.ReadBits(3);
  if (maxSubLayersMinus1 > 6) {
    return false;
  }
  reader.ReadBit(); // sps_temporal_id_nesting_flag

  // profile_tier_level(1, maxSubLayersMinus1)
  reader.ReadBits(8);  // general_profile_space, tier_flag, profile_idc
  reader.ReadBits(32); // general_profile_compatibility_flag
  reader.ReadBits(32); // general_constraint_indicator_flags (high 32)
  reader.ReadBits(16); // general_constraint_indicator_flags (low 16)
  reader.ReadBits(8);  // general_level_idc
  bool subLayerProfilePresent[8] = {};
  bool subLayerLevelPresent[8] = {};
  for (uint32_t i = 0; i < maxSubLayersMinus1; ++i) {
    subLayerProfilePresent[i] = reader.ReadBit();
    subLayerLevelPresent[i] = reader.ReadBit();
  }
  if (maxSubLayersMinus1 > 0) {
    for (uint32_t i = maxSubLayersMinus1; i < 8; ++i) {
      reader.ReadBits(2); // reserved_zero_2bits
    }
  }
  for (uint32_t i = 0; i < maxSubLayersMinus1; ++i) {
    if (subLayerProfilePresent[i]) {
      reader.ReadBits(32);
      reader.ReadBits(32);
      reader.ReadBits(24);
    }
    if (subLayerLevelPresent[i]) {
      reader.ReadBits(8);
    }
  }

  reader.ReadUvlc(); // sps_seq_parameter_set_id
  uint32_t chromaFormatIdc = reader.ReadUvlc();
  if (chromaFormatIdc > 3) {
    return false;
  }
  if (chromaFormatIdc == 3) {
    reader.ReadBit(); // separate_colour_plane_flag
  }
  int64_t width = static_cast<int64_t>(reader.ReadUvlc());
  int64_t height = static_cast<int64_t>(reader.ReadUvlc());
  if (reader.ReadBit()) { // conformance_window_flag
    int64_t left = static_cast<int64_t>(reader.ReadUvlc());
    int64_t right = static_cast<int64_t>(reader.ReadUvlc());
    int64_t top = static_cast<int64_t>(reader.ReadUvlc());
    int64_t bottom = static_cast<int64_t>(reader.ReadUvlc());
    int64_t subWidthC = (chromaFormatIdc == 1 || chromaFormatIdc == 2) ? 2 : 1;
    int64_t subHeightC = (chromaFormatIdc == 1) ? 2 : 1;
    width -= (left + right) * subWidthC;
    height -= (top + bottom) * subHeightC;
  }

  if (reader.HasOverflow() || width <= 0 || height <= 0 || width > 16384 || height > 16384) {
    return false;
  }
  *outWidth = static_cast<int32_t>(width);
  *outHeight = static_cast<int32_t>(height);
  return true;
}

bool ParseVp8KeyframeDimensions(const uint8_t* data, size_t size, int32_t* outWidth, int32_t* outHeight) {
  if (size < 10) {
    return false;
  }
  if ((data[0] & 0x01) != 0) { // Not a keyframe
    return false;
  }
  if (data[3] != 0x9d || data[4] != 0x01 || data[5] != 0x2a) {
    return false;
  }
  int32_t width = (data[6] | (data[7] << 8)) & 0x3FFF;
  int32_t height = (data[8] | (data[9] << 8)) & 0x3FFF;
  if (width <= 0 || height <= 0) {
    return false;
  }
  *outWidth = width;
  *outHeight = height;
  return true;
}

bool ParseVp9KeyframeDimensions(const uint8_t* data, size_t size, int32_t* outWidth, int32_t* outHeight) {
  BitReader reader(data, size);
  if (reader.ReadBits(2) != 2) {
    return false;
  }
  uint32_t profileLow = reader.ReadBits(1);
  uint32_t profileHigh = reader.ReadBits(1);
  uint8_t profile = static_cast<uint8_t>((profileHigh << 1) | profileLow);
  if (profile == 3 && reader.ReadBits(1) != 0) {
    return false;
  }
  if (reader.ReadBit()) { // show_existing_frame
    return false;
  }
  bool isKeyFrame = (reader.ReadBits(1) == 0);
  reader.ReadBit(); // show_frame
  reader.ReadBit(); // error_resilient_mode
  if (!isKeyFrame) {
    return false;
  }
  if (reader.ReadBits(24) != 0x498342) {
    return false;
  }
  if (profile >= 2) {
    reader.ReadBit(); // ten_or_twelve_bit
  }
  uint8_t colorSpace = static_cast<uint8_t>(reader.ReadBits(3));
  if (colorSpace != 7) {
    reader.ReadBit(); // color_range
    if (profile == 1 || profile == 3) {
      reader.ReadBits(2); // subsampling_x, subsampling_y
      reader.ReadBit();   // reserved_zero
    }
  } else if (profile == 1 || profile == 3) {
    reader.ReadBit(); // reserved_zero
  }
  int32_t width = static_cast<int32_t>(reader.ReadBits(16)) + 1;
  int32_t height = static_cast<int32_t>(reader.ReadBits(16)) + 1;
  if (reader.HasOverflow() || width <= 0 || height <= 0) {
    return false;
  }
  *outWidth = width;
  *outHeight = height;
  return true;
}

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

bool ParseAv1PacketInfo(
    const uint8_t* packetData, size_t packetSize, int32_t* outWidth, int32_t* outHeight, bool* outHasDimensions, bool* outHasFrameData) {
  *outHasDimensions = false;
  *outHasFrameData = false;
  size_t offset = 0;
  while (offset < packetSize) {
    uint8_t header = packetData[offset];
    if ((header & 0x80) != 0) {
      return false;
    }
    uint8_t obuType = (header >> 3) & 0x0F;
    bool hasExtension = (header & 0x04) != 0;
    bool hasSizeField = (header & 0x02) != 0;
    size_t headerLen = 1 + (hasExtension ? 1 : 0);
    if (offset + headerLen > packetSize) {
      return false;
    }
    size_t payloadSize = 0;
    if (hasSizeField) {
      size_t lebBytes = 0;
      if (!ReadLeb128(packetData + offset + headerLen, packetSize - offset - headerLen, &payloadSize, &lebBytes)) {
        return false;
      }
      headerLen += lebBytes;
      if (offset + headerLen + payloadSize > packetSize) {
        return false;
      }
    } else {
      payloadSize = packetSize - offset - headerLen;
    }
    if (obuType == AV1_OBU_SEQUENCE_HEADER) {
      BitReader reader(packetData + offset + headerLen, payloadSize);
      reader.ReadBits(3); // seq_profile
      reader.ReadBit();   // still_picture
      bool reducedStillPictureHeader = reader.ReadBit();
      if (reducedStillPictureHeader) {
        reader.ReadBits(5);
      } else {
        bool timingInfoPresentFlag = reader.ReadBit();
        bool decoderModelInfoPresentFlag = false;
        uint32_t bufferDelayLengthMinus1 = 0;
        if (timingInfoPresentFlag) {
          reader.ReadBits(32);
          reader.ReadBits(32);
          if (reader.ReadBit()) {
            reader.ReadUvlc();
          }
          decoderModelInfoPresentFlag = reader.ReadBit();
          if (decoderModelInfoPresentFlag) {
            bufferDelayLengthMinus1 = reader.ReadBits(5);
            reader.ReadBits(32);
            reader.ReadBits(5);
            reader.ReadBits(5);
          }
        }
        bool initialDisplayDelayPresentFlag = reader.ReadBit();
        uint32_t operatingPointsCntMinus1 = reader.ReadBits(5);
        for (uint32_t i = 0; i <= operatingPointsCntMinus1; ++i) {
          reader.ReadBits(12);
          uint8_t seqLevelIdx = static_cast<uint8_t>(reader.ReadBits(5));
          if (seqLevelIdx > 7) {
            reader.ReadBit();
          }
          if (decoderModelInfoPresentFlag && reader.ReadBit()) {
            reader.ReadBits(bufferDelayLengthMinus1 + 1);
            reader.ReadBits(bufferDelayLengthMinus1 + 1);
            reader.ReadBit();
          }
          if (initialDisplayDelayPresentFlag && reader.ReadBit()) {
            reader.ReadBits(4);
          }
        }
      }
      uint32_t frameWidthBitsMinus1 = reader.ReadBits(4);
      uint32_t frameHeightBitsMinus1 = reader.ReadBits(4);
      int32_t width = static_cast<int32_t>(reader.ReadBits(frameWidthBitsMinus1 + 1)) + 1;
      int32_t height = static_cast<int32_t>(reader.ReadBits(frameHeightBitsMinus1 + 1)) + 1;
      if (!reader.HasOverflow() && width > 0 && height > 0) {
        *outWidth = width;
        *outHeight = height;
        *outHasDimensions = true;
      }
    } else if (obuType == AV1_OBU_FRAME || obuType == AV1_OBU_TILE_GROUP) {
      *outHasFrameData = true;
    }
    offset += headerLen + payloadSize;
  }
  return true;
}

inline uint8_t ClampByte(int32_t v) {
  return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

inline void YuvToBgraPixelWithChromaTerms(uint8_t yVal, int32_t rChroma, int32_t gChroma, int32_t bChroma, uint8_t* dstBgra) {
  int32_t c = static_cast<int32_t>(yVal) - 16;
  if (c < 0) c = 0;
  int32_t yTerm = 298 * c;
  dstBgra[0] = ClampByte((yTerm + bChroma) >> 8);
  dstBgra[1] = ClampByte((yTerm + gChroma) >> 8);
  dstBgra[2] = ClampByte((yTerm + rChroma) >> 8);
  dstBgra[3] = 0xFF;
}

inline void YuvPairToBgra(uint8_t y0, uint8_t y1, uint8_t uVal, uint8_t vVal, uint8_t* dst0, uint8_t* dst1) {
  int32_t d = static_cast<int32_t>(uVal) - 128;
  int32_t e = static_cast<int32_t>(vVal) - 128;
  int32_t rChroma = 409 * e + 128;
  int32_t gChroma = -100 * d - 208 * e + 128;
  int32_t bChroma = 516 * d + 128;
  YuvToBgraPixelWithChromaTerms(y0, rChroma, gChroma, bChroma, dst0);
  if (dst1 != nullptr) {
    YuvToBgraPixelWithChromaTerms(y1, rChroma, gChroma, bChroma, dst1);
  }
}

class WinVideoDecoder {
public:
  static WinVideoDecoder* Create(uint32_t codecType) {
    GUID subtype;
    switch (codecType) {
      case kCodecTypeAVC:
        subtype = kSubTypeH264;
        break;
      case kCodecTypeHEVC:
        subtype = kSubTypeHEVC;
        break;
      case kCodecTypeVP8:
        subtype = kSubTypeVP80;
        break;
      case kCodecTypeVP9:
        subtype = kSubTypeVP90;
        break;
      case kCodecTypeAV1:
        subtype = kSubTypeAV1;
        break;
      default:
        return nullptr;
    }

    auto* decoder = new WinVideoDecoder(codecType, subtype);
    if (!decoder->Initialize()) {
      delete decoder;
      return nullptr;
    }
    return decoder;
  }

  ~WinVideoDecoder() {
    ScopedComInitializer comInit;
    SrwLockGuard lock(&decodeLock_);
    if (decoder_ != nullptr) {
      decoder_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
      decoder_->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
      decoder_->Release();
      decoder_ = nullptr;
    }
    if (dxgiManager_ != nullptr) {
      dxgiManager_->Release();
      dxgiManager_ = nullptr;
    }
    if (d3dContext_ != nullptr) {
      d3dContext_->Release();
      d3dContext_ = nullptr;
    }
    if (d3dDevice_ != nullptr) {
      d3dDevice_->Release();
      d3dDevice_ = nullptr;
    }
    if (mfStarted_) {
      MFShutdown();
    }
  }

  jint Decode(
      const uint8_t* packetData, size_t packetSize, uint8_t* outputPixels, size_t outputCapacity, int32_t* outWidth, int32_t* outHeight) {
    ScopedComInitializer comInit;
    if (!comInit.IsValid()) {
      return DECODE_ERROR;
    }
    SrwLockGuard lock(&decodeLock_);
    if (packetSize == 0 || decoder_ == nullptr) {
      return DECODE_NO_FRAME;
    }

    static const uint8_t kStartCode[4] = { 0x00, 0x00, 0x00, 0x01 };
    static const uint8_t kHevcAud[7] = { 0x00, 0x00, 0x00, 0x01, 0x46, 0x01, 0x50 };
    static const uint8_t kAvcAud[6] = { 0x00, 0x00, 0x00, 0x01, 0x09, 0xF0 };

    BufferChunk chunks[8];
    size_t chunkCount = 0;

    int32_t newWidth = parsedWidth_;
    int32_t newHeight = parsedHeight_;
    bool isHevc = (codecType_ == kCodecTypeHEVC);
    bool packetHasVps = false;
    bool packetHasSps = false;
    bool packetHasPps = false;
    bool endsWithAud = false;

    if (codecType_ == kCodecTypeAVC || codecType_ == kCodecTypeHEVC) {
      std::vector<NalUnit> units = ParseAnnexBNalUnits(packetData, packetSize, isHevc);
      if (units.empty()) {
        return DECODE_ERROR;
      }

      bool hasSlice = false;
      for (const auto& unit : units) {
        if (isHevc) {
          if (unit.type == HEVC_NAL_VPS) {
            packetHasVps = true;
            if (vps_.size() != unit.size || std::memcmp(vps_.data(), unit.data, unit.size) != 0) {
              vps_.assign(unit.data, unit.data + unit.size);
              sentConfig_ = false;
            }
          } else if (unit.type == HEVC_NAL_SPS) {
            packetHasSps = true;
            if (sps_.size() != unit.size || std::memcmp(sps_.data(), unit.data, unit.size) != 0) {
              sps_.assign(unit.data, unit.data + unit.size);
              sentConfig_ = false;
            }
            int32_t w = 0, h = 0;
            if (ParseHevcSpsDimensions(unit.data, unit.size, &w, &h)) {
              newWidth = w;
              newHeight = h;
            }
          } else if (unit.type == HEVC_NAL_PPS) {
            packetHasPps = true;
            if (pps_.size() != unit.size || std::memcmp(pps_.data(), unit.data, unit.size) != 0) {
              pps_.assign(unit.data, unit.data + unit.size);
              sentConfig_ = false;
            }
          } else if (unit.type <= HEVC_NAL_MAX_VCL) {
            hasSlice = true;
          }
        } else {
          if (unit.type == AVC_NAL_SPS) {
            packetHasSps = true;
            if (sps_.size() != unit.size || std::memcmp(sps_.data(), unit.data, unit.size) != 0) {
              sps_.assign(unit.data, unit.data + unit.size);
              sentConfig_ = false;
            }
            int32_t w = 0, h = 0;
            if (ParseAvcSpsDimensions(unit.data, unit.size, &w, &h)) {
              newWidth = w;
              newHeight = h;
            }
          } else if (unit.type == AVC_NAL_PPS) {
            packetHasPps = true;
            if (pps_.size() != unit.size || std::memcmp(pps_.data(), unit.data, unit.size) != 0) {
              pps_.assign(unit.data, unit.data + unit.size);
              sentConfig_ = false;
            }
          } else if (unit.type >= AVC_NAL_SLICE_NON_IDR && unit.type <= AVC_NAL_SLICE_IDR) {
            hasSlice = true;
          }
        }
      }

      if (!hasSlice) {
        parsedWidth_ = newWidth;
        parsedHeight_ = newHeight;
        return DECODE_NO_FRAME;
      }

      if (sps_.empty() || pps_.empty() || (isHevc && vps_.empty())) {
        return DECODE_ERROR;
      }

      endsWithAud = (units.back().type == (isHevc ? HEVC_NAL_AUD : AVC_NAL_AUD));
    } else if (codecType_ == kCodecTypeVP8) {
      int32_t w = 0, h = 0;
      if (ParseVp8KeyframeDimensions(packetData, packetSize, &w, &h)) {
        newWidth = w;
        newHeight = h;
      }
    } else if (codecType_ == kCodecTypeVP9) {
      int32_t w = 0, h = 0;
      if (ParseVp9KeyframeDimensions(packetData, packetSize, &w, &h)) {
        newWidth = w;
        newHeight = h;
      }
    } else if (codecType_ == kCodecTypeAV1) {
      int32_t w = 0, h = 0;
      bool hasDims = false;
      bool hasFrameData = false;
      if (!ParseAv1PacketInfo(packetData, packetSize, &w, &h, &hasDims, &hasFrameData)) {
        return DECODE_ERROR;
      }
      if (hasDims) {
        newWidth = w;
        newHeight = h;
      }
      if (!hasFrameData) {
        parsedWidth_ = newWidth;
        parsedHeight_ = newHeight;
        return DECODE_NO_FRAME;
      }
    }

    bool dimensionsChanged =
        typesConfigured_ && newWidth > 0 && newHeight > 0 && (newWidth != parsedWidth_ || newHeight != parsedHeight_);
    parsedWidth_ = newWidth;
    parsedHeight_ = newHeight;

    if (!typesConfigured_ || dimensionsChanged) {
      if (!d3d11Configured_) {
        d3d11Configured_ = true;
        TryConfigureD3D11Manager();
      }
      if (dimensionsChanged) {
        decoder_->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
        sentConfig_ = false;
      }
      int32_t initWidth = parsedWidth_ > 0 ? parsedWidth_ : 1920;
      int32_t initHeight = parsedHeight_ > 0 ? parsedHeight_ : 1080;
      if (!ConfigureInputAndOutputTypes(initWidth, initHeight)) {
        if (usingD3D11_) {
          DisableD3D11Manager();
          if (!ConfigureInputAndOutputTypes(initWidth, initHeight)) {
            return DECODE_ERROR;
          }
        } else {
          return DECODE_ERROR;
        }
      }
    }

    if (codecType_ == kCodecTypeAVC || codecType_ == kCodecTypeHEVC) {
      bool packetHasAllConfig = packetHasSps && packetHasPps && (!isHevc || packetHasVps);
      if (!sentConfig_ && !packetHasAllConfig) {
        if (isHevc && !vps_.empty()) {
          chunks[chunkCount++] = { kStartCode, sizeof(kStartCode) };
          chunks[chunkCount++] = { vps_.data(), vps_.size() };
        }
        chunks[chunkCount++] = { kStartCode, sizeof(kStartCode) };
        chunks[chunkCount++] = { sps_.data(), sps_.size() };
        chunks[chunkCount++] = { kStartCode, sizeof(kStartCode) };
        chunks[chunkCount++] = { pps_.data(), pps_.size() };
      }
      sentConfig_ = true;
      chunks[chunkCount++] = { packetData, packetSize };
      if (!endsWithAud) {
        if (isHevc) {
          chunks[chunkCount++] = { kHevcAud, sizeof(kHevcAud) };
        } else {
          chunks[chunkCount++] = { kAvcAud, sizeof(kAvcAud) };
        }
      }
    } else {
      chunks[chunkCount++] = { packetData, packetSize };
    }

    IMFSample* inputSample = CreateMediaSampleFromChunks(chunks, chunkCount);
    if (inputSample == nullptr) {
      return DECODE_ERROR;
    }

    IMFSample* latestOutputSample = nullptr;
    HRESULT hr = decoder_->ProcessInput(0, inputSample, 0);
    if (hr == MF_E_NOTACCEPTING) {
      if (!CollectOutputSamples(&latestOutputSample, inputSample)) {
        if (latestOutputSample != nullptr) latestOutputSample->Release();
        inputSample->Release();
        return DECODE_ERROR;
      }
      hr = decoder_->ProcessInput(0, inputSample, 0);
    }

    if (FAILED(hr)) {
      if (usingD3D11_ && !hasDecodedFrames_) {
        DisableD3D11Manager();
        int32_t initWidth = parsedWidth_ > 0 ? parsedWidth_ : 1920;
        int32_t initHeight = parsedHeight_ > 0 ? parsedHeight_ : 1080;
        if (ConfigureInputAndOutputTypes(initWidth, initHeight)) {
          hr = decoder_->ProcessInput(0, inputSample, 0);
        }
      }
      if (FAILED(hr)) {
        if (latestOutputSample != nullptr) latestOutputSample->Release();
        inputSample->Release();
        return DECODE_ERROR;
      }
    }

    if (!CollectOutputSamples(&latestOutputSample, inputSample)) {
      if (latestOutputSample != nullptr) latestOutputSample->Release();
      inputSample->Release();
      return DECODE_ERROR;
    }
    inputSample->Release();

    if (latestOutputSample == nullptr) {
      // If the MFT buffered the frame due to SPS VUI reorder settings (e.g. single-frame streams),
      // drain the reorder queue and reset sentConfig_ so parameter sets are re-supplied on the next frame.
      if (SUCCEEDED(decoder_->ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0))) {
        bool ok = CollectOutputSamples(&latestOutputSample, nullptr);
        decoder_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
        sentConfig_ = false;
        if (!ok) {
          if (latestOutputSample != nullptr) latestOutputSample->Release();
          return DECODE_ERROR;
        }
      }
    }

    if (latestOutputSample != nullptr) {
      bool converted = ConvertSampleToBgra(latestOutputSample, outputPixels, outputCapacity, outWidth, outHeight);
      latestOutputSample->Release();
      if (!converted) {
        return DECODE_ERROR;
      }
      hasDecodedFrames_ = true;
      return DECODE_FRAME_PRODUCED;
    }

    return DECODE_NO_FRAME;
  }

private:
  WinVideoDecoder(uint32_t codecType, const GUID& subtype)
      : codecType_(codecType), subtype_(subtype) {}

  bool Initialize() {
    ScopedComInitializer comInit;
    if (!comInit.IsValid()) {
      return false;
    }

    HRESULT hr = MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET);
    if (FAILED(hr)) {
      return false;
    }
    mfStarted_ = true;

    MFT_REGISTER_TYPE_INFO inputInfo = { MFMediaType_Video, subtype_ };
    IMFActivate** activates = nullptr;
    UINT32 count = 0;
    UINT32 flags = MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_LOCALMFT | MFT_ENUM_FLAG_SORTANDFILTER;
    hr = MFTEnumEx(MFT_CATEGORY_VIDEO_DECODER, flags, &inputInfo, nullptr, &activates, &count);
    if (FAILED(hr) || count == 0 || activates == nullptr) {
      return false;
    }

    for (UINT32 i = 0; i < count; ++i) {
      if (decoder_ == nullptr) {
        activates[i]->ActivateObject(IID_PPV_ARGS(&decoder_));
      }
      activates[i]->Release();
    }
    CoTaskMemFree(activates);

    if (decoder_ == nullptr) {
      return false;
    }

    EnableLowLatencyMode();
    return true;
  }

  void EnableLowLatencyMode() {
    IMFAttributes* attributes = nullptr;
    if (SUCCEEDED(decoder_->GetAttributes(&attributes)) && attributes != nullptr) {
      attributes->SetUINT32(kCodecApiAvLowLatencyMode, TRUE);
      attributes->Release();
    }

    ICodecAPI* codecApi = nullptr;
    if (SUCCEEDED(decoder_->QueryInterface(kIidICodecAPI, reinterpret_cast<void**>(&codecApi))) &&
        codecApi != nullptr) {
      VARIANT var = {};
      var.vt = VT_BOOL;
      var.boolVal = VARIANT_TRUE;
      codecApi->SetValue(&kCodecApiAvLowLatencyMode, &var);
      var.vt = VT_UI4;
      var.ulVal = 1;
      codecApi->SetValue(&kCodecApiAvLowLatencyMode, &var);
      codecApi->Release();
    }
  }

  void TryConfigureD3D11Manager() {
    IMFAttributes* attributes = nullptr;
    UINT32 d3d11Aware = 0;
    if (SUCCEEDED(decoder_->GetAttributes(&attributes)) && attributes != nullptr) {
      attributes->GetUINT32(kMfSaD3D11Aware, &d3d11Aware);
      attributes->Release();
    }
    if (d3d11Aware == 0) {
      return;
    }

    D3D_FEATURE_LEVEL featureLevels[] = {
      D3D_FEATURE_LEVEL_11_1,
      D3D_FEATURE_LEVEL_11_0,
      D3D_FEATURE_LEVEL_10_1,
      D3D_FEATURE_LEVEL_10_0
    };
    D3D_FEATURE_LEVEL actualLevel;
    UINT creationFlags = D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT;
    HRESULT hr = D3D11CreateDevice(
        nullptr,
        D3D_DRIVER_TYPE_HARDWARE,
        nullptr,
        creationFlags,
        featureLevels,
        ARRAYSIZE(featureLevels),
        D3D11_SDK_VERSION,
        &d3dDevice_,
        &actualLevel,
        &d3dContext_);
    if (FAILED(hr) || d3dDevice_ == nullptr) {
      return;
    }

    ID3D10Multithread* multithread = nullptr;
    if (SUCCEEDED(d3dDevice_->QueryInterface(IID_PPV_ARGS(&multithread))) && multithread != nullptr) {
      multithread->SetMultithreadProtected(TRUE);
      multithread->Release();
    }

    UINT resetToken = 0;
    if (SUCCEEDED(MFCreateDXGIDeviceManager(&resetToken, &dxgiManager_)) && dxgiManager_ != nullptr) {
      if (SUCCEEDED(dxgiManager_->ResetDevice(d3dDevice_, resetToken))) {
        if (SUCCEEDED(decoder_->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, reinterpret_cast<ULONG_PTR>(dxgiManager_)))) {
          usingD3D11_ = true;
        }
      }
    }
  }

  void DisableD3D11Manager() {
    if (usingD3D11_ && decoder_ != nullptr) {
      decoder_->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, 0);
      usingD3D11_ = false;
    }
  }

  bool ConfigureInputAndOutputTypes(int32_t width, int32_t height) {
    EnableLowLatencyMode();

    IMFMediaType* inputType = nullptr;
    if (FAILED(MFCreateMediaType(&inputType)) || inputType == nullptr) {
      return false;
    }

    inputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    inputType->SetGUID(MF_MT_SUBTYPE, subtype_);
    MFSetAttributeSize(inputType, MF_MT_FRAME_SIZE, static_cast<UINT32>(width), static_cast<UINT32>(height));
    MFSetAttributeRatio(inputType, MF_MT_FRAME_RATE, 60, 1);
    MFSetAttributeRatio(inputType, MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    inputType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);

    HRESULT hr = decoder_->SetInputType(0, inputType, 0);
    inputType->Release();
    if (FAILED(hr)) {
      return false;
    }

    if (!ConfigureOutputType()) {
      return false;
    }

    decoder_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    decoder_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    typesConfigured_ = true;
    return true;
  }

  static int GetSubtypePriority(const GUID& subtype) {
    if (IsEqualGUID(subtype, kSubTypeNV12)) return 100;
    if (IsEqualGUID(subtype, kSubTypeARGB32) || IsEqualGUID(subtype, kSubTypeRGB32)) return 90;
    if (IsEqualGUID(subtype, kSubTypeIYUV) || IsEqualGUID(subtype, kSubTypeYV12)) return 80;
    if (IsEqualGUID(subtype, kSubTypeYUY2)) return 70;
    return 0;
  }

  bool ConfigureOutputType() {
    IMFMediaType* bestType = nullptr;
    int bestPriority = 0;
    GUID bestSubtype = {};

    for (DWORD index = 0; ; ++index) {
      IMFMediaType* availType = nullptr;
      HRESULT hr = decoder_->GetOutputAvailableType(0, index, &availType);
      if (FAILED(hr) || availType == nullptr) {
        break;
      }
      GUID subtype = {};
      if (SUCCEEDED(availType->GetGUID(MF_MT_SUBTYPE, &subtype))) {
        int priority = GetSubtypePriority(subtype);
        if (priority > bestPriority) {
          if (bestType != nullptr) {
            bestType->Release();
          }
          bestType = availType;
          bestPriority = priority;
          bestSubtype = subtype;
          if (priority == 100) {
            break;
          }
          continue;
        }
      }
      availType->Release();
    }

    if (bestType == nullptr) {
      return false;
    }

    HRESULT hr = decoder_->SetOutputType(0, bestType, 0);
    if (SUCCEEDED(hr)) {
      outputSubtype_ = bestSubtype;
      UINT32 w = 0, h = 0;
      if (SUCCEEDED(MFGetAttributeSize(bestType, MF_MT_FRAME_SIZE, &w, &h)) && w > 0 && h > 0) {
        surfaceWidth_ = static_cast<int32_t>(w);
        surfaceHeight_ = static_cast<int32_t>(h);
      }

      UINT32 strideAttr = 0;
      if (SUCCEEDED(bestType->GetUINT32(MF_MT_DEFAULT_STRIDE, &strideAttr))) {
        defaultStride_ = static_cast<int32_t>(strideAttr);
      } else {
        defaultStride_ = 0;
      }

      apertureOffsetX_ = 0;
      apertureOffsetY_ = 0;
      visibleWidth_ = surfaceWidth_;
      visibleHeight_ = surfaceHeight_;
      MFVideoArea aperture = {};
      UINT32 blobSize = 0;
      if (SUCCEEDED(bestType->GetBlob(MF_MT_MINIMUM_DISPLAY_APERTURE, reinterpret_cast<UINT8*>(&aperture), sizeof(aperture), &blobSize)) ||
          SUCCEEDED(bestType->GetBlob(MF_MT_GEOMETRIC_APERTURE, reinterpret_cast<UINT8*>(&aperture), sizeof(aperture), &blobSize))) {
        int32_t offX = std::max<int32_t>(0, aperture.OffsetX.value);
        int32_t offY = std::max<int32_t>(0, aperture.OffsetY.value);
        if (aperture.Area.cx > 0 && offX + aperture.Area.cx <= surfaceWidth_ &&
            aperture.Area.cy > 0 && offY + aperture.Area.cy <= surfaceHeight_) {
          apertureOffsetX_ = offX;
          apertureOffsetY_ = offY;
          visibleWidth_ = aperture.Area.cx;
          visibleHeight_ = aperture.Area.cy;
        }
      } else if (parsedWidth_ > 0 && parsedHeight_ > 0 &&
                 parsedWidth_ <= surfaceWidth_ && parsedHeight_ <= surfaceHeight_) {
        visibleWidth_ = parsedWidth_;
        visibleHeight_ = parsedHeight_;
      }
    }

    bestType->Release();
    return SUCCEEDED(hr);
  }

  IMFSample* CreateMediaSampleFromChunks(const BufferChunk* chunks, size_t chunkCount) {
    size_t totalSize = 0;
    for (size_t i = 0; i < chunkCount; ++i) {
      totalSize += chunks[i].size;
    }
    if (totalSize == 0 || totalSize > MAXDWORD) {
      return nullptr;
    }

    IMFMediaBuffer* buffer = nullptr;
    if (FAILED(MFCreateMemoryBuffer(static_cast<DWORD>(totalSize), &buffer)) || buffer == nullptr) {
      return nullptr;
    }

    BYTE* dst = nullptr;
    if (FAILED(buffer->Lock(&dst, nullptr, nullptr)) || dst == nullptr) {
      buffer->Release();
      return nullptr;
    }
    size_t offset = 0;
    for (size_t i = 0; i < chunkCount; ++i) {
      if (chunks[i].size > 0) {
        std::memcpy(dst + offset, chunks[i].data, chunks[i].size);
        offset += chunks[i].size;
      }
    }
    buffer->Unlock();
    buffer->SetCurrentLength(static_cast<DWORD>(totalSize));

    IMFSample* sample = nullptr;
    if (FAILED(MFCreateSample(&sample)) || sample == nullptr) {
      buffer->Release();
      return nullptr;
    }
    sample->AddBuffer(buffer);
    buffer->Release();

    sample->SetSampleTime(sampleTime_);
    sample->SetSampleDuration(166667); // ~60fps in 100ns units
    sampleTime_ += 166667;
    return sample;
  }

  bool CollectOutputSamples(IMFSample** inOutLatestSample, IMFSample* currentInputSample) {
    while (true) {
      MFT_OUTPUT_STREAM_INFO streamInfo = {};
      if (FAILED(decoder_->GetOutputStreamInfo(0, &streamInfo))) {
        return false;
      }

      bool mftProvidesSamples =
          (streamInfo.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) != 0;
      IMFSample* allocatedSample = nullptr;
      if (!mftProvidesSamples) {
        DWORD allocSize = streamInfo.cbSize;
        DWORD minExpected = static_cast<DWORD>(std::max(1, surfaceWidth_) * std::max(1, surfaceHeight_) * 4);
        if (allocSize < minExpected) {
          allocSize = minExpected;
        }
        IMFMediaBuffer* outBuf = nullptr;
        if (FAILED(MFCreateMemoryBuffer(allocSize, &outBuf)) || outBuf == nullptr) {
          return false;
        }
        if (FAILED(MFCreateSample(&allocatedSample)) || allocatedSample == nullptr) {
          outBuf->Release();
          return false;
        }
        allocatedSample->AddBuffer(outBuf);
        outBuf->Release();
      }

      MFT_OUTPUT_DATA_BUFFER outputData = {};
      outputData.dwStreamID = 0;
      outputData.pSample = allocatedSample;

      DWORD status = 0;
      HRESULT hr = decoder_->ProcessOutput(0, 1, &outputData, &status);
      if (outputData.pEvents != nullptr) {
        outputData.pEvents->Release();
      }

      auto releaseCurrentOutput = [&]() {
        if (outputData.pSample != nullptr && outputData.pSample != allocatedSample) {
          outputData.pSample->Release();
        }
        if (allocatedSample != nullptr) {
          allocatedSample->Release();
        }
      };

      if (hr == MF_E_TRANSFORM_STREAM_CHANGE || (outputData.dwStatus & MFT_OUTPUT_DATA_BUFFER_FORMAT_CHANGE) != 0) {
        releaseCurrentOutput();
        if (!ConfigureOutputType()) {
          return false;
        }
        continue;
      }

      if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
        releaseCurrentOutput();
        return true;
      }

      if (FAILED(hr)) {
        releaseCurrentOutput();
        if (usingD3D11_ && !hasDecodedFrames_) {
          DisableD3D11Manager();
          int32_t initWidth = parsedWidth_ > 0 ? parsedWidth_ : 1920;
          int32_t initHeight = parsedHeight_ > 0 ? parsedHeight_ : 1080;
          if (!ConfigureInputAndOutputTypes(initWidth, initHeight)) {
            return false;
          }
          if (currentInputSample != nullptr) {
            if (FAILED(decoder_->ProcessInput(0, currentInputSample, 0))) {
              return false;
            }
            currentInputSample = nullptr;
            continue;
          }
          return true;
        }
        return false;
      }

      IMFSample* resultSample = outputData.pSample;
      if (resultSample != nullptr) {
        if (allocatedSample != nullptr && resultSample != allocatedSample) {
          allocatedSample->Release();
        }
        if (*inOutLatestSample != nullptr) {
          (*inOutLatestSample)->Release();
        }
        *inOutLatestSample = resultSample;
      } else if (allocatedSample != nullptr) {
        allocatedSample->Release();
      }
    }
  }

  bool ConvertSampleToBgra(IMFSample* sample, uint8_t* outputPixels, size_t outputCapacity, int32_t* outWidth, int32_t* outHeight) {
    IMFMediaBuffer* buffer = nullptr;
    if (FAILED(sample->ConvertToContiguousBuffer(&buffer)) || buffer == nullptr) {
      return false;
    }

    int32_t width = visibleWidth_;
    int32_t height = visibleHeight_;
    if (parsedWidth_ > 0 && parsedHeight_ > 0 &&
        apertureOffsetX_ + parsedWidth_ <= surfaceWidth_ &&
        apertureOffsetY_ + parsedHeight_ <= surfaceHeight_) {
      width = parsedWidth_;
      height = parsedHeight_;
    }
    if (width <= 0 || height <= 0) {
      buffer->Release();
      return false;
    }

    size_t dstRowBytes = static_cast<size_t>(width) * 4;
    if (dstRowBytes * static_cast<size_t>(height) > outputCapacity) {
      buffer->Release();
      return false;
    }

    BYTE* scanline0 = nullptr;
    LONG pitch = 0;
    BYTE* bufferStart = nullptr;
    DWORD bufferLength = 0;
    IMF2DBuffer* buffer2D = nullptr;
    IMF2DBuffer2* buffer2D2 = nullptr;
    bool locked2D = false;

    if (SUCCEEDED(buffer->QueryInterface(kIidIMF2DBuffer2, reinterpret_cast<void**>(&buffer2D2))) && buffer2D2 != nullptr) {
      if (SUCCEEDED(buffer2D2->Lock2DSize(MF2DBuffer_LockFlags_Read, &scanline0, &pitch, &bufferStart, &bufferLength))) {
        locked2D = true;
      }
    }
    if (!locked2D && SUCCEEDED(buffer->QueryInterface(IID_PPV_ARGS(&buffer2D))) && buffer2D != nullptr) {
      if (SUCCEEDED(buffer2D->Lock2D(&scanline0, &pitch))) {
        locked2D = true;
      }
    }

    BYTE* rawData = nullptr;
    DWORD currentLen = 0;
    if (!locked2D) {
      if (FAILED(buffer->Lock(&rawData, nullptr, &currentLen)) || rawData == nullptr) {
        if (buffer2D2 != nullptr) buffer2D2->Release();
        if (buffer2D != nullptr) buffer2D->Release();
        buffer->Release();
        return false;
      }
      bufferStart = rawData;
      bufferLength = currentLen;
      if (defaultStride_ != 0) {
        pitch = defaultStride_;
      } else if (IsEqualGUID(outputSubtype_, kSubTypeARGB32) || IsEqualGUID(outputSubtype_, kSubTypeRGB32)) {
        pitch = surfaceWidth_ * 4;
      } else if (IsEqualGUID(outputSubtype_, kSubTypeYUY2)) {
        pitch = ((surfaceWidth_ + 1) & ~1) * 2;
      } else {
        pitch = (surfaceWidth_ + 1) & ~1;
      }
      size_t absPitch = static_cast<size_t>(pitch < 0 ? -static_cast<ptrdiff_t>(pitch) : static_cast<ptrdiff_t>(pitch));
      size_t requiredLen = absPitch * static_cast<size_t>(surfaceHeight_);
      if (IsEqualGUID(outputSubtype_, kSubTypeNV12)) {
        requiredLen += absPitch * static_cast<size_t>((surfaceHeight_ + 1) / 2);
      } else if (IsEqualGUID(outputSubtype_, kSubTypeIYUV) || IsEqualGUID(outputSubtype_, kSubTypeYV12)) {
        requiredLen += 2 * ((absPitch + 1) / 2) * static_cast<size_t>((surfaceHeight_ + 1) / 2);
      }
      if (currentLen < requiredLen) {
        buffer->Unlock();
        if (buffer2D2 != nullptr) buffer2D2->Release();
        if (buffer2D != nullptr) buffer2D->Release();
        buffer->Release();
        return false;
      }
      if (pitch < 0) {
        scanline0 = rawData + absPitch * static_cast<size_t>(surfaceHeight_ - 1);
      } else {
        scanline0 = rawData;
      }
    }

    bool converted = false;
    ptrdiff_t stride = static_cast<ptrdiff_t>(pitch);
    ptrdiff_t absStride = stride < 0 ? -stride : stride;
    size_t offX = static_cast<size_t>(apertureOffsetX_);
    size_t offY = static_cast<size_t>(apertureOffsetY_);

    // Determine effective vertical plane height in case a hardware decoder aligned the Y plane to 16/32 rows.
    size_t planeHeight = static_cast<size_t>(surfaceHeight_);
    if (stride > 0 && bufferStart != nullptr && scanline0 >= bufferStart && bufferLength > static_cast<size_t>(scanline0 - bufferStart)) {
      size_t availBytes = bufferLength - static_cast<size_t>(scanline0 - bufferStart);
      size_t availRows = availBytes / static_cast<size_t>(absStride);
      size_t aligned16 = (planeHeight + 15) & ~static_cast<size_t>(15);
      if (aligned16 > planeHeight && availRows == aligned16 + aligned16 / 2) {
        planeHeight = aligned16;
      }
    }

    if (IsEqualGUID(outputSubtype_, kSubTypeNV12)) {
      size_t uvHeight = (planeHeight + 1) / 2;
      if (stride > 0 && static_cast<size_t>(absStride) >= offX + static_cast<size_t>(width) && absStride >= 2 && uvHeight > 0) {
        const uint8_t* yPlane = scanline0;
        const uint8_t* uvPlane = yPlane + static_cast<size_t>(absStride) * planeHeight;
        for (int32_t y = 0; y < height; ++y) {
          size_t srcY = offY + static_cast<size_t>(y);
          const uint8_t* yRow = yPlane + srcY * static_cast<size_t>(absStride) + offX;
          size_t uvY = std::min<size_t>(srcY / 2, uvHeight - 1);
          const uint8_t* uvRow = uvPlane + uvY * static_cast<size_t>(absStride) + (offX & ~static_cast<size_t>(1));
          uint8_t* dstRow = outputPixels + static_cast<size_t>(y) * dstRowBytes;
          size_t maxUvOffset = static_cast<size_t>(absStride) - (offX & ~static_cast<size_t>(1)) - 2;
          for (int32_t x = 0; x < width; x += 2) {
            size_t uvOffset = std::min<size_t>(static_cast<size_t>(x), maxUvOffset);
            uint8_t y0 = yRow[x];
            uint8_t y1 = (x + 1 < width) ? yRow[x + 1] : y0;
            uint8_t* dst0 = dstRow + static_cast<size_t>(x) * 4;
            uint8_t* dst1 = (x + 1 < width) ? (dst0 + 4) : nullptr;
            YuvPairToBgra(y0, y1, uvRow[uvOffset], uvRow[uvOffset + 1], dst0, dst1);
          }
        }
        converted = true;
      }
    } else if (IsEqualGUID(outputSubtype_, kSubTypeARGB32) || IsEqualGUID(outputSubtype_, kSubTypeRGB32)) {
      if (static_cast<size_t>(absStride) >= (offX + static_cast<size_t>(width)) * 4) {
        for (int32_t y = 0; y < height; ++y) {
          const uint8_t* srcRow = scanline0 + static_cast<ptrdiff_t>(offY + static_cast<size_t>(y)) * stride + offX * 4;
          auto* dstRow32 = reinterpret_cast<uint32_t*>(outputPixels + static_cast<size_t>(y) * dstRowBytes);
          const auto* srcRow32 = reinterpret_cast<const uint32_t*>(srcRow);
          for (int32_t x = 0; x < width; ++x) {
            dstRow32[x] = srcRow32[x] | 0xFF000000u;
          }
        }
        converted = true;
      }
    } else if (IsEqualGUID(outputSubtype_, kSubTypeIYUV) || IsEqualGUID(outputSubtype_, kSubTypeYV12)) {
      bool isYv12 = IsEqualGUID(outputSubtype_, kSubTypeYV12);
      size_t uvStride = (static_cast<size_t>(absStride) + 1) / 2;
      size_t uvHeight = (planeHeight + 1) / 2;
      if (stride > 0 && static_cast<size_t>(absStride) >= offX + static_cast<size_t>(width) && uvStride > 0 && uvHeight > 0) {
        const uint8_t* yPlane = scanline0;
        const uint8_t* plane1 = yPlane + static_cast<size_t>(absStride) * planeHeight;
        const uint8_t* plane2 = plane1 + uvStride * uvHeight;
        const uint8_t* uPlane = isYv12 ? plane2 : plane1;
        const uint8_t* vPlane = isYv12 ? plane1 : plane2;
        for (int32_t y = 0; y < height; ++y) {
          size_t srcY = offY + static_cast<size_t>(y);
          const uint8_t* yRow = yPlane + srcY * static_cast<size_t>(absStride) + offX;
          size_t uvY = std::min<size_t>(srcY / 2, uvHeight - 1);
          const uint8_t* uRow = uPlane + uvY * uvStride + (offX / 2);
          const uint8_t* vRow = vPlane + uvY * uvStride + (offX / 2);
          uint8_t* dstRow = outputPixels + static_cast<size_t>(y) * dstRowBytes;
          size_t maxUvX = uvStride - (offX / 2) - 1;
          for (int32_t x = 0; x < width; x += 2) {
            size_t uvX = std::min<size_t>(static_cast<size_t>(x / 2), maxUvX);
            uint8_t y0 = yRow[x];
            uint8_t y1 = (x + 1 < width) ? yRow[x + 1] : y0;
            uint8_t* dst0 = dstRow + static_cast<size_t>(x) * 4;
            uint8_t* dst1 = (x + 1 < width) ? (dst0 + 4) : nullptr;
            YuvPairToBgra(y0, y1, uRow[uvX], vRow[uvX], dst0, dst1);
          }
        }
        converted = true;
      }
    } else if (IsEqualGUID(outputSubtype_, kSubTypeYUY2)) {
      if (static_cast<size_t>(absStride) >= (offX + static_cast<size_t>(width)) * 2 && absStride >= 4) {
        for (int32_t y = 0; y < height; ++y) {
          const uint8_t* srcRow = scanline0 + static_cast<ptrdiff_t>(offY + static_cast<size_t>(y)) * stride + (offX & ~static_cast<size_t>(1)) * 2;
          uint8_t* dstRow = outputPixels + static_cast<size_t>(y) * dstRowBytes;
          size_t maxMacroOffset = static_cast<size_t>(absStride) - (offX & ~static_cast<size_t>(1)) * 2 - 4;
          for (int32_t x = 0; x < width; x += 2) {
            size_t macroOffset = std::min<size_t>(static_cast<size_t>(x) * 2, maxMacroOffset);
            uint8_t y0 = srcRow[macroOffset];
            uint8_t u = srcRow[macroOffset + 1];
            uint8_t y1 = srcRow[macroOffset + 2];
            uint8_t v = srcRow[macroOffset + 3];
            uint8_t* dst0 = dstRow + static_cast<size_t>(x) * 4;
            uint8_t* dst1 = (x + 1 < width) ? (dst0 + 4) : nullptr;
            YuvPairToBgra(y0, y1, u, v, dst0, dst1);
          }
        }
        converted = true;
      }
    }

    if (locked2D) {
      if (buffer2D2 != nullptr) {
        buffer2D2->Unlock2D();
      } else if (buffer2D != nullptr) {
        buffer2D->Unlock2D();
      }
    } else {
      buffer->Unlock();
    }
    if (buffer2D2 != nullptr) {
      buffer2D2->Release();
    }
    if (buffer2D != nullptr) {
      buffer2D->Release();
    }
    buffer->Release();

    if (converted) {
      *outWidth = width;
      *outHeight = height;
    }
    return converted;
  }

  const uint32_t codecType_;
  const GUID subtype_;
  SRWLOCK decodeLock_ = SRWLOCK_INIT;
  bool mfStarted_ = false;
  bool d3d11Configured_ = false;
  bool usingD3D11_ = false;
  bool typesConfigured_ = false;
  bool sentConfig_ = false;
  bool hasDecodedFrames_ = false;

  IMFTransform* decoder_ = nullptr;
  ID3D11Device* d3dDevice_ = nullptr;
  ID3D11DeviceContext* d3dContext_ = nullptr;
  IMFDXGIDeviceManager* dxgiManager_ = nullptr;

  GUID outputSubtype_ = {};
  int32_t parsedWidth_ = 0;
  int32_t parsedHeight_ = 0;
  int32_t surfaceWidth_ = 1920;
  int32_t surfaceHeight_ = 1080;
  int32_t apertureOffsetX_ = 0;
  int32_t apertureOffsetY_ = 0;
  int32_t visibleWidth_ = 1920;
  int32_t visibleHeight_ = 1080;
  int32_t defaultStride_ = 0;
  LONGLONG sampleTime_ = 0;

  std::vector<uint8_t> vps_;
  std::vector<uint8_t> sps_;
  std::vector<uint8_t> pps_;
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_android_tools_idea_streaming_device_OsVideoDecoder_createNativeDecoder(JNIEnv*, jclass, jint codecType) {
  return reinterpret_cast<jlong>(WinVideoDecoder::Create(static_cast<uint32_t>(codecType)));
}

JNIEXPORT jint JNICALL
Java_com_android_tools_idea_streaming_device_OsVideoDecoder_decodeFrame(
    JNIEnv* env, jclass, jlong handle, jobject packetBuffer, jint packetOffset, jint packetSize, jobject outputPixelBuffer,
    jint outputCapacity, jintArray outDimensions) {
  auto* decoder = reinterpret_cast<WinVideoDecoder*>(handle);
  if (decoder == nullptr || packetBuffer == nullptr || outputPixelBuffer == nullptr || outDimensions == nullptr) {
    return DECODE_ERROR;
  }

  jlong packetCapacity = env->GetDirectBufferCapacity(packetBuffer);
  jlong outputPixelCapacity = env->GetDirectBufferCapacity(outputPixelBuffer);
  jsize dimsLength = env->GetArrayLength(outDimensions);
  if (packetOffset < 0 || packetSize < 0 || outputCapacity < 0 || dimsLength < 2 ||
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
    jint dims[2] = { width, height };
    env->SetIntArrayRegion(outDimensions, 0, 2, dims);
  }

  return result;
}

JNIEXPORT void JNICALL
Java_com_android_tools_idea_streaming_device_OsVideoDecoder_destroyNativeDecoder(JNIEnv*, jclass, jlong handle) {
  auto* decoder = reinterpret_cast<WinVideoDecoder*>(handle);
  delete decoder;
}

} // extern "C"
