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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import org.junit.Test;

public final class AswbPathsTest {
  @Test
  public void isVersionedDirIsRoot() {
    // Arrange
    var dir = Path.of("/");

    // Act
    var versioned = AswbPaths.isVersioned(dir);

    // Assert
    assertFalse(versioned);
  }

  @Test
  public void isVersionedDirIsVersioned() {
    // Arrange
    var dir = Path.of("/opt/android-studio-with-blaze-qa-2026.2.3.3-BUILDID");

    // Act
    var versioned = AswbPaths.isVersioned(dir);

    // Assert
    assertTrue(versioned);
  }

  @Test
  public void isVersionedDirIsNotVersioned() {
    // Arrange
    var dir = Path.of("/opt/android-studio-with-blaze-qa");

    // Act
    var versioned = AswbPaths.isVersioned(dir);

    // Assert
    assertFalse(versioned);
  }

  @Test
  public void stripVersionDirIsRoot() {
    // Arrange
    var dir = Path.of("/");

    // Act
    var strippedDir = AswbPaths.stripVersion(dir);

    // Assert
    assertEquals(Path.of("/"), strippedDir);
  }

  @Test
  public void stripVersionDirIsVersioned() {
    // Arrange
    var dir = Path.of("/opt/android-studio-with-blaze-qa-2026.2.3.3-BUILDID");

    // Act
    var strippedDir = AswbPaths.stripVersion(dir);

    // Assert
    assertEquals(Path.of("/opt/android-studio-with-blaze-qa"), strippedDir);
  }

  @Test
  public void stripVersionDirIsNotVersioned() {
    // Arrange
    var dir = Path.of("/opt/android-studio-with-blaze-qa");

    // Act
    var strippedDir = AswbPaths.stripVersion(dir);

    // Assert
    assertEquals(Path.of("/opt/android-studio-with-blaze-qa"), strippedDir);
  }
}
