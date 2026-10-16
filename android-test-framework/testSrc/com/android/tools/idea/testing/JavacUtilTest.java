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
package com.android.tools.idea.testing;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.nio.file.Files;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Checks that {@link JavacUtil#getJavac()} returns a working compiler under the current system
 * class loader.
 *
 * <p>The {@code JavacUtilPathClassLoaderTest} target runs this test with {@code
 * -Djava.system.class.loader=com.intellij.util.lang.PathClassLoader}, the configuration used when
 * tests are launched from IntelliJ IDEA, where {@link ToolProvider#getSystemJavaCompiler()}
 * returns null (b/285585692).
 */
public class JavacUtilTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void getJavacCompilesJavaSource() throws Exception {
    System.out.println("System class loader: " + ClassLoader.getSystemClassLoader());
    System.out.println(
        "ToolProvider.getSystemJavaCompiler(): " + ToolProvider.getSystemJavaCompiler());

    File source = temporaryFolder.newFile("Foo.java");
    Files.writeString(source.toPath(), "class Foo {}");
    File outputDir = temporaryFolder.newFolder("out");

    JavaCompiler javac = JavacUtil.getJavac();
    assertThat(javac).isNotNull();
    System.out.println(
        "JavacUtil.getJavac(): "
            + javac.getClass().getName()
            + " module="
            + javac.getClass().getModule()
            + " loader="
            + javac.getClass().getClassLoader());
    int exitCode = javac.run(null, null, null, "-d", outputDir.getPath(), source.getPath());

    assertThat(exitCode).isEqualTo(0);
    assertThat(new File(outputDir, "Foo.class").isFile()).isTrue();
  }
}
