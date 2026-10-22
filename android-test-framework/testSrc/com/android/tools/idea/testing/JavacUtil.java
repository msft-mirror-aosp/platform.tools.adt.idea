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
package com.android.tools.idea.testing;

import java.util.ServiceLoader;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public class JavacUtil {
  /**
   * Returns a [JavaCompiler] that can be used to compile java files.
   */
  public static JavaCompiler getJavac() {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    if (javac == null) {
      // http://b/285585692
      // When tests run from IntelliJ IDEA, PathClassLoader is the system class loader and its
      // parent is the platform class loader, so the application class loader that defines
      // jdk.compiler is skipped and ToolProvider.getSystemJavaCompiler() cannot find javac.
      // Look it up through the boot module layer instead, which does not depend on class loaders.
      javac =
          ServiceLoader.load(ModuleLayer.boot(), JavaCompiler.class)
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("No JavaCompiler found"));
    }
    return javac;
  }
}
