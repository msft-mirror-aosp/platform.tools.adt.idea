/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.testartifacts.scopes;

import static com.android.tools.idea.projectsystem.gradle.GradleProjectPathKt.getGradleProjectPath;
import static com.android.tools.idea.projectsystem.gradle.LinkedAndroidModuleGroupUtilsKt.getAndroidTestModule;
import static com.android.tools.idea.projectsystem.gradle.LinkedAndroidModuleGroupUtilsKt.getMainModule;
import static com.android.tools.idea.projectsystem.gradle.LinkedAndroidModuleGroupUtilsKt.getScreenshotTestModule;
import static com.android.tools.idea.projectsystem.gradle.LinkedAndroidModuleGroupUtilsKt.getTestSuiteModules;
import static com.android.tools.idea.projectsystem.gradle.LinkedAndroidModuleGroupUtilsKt.getUnitTestModule;
import static com.android.tools.idea.projectsystem.ProjectSystemUtil.getModuleSystem;

import com.android.tools.idea.gradle.model.IdeTestSuite;
import com.android.tools.idea.gradle.model.IdeTestSuiteKt;
import com.android.tools.idea.gradle.project.model.GradleAndroidModel;
import com.android.tools.idea.projectsystem.AndroidModuleSystem.Type;
import com.android.tools.idea.projectsystem.TestArtifactSearchScopes;
import com.android.tools.idea.projectsystem.gradle.GradleProjectPath;
import com.android.tools.idea.projectsystem.gradle.GradleSourceSetProjectPath;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gradle implementation of {@link TestArtifactSearchScopes}, differentiates {@code test/}, {@code screenshotTest/},
 * and {@code androidTest/} sources based on information from the model. Sources of AGP test suites are test sources,
 * and sources of test suites that run Compose Preview screenshot tests are also screenshot test sources.
 */
public final class GradleTestArtifactSearchScopes implements TestArtifactSearchScopes {
  @NotNull private final Module myModule;

  public GradleTestArtifactSearchScopes(@NotNull Module module) {
    myModule = module;
  }

  @Override
  public boolean isAndroidTestSource(@NotNull VirtualFile file) {
    return getAndroidTestSourceScope().accept(file);
  }

  @Override
  public boolean isUnitTestSource(@NotNull VirtualFile file) {
    return getUnitTestSourceScope().accept(file);
  }

  @Override
  public boolean isScreenshotTestSource(@NotNull VirtualFile file) {
    return getScreenshotTestSourceScope().accept(file);
  }

  @Override
  public boolean isTestSource(@NotNull VirtualFile file) {
    if (isAndroidTestSource(file) || isUnitTestSource(file) || isScreenshotTestSource(file)) {
      return true;
    }
    for (Module testSuiteModule : getTestSuiteModules(myModule)) {
      if (testSuiteModule.getModuleContentScope().accept(file)) {
        return true;
      }
    }
    return false;
  }

  @Override
  @NotNull
  public GlobalSearchScope getAndroidTestSourceScope() {
    Module androidTestModule;
    if (getModuleSystem(myModule).getType() == Type.TYPE_NON_ANDROID) {
      androidTestModule = null;
    }
    else if (getModuleSystem(myModule).getType() == Type.TYPE_TEST) {
      androidTestModule = getMainModule(myModule);
    }
    else {
      androidTestModule = getAndroidTestModule(myModule);
    }
    return androidTestModule != null ? androidTestModule.getModuleContentScope() : GlobalSearchScope.EMPTY_SCOPE;
  }

  @Override
  @NotNull
  public GlobalSearchScope getUnitTestSourceScope() {
    Module unitTestModule = getUnitTestModule(myModule);
    return unitTestModule != null ? unitTestModule.getModuleContentScope() : GlobalSearchScope.EMPTY_SCOPE;
  }

  @Override
  @NotNull
  public GlobalSearchScope getScreenshotTestSourceScope() {
    Module screenshotTestModule = getScreenshotTestModule(myModule);
    GlobalSearchScope scope = screenshotTestModule != null ? screenshotTestModule.getModuleContentScope() : GlobalSearchScope.EMPTY_SCOPE;
    List<Module> testSuiteModules = getTestSuiteModules(myModule);
    GradleAndroidModel androidModel = testSuiteModules.isEmpty() ? null : GradleAndroidModel.get(myModule);
    if (androidModel == null) {
      return scope;
    }
    // The source set names of the test suites that run Compose Preview screenshot tests.
    Set<String> screenshotSourceSetNames = new HashSet<>();
    for (IdeTestSuite suite : androidModel.getTestSuites()) {
      if (IdeTestSuiteKt.isScreenshotTestSuite(suite)) {
        screenshotSourceSetNames.addAll(IdeTestSuiteKt.getSourceSetNames(suite));
      }
    }
    if (screenshotSourceSetNames.isEmpty()) {
      return scope;
    }
    for (Module testSuiteModule : testSuiteModules) {
      if (screenshotSourceSetNames.contains(getSourceSetName(testSuiteModule))) {
        scope = scope.uniteWith(testSuiteModule.getModuleContentScope());
      }
    }
    return scope;
  }

  /**
   * Returns the source set name of the given test suite module. Test suite modules are source set modules, so this reads it from their
   * Gradle project path rather than with {@code getTestSuiteSourceSetName}, which resolves the module group again for each module.
   */
  @Nullable
  private static String getSourceSetName(@NotNull Module testSuiteModule) {
    GradleProjectPath gradleProjectPath = getGradleProjectPath(testSuiteModule);
    return gradleProjectPath instanceof GradleSourceSetProjectPath
           ? ((GradleSourceSetProjectPath)gradleProjectPath).getSourceSet().getSourceSetName()
           : null;
  }
}
