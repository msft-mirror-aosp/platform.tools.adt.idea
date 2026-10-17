/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.idea.bazel.java.run.producers;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.idea.bazel.java.run.producers.BlazeJUnitTestFilterFlags.JUnitVersion;
import com.google.idea.bazel.java.run.producers.JUnitParameterizedClassHeuristic.ParameterizedTestInfo;
import com.google.idea.blaze.base.BlazeTestCase;
import com.google.idea.blaze.base.run.producers.TestFilterSyntax;
import com.google.idea.blaze.base.run.producers.TestSelector;
import com.google.idea.common.experiments.ExperimentService;
import com.google.idea.common.experiments.MockExperimentService;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BlazeJUnitTestFilterFlags}. */
@RunWith(JUnit4.class)
public class BlazeJUnitTestFilterFlagsTest extends BlazeTestCase {

  @Override
  protected void initTest(
      @NotNull Container applicationServices, @NotNull Container projectServices) {
    ExperimentService experimentService = new MockExperimentService();
    applicationServices.register(ExperimentService.class, experimentService);
  }

  @Test
  public void testSingleJUnit4ClassFilter() {
    assertThat(
            BlazeJUnitTestFilterFlags.testFilterForClassAndMethods(
                "com.google.idea.ClassName", JUnitVersion.JUNIT_4, ImmutableList.of(), null))
        .isEqualTo("com.google.idea.ClassName#");
  }

  @Test
  public void testSingleJUnit3ClassFilter() {
    assertThat(
            BlazeJUnitTestFilterFlags.testFilterForClassAndMethods(
                "com.google.idea.ClassName", JUnitVersion.JUNIT_3, ImmutableList.of(), null))
        .isEqualTo("com.google.idea.ClassName");
  }

  @Test
  public void testParameterizedIgnoredForSingleClass() {
    assertThat(
            BlazeJUnitTestFilterFlags.testFilterForClassAndMethods(
                "com.google.idea.ClassName", JUnitVersion.JUNIT_4, ImmutableList.of(), null))
        .isEqualTo("com.google.idea.ClassName#");
  }

  @Test
  public void testJUnit4ClassAndSingleMethod() {
    assertThat(
            BlazeJUnitTestFilterFlags.testFilterForClassAndMethods(
                "com.google.idea.ClassName",
                JUnitVersion.JUNIT_4,
                ImmutableList.of("testMethod1"),
                null))
        .isEqualTo("com.google.idea.ClassName#testMethod1$");
  }

  @Test
  public void testJUnit3ClassAndSingleMethod() {
    assertThat(
            BlazeJUnitTestFilterFlags.testFilterForClassAndMethods(
                "com.google.idea.ClassName",
                JUnitVersion.JUNIT_3,
                ImmutableList.of("testMethod1"),
                null))
        .isEqualTo("com.google.idea.ClassName#testMethod1");
  }

  @Test
  public void testJUnit4ClassAndMultipleMethods() {
    assertThat(
            BlazeJUnitTestFilterFlags.testFilterForClassAndMethods(
                "com.google.idea.ClassName",
                JUnitVersion.JUNIT_4,
                ImmutableList.of("testMethod1", "testMethod2"),
                null))
        .isEqualTo("com.google.idea.ClassName#(testMethod1|testMethod2)$");
  }

  @Test
  public void testJUnit4ParameterizedClassAndMultipleMethods() {
    assertThat(
            BlazeJUnitTestFilterFlags.testFilterForClassAndMethods(
                "com.google.idea.ClassName",
                JUnitVersion.JUNIT_4,
                ImmutableList.of("testMethod1(\\[.*\\])?", "testMethod2(\\[.*\\])?"),
                null))
        .isEqualTo("com.google.idea.ClassName#(testMethod1(\\[.*\\])?|testMethod2(\\[.*\\])?)$");
  }

  @Test
  public void testJUnit3ClassAndMultipleMethods() {
    assertThat(
            BlazeJUnitTestFilterFlags.testFilterForClassAndMethods(
                "com.google.idea.ClassName",
                JUnitVersion.JUNIT_3,
                ImmutableList.of("testMethod1", "testMethod2"),
                null))
        .isEqualTo("com.google.idea.ClassName#testMethod1,testMethod2");
  }

  @Test
  public void testParameterizedClassNameWithNoMethods() {
    ParameterizedTestInfo parameterizedTestInfo =
        ParameterizedTestInfo.builder()
            .runnerClass("BurstJUnit4")
            .testClassSuffixRegex("\\[MY_SUFFIX\\]")
            .testMethodSuffixRegex(".*")
            .build();
    assertThat(
            BlazeJUnitTestFilterFlags.testFilterForClassAndMethods(
                "com.google.idea.ClassName",
                JUnitVersion.JUNIT_4,
                ImmutableList.of(),
                parameterizedTestInfo))
        .isEqualTo("com.google.idea.ClassName\\[MY_SUFFIX\\]#");
  }

  @Test
  public void testParameterizedClassNameWithMethods() {
    ParameterizedTestInfo parameterizedTestInfo =
        ParameterizedTestInfo.builder()
            .runnerClass("BurstJUnit4")
            .testClassSuffixRegex("\\[MY_SUFFIX\\]")
            .testMethodSuffixRegex(".*")
            .build();
    assertThat(
            BlazeJUnitTestFilterFlags.testFilterForClassAndMethods(
                "com.google.idea.ClassName",
                JUnitVersion.JUNIT_4,
                ImmutableList.of("testMethod1", "testMethod2"),
                parameterizedTestInfo))
        .isEqualTo("com.google.idea.ClassName\\[MY_SUFFIX\\]#(testMethod1|testMethod2)$");
  }

  @Test
  public void testFilterForSelectors_jUnit4ClassAndMethod_acceptsBracketAndParenthesisSuffixes() {
    String classFilter =
        BlazeJUnitTestFilterFlags.testFilterForSelectors(
            ImmutableList.of(
                new TestSelector(
                    "com.google.idea.ClassName", null, null, TestFilterSyntax.JUNIT_4)));
    assertThat(classFilter).isEqualTo("com.google.idea.ClassName(\\[.*\\])?(\\(.*\\))?#");

    String methodFilter =
        BlazeJUnitTestFilterFlags.testFilterForSelectors(
            ImmutableList.of(
                new TestSelector(
                    "com.google.idea.ClassName", "testMethod1", null, TestFilterSyntax.JUNIT_4)));
    assertThat(methodFilter)
        .isEqualTo(
            "com.google.idea.ClassName(\\[.*\\])?(\\(.*\\))?#testMethod1(\\[.*\\])?(\\(.*\\))?$");
    assertThat("com.google.idea.ClassName#testMethod1").matches(methodFilter);
    assertThat("com.google.idea.ClassName#testMethod1[param]").matches(methodFilter);
    assertThat("com.google.idea.ClassName#testMethod1(param)").matches(methodFilter);
    assertThat("com.google.idea.ClassName[classParam]#testMethod1[param]").matches(methodFilter);
    assertThat("com.google.idea.ClassName(classParam)#testMethod1(param)").matches(methodFilter);
    assertThat("com.google.idea.ClassName#testMethod1Other").doesNotMatch(methodFilter);
  }

  @Test
  public void testFilterForSelectors_jUnit4ParameterizedMethod_usesParameterFilterRegex() {
    String singleParamFilter =
        BlazeJUnitTestFilterFlags.testFilterForSelectors(
            ImmutableList.of(
                new TestSelector(
                    "com.google.idea.ClassName",
                    "testMethod1",
                    "\\[param1\\]",
                    TestFilterSyntax.JUNIT_4)));
    assertThat(singleParamFilter)
        .isEqualTo("com.google.idea.ClassName(\\[.*\\])?(\\(.*\\))?#testMethod1\\[param1\\]$");
    assertThat("com.google.idea.ClassName#testMethod1[param1]").matches(singleParamFilter);
    assertThat("com.google.idea.ClassName[classParam]#testMethod1[param1]")
        .matches(singleParamFilter);
    assertThat("com.google.idea.ClassName#testMethod1[param2]").doesNotMatch(singleParamFilter);

    String multiParamFilter =
        BlazeJUnitTestFilterFlags.testFilterForSelectors(
            ImmutableList.of(
                new TestSelector(
                    "com.google.idea.ClassName",
                    "testMethod1",
                    "\\[param2\\]",
                    TestFilterSyntax.JUNIT_4),
                new TestSelector(
                    "com.google.idea.ClassName",
                    "testMethod1",
                    "\\[param1\\]",
                    TestFilterSyntax.JUNIT_4)));
    assertThat(multiParamFilter)
        .isEqualTo(
            "com.google.idea.ClassName(\\[.*\\])?(\\(.*\\))?#(testMethod1\\[param1\\]|testMethod1\\[param2\\])$");
    assertThat("com.google.idea.ClassName#testMethod1[param1]").matches(multiParamFilter);
    assertThat("com.google.idea.ClassName#testMethod1[param2]").matches(multiParamFilter);
    assertThat("com.google.idea.ClassName#testMethod1[param3]").doesNotMatch(multiParamFilter);
  }
}
