/*
 * Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.idea.bazel.java.run;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.idea.blaze.base.BlazeIntegrationTestCase;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.run.producers.TestFilterSyntax;
import com.google.idea.blaze.base.run.producers.TestSelector;
import com.google.idea.blaze.common.Label;
import com.intellij.execution.Location;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.List;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration tests for {@link BlazeJavaTestEventsHandler}. */
@RunWith(JUnit4.class)
public class BlazeJavaTestEventsHandlerTest extends BlazeIntegrationTestCase {

  private BlazeJavaTestEventsHandler handler;

  @Before
  public final void doSetUp() {
    // EPs must be set up before initializing BlazeJavaTestEventsHandler
    handler = new BlazeJavaTestEventsHandler();
  }

  @Test
  public void testSuiteLocationResolves() {
    PsiFile javaFile =
        workspace.createPsiFile(
            new WorkspacePath("java/com/google/lib/JavaClass.java"),
            "package com.google.lib;",
            "public class JavaClass {}");
    PsiClass javaClass = ((PsiClassOwner) javaFile).getClasses()[0];
    assertThat(javaClass).isNotNull();

    String url =
        handler.suiteLocationUrl(
            Label.of("//java/com/google/lib:JavaClass"), null, "com.google.lib.JavaClass");
    Location<?> location = getLocation(url);
    assertThat(location.getPsiElement()).isEqualTo(javaClass);
  }

  @Test
  public void testMethodLocationResolves() {
    PsiFile javaFile =
        workspace.createPsiFile(
            new WorkspacePath("java/com/google/lib/JavaClass.java"),
            "package com.google.lib;",
            "public class JavaClass {",
            "  public void testMethod() {}",
            "}");
    PsiClass javaClass = ((PsiClassOwner) javaFile).getClasses()[0];
    PsiMethod method = javaClass.findMethodsByName("testMethod", false)[0];
    assertThat(method).isNotNull();

    String url =
        handler.testLocationUrl(
            Label.of("//java/com/google/lib:JavaClass"),
            null,
            null,
            "testMethod",
            "com.google.lib.JavaClass");
    Location<?> location = getLocation(url);
    assertThat(location.getPsiElement()).isEqualTo(method);
  }

  @Test
  public void testParameterizedMethodLocationResolves() {
    PsiFile javaFile =
        workspace.createPsiFile(
            new WorkspacePath("java/com/google/lib/JavaClass.java"),
            "package com.google.lib;",
            "public class JavaClass {",
            "  public void testMethod() {}",
            "}");
    PsiClass javaClass = ((PsiClassOwner) javaFile).getClasses()[0];
    PsiMethod method = javaClass.findMethodsByName("testMethod", false)[0];
    assertThat(method).isNotNull();

    String url =
        handler.testLocationUrl(
            Label.of("//java/com/google/lib:JavaClass"),
            null,
            "testMethod",
            "[0] true (testMethod)",
            "com.google.lib.JavaClass");
    Location<?> location = getLocation(url);
    assertThat(location.getPsiElement()).isEqualTo(method);
  }

  @Test
  public void testInheritedParameterizedMethodLocationUsesConcreteSubclassInSelectors() {
    workspace.createPsiFile(
        new WorkspacePath("org/junit/Test.java"),
        "package org.junit;",
        "public @interface Test {}");
    workspace.createPsiFile(
        new WorkspacePath("java/com/google/lib/BaseTest.java"),
        "package com.google.lib;",
        "public abstract class BaseTest {",
        "  @org.junit.Test",
        "  public void testMethod() {}",
        "}");
    workspace.createPsiFile(
        new WorkspacePath("java/com/google/lib/ConcreteTest.java"),
        "package com.google.lib;",
        "public class ConcreteTest extends com.google.lib.BaseTest {}");

    String parameterizedUrl =
        handler.testLocationUrl(
            Label.of("//java/com/google/lib:ConcreteTest"),
            null,
            "testMethod",
            "[0] true (testMethod)",
            "com.google.lib.ConcreteTest");
    Location<?> parameterizedLocation = getLocation(parameterizedUrl);
    assertThat(parameterizedLocation).isNotNull();

    List<TestSelector> parameterizedSelectors =
        handler.getTestSelectors(getProject(), ImmutableList.of(parameterizedLocation));
    assertThat(parameterizedSelectors)
        .containsExactly(
            new TestSelector(
                "com.google.lib.ConcreteTest", "testMethod", "\\[0\\]", TestFilterSyntax.JUNIT_4));

    String unparameterizedUrl =
        handler.testLocationUrl(
            Label.of("//java/com/google/lib:ConcreteTest"),
            null,
            null,
            "testMethod",
            "com.google.lib.ConcreteTest");
    Location<?> unparameterizedLocation = getLocation(unparameterizedUrl);
    assertThat(unparameterizedLocation).isNotNull();

    List<TestSelector> unparameterizedSelectors =
        handler.getTestSelectors(getProject(), ImmutableList.of(unparameterizedLocation));
    assertThat(unparameterizedSelectors)
        .containsExactly(
            new TestSelector(
                "com.google.lib.ConcreteTest", "testMethod", null, TestFilterSyntax.JUNIT_4));
  }

  @Nullable
  private Location<?> getLocation(String url) {
    String protocol = VirtualFileManager.extractProtocol(url);
    String path = VirtualFileManager.extractPath(url);
    if (protocol == null) {
      return null;
    }
    return Iterables.getFirst(
        handler
            .getTestLocator()
            .getLocation(protocol, path, getProject(), GlobalSearchScope.allScope(getProject())),
        null);
  }
}
