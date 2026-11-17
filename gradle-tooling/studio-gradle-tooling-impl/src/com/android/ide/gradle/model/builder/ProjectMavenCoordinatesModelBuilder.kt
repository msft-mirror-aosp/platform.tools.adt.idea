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
package com.android.ide.gradle.model.builder

import java.io.Serializable
import org.gradle.api.Project
import org.gradle.tooling.provider.model.ToolingModelBuilder

class ProjectMavenCoordinatesModelBuilder : ToolingModelBuilder {
  override fun canBuild(modelName: String): Boolean = modelName == ProjectMavenCoordinates::class.java.name

  override fun buildAll(modelName: String, project: Project): Any {
    check(canBuild(modelName)) { "Unexpected model name requested: $modelName" }
    // project.group and project.version are typed as Any in Gradle (e.g. Groovy GString or custom version objects), so convert eagerly to
    // String for serialization. Collect unconditionally (including blank group or "unspecified" version) so matching handles filtering.
    return ProjectMavenCoordinatesImpl(
      group = project.group?.toString().orEmpty(),
      name = project.name,
      version = project.version?.toString() ?: Project.DEFAULT_VERSION,
    )
  }
}

interface ProjectMavenCoordinates {
  val group: String
  val name: String
  val version: String
}

data class ProjectMavenCoordinatesImpl(override val group: String, override val name: String, override val version: String) :
  ProjectMavenCoordinates, Serializable
