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
package com.android.tools.idea.gradle.project.model;

import com.android.builder.model.Library;
import com.android.builder.model.MavenCoordinates;
import com.android.tools.idea.gradle.model.IdeLibrary;
import com.android.tools.idea.gradle.model.stubs.LibraryStub;
import com.android.tools.idea.gradle.model.stubs.MavenCoordinatesStub;
import com.google.common.truth.Truth;
import org.junit.Test;

/** Tests for {@link IdeLibrary}. */
public class IdeLibraryTest {

  @Test
    public void computeMavenAddress() {
      Library library = new LibraryStub() {
        @Override
        public MavenCoordinates getResolvedCoordinates() {
          return new MavenCoordinatesStub("com.android.tools", "test", "2.1", "aar");
        }
      };
      Truth.assertThat(computeCoordinates(library.getResolvedCoordinates())).isEqualTo("com.android.tools:test:2.1@aar");
    }

    @Test
    public void computeMavenAddressWithNestedModuleLibrary() {
      Library library = new LibraryStub() {
        @Override
        public MavenCoordinates getResolvedCoordinates() {
          return new MavenCoordinatesStub("myGroup", ":androidLib:subModule", "undefined", "aar");
        }
      };
      Truth.assertThat(computeCoordinates(library.getResolvedCoordinates())).isEqualTo("myGroup:androidLib.subModule:undefined@aar");
    }

  private String computeCoordinates(MavenCoordinates coordinate) {
      String artifactId = coordinate.getArtifactId();
      if (artifactId.startsWith(":")) {
        artifactId = artifactId.substring(1);
      }
      artifactId = artifactId.replace(':', '.');

      String address = coordinate.getGroupId() + ":" + artifactId + ":" + coordinate.getVersion();
      String classifier = coordinate.getClassifier();
      if (classifier != null) {
        address = address + ":" + classifier;
      }
      String packaging = coordinate.getPackaging();
      address = address + "@" + packaging;
      return address;
    }
}
