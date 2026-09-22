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
#import <CoreFoundation/CoreFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <VideoToolbox/VideoToolbox.h>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace {

constexpr CMVideoCodecType kCodecTypeAV1 = 'av01';
constexpr CMVideoCodecType kCodecTypeAVC = kCMVideoCodecType_H264;
constexpr CMVideoCodecType kCodecTypeHEVC = kCMVideoCodecType_HEVC;
constexpr CMVideoCodecType kCodecTypeVP9 = 'vp09';

constexpr jint DECODE_ERROR = -1;
constexpr jint DECODE_NO_FRAME = 0;
constexpr jint DECODE_FRAME_PRODUCED = 1;

// H.264 (AVC) NAL unit constants.
constexpr uint8_t AVC_NAL_UNIT_TYPE_MASK = 0x1F;
constexpr uint8_t AVC_NAL_SPS = 7;
constexpr uint8_t AVC_NAL_PPS = 8;
constexpr uint8_t AVC_NAL_AUD = 9;

// H.265 (HEVC) NAL unit constants.
constexpr uint8_t HEVC_NAL_UNIT_TYPE_MASK = 0x3F;
constexpr uint8_t HEVC_NAL_VPS = 32;
constexpr uint8_t HEVC_NAL_SPS = 33;
constexpr uint8_t HEVC_NAL_PPS = 34;
constexpr uint8_t HEVC_NAL_AUD = 35;

// AV1 OBU type constants.
constexpr uint8_t AV1_OBU_SEQUENCE_HEADER = 1;
constexpr uint8_t AV1_OBU_TEMPORAL_DELIMITER = 2;
constexpr uint8_t AV1_OBU_TILE_GROUP = 4;
constexpr uint8_t AV1_OBU_FRAME = 6;
constexpr uint8_t AV1_OBU_REDUNDANT_FRAME_HEADER = 7;
constexpr uint8_t AV1_OBU_PADDING = 15;

struct NalUnit {
  const uint8_t* data;
  size_t size;
  uint8_t type;
};

struct FrameDecodeContext {
  std::mutex mutex;
  jobject outputBufferRef = nullptr;
  uint8_t* outputPixels = nullptr;
  size_t outputCapacity = 0;
  int32_t decodedWidth = 0;
  int32_t decodedHeight = 0;
  bool frameProduced = false;
};

class BitReader {
public:
  BitReader(const uint8_t* data, size_t size)
      : data_(data), totalBits_(size * 8), bitPos_(0) {}

  bool HasBits(size_t count) const {
    return bitPos_ + count <= totalBits_;
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
      ++leadingZeros;
      if (overflow_ || leadingZeros >= 32) {
        return 0xFFFFFFFF;
      }
    }
    if (leadingZeros == 0) {
      return 0;
    }
    uint32_t value = ReadBits(leadingZeros);
    return value + (1u << leadingZeros) - 1;
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

// Finds all NAL units in an Annex B byte stream (for AVC and HEVC).
std::vector<NalUnit> ParseAnnexBNalUnits(const uint8_t* data, size_t size, bool isHevc) {
  std::vector<NalUnit> units;
  if (size < 4) {
    return units;
  }

  // Find start codes (0x000001 or 0x00000001).
  std::vector<size_t> startCodeOffsets;
  for (size_t i = 0; i + 2 < size; ++i) {
    if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
      if (i > 0 && data[i - 1] == 0) {
        startCodeOffsets.push_back(i - 1); // 4-byte start code
      } else {
        startCodeOffsets.push_back(i);     // 3-byte start code
      }
    }
  }

  size_t minHeaderBytes = isHevc ? 2 : 1;
  for (size_t i = 0; i < startCodeOffsets.size(); ++i) {
    size_t start = startCodeOffsets[i];
    size_t prefixLen = (data[start] == 0 && data[start + 1] == 0 && data[start + 2] == 0) ? 4 : 3;
    size_t payloadStart = start + prefixLen;

    size_t payloadEnd = (i + 1 < startCodeOffsets.size()) ? startCodeOffsets[i + 1] : size;
    // Strip trailing zeroes between NAL units
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

struct Vp9FrameHeaderInfo {
  bool isKeyFrame = false;
  int32_t width = 0;
  int32_t height = 0;
  uint8_t profile = 0;
  uint8_t bitDepth = 8;
  uint8_t subsamplingX = 1;
  uint8_t subsamplingY = 1;
  uint8_t colorSpace = 0;
  uint8_t colorRange = 0;
};

bool ParseVp9Header(const uint8_t* data, size_t size, Vp9FrameHeaderInfo* info) {
  BitReader reader(data, size);
  if (reader.ReadBits(2) != 2) { // frame_marker must be 2
    return false;
  }
  uint32_t profileLow = reader.ReadBits(1);
  uint32_t profileHigh = reader.ReadBits(1);
  uint8_t profile = static_cast<uint8_t>((profileHigh << 1) | profileLow);
  if (profile == 3 && reader.ReadBits(1) != 0) { // reserved_zero
    return false;
  }
  if (reader.ReadBit()) { // show_existing_frame
    info->isKeyFrame = false;
    return !reader.HasOverflow();
  }

  bool isKeyFrame = (reader.ReadBits(1) == 0);
  reader.ReadBit(); // show_frame
  reader.ReadBit(); // error_resilient_mode

  if (!isKeyFrame) {
    info->isKeyFrame = false;
    return !reader.HasOverflow();
  }

  if (reader.ReadBits(24) != 0x498342) { // frame_sync_code
    return false;
  }

  uint8_t bitDepth = 8;
  if (profile >= 2) {
    bitDepth = reader.ReadBit() ? 12 : 10;
  }
  uint8_t colorSpace = static_cast<uint8_t>(reader.ReadBits(3));
  uint8_t colorRange = 0;
  uint8_t subsamplingX = 1;
  uint8_t subsamplingY = 1;

  if (colorSpace != 7) { // Not CS_RGB
    colorRange = reader.ReadBit() ? 1 : 0;
    if (profile == 1 || profile == 3) {
      subsamplingX = reader.ReadBit() ? 1 : 0;
      subsamplingY = reader.ReadBit() ? 1 : 0;
      reader.ReadBit(); // reserved_zero
    }
  } else {
    colorRange = 1;
    if (profile == 1 || profile == 3) {
      subsamplingX = 0;
      subsamplingY = 0;
      reader.ReadBit(); // reserved_zero
    }
  }

  int32_t width = static_cast<int32_t>(reader.ReadBits(16)) + 1;
  int32_t height = static_cast<int32_t>(reader.ReadBits(16)) + 1;
  if (reader.HasOverflow() || width <= 0 || height <= 0) {
    return false;
  }

  info->isKeyFrame = true;
  info->width = width;
  info->height = height;
  info->profile = profile;
  info->bitDepth = bitDepth;
  info->subsamplingX = subsamplingX;
  info->subsamplingY = subsamplingY;
  info->colorSpace = colorSpace;
  info->colorRange = colorRange;
  return true;
}

std::vector<uint8_t> BuildVpccAtom(const Vp9FrameHeaderInfo& info) {
  uint8_t chromaSubsampling = 1;
  if (info.subsamplingX == 1 && info.subsamplingY == 1) {
    chromaSubsampling = 1; // 4:2:0 colocated with luma
  } else if (info.subsamplingX == 1 && info.subsamplingY == 0) {
    chromaSubsampling = 2; // 4:2:2
  } else if (info.subsamplingX == 0 && info.subsamplingY == 0) {
    chromaSubsampling = 3; // 4:4:4
  }

  uint8_t primaries = 2; // Unspecified
  uint8_t transfer = 2;  // Unspecified
  uint8_t matrix = 2;    // Unspecified
  switch (info.colorSpace) {
    case 1: // CS_BT_601
    case 3: // CS_SMPTE_170
      primaries = 6; transfer = 6; matrix = 6; break;
    case 2: // CS_BT_709
      primaries = 1; transfer = 1; matrix = 1; break;
    case 4: // CS_SMPTE_240
      primaries = 7; transfer = 7; matrix = 7; break;
    case 5: // CS_BT_2020
      primaries = 9; transfer = 14; matrix = 9; break;
    case 7: // CS_RGB
      primaries = 1; transfer = 13; matrix = 0; break;
    default:
      break;
  }

  return {
    1, // version = 1
    0, 0, 0, // flags = 0
    info.profile,
    10, // level = 1.0
    static_cast<uint8_t>((info.bitDepth << 4) | (chromaSubsampling << 1) | (info.colorRange & 1)),
    primaries,
    transfer,
    matrix,
    0, 0 // codecInitializationDataSize = 0
  };
}

struct Av1SequenceHeaderInfo {
  int32_t width = 0;
  int32_t height = 0;
  uint8_t seqProfile = 0;
  uint8_t seqLevelIdx0 = 0;
  uint8_t seqTier0 = 0;
  uint8_t highBitdepth = 0;
  uint8_t twelveBit = 0;
  uint8_t monochrome = 0;
  uint8_t chromaSubsamplingX = 1;
  uint8_t chromaSubsamplingY = 1;
  uint8_t chromaSamplePosition = 0;
};

bool ParseAv1SequenceHeader(const uint8_t* data, size_t size, Av1SequenceHeaderInfo* info) {
  BitReader reader(data, size);
  info->seqProfile = static_cast<uint8_t>(reader.ReadBits(3));
  reader.ReadBit(); // still_picture
  bool reducedStillPictureHeader = reader.ReadBit();
  if (reducedStillPictureHeader) {
    info->seqLevelIdx0 = static_cast<uint8_t>(reader.ReadBits(5));
    info->seqTier0 = 0;
  } else {
    bool timingInfoPresentFlag = reader.ReadBit();
    bool decoderModelInfoPresentFlag = false;
    uint32_t bufferDelayLengthMinus1 = 0;
    if (timingInfoPresentFlag) {
      reader.ReadBits(32); // num_units_in_display_tick
      reader.ReadBits(32); // time_scale
      if (reader.ReadBit()) { // equal_picture_interval
        reader.ReadUvlc(); // num_ticks_per_picture_minus_1
      }
      decoderModelInfoPresentFlag = reader.ReadBit();
      if (decoderModelInfoPresentFlag) {
        bufferDelayLengthMinus1 = reader.ReadBits(5);
        reader.ReadBits(32); // num_units_in_decoding_tick
        reader.ReadBits(5);  // buffer_removal_time_length_minus_1
        reader.ReadBits(5);  // frame_presentation_time_length_minus_1
      }
    }
    bool initialDisplayDelayPresentFlag = reader.ReadBit();
    uint32_t operatingPointsCntMinus1 = reader.ReadBits(5);
    for (uint32_t i = 0; i <= operatingPointsCntMinus1; ++i) {
      reader.ReadBits(12); // operating_point_idc
      uint8_t seqLevelIdx = static_cast<uint8_t>(reader.ReadBits(5));
      uint8_t seqTier = (seqLevelIdx > 7) ? static_cast<uint8_t>(reader.ReadBit()) : 0;
      if (i == 0) {
        info->seqLevelIdx0 = seqLevelIdx;
        info->seqTier0 = seqTier;
      }
      if (decoderModelInfoPresentFlag) {
        if (reader.ReadBit()) { // decoder_model_present_for_this_op
          reader.ReadBits(bufferDelayLengthMinus1 + 1); // decoder_buffer_delay
          reader.ReadBits(bufferDelayLengthMinus1 + 1); // encoder_buffer_delay
          reader.ReadBit(); // low_delay_mode_flag
        }
      }
      if (initialDisplayDelayPresentFlag) {
        if (reader.ReadBit()) { // initial_display_delay_present_for_this_op
          reader.ReadBits(4); // initial_display_delay_minus_1
        }
      }
    }
  }

  uint32_t frameWidthBitsMinus1 = reader.ReadBits(4);
  uint32_t frameHeightBitsMinus1 = reader.ReadBits(4);
  info->width = static_cast<int32_t>(reader.ReadBits(frameWidthBitsMinus1 + 1)) + 1;
  info->height = static_cast<int32_t>(reader.ReadBits(frameHeightBitsMinus1 + 1)) + 1;

  if (!reducedStillPictureHeader) {
    if (reader.ReadBit()) { // frame_id_numbers_present_flag
      reader.ReadBits(4); // delta_frame_id_length_minus_2
      reader.ReadBits(3); // additional_frame_id_length_minus_1
    }
  }

  reader.ReadBit(); // use_128x128_superblock
  reader.ReadBit(); // enable_filter_intra
  reader.ReadBit(); // enable_intra_edge_filter

  if (!reducedStillPictureHeader) {
    reader.ReadBit(); // enable_interintra_compound
    reader.ReadBit(); // enable_masked_compound
    reader.ReadBit(); // enable_warped_motion
    reader.ReadBit(); // enable_dual_filter
    bool enableOrderHint = reader.ReadBit();
    if (enableOrderHint) {
      reader.ReadBit(); // enable_jnt_comp
      reader.ReadBit(); // enable_ref_frame_mvs
    }
    bool seqChooseScreenContentTools = reader.ReadBit();
    uint32_t seqForceScreenContentTools = seqChooseScreenContentTools ? 2 : reader.ReadBits(1);
    if (seqForceScreenContentTools > 0) {
      if (!reader.ReadBit()) { // !seq_choose_integer_mv
        reader.ReadBit(); // seq_force_integer_mv
      }
    }
    if (enableOrderHint) {
      reader.ReadBits(3); // order_hint_bits_minus_1
    }
  }

  reader.ReadBit(); // enable_superres
  reader.ReadBit(); // enable_cdef
  reader.ReadBit(); // enable_restoration

  // color_config()
  info->highBitdepth = static_cast<uint8_t>(reader.ReadBit());
  if (info->seqProfile == 2 && info->highBitdepth) {
    info->twelveBit = static_cast<uint8_t>(reader.ReadBit());
  } else {
    info->twelveBit = 0;
  }
  if (info->seqProfile == 1) {
    info->monochrome = 0;
  } else {
    info->monochrome = static_cast<uint8_t>(reader.ReadBit());
  }

  uint32_t colorPrimaries = 2;
  uint32_t transferCharacteristics = 2;
  uint32_t matrixCoefficients = 2;
  if (reader.ReadBit()) { // color_description_present_flag
    colorPrimaries = reader.ReadBits(8);
    transferCharacteristics = reader.ReadBits(8);
    matrixCoefficients = reader.ReadBits(8);
  }

  if (info->monochrome) {
    reader.ReadBit(); // color_range
    info->chromaSubsamplingX = 1;
    info->chromaSubsamplingY = 1;
    info->chromaSamplePosition = 0;
  } else if (colorPrimaries == 1 && transferCharacteristics == 13 && matrixCoefficients == 0) {
    info->chromaSubsamplingX = 0;
    info->chromaSubsamplingY = 0;
    info->chromaSamplePosition = 0;
  } else {
    reader.ReadBit(); // color_range
    if (info->seqProfile == 0) {
      info->chromaSubsamplingX = 1;
      info->chromaSubsamplingY = 1;
    } else if (info->seqProfile == 1) {
      info->chromaSubsamplingX = 0;
      info->chromaSubsamplingY = 0;
    } else {
      if (info->twelveBit) {
        info->chromaSubsamplingX = static_cast<uint8_t>(reader.ReadBit());
        info->chromaSubsamplingY = info->chromaSubsamplingX ? static_cast<uint8_t>(reader.ReadBit()) : 0;
      } else {
        info->chromaSubsamplingX = 1;
        info->chromaSubsamplingY = 0;
      }
    }
    if (info->chromaSubsamplingX && info->chromaSubsamplingY) {
      info->chromaSamplePosition = static_cast<uint8_t>(reader.ReadBits(2));
    } else {
      info->chromaSamplePosition = 0;
    }
  }

  return !reader.HasOverflow() && info->width > 0 && info->height > 0;
}

class MacVideoDecoder {
public:
  explicit MacVideoDecoder(CMVideoCodecType codecType) : codecType_(codecType) {
    if (@available(macOS 11.0, *)) {
      VTRegisterSupplementalVideoDecoderIfAvailable(codecType_);
    }
  }

  ~MacVideoDecoder() {
    std::lock_guard<std::mutex> decodeLock(decodeMutex_);
    ReleaseSession();
    if (formatDesc_ != nullptr) {
      CFRelease(formatDesc_);
    }
  }

  // Decodes a video packet. Returns DECODE_FRAME_PRODUCED (1) if a decoded frame was written to
  // outputPixels, DECODE_NO_FRAME (0) if no frame was produced, or DECODE_ERROR (-1) on failure.
  jint Decode(const uint8_t* packetData, size_t packetSize,
              jobject outputBufferRef, uint8_t* outputPixels, size_t outputCapacity,
              int32_t* outWidth, int32_t* outHeight) {
    std::lock_guard<std::mutex> decodeLock(decodeMutex_);
    if (packetSize == 0) {
      return DECODE_NO_FRAME;
    }

    switch (codecType_) {
      case kCodecTypeAVC:
        return DecodeAvc(packetData, packetSize, outputBufferRef, outputPixels, outputCapacity, outWidth, outHeight);
      case kCodecTypeHEVC:
        return DecodeHevc(packetData, packetSize, outputBufferRef, outputPixels, outputCapacity, outWidth, outHeight);
      case kCodecTypeVP9:
        return DecodeVp9(packetData, packetSize, outputBufferRef, outputPixels, outputCapacity, outWidth, outHeight);
      case kCodecTypeAV1:
        return DecodeAv1(packetData, packetSize, outputBufferRef, outputPixels, outputCapacity, outWidth, outHeight);
      default:
        return DECODE_ERROR;
    }
  }

private:
  jint DecodeAvc(const uint8_t* packetData, size_t packetSize,
                 jobject outputBufferRef, uint8_t* outputPixels, size_t outputCapacity,
                 int32_t* outWidth, int32_t* outHeight) {
    std::vector<NalUnit> nalUnits = ParseAnnexBNalUnits(packetData, packetSize, /*isHevc=*/false);
    if (nalUnits.empty()) {
      return hasDecodedFrames_ ? DECODE_NO_FRAME : DECODE_ERROR;
    }

    bool paramsUpdated = false;
    std::vector<uint8_t> sampleData;

    for (const auto& unit : nalUnits) {
      if (unit.type == AVC_NAL_SPS) {
        if (sps_.size() != unit.size || memcmp(sps_.data(), unit.data, unit.size) != 0) {
          sps_.assign(unit.data, unit.data + unit.size);
          paramsUpdated = true;
        }
      } else if (unit.type == AVC_NAL_PPS) {
        if (pps_.size() != unit.size || memcmp(pps_.data(), unit.data, unit.size) != 0) {
          pps_.assign(unit.data, unit.data + unit.size);
          paramsUpdated = true;
        }
      } else if (unit.type != AVC_NAL_AUD) {
        AppendLengthPrefixedNalUnit(&sampleData, unit);
      }
    }

    if (paramsUpdated && !sps_.empty() && !pps_.empty()) {
      if (!UpdateAvcFormatDescription()) {
        return DECODE_ERROR;
      }
    }

    if (sampleData.empty() || formatDesc_ == nullptr) {
      return DECODE_NO_FRAME;
    }

    return DecodeSampleBuffer(sampleData.data(), sampleData.size(), outputBufferRef, outputPixels, outputCapacity, outWidth, outHeight);
  }

  jint DecodeHevc(const uint8_t* packetData, size_t packetSize,
                  jobject outputBufferRef, uint8_t* outputPixels, size_t outputCapacity,
                  int32_t* outWidth, int32_t* outHeight) {
    std::vector<NalUnit> nalUnits = ParseAnnexBNalUnits(packetData, packetSize, /*isHevc=*/true);
    if (nalUnits.empty()) {
      return hasDecodedFrames_ ? DECODE_NO_FRAME : DECODE_ERROR;
    }

    bool paramsUpdated = false;
    std::vector<uint8_t> sampleData;

    for (const auto& unit : nalUnits) {
      if (unit.type == HEVC_NAL_VPS) {
        if (vps_.size() != unit.size || memcmp(vps_.data(), unit.data, unit.size) != 0) {
          vps_.assign(unit.data, unit.data + unit.size);
          paramsUpdated = true;
        }
      } else if (unit.type == HEVC_NAL_SPS) {
        if (sps_.size() != unit.size || memcmp(sps_.data(), unit.data, unit.size) != 0) {
          sps_.assign(unit.data, unit.data + unit.size);
          paramsUpdated = true;
        }
      } else if (unit.type == HEVC_NAL_PPS) {
        if (pps_.size() != unit.size || memcmp(pps_.data(), unit.data, unit.size) != 0) {
          pps_.assign(unit.data, unit.data + unit.size);
          paramsUpdated = true;
        }
      } else if (unit.type != HEVC_NAL_AUD) {
        AppendLengthPrefixedNalUnit(&sampleData, unit);
      }
    }

    if (paramsUpdated && !vps_.empty() && !sps_.empty() && !pps_.empty()) {
      if (!UpdateHevcFormatDescription()) {
        return DECODE_ERROR;
      }
    }

    if (sampleData.empty() || formatDesc_ == nullptr) {
      return DECODE_NO_FRAME;
    }

    return DecodeSampleBuffer(sampleData.data(), sampleData.size(), outputBufferRef, outputPixels, outputCapacity, outWidth, outHeight);
  }

  jint DecodeVp9(const uint8_t* packetData, size_t packetSize,
                 jobject outputBufferRef, uint8_t* outputPixels, size_t outputCapacity,
                 int32_t* outWidth, int32_t* outHeight) {
    Vp9FrameHeaderInfo info;
    if (!ParseVp9Header(packetData, packetSize, &info)) {
      return hasDecodedFrames_ ? DECODE_NO_FRAME : DECODE_ERROR;
    }

    if (info.isKeyFrame) {
      std::vector<uint8_t> vpcc = BuildVpccAtom(info);
      if (formatDesc_ == nullptr || info.width != currentWidth_ || info.height != currentHeight_ || vpcc != codecConfig_) {
        if (!UpdateFormatDescriptionWithAtom(kCodecTypeVP9, info.width, info.height, @"vpcC", vpcc)) {
          return DECODE_ERROR;
        }
      }
    }

    if (formatDesc_ == nullptr) {
      return DECODE_NO_FRAME;
    }

    return DecodeSampleBuffer(packetData, packetSize, outputBufferRef, outputPixels, outputCapacity, outWidth, outHeight);
  }

  jint DecodeAv1(const uint8_t* packetData, size_t packetSize,
                 jobject outputBufferRef, uint8_t* outputPixels, size_t outputCapacity,
                 int32_t* outWidth, int32_t* outHeight) {
    std::vector<uint8_t> sampleData;
    bool hasFrameData = false;
    size_t offset = 0;

    while (offset < packetSize) {
      uint8_t header = packetData[offset];
      if ((header & 0x80) != 0) { // obu_forbidden_bit must be 0
        return hasDecodedFrames_ ? DECODE_NO_FRAME : DECODE_ERROR;
      }
      uint8_t obuType = (header >> 3) & 0x0F;
      bool hasExtension = (header & 0x04) != 0;
      bool hasSizeField = (header & 0x02) != 0;
      size_t headerLen = 1 + (hasExtension ? 1 : 0);
      if (offset + headerLen > packetSize) {
        return hasDecodedFrames_ ? DECODE_NO_FRAME : DECODE_ERROR;
      }

      size_t payloadSize = 0;
      if (hasSizeField) {
        size_t lebBytes = 0;
        if (!ReadLeb128(packetData + offset + headerLen, packetSize - offset - headerLen, &payloadSize, &lebBytes)) {
          return hasDecodedFrames_ ? DECODE_NO_FRAME : DECODE_ERROR;
        }
        headerLen += lebBytes;
        if (offset + headerLen + payloadSize > packetSize) {
          return hasDecodedFrames_ ? DECODE_NO_FRAME : DECODE_ERROR;
        }
      } else {
        payloadSize = packetSize - offset - headerLen;
      }

      size_t totalObuSize = headerLen + payloadSize;
      const uint8_t* obuStart = packetData + offset;
      const uint8_t* payloadStart = obuStart + headerLen;

      if (obuType == AV1_OBU_SEQUENCE_HEADER) {
        Av1SequenceHeaderInfo seqInfo;
        if (!ParseAv1SequenceHeader(payloadStart, payloadSize, &seqInfo)) {
          return DECODE_ERROR;
        }
        std::vector<uint8_t> av1c;
        av1c.reserve(4 + totalObuSize);
        av1c.push_back(0x81); // marker = 1, version = 1
        av1c.push_back(static_cast<uint8_t>(((seqInfo.seqProfile & 0x07) << 5) | (seqInfo.seqLevelIdx0 & 0x1F)));
        av1c.push_back(static_cast<uint8_t>(
            ((seqInfo.seqTier0 & 0x01) << 7) |
            ((seqInfo.highBitdepth & 0x01) << 6) |
            ((seqInfo.twelveBit & 0x01) << 5) |
            ((seqInfo.monochrome & 0x01) << 4) |
            ((seqInfo.chromaSubsamplingX & 0x01) << 3) |
            ((seqInfo.chromaSubsamplingY & 0x01) << 2) |
            (seqInfo.chromaSamplePosition & 0x03)));
        av1c.push_back(0x00);
        av1c.insert(av1c.end(), obuStart, obuStart + totalObuSize);

        if (formatDesc_ == nullptr || seqInfo.width != currentWidth_ || seqInfo.height != currentHeight_ || av1c != codecConfig_) {
          if (!UpdateFormatDescriptionWithAtom(kCodecTypeAV1, seqInfo.width, seqInfo.height, @"av1C", av1c)) {
            return DECODE_ERROR;
          }
        }
      }

      if (obuType != AV1_OBU_TEMPORAL_DELIMITER &&
          obuType != AV1_OBU_REDUNDANT_FRAME_HEADER &&
          obuType != AV1_OBU_PADDING) {
        sampleData.insert(sampleData.end(), obuStart, obuStart + totalObuSize);
        if (obuType == AV1_OBU_FRAME || obuType == AV1_OBU_TILE_GROUP) {
          hasFrameData = true;
        }
      }

      offset += totalObuSize;
    }

    if (!hasFrameData || sampleData.empty() || formatDesc_ == nullptr) {
      return DECODE_NO_FRAME;
    }

    return DecodeSampleBuffer(sampleData.data(), sampleData.size(), outputBufferRef, outputPixels, outputCapacity, outWidth, outHeight);
  }

  static void AppendLengthPrefixedNalUnit(std::vector<uint8_t>* dest, const NalUnit& unit) {
    uint32_t lenBe = __builtin_bswap32(static_cast<uint32_t>(unit.size));
    const uint8_t* lenPtr = reinterpret_cast<const uint8_t*>(&lenBe);
    dest->insert(dest->end(), lenPtr, lenPtr + 4);
    dest->insert(dest->end(), unit.data, unit.data + unit.size);
  }

  jint DecodeSampleBuffer(const uint8_t* sampleData, size_t sampleSize,
                          jobject outputBufferRef, uint8_t* outputPixels, size_t outputCapacity,
                          int32_t* outWidth, int32_t* outHeight) {
    if (session_ == nullptr) {
      if (!CreateSession()) {
        return DECODE_ERROR;
      }
    }

    CMBlockBufferRef blockBuffer = nullptr;
    OSStatus status = CMBlockBufferCreateWithMemoryBlock(
        kCFAllocatorDefault,
        nullptr,
        sampleSize,
        kCFAllocatorDefault,
        nullptr,
        0,
        sampleSize,
        0,
        &blockBuffer);
    if (status != noErr || blockBuffer == nullptr) {
      return DECODE_ERROR;
    }

    status = CMBlockBufferReplaceDataBytes(
        sampleData,
        blockBuffer,
        0,
        sampleSize);
    if (status != noErr) {
      CFRelease(blockBuffer);
      return DECODE_ERROR;
    }

    CMSampleBufferRef sampleBuffer = nullptr;
    status = CMSampleBufferCreateReady(
        kCFAllocatorDefault,
        blockBuffer,
        formatDesc_,
        1,
        0,
        nullptr,
        1,
        &sampleSize,
        &sampleBuffer);

    jint result = DECODE_NO_FRAME;
    if (status == noErr && sampleBuffer != nullptr) {
      FrameDecodeContext frameContext;
      frameContext.outputBufferRef = outputBufferRef;
      frameContext.outputPixels = outputPixels;
      frameContext.outputCapacity = outputCapacity;

      // Decode synchronously (flags = 0) and explicitly wait for any asynchronous
      // VideoToolbox callback invocations to complete before returning.
      VTDecodeFrameFlags decodeFlags = 0;
      VTDecodeInfoFlags flagOut = 0;
      status = VTDecompressionSessionDecodeFrame(
          session_,
          sampleBuffer,
          decodeFlags,
          &frameContext,
          &flagOut);
      VTDecompressionSessionWaitForAsynchronousFrames(session_);

      std::lock_guard<std::mutex> contextLock(frameContext.mutex);
      frameContext.outputPixels = nullptr;
      frameContext.outputCapacity = 0;
      if (status == noErr && frameContext.frameProduced) {
        hasDecodedFrames_ = true;
        *outWidth = frameContext.decodedWidth;
        *outHeight = frameContext.decodedHeight;
        result = DECODE_FRAME_PRODUCED;
      } else if (status != noErr && !hasDecodedFrames_) {
        result = DECODE_ERROR;
      }
    } else if (!hasDecodedFrames_) {
      result = DECODE_ERROR;
    }

    if (sampleBuffer != nullptr) {
      CFRelease(sampleBuffer);
    }
    if (blockBuffer != nullptr) {
      CFRelease(blockBuffer);
    }

    return result;
  }

  bool UpdateAvcFormatDescription() {
    const uint8_t* parameterSetPointers[] = { sps_.data(), pps_.data() };
    const size_t parameterSetSizes[] = { sps_.size(), pps_.size() };

    CMVideoFormatDescriptionRef newFormatDesc = nullptr;
    OSStatus status = CMVideoFormatDescriptionCreateFromH264ParameterSets(
        kCFAllocatorDefault,
        2,
        parameterSetPointers,
        parameterSetSizes,
        4, // NALUnitHeaderLength
        &newFormatDesc);

    if (status != noErr || newFormatDesc == nullptr) {
      return false;
    }

    ApplyNewFormatDescription(newFormatDesc);
    return true;
  }

  bool UpdateHevcFormatDescription() {
    const uint8_t* parameterSetPointers[] = { vps_.data(), sps_.data(), pps_.data() };
    const size_t parameterSetSizes[] = { vps_.size(), sps_.size(), pps_.size() };

    CMVideoFormatDescriptionRef newFormatDesc = nullptr;
    OSStatus status = CMVideoFormatDescriptionCreateFromHEVCParameterSets(
        kCFAllocatorDefault,
        3,
        parameterSetPointers,
        parameterSetSizes,
        4, // NALUnitHeaderLength
        nullptr,
        &newFormatDesc);

    if (status != noErr || newFormatDesc == nullptr) {
      return false;
    }

    ApplyNewFormatDescription(newFormatDesc);
    return true;
  }

  bool UpdateFormatDescriptionWithAtom(
      CMVideoCodecType codecType, int32_t width, int32_t height,
      NSString* atomName, const std::vector<uint8_t>& atomData) {
    NSData* data = [NSData dataWithBytes:atomData.data() length:atomData.size()];
    NSDictionary* extensions = @{
      (id)kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms: @{
        atomName: data
      }
    };

    CMVideoFormatDescriptionRef newFormatDesc = nullptr;
    OSStatus status = CMVideoFormatDescriptionCreate(
        kCFAllocatorDefault,
        codecType,
        width,
        height,
        (__bridge CFDictionaryRef)extensions,
        &newFormatDesc);

    if (status != noErr || newFormatDesc == nullptr) {
      return false;
    }

    ApplyNewFormatDescription(newFormatDesc);
    currentWidth_ = width;
    currentHeight_ = height;
    codecConfig_ = atomData;
    return true;
  }

  void ApplyNewFormatDescription(CMVideoFormatDescriptionRef newFormatDesc) {
    if (session_ != nullptr && !VTDecompressionSessionCanAcceptFormatDescription(session_, newFormatDesc)) {
      ReleaseSession();
    }
    if (formatDesc_ != nullptr) {
      CFRelease(formatDesc_);
    }
    formatDesc_ = newFormatDesc;
  }

  bool CreateSession() {
    ReleaseSession();

    NSDictionary* destinationImageBufferAttributes = @{
      (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
      (id)kCVPixelBufferMetalCompatibilityKey: @NO
    };

    VTDecompressionOutputCallbackRecord callbackRecord;
    callbackRecord.decompressionOutputCallback = OnDecompressionOutput;
    callbackRecord.decompressionOutputRefCon = this;

    OSStatus status = VTDecompressionSessionCreate(
        kCFAllocatorDefault,
        formatDesc_,
        nullptr,
        (__bridge CFDictionaryRef)destinationImageBufferAttributes,
        &callbackRecord,
        &session_);

    if (status != noErr || session_ == nullptr) {
      return false;
    }

    VTSessionSetProperty(session_, kVTDecompressionPropertyKey_RealTime, kCFBooleanTrue);
    return true;
  }

  void ReleaseSession() {
    if (session_ != nullptr) {
      VTDecompressionSessionWaitForAsynchronousFrames(session_);
      VTDecompressionSessionInvalidate(session_);
      CFRelease(session_);
      session_ = nullptr;
    }
  }

  static void OnDecompressionOutput(
      void* decompressionOutputRefCon,
      void* sourceFrameRefCon,
      OSStatus status,
      VTDecodeInfoFlags infoFlags,
      CVImageBufferRef imageBuffer,
      CMTime presentationTimeStamp,
      CMTime presentationDuration) {
    auto* frameContext = static_cast<FrameDecodeContext*>(sourceFrameRefCon);
    if (frameContext != nullptr) {
      HandleFrame(frameContext, status, imageBuffer);
    }
  }

  static void HandleFrame(FrameDecodeContext* frameContext, OSStatus status, CVImageBufferRef imageBuffer) {
    if (status != noErr || imageBuffer == nullptr) {
      return;
    }

    std::lock_guard<std::mutex> lock(frameContext->mutex);
    if (frameContext->outputPixels == nullptr) {
      return;
    }

    CVPixelBufferRef pixelBuffer = static_cast<CVPixelBufferRef>(imageBuffer);
    if (CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess) {
      return;
    }

    size_t width = CVPixelBufferGetWidth(pixelBuffer);
    size_t height = CVPixelBufferGetHeight(pixelBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer);
    const auto* src = static_cast<const uint8_t*>(CVPixelBufferGetBaseAddress(pixelBuffer));

    size_t rowBytes = width * 4;
    size_t totalBytes = rowBytes * height;

    if (src != nullptr && totalBytes <= frameContext->outputCapacity) {
      if (bytesPerRow == rowBytes) {
        memcpy(frameContext->outputPixels, src, totalBytes);
      } else {
        for (size_t y = 0; y < height; ++y) {
          memcpy(frameContext->outputPixels + y * rowBytes, src + y * bytesPerRow, rowBytes);
        }
      }
      frameContext->decodedWidth = static_cast<int32_t>(width);
      frameContext->decodedHeight = static_cast<int32_t>(height);
      frameContext->frameProduced = true;
    }

    CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
  }

  const CMVideoCodecType codecType_;
  std::mutex decodeMutex_;
  std::vector<uint8_t> vps_;
  std::vector<uint8_t> sps_;
  std::vector<uint8_t> pps_;
  std::vector<uint8_t> codecConfig_;
  int32_t currentWidth_ = 0;
  int32_t currentHeight_ = 0;
  bool hasDecodedFrames_ = false;
  CMVideoFormatDescriptionRef formatDesc_ = nullptr;
  VTDecompressionSessionRef session_ = nullptr;
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_android_tools_idea_streaming_device_VideoDecoderMac_createNativeDecoder(
    JNIEnv* env, jclass clazz, jint codecType) {
  CMVideoCodecType type = static_cast<CMVideoCodecType>(codecType);
  if (type != kCodecTypeAV1 && type != kCodecTypeAVC && type != kCodecTypeHEVC && type != kCodecTypeVP9) {
    return 0;
  }
  return reinterpret_cast<jlong>(new MacVideoDecoder(type));
}

JNIEXPORT jint JNICALL
Java_com_android_tools_idea_streaming_device_VideoDecoderMac_decodeFrame(
    JNIEnv* env, jclass clazz, jlong handle,
    jobject packetBuffer, jint packetOffset, jint packetSize,
    jobject outputPixelBuffer, jint outputCapacity,
    jintArray outDimensions) {
  auto* decoder = reinterpret_cast<MacVideoDecoder*>(handle);
  if (decoder == nullptr || packetBuffer == nullptr || outputPixelBuffer == nullptr) {
    return DECODE_ERROR;
  }

  jlong packetCapacity = env->GetDirectBufferCapacity(packetBuffer);
  jlong outputPixelCapacity = env->GetDirectBufferCapacity(outputPixelBuffer);
  if (packetOffset < 0 || packetSize < 0 || outputCapacity < 0 ||
      static_cast<jlong>(packetOffset) + packetSize > packetCapacity ||
      outputCapacity > outputPixelCapacity) {
    return DECODE_ERROR;
  }

  const auto* packetBytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(packetBuffer));
  auto* outputBytes = static_cast<uint8_t*>(env->GetDirectBufferAddress(outputPixelBuffer));
  if (packetBytes == nullptr || outputBytes == nullptr) {
    return DECODE_ERROR;
  }

  jobject outputBufferRef = env->NewGlobalRef(outputPixelBuffer);
  if (outputBufferRef == nullptr) {
    return DECODE_ERROR;
  }

  int32_t width = 0;
  int32_t height = 0;
  jint result = decoder->Decode(
      packetBytes + packetOffset, static_cast<size_t>(packetSize),
      outputBufferRef, outputBytes, static_cast<size_t>(outputCapacity),
      &width, &height);

  env->DeleteGlobalRef(outputBufferRef);

  if (result == DECODE_FRAME_PRODUCED && outDimensions != nullptr) {
    jint dims[2] = { width, height };
    env->SetIntArrayRegion(outDimensions, 0, 2, dims);
  }

  return result;
}

JNIEXPORT void JNICALL
Java_com_android_tools_idea_streaming_device_VideoDecoderMac_destroyNativeDecoder(
    JNIEnv* env, jclass clazz, jlong handle) {
  auto* decoder = reinterpret_cast<MacVideoDecoder*>(handle);
  delete decoder;
}

} // extern "C"
