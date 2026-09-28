/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.idea.gradle.structure.configurables.android.dependencies.project.treeview

import com.android.tools.idea.gradle.model.IdeTestSuiteArtifactName
import com.android.tools.idea.gradle.model.WellKnownIdeArtifactName
import com.android.tools.idea.gradle.structure.configurables.ui.PsUISettings
import com.android.tools.idea.gradle.structure.configurables.ui.treeview.AbstractPsModelNode
import com.android.tools.idea.gradle.structure.configurables.ui.treeview.AbstractPsNode
import com.android.tools.idea.gradle.structure.model.android.PsAndroidArtifact
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.roots.ui.CellAppearanceEx
import com.intellij.openapi.util.text.StringUtil.isEmpty
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes.GRAY_ATTRIBUTES
import com.intellij.ui.treeStructure.SimpleNode

class TargetAndroidArtifactNode
internal constructor(val artifact: PsAndroidArtifact, private val myVersion: String?, uiSettings: PsUISettings) :
  AbstractPsModelNode<PsAndroidArtifact>(uiSettings), CellAppearanceEx {
  private var myChildren = emptyList<AbstractPsNode>()

  override val models: List<PsAndroidArtifact> = listOf(artifact)

  init {
    autoExpandNode = false
    updateNameAndIcon()
  }

  override fun getChildren(): Array<SimpleNode> {
    return myChildren.toTypedArray()
  }

  internal fun setChildren(children: List<AbstractPsNode>) {
    myChildren = children
  }

  override fun update(presentation: PresentationData) {
    super.update(presentation)
    presentation.presentableText = text
  }

  override fun nameOf(model: PsAndroidArtifact): String = buildString {
    append(model.parent.name)
    when (model.resolvedName) {
      WellKnownIdeArtifactName.MAIN -> Unit
      WellKnownIdeArtifactName.ANDROID_TEST -> append(" (androidTest)")
      WellKnownIdeArtifactName.UNIT_TEST -> append(" (test)")
      WellKnownIdeArtifactName.TEST_FIXTURES -> append(" (testFixtures)")
      WellKnownIdeArtifactName.SCREENSHOT_TEST -> append(" (screenshotTest)")
      // Unlike androidTest or test, test suites do not have hardcoded sourceSet names. Their
      // sourceSet names are dynamic and stored in IdeTestSuiteArtifactName, which is fetched here
      // via `model.resolvedName.name` (e.g. "debug (firstTest)").
      is IdeTestSuiteArtifactName -> append(" (${model.resolvedName.name})")
    }
  }

  override fun getText(): String = name

  override fun customize(component: SimpleColoredComponent) {
    if (!isEmpty(myVersion)) {
      component.append(" ")
      component.append("($myVersion)", GRAY_ATTRIBUTES)
    }
  }
}
