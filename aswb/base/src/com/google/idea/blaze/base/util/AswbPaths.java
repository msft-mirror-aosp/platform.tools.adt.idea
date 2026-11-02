/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.idea.blaze.base.util;

import java.nio.file.Path;
import java.util.regex.Pattern;

public final class AswbPaths {
  private static final Pattern VERSIONED_HOME_DIR = Pattern.compile("(android-studio-with-blaze-[^-]+)-.+");

  private AswbPaths() {
  }

  public static boolean isVersioned(Path homeDir) {
    var name = homeDir.getFileName();

    if (name == null) {
      return false;
    }

    return VERSIONED_HOME_DIR.matcher(name.toString()).matches();
  }

  public static Path stripVersion(Path homeDir) {
    var name = homeDir.getFileName();

    if (name == null) {
      return homeDir;
    }

    var matcher = VERSIONED_HOME_DIR.matcher(name.toString());
    return matcher.matches() ? homeDir.resolveSibling(matcher.group(1)) : homeDir;
  }
}
