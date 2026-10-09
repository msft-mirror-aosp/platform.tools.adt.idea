/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea;

import static com.google.common.truth.Truth.assertThat;

import com.android.tools.asdriver.tests.AndroidStudio;
import com.android.tools.asdriver.tests.AndroidStudioInstallation;
import com.android.tools.testlib.Display;
import com.android.tools.testlib.TestFileSystem;
import com.intellij.openapi.util.SystemInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StartUpTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void startUpTest() throws Exception {
    TestFileSystem fileSystem = new TestFileSystem(tempFolder.getRoot().toPath());
    AndroidStudioInstallation install = AndroidStudioInstallation.fromDir(fileSystem);
    if (SystemInfo.isMac) {
      install.addVmOption("-Djava.awt.headless=true");
    }
    try (Display display = Display.createDefault();
        AndroidStudio studio = install.run(display)) {
      // Check that AndroidStudioApplicationInfo.xml was patched properly, and that it is not
      // overridden by
      // ApplicationNamesInfo.getAppInfoData (see org.jetbrains.intellij.build.tasks.injectAppInfo
      // and Change Id51ff2663).
      String version = studio.version();
      assertThat(version).startsWith("Android Studio");
      assertThat(version).doesNotContain("dev");
      String javaHome = install.getStudioDir().resolve("jbr").toString();
      // On Mac the java.home has "/Contents/Home" added to the jbr path that are not present on
      // other installations
      if (SystemInfo.isMac) {
        assertThat(javaHome).doesNotContain("/Contents/Home");
        javaHome += "/Contents/Home";
      }
      // Java Home will point to the run files directory(which is a symlink) for the test's
      // AndroidStudioInstallation instance
      // The Ide will resolve java.home property to a real path
      // So to verify that both paths refer to the same directory, we use Files.isSameFile()
      // to compare them
      assertThat(
              Files.isSameFile(
                  Path.of(studio.getSystemProperty("java.home"), "bin/java"),
                  Path.of(javaHome, "bin/java")))
          .isTrue();

      // Wait for plugin manager to load all plugins
      Matcher matcher =
          install
              .getIdeaLog()
              .waitForMatchingLine(
                  ".*AppStarter - Loaded bundled plugins:(.*)", 10, TimeUnit.SECONDS);
      // Each entry has the form "Name (id, version)". Names may contain parentheses too, e.g. "Pseudo Terminal (PTY4J)".
      List<String> plugins = new ArrayList<>();
      Matcher pluginMatcher = Pattern.compile("(.+?) \\([^()]*, [^()]*\\)(?:, |$)").matcher(matcher.group(1).strip());
      while (pluginMatcher.find()) {
        plugins.add(pluginMatcher.group(1).strip());
      }

      List<String> expectedPlugins =
          new ArrayList<>(
              Arrays.asList(
                  "Artifacts Repository Resolver",
                  "Android",
                  "Android APK Support",
                  "Android Design Tools",
                  "Android SDK Upgrade Assistant",
                  "Android Studio Driver",
                  "Android NDK Support",
                  "App Links Assistant",
                  "App Links Assistant AI",
                  "Bookmarks",
                  "C/C++ Language Support via Classic Engine",
                  "CIDR Base",
                  "CIDR Debugger",
                  "Clangd Support",
                  "Code Coverage for Java",
                  "Compose UI",
                  "Configuration Script",
                  "Copyright",
                  "Data Editor and Viewer",
                  "Eclipse Keymap",
                  "EditorConfig",
                  "Device Streaming",
                  "DevKit Runtime",
                  "Firebase Services",
                  "GMD Code Completion",
                  "Gemini",
                  "Git for App Insights",
                  "Git",
                  "Modal Commit Interface",
                  "GitHub",
                  "GitLab",
                  "Gradle",
                  "Gradle DSL Parser Support",
                  "Gradle for Java",
                  "Groovy",
                  "Groovy Live Templates",
                  "Groovy Scripting",
                  "HTML Tools",
                  // IntelliJ 2026.3 no longer gives the core plugin a display name, so it is logged by ID.
                  "com.intellij",
                  "Images",
                  "JSON",
                  "Natural Languages",
                  "Navigation Bar",
                  "JUnit",
                  "Java",
                  "Java Bytecode Decompiler",
                  "Java IDE Customization",
                  "Compose Multiplatform",
                  "Java Internationalization",
                  "Java Native Access (JNA)",
                  "Jetpack Compose",
                  "Kotlin",
                  "Lightbuild",
                  "Lightbuild AI Plugin",
                  "Machine Learning Code Completion",
                  "Markdown",
                  "Mercurial",
                  "Mermaid",
                  "NetBeans Keymap",
                  "Performance Diagnostics",
                  "Problems View",
                  "Properties",
                  "Pseudo Terminal (PTY4J)",
                  "Recent Files",
                  "Services View",
                  "Shell Script",
                  "Smali Support",
                  "Structural Search",
                  "Structure View",
                  "Tasks and Contexts",
                  "Terminal",
                  "Test Recorder",
                  "Test Runner",
                  "TestNG",
                  "TextMate Bundles",
                  "TODO Comments",
                  "Toml",
                  "Version Control",
                  "Visual Studio Keymap",
                  "WebP",
                  "XML and HTML",
                  "YAML"));

      assertThat(plugins).containsExactlyElementsIn(expectedPlugins);

      install.verify();
    }
  }
}
