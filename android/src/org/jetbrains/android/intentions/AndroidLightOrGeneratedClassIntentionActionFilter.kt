/*
 * Copyright (C) 2019 The Android Open Source Project
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
package org.jetbrains.android.intentions

import com.android.SdkConstants
import com.intellij.codeInsight.daemon.impl.IntentionActionFilter
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.java.actions.CreateMemberModCommandAction
import com.intellij.openapi.roots.JavaProjectRootsUtil.isInGeneratedCode
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import org.jetbrains.android.augment.AndroidLightClassBase

/**
 * Filters out intentions that would modify light classes or generated Android classes.
 *
 * For light classes this is not possible (and fails when attempted), for generated classes like `BuildConfig` it's confusing to offer this
 * to users, since the right way to add new fields to it is via build.gradle.
 */
class AndroidLightOrGeneratedClassIntentionActionFilter : IntentionActionFilter {

  override fun accept(intentionAction: IntentionAction, file: PsiFile?): Boolean {
    val targetPsiClass = getTargetClass(intentionAction, file) ?: return true

    return when {
      targetPsiClass is AndroidLightClassBase -> false
      targetPsiClass.name == SdkConstants.FN_BUILD_CONFIG_BASE -> {
        val virtualFile = targetPsiClass.containingFile?.virtualFile ?: return true
        !isInGeneratedCode(virtualFile, targetPsiClass.project)
      }
      else -> true
    }
  }

  private fun getTargetClass(intentionAction: IntentionAction, file: PsiFile?): PsiClass? {
    // ModCommand-based actions (e.g. CreateFieldAction) return null from getElementToMakeWritable.
    // Instead, for CreateMemberModCommandActions (e.g. CreateFieldAction), we can get the targetClass.
    @Suppress("UnstableApiUsage")
    (intentionAction.asModCommandAction() as? CreateMemberModCommandAction)?.let {
      return it.targetClass
    }
    // Fall back to the old code path, which may still work for non-ModCommand intentions.
    return file?.let(intentionAction::getElementToMakeWritable) as? PsiClass
  }
}
