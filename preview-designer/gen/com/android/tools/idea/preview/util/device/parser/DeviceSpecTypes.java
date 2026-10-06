/*
 * Copyright (C) 2024 The Android Open Source Project
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

// ATTENTION: This file has been automatically generated from
// preview-designer/src/com/android/tools/idea/preview/util/device/parser/device.bnf.
// Do not edit it manually.
package com.android.tools.idea.preview.util.device.parser;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.PsiElement;
import com.intellij.lang.ASTNode;
import com.android.tools.idea.preview.util.device.parser.impl.*;

public interface DeviceSpecTypes {

  IElementType BOOLEAN_T = new DeviceSpecElementType("BOOLEAN_T");
  IElementType CHIN_SIZE_PARAM = new DeviceSpecElementType("CHIN_SIZE_PARAM");
  IElementType CUTOUT_PARAM = new DeviceSpecElementType("CUTOUT_PARAM");
  IElementType CUTOUT_T = new DeviceSpecElementType("CUTOUT_T");
  IElementType DPI_PARAM = new DeviceSpecElementType("DPI_PARAM");
  IElementType HAS_CAMERA_PARAM = new DeviceSpecElementType("HAS_CAMERA_PARAM");
  IElementType HAS_MICROPHONE_PARAM = new DeviceSpecElementType("HAS_MICROPHONE_PARAM");
  IElementType HEIGHT_PARAM = new DeviceSpecElementType("HEIGHT_PARAM");
  IElementType ID_PARAM = new DeviceSpecElementType("ID_PARAM");
  IElementType IS_ROUND_PARAM = new DeviceSpecElementType("IS_ROUND_PARAM");
  IElementType KEYBOARD_KIND_PARAM = new DeviceSpecElementType("KEYBOARD_KIND_PARAM");
  IElementType KEYBOARD_KIND_T = new DeviceSpecElementType("KEYBOARD_KIND_T");
  IElementType NAME_PARAM = new DeviceSpecElementType("NAME_PARAM");
  IElementType NAVIGATION_PARAM = new DeviceSpecElementType("NAVIGATION_PARAM");
  IElementType NAVIGATION_T = new DeviceSpecElementType("NAVIGATION_T");
  IElementType ORIENTATION_PARAM = new DeviceSpecElementType("ORIENTATION_PARAM");
  IElementType ORIENTATION_T = new DeviceSpecElementType("ORIENTATION_T");
  IElementType PARAM = new DeviceSpecElementType("PARAM");
  IElementType PARENT_PARAM = new DeviceSpecElementType("PARENT_PARAM");
  IElementType POINTER_PRECISION_PARAM = new DeviceSpecElementType("POINTER_PRECISION_PARAM");
  IElementType POINTER_PRECISION_T = new DeviceSpecElementType("POINTER_PRECISION_T");
  IElementType SIZE_T = new DeviceSpecElementType("SIZE_T");
  IElementType SPEC = new DeviceSpecElementType("SPEC");
  IElementType UNIT = new DeviceSpecElementType("UNIT");
  IElementType VIEWING_DISTANCE_PARAM = new DeviceSpecElementType("VIEWING_DISTANCE_PARAM");
  IElementType VIEWING_DISTANCE_T = new DeviceSpecElementType("VIEWING_DISTANCE_T");
  IElementType WIDTH_PARAM = new DeviceSpecElementType("WIDTH_PARAM");
  IElementType WINDOW_POSTURE_PARAM = new DeviceSpecElementType("WINDOW_POSTURE_PARAM");
  IElementType WINDOW_POSTURE_T = new DeviceSpecElementType("WINDOW_POSTURE_T");

  IElementType CHIN_SIZE_KEYWORD = new DeviceSpecTokenType("chinSize");
  IElementType COLON = new DeviceSpecTokenType(":");
  IElementType COMMA = new DeviceSpecTokenType(",");
  IElementType CUTOUT_CORNER_KEYWORD = new DeviceSpecTokenType("corner");
  IElementType CUTOUT_DOUBLE_KEYWORD = new DeviceSpecTokenType("double");
  IElementType CUTOUT_HOLE_KEYWORD = new DeviceSpecTokenType("punch_hole");
  IElementType CUTOUT_KEYWORD = new DeviceSpecTokenType("cutout");
  IElementType CUTOUT_NONE_KEYWORD = new DeviceSpecTokenType("none");
  IElementType CUTOUT_TALL_KEYWORD = new DeviceSpecTokenType("tall");
  IElementType DISTANCE_FAR_KEYWORD = new DeviceSpecTokenType("far");
  IElementType DISTANCE_MEDIUM_KEYWORD = new DeviceSpecTokenType("medium");
  IElementType DISTANCE_NEAR_KEYWORD = new DeviceSpecTokenType("near");
  IElementType DP = new DeviceSpecTokenType("dp");
  IElementType DPI_KEYWORD = new DeviceSpecTokenType("dpi");
  IElementType EQUALS = new DeviceSpecTokenType("=");
  IElementType FALSE = new DeviceSpecTokenType("false");
  IElementType HAS_CAMERA_KEYWORD = new DeviceSpecTokenType("hasCamera");
  IElementType HAS_MICROPHONE_KEYWORD = new DeviceSpecTokenType("hasMicrophone");
  IElementType HEIGHT_KEYWORD = new DeviceSpecTokenType("height");
  IElementType ID_KEYWORD = new DeviceSpecTokenType("id");
  IElementType IS_ROUND_KEYWORD = new DeviceSpecTokenType("isRound");
  IElementType KEYBOARD_KIND_KEYWORD = new DeviceSpecTokenType("keyboardKind");
  IElementType KEYBOARD_PHYSICAL_KEYWORD = new DeviceSpecTokenType("physical");
  IElementType KEYBOARD_VIRTUAL_KEYWORD = new DeviceSpecTokenType("virtual");
  IElementType LANDSCAPE_KEYWORD = new DeviceSpecTokenType("landscape");
  IElementType NAME_KEYWORD = new DeviceSpecTokenType("name");
  IElementType NAVIGATION_KEYWORD = new DeviceSpecTokenType("navigation");
  IElementType NAV_BUTTONS_KEYWORD = new DeviceSpecTokenType("buttons");
  IElementType NAV_GESTURE_KEYWORD = new DeviceSpecTokenType("gesture");
  IElementType NUMERIC_T = new DeviceSpecTokenType("NUMERIC_T");
  IElementType ORIENTATION_KEYWORD = new DeviceSpecTokenType("orientation");
  IElementType PARENT_KEYWORD = new DeviceSpecTokenType("parent");
  IElementType POINTER_BLUNT_KEYWORD = new DeviceSpecTokenType("blunt");
  IElementType POINTER_COARSE_KEYWORD = new DeviceSpecTokenType("coarse");
  IElementType POINTER_FINE_KEYWORD = new DeviceSpecTokenType("fine");
  IElementType POINTER_PRECISION_KEYWORD = new DeviceSpecTokenType("pointerPrecision");
  IElementType PORTRAIT_KEYWORD = new DeviceSpecTokenType("portrait");
  IElementType POSTURE_BOOK_KEYWORD = new DeviceSpecTokenType("book");
  IElementType POSTURE_FLAT_KEYWORD = new DeviceSpecTokenType("flat");
  IElementType POSTURE_TABLETOP_KEYWORD = new DeviceSpecTokenType("tabletop");
  IElementType PX = new DeviceSpecTokenType("px");
  IElementType SPEC_KEYWORD = new DeviceSpecTokenType("spec");
  IElementType SQUARE_KEYWORD = new DeviceSpecTokenType("square");
  IElementType STRING_T = new DeviceSpecTokenType("STRING_T");
  IElementType TRUE = new DeviceSpecTokenType("true");
  IElementType VIEWING_DISTANCE_KEYWORD = new DeviceSpecTokenType("viewingDistance");
  IElementType WIDTH_KEYWORD = new DeviceSpecTokenType("width");
  IElementType WINDOW_POSTURE_KEYWORD = new DeviceSpecTokenType("windowPosture");

  class Factory {
    public static PsiElement createElement(ASTNode node) {
      IElementType type = node.getElementType();
      if (type == BOOLEAN_T) {
        return new DeviceSpecBooleanTImpl(node);
      }
      else if (type == CHIN_SIZE_PARAM) {
        return new DeviceSpecChinSizeParamImpl(node);
      }
      else if (type == CUTOUT_PARAM) {
        return new DeviceSpecCutoutParamImpl(node);
      }
      else if (type == CUTOUT_T) {
        return new DeviceSpecCutoutTImpl(node);
      }
      else if (type == DPI_PARAM) {
        return new DeviceSpecDpiParamImpl(node);
      }
      else if (type == HAS_CAMERA_PARAM) {
        return new DeviceSpecHasCameraParamImpl(node);
      }
      else if (type == HAS_MICROPHONE_PARAM) {
        return new DeviceSpecHasMicrophoneParamImpl(node);
      }
      else if (type == HEIGHT_PARAM) {
        return new DeviceSpecHeightParamImpl(node);
      }
      else if (type == ID_PARAM) {
        return new DeviceSpecIdParamImpl(node);
      }
      else if (type == IS_ROUND_PARAM) {
        return new DeviceSpecIsRoundParamImpl(node);
      }
      else if (type == KEYBOARD_KIND_PARAM) {
        return new DeviceSpecKeyboardKindParamImpl(node);
      }
      else if (type == KEYBOARD_KIND_T) {
        return new DeviceSpecKeyboardKindTImpl(node);
      }
      else if (type == NAME_PARAM) {
        return new DeviceSpecNameParamImpl(node);
      }
      else if (type == NAVIGATION_PARAM) {
        return new DeviceSpecNavigationParamImpl(node);
      }
      else if (type == NAVIGATION_T) {
        return new DeviceSpecNavigationTImpl(node);
      }
      else if (type == ORIENTATION_PARAM) {
        return new DeviceSpecOrientationParamImpl(node);
      }
      else if (type == ORIENTATION_T) {
        return new DeviceSpecOrientationTImpl(node);
      }
      else if (type == PARENT_PARAM) {
        return new DeviceSpecParentParamImpl(node);
      }
      else if (type == POINTER_PRECISION_PARAM) {
        return new DeviceSpecPointerPrecisionParamImpl(node);
      }
      else if (type == POINTER_PRECISION_T) {
        return new DeviceSpecPointerPrecisionTImpl(node);
      }
      else if (type == SIZE_T) {
        return new DeviceSpecSizeTImpl(node);
      }
      else if (type == SPEC) {
        return new DeviceSpecSpecImpl(node);
      }
      else if (type == UNIT) {
        return new DeviceSpecUnitImpl(node);
      }
      else if (type == VIEWING_DISTANCE_PARAM) {
        return new DeviceSpecViewingDistanceParamImpl(node);
      }
      else if (type == VIEWING_DISTANCE_T) {
        return new DeviceSpecViewingDistanceTImpl(node);
      }
      else if (type == WIDTH_PARAM) {
        return new DeviceSpecWidthParamImpl(node);
      }
      else if (type == WINDOW_POSTURE_PARAM) {
        return new DeviceSpecWindowPostureParamImpl(node);
      }
      else if (type == WINDOW_POSTURE_T) {
        return new DeviceSpecWindowPostureTImpl(node);
      }
      throw new AssertionError("Unknown element type: " + type);
    }
  }
}
