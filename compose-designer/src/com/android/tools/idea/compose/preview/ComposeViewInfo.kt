/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.compose.preview

import com.android.sdklib.AndroidCoordinate
import com.android.tools.rendering.compose.area as renderingArea
import com.android.tools.rendering.compose.containsPoint as renderingContainsPoint
import com.android.tools.rendering.compose.findAllHitsInFile as renderingFindAllHitsInFile
import com.android.tools.rendering.compose.findAllHitsWithPoint as renderingFindAllHitsWithPoint
import com.android.tools.rendering.compose.findHitWithDepth as renderingFindHitWithDepth
import com.android.tools.rendering.compose.findSmallestHit as renderingFindSmallestHit
import com.android.tools.rendering.compose.isEmpty as renderingIsEmpty
import com.android.tools.rendering.compose.isInFile as renderingIsInFile
import com.android.tools.rendering.compose.isNotEmpty as renderingIsNotEmpty
import com.google.common.annotations.VisibleForTesting

typealias ComposeViewInfo = com.android.tools.rendering.compose.ComposeViewInfo

typealias PxBounds = com.android.tools.rendering.compose.PxBounds

@VisibleForTesting
fun ComposeViewInfo.findHitWithDepth(x: Int, y: Int): Collection<Pair<Int, ComposeViewInfo>> = renderingFindHitWithDepth(x, y)

fun List<ComposeViewInfo>.findHitWithDepth(x: Int, y: Int): Collection<Pair<Int, ComposeViewInfo>> = renderingFindHitWithDepth(x, y)

fun ComposeViewInfo.findSmallestHit(
  @AndroidCoordinate x: Int,
  @AndroidCoordinate y: Int,
): Collection<ComposeViewInfo> = renderingFindSmallestHit(x, y)

fun List<ComposeViewInfo>.findSmallestHit(x: Int, y: Int): Collection<ComposeViewInfo> = renderingFindSmallestHit(x, y)

fun ComposeViewInfo.findAllHitsWithPoint(x: Int, y: Int): List<ComposeViewInfo> = renderingFindAllHitsWithPoint(x, y)

fun List<ComposeViewInfo>.findAllHitsWithPoint(x: Int, y: Int): Collection<ComposeViewInfo> = renderingFindAllHitsWithPoint(x, y)

fun ComposeViewInfo.findAllHitsInFile(fileName: String): List<ComposeViewInfo> = renderingFindAllHitsInFile(fileName)

fun List<ComposeViewInfo>.findAllHitsInFile(fileName: String): Collection<ComposeViewInfo> = renderingFindAllHitsInFile(fileName)

fun ComposeViewInfo.containsPoint(x: Int, y: Int): Boolean = renderingContainsPoint(x, y)

fun ComposeViewInfo.isInFile(fileName: String): Boolean = renderingIsInFile(fileName)

@VisibleForTesting fun PxBounds.containsPoint(@AndroidCoordinate x: Int, @AndroidCoordinate y: Int): Boolean = renderingContainsPoint(x, y)

@VisibleForTesting fun PxBounds.area(): Int = renderingArea()

@VisibleForTesting fun PxBounds.isEmpty(): Boolean = renderingIsEmpty()

@VisibleForTesting fun PxBounds.isNotEmpty(): Boolean = renderingIsNotEmpty()
