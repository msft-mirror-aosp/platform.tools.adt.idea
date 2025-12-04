/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.gradle.dsl.model.java;

import com.android.tools.idea.gradle.dsl.api.dependencies.DependenciesModel;
import com.android.tools.idea.gradle.dsl.api.java.JavaDeclarativeModel;
import com.android.tools.idea.gradle.dsl.api.java.JavaTestDeclarativeModel;
import com.android.tools.idea.gradle.dsl.api.java.LanguageLevelPropertyModel;
import com.android.tools.idea.gradle.dsl.model.GradleDslBlockModel;
import com.android.tools.idea.gradle.dsl.model.dependencies.ScriptDependenciesModelImpl;
import com.android.tools.idea.gradle.dsl.parser.dependencies.DependenciesDslElement;
import com.android.tools.idea.gradle.dsl.parser.java.JavaDclElement;
import com.android.tools.idea.gradle.dsl.parser.java.TestingDclElement;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class JavaDeclarativeModelImpl extends GradleDslBlockModel implements JavaDeclarativeModel {
  @NonNls public static final String JAVA_VERSION = "mJavaVersion";
  @NonNls public static final String MAIN_CLASS = "mMainClass";

  public JavaDeclarativeModelImpl(@NotNull JavaDclElement dslElement) {
    super(dslElement);
  }

  @NotNull
  @Override
  public LanguageLevelPropertyModel javaVersion() {
    return getLanguageModelForProperty(JAVA_VERSION);
  }

  @NotNull
  @Override
  public String mainClass() {
    //TODO temporary
    return getModelForProperty(MAIN_CLASS).toString()
      .replace("mainClass = ", "").replace("\"", "");
  }

  @NotNull
  @Override
  public DependenciesModel dependencies() {
    DependenciesDslElement dependenciesDslElement = myDslElement.ensurePropertyElement(DependenciesDslElement.DEPENDENCIES);
    return new ScriptDependenciesModelImpl(dependenciesDslElement);
  }

  @Override
  public @NotNull JavaTestDeclarativeModel testing() {
    TestingDclElement testingDclElement = myDslElement.ensurePropertyElement(TestingDclElement.TESTING);
    return new JavaTestDeclarativeModelImpl(testingDclElement);
  }
}
