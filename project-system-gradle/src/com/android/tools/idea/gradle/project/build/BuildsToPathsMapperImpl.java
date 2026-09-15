/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.build;

import static com.android.tools.idea.gradle.util.BuildOutputUtil.getOutputFilesFromListingFile;
import static com.android.tools.idea.gradle.util.BuildOutputUtil.getOutputListingFileOrLogError;

import com.android.tools.idea.gradle.actions.BuildsToPathsMapper;
import com.android.tools.idea.gradle.model.IdeBuildTasksAndOutputInformation;
import com.android.tools.idea.gradle.model.IdeVariantBuildInformation;
import com.android.tools.idea.gradle.project.build.invoker.AssembleInvocationResult;
import com.android.tools.idea.gradle.project.model.GradleAndroidModel;
import com.android.tools.idea.gradle.util.OutputType;
import com.intellij.openapi.module.Module;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;

/**
 * <p> Generates a map from module/build variant to the location of generated apk or bundle,
 * if it's a single apk, returns the apk file;
 * if there're multiple apks, returns the parent folder of apk files;
 * if it's app bundle, returns the bundle file.
 */
public class BuildsToPathsMapperImpl extends BuildsToPathsMapper {
  @Override
  @NotNull
  public Map<String, File> getBuildsToPaths(@NotNull AssembleInvocationResult assembleResult,
                                            @NotNull List<String> buildVariants,
                                            @NotNull Collection<Module> modules,
                                            boolean isAppBundle) {
    boolean isSigned = !buildVariants.isEmpty();
    if (isSigned) {
      assert modules.size() == 1;
    }

    TreeMap<String, File> buildsToPathsCollector = new TreeMap<>();

    for (Module module : modules) {
      GradleAndroidModel androidModel = GradleAndroidModel.get(module);
      if (androidModel == null) {
        continue;
      }

      if (!isSigned) {
        buildVariants = Collections.singletonList(androidModel.getSelectedVariant().getName());
      }

      for (String buildVariant : buildVariants) {
        collectBuildsToPaths(androidModel, module, buildVariant, buildsToPathsCollector, isAppBundle, isSigned
        );
      }
    }

    return buildsToPathsCollector;
  }

  private static void collectBuildsToPaths(@NotNull GradleAndroidModel androidModel,
                                           @NotNull Module module,
                                           @NotNull String buildVariant,
                                           @NotNull Map<String, File> buildsToPathsCollector,
                                           boolean isAppBundle,
                                           boolean isSigned) {
    File outputFolderOrFile = null;
    OutputType outputType = isAppBundle ? OutputType.Bundle : OutputType.Apk;
    IdeBuildTasksAndOutputInformation outputInformation =
      androidModel.getAndroidProject().getVariantsBuildInformation().stream()
        .filter(it -> it.getVariantName().equals(buildVariant))
        .findFirst()
        .map(IdeVariantBuildInformation::getBuildInformation)
        .orElse(null);
    List<File> outputFiles = null;
    if (outputInformation != null) {
      String outputListingFile = getOutputListingFileOrLogError(outputInformation, outputType);
      if (outputListingFile != null) {
        outputFiles = getOutputFilesFromListingFile(outputListingFile);
      }
    }
    outputFolderOrFile =
      outputFiles != null
      ? outputFiles.size() > 1
        ? outputFiles.get(0).getParentFile()
        : !outputFiles.isEmpty() ? outputFiles.get(0) : null
      : null;

    if (outputFolderOrFile == null) {
      return;
    }

    buildsToPathsCollector.put(isSigned ? buildVariant : module.getName(), outputFolderOrFile);
  }
}
