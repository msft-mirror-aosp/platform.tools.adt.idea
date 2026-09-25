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
package com.google.idea.blaze.base.run.state;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.idea.blaze.base.command.BlazeFlags;
import com.google.idea.blaze.base.execution.BlazeParametersListUtil;
import com.google.idea.blaze.base.ui.UiUtil;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.ui.JBUI;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.JLabel;
import org.jdom.Element;

/** State for a list of user-defined flags. */
public final class RunConfigurationFlagsState implements RunConfigurationState {

  private final String tag;
  private final String fieldLabel;

  /** Unprocessed flags, as the user entered them, tokenised on unquoted whitespace. */
  private ImmutableList<String> flags = ImmutableList.of();

  public RunConfigurationFlagsState(String tag, String fieldLabel) {
    this.tag = tag;
    this.fieldLabel = fieldLabel;
  }

  /** Flags ready to be used directly as args for external processes. */
  public List<String> getFlagsForExternalProcesses() {
    List<String> processedFlags =
        flags.stream()
            .map(s -> ParametersListUtil.parse(s, false, true).get(0))
            .collect(Collectors.toList());
    return BlazeFlags.expandBuildFlags(processedFlags);
  }

  /** Unprocessed flags that haven't been macro expanded or processed for escaping/quotes. */
  public List<String> getRawFlags() {
    return flags;
  }

  public void setRawFlags(List<String> flags) {
    this.flags = ImmutableList.copyOf(flags);
  }

  public RunConfigurationFlagsState copy() {
    RunConfigurationFlagsState state = new RunConfigurationFlagsState(tag, fieldLabel);
    state.setRawFlags(getRawFlags());
    return state;
  }

  @Override
  public void readExternal(Element element) {
    ImmutableList.Builder<String> flagsBuilder = ImmutableList.builder();
    for (Element e : element.getChildren(tag)) {
      String flag = e.getTextTrim();
      if (flag != null && !flag.isEmpty()) {
        flagsBuilder.add(flag);
      }
    }
    flags = flagsBuilder.build();
  }

  @Override
  public void writeExternal(Element element) {
    element.removeChildren(tag);
    for (String flag : flags) {
      Element child = new Element(tag);
      child.setText(flag);
      element.addContent(child);
    }
  }

  @Override
  public RunConfigurationStateEditor getEditor(Project project) {
    return new RunConfigurationFlagsStateEditor(project, fieldLabel);
  }

  /** Editor component for flags list */
  protected static class RunConfigurationFlagsStateEditor implements RunConfigurationStateEditor {

    private final EditorTextField flagsField;
    private final String fieldLabel;

    RunConfigurationFlagsStateEditor(@Nullable Project project, String fieldLabel) {
      this.fieldLabel = fieldLabel;
      flagsField = createFlagsField(project);
    }

    private static EditorTextField createFlagsField(@Nullable Project project) {
      EditorTextField field = new EditorTextField("", project, PlainTextFileType.INSTANCE);
      field.setOneLineMode(false);
      field.setPreferredSize(JBUI.size(100, 100));
      field.setMinimumSize(JBUI.size(100, 50));
      field.addSettingsProvider(
          editor -> {
            editor.setHorizontalScrollbarVisible(false);
            editor.setVerticalScrollbarVisible(true);
            editor.getSettings().setUseSoftWraps(true);
          });
      return field;
    }

    @Override
    public void setComponentEnabled(boolean enabled) {
      flagsField.setEnabled(enabled);
    }

    @Override
    public void resetEditorFrom(RunConfigurationState genericState) {
      RunConfigurationFlagsState state = (RunConfigurationFlagsState) genericState;
      // join on newline chars only, otherwise leave unchanged
      flagsField.setText(Joiner.on('\n').join(state.getRawFlags()));
    }

    @Override
    public void applyEditorTo(RunConfigurationState genericState) {
      RunConfigurationFlagsState state = (RunConfigurationFlagsState) genericState;
      // split on unescaped whitespace and newlines only. Otherwise leave unchanged.
      List<String> list = BlazeParametersListUtil.splitParameters(flagsField.getText());
      state.setRawFlags(list);
    }

    @Override
    public JComponent createComponent() {
      return UiUtil.createBox(new JLabel(fieldLabel), flagsField);
    }

    @VisibleForTesting
    JComponent getInternalComponent() {
      return flagsField;
    }
  }
}
