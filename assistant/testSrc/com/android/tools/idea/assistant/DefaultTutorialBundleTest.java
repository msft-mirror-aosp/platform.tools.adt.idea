/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.idea.assistant;

import com.android.tools.idea.assistant.datamodel.TutorialBundleData;
import com.intellij.openapi.util.SystemInfo;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.bind.JAXBException;
import junit.framework.TestCase;
import org.xml.sax.SAXParseException;

public class DefaultTutorialBundleTest extends TestCase {
  private static final String BUNDLE_BODY =
    "<tutorialBundle name=\"Test Assistant\" stepByStep=\"false\">\n" +
    "  <feature name=\"Test Feature\">\n" +
    "    <tutorial label=\"Test Tutorial\" key=\"test-key\">\n" +
    "      <step label=\"Step 1\">\n" +
    "        <stepElement>\n" +
    "          <section>Test section content</section>\n" +
    "        </stepElement>\n" +
    "      </step>\n" +
    "    </tutorial>\n" +
    "  </feature>\n" +
    "</tutorialBundle>";

  public void testGetIconResourcePath() {
    assertEquals("/foo/bar/blah", DefaultTutorialBundle.getIconResourcePath("/foo", "bar/blah"));
    assertEquals("/foo/bar/blah", DefaultTutorialBundle.getIconResourcePath("/foo", "/bar/blah"));
    assertEquals("/foo/bar/blah", DefaultTutorialBundle.getIconResourcePath("/foo/", "bar/blah"));
    assertEquals("/foo/bar/blah", DefaultTutorialBundle.getIconResourcePath("/foo/", "/bar/blah"));

    if (SystemInfo.isWindows) {
      assertEquals("/foo/bar/blah", DefaultTutorialBundle.getIconResourcePath("\\foo", "bar/blah"));
      assertEquals("/foo/bar/blah", DefaultTutorialBundle.getIconResourcePath("\\foo", "\\bar/blah"));
      assertEquals("/foo/bar/blah", DefaultTutorialBundle.getIconResourcePath("\\foo\\", "bar/blah"));
      assertEquals("/foo/bar/blah", DefaultTutorialBundle.getIconResourcePath("\\foo\\", "\\bar/blah"));
    }
  }

  public void testParseValidXml() throws Exception {
    String validXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + BUNDLE_BODY;

    try (InputStream stream = new ByteArrayInputStream(validXml.getBytes(StandardCharsets.UTF_8))) {
      TutorialBundleData bundle = DefaultTutorialBundle.parse(stream, "test-bundle-id");
      assertNotNull(bundle);
      assertEquals("Test Assistant", bundle.getName());
      assertEquals("test-bundle-id", bundle.getBundleCreatorId());
      assertEquals(1, bundle.getFeatures().size());
      assertEquals("Test Feature", bundle.getFeatures().get(0).getName());
      assertEquals(1, bundle.getFeatures().get(0).getTutorials().size());
      assertEquals("Test Tutorial", bundle.getFeatures().get(0).getTutorials().get(0).getLabel());
    }
  }

  public void testParseDisallowsDoctype() throws Exception {
    String xmlWithDoctype =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
      "<!DOCTYPE tutorialBundle>\n" +
      BUNDLE_BODY;

    assertParseRejectsDoctype(xmlWithDoctype);
  }

  public void testParseDisallowsExternalEntities() throws Exception {
    // Classic XXE payload: a parameter entity pointing at an external resource. The DOCTYPE must be
    // rejected before the parser ever attempts to dereference the entity.
    String xxeXml =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
      "<!DOCTYPE tutorialBundle [\n" +
      "  <!ENTITY % ext SYSTEM \"http://127.0.0.1:65432/test\">\n" +
      "  %ext;\n" +
      "]>\n" +
      BUNDLE_BODY;

    assertParseRejectsDoctype(xxeXml);
  }

  private static void assertParseRejectsDoctype(String xml) throws Exception {
    try (InputStream stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
      try {
        DefaultTutorialBundle.parse(stream, "test-bundle-id");
        fail("Expected JAXBException when parsing XML with DOCTYPE declaration");
      }
      catch (JAXBException expected) {
        Throwable linked = expected.getLinkedException();
        assertNotNull("Expected linked SAXParseException", linked);
        assertTrue("Expected SAXParseException but got: " + linked.getClass().getName(), linked instanceof SAXParseException);
        assertTrue("Expected disallow-doctype-decl message but got: " + linked.getMessage(),
                   linked.getMessage().contains("disallow-doctype-decl"));
      }
    }
  }
}
