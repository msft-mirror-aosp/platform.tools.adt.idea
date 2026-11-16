/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.profilers.profilingconfig;

import com.android.tools.adtui.ui.options.OptionsPanel;
import com.android.tools.idea.help.AndroidWebHelpProvider;
import com.android.tools.idea.run.profiler.CpuProfilerConfig;
import com.android.tools.idea.run.profiler.CpuProfilerConfigsState;
import com.android.tools.profilers.IdeProfilerServices;
import com.android.tools.profilers.ProfilerColors;
import com.android.tools.profilers.cpu.config.CpuProfilerConfigModel;
import com.android.tools.profilers.cpu.config.ProfilingConfiguration;
import com.intellij.ide.actions.ShowSettingsUtilImpl;
import com.intellij.openapi.actionSystem.ActionToolbarPosition;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.ex.SingleConfigurableEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.VerticalFlowLayout;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBDimension;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.Action;
import java.util.stream.Collectors;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

public class CpuProfilingConfigurationsDialog extends SingleConfigurableEditor {

  @NotNull private final CpuProfilerConfigModel myProfilerModel;

  @NotNull private Consumer<ProfilingConfiguration> myOnCloseCallback;

  private final int myDeviceLevel;

  public CpuProfilingConfigurationsDialog(@NotNull final Project project,
                                          int deviceLevel,
                                          @NotNull CpuProfilerConfigModel model,
                                          @NotNull Consumer<ProfilingConfiguration> onCloseCallback,
                                          @NotNull IdeProfilerServices ideProfilerServices) {
    this(project, deviceLevel, model, onCloseCallback,
         new ProfilingConfigurable(project, model, deviceLevel, ideProfilerServices));
  }

  public CpuProfilingConfigurationsDialog(@NotNull final Project project,
                                          int deviceLevel,
                                          @NotNull CpuProfilerConfigModel model,
                                          @NotNull Consumer<ProfilingConfiguration> onCloseCallback,
                                          @NotNull ProfilingConfigurable profilingConfigurable) {
    // Don't have the apply button in the EditConfig Dialog.
    super(project, profilingConfigurable, ShowSettingsUtilImpl.createDimensionKey(profilingConfigurable), false, IdeModalityType.IDE);
    myProfilerModel = model;
    myOnCloseCallback = onCloseCallback;
    myDeviceLevel = deviceLevel;
    setHorizontalStretch(1.3F);
  }

  @Override
  @VisibleForTesting
  protected Action @NotNull [] createActions() {
    return super.createActions();
  }

  @Nullable
  private ProfilingConfiguration getSelectedConfiguration() {
    ProfilingConfigurable configurable = (ProfilingConfigurable)getConfigurable();
    return configurable.getSelectedConfiguration();
  }

  @Override
  public void dispose() {
    ProfilingConfiguration selectedConfig = myProfilerModel.getProfilingConfiguration();
    if (getExitCode() == OK_EXIT_CODE) {
      selectedConfig = getSelectedConfiguration(); // Call this *before* dispose, otherwise it will be null
    }
    super.dispose();
    // If selected configuration is not supported by the device, we don't try to apply the callback on it.
    boolean selectedConfigSupported = selectedConfig != null && selectedConfig.isDeviceLevelSupported(myDeviceLevel);
    myOnCloseCallback.accept(selectedConfigSupported ? selectedConfig : null);
  }

  @Override
  public void doCancelAction() {
    ProfilingConfigurable configurable = ((ProfilingConfigurable)this.getConfigurable());
    configurable.trackTaskSettingsOpenedMetric(false);
    super.doCancelAction();
  }

  @Override
  protected void doOKAction() {
    ProfilingConfigurable configurable = ((ProfilingConfigurable)this.getConfigurable());
    configurable.trackTaskSettingsOpenedMetric(configurable.isModified());
    super.doOKAction();
  }

  @VisibleForTesting
  static class ProfilingConfigurable implements Configurable {

    /**
     * Horizontal splitter to divide the dialog into a list of configurations and a the key-value configurations panel.
     */
    private final JBSplitter mySplitter = new JBSplitter("ProfilingConfigurable.dividerProportion", 0.3f);

    /**
     * List of profiling configurations of {@link #myProject}.
     */

    @NotNull
    private final JList<ProfilingConfiguration> myConfigurations;

    /**
     * The configurations model contains the editable task configurations.
     */
    @NotNull
    private final DefaultListModel<ProfilingConfiguration> myConfigurationsModel;

    @VisibleForTesting
    DefaultListModel<ProfilingConfiguration> getConfigurationModel() {
      return this.myConfigurationsModel;
    }

    private final Project myProject;

    /**
     * Panel containing key-value CPU profiling settings.
     */
    private OptionsPanel myProfilersPanel;

    private CpuProfilerConfigModel myProfilerModel;

    private int myDeviceLevel;

    private final IdeProfilerServices myIdeProfilerServices;

    public ProfilingConfigurable(Project project,
                                 CpuProfilerConfigModel model,
                                 int deviceLevel,
                                 IdeProfilerServices ideProfilerServices) {
      myProject = project;
      myProfilerModel = model;
      myDeviceLevel = deviceLevel;
      myProfilersPanel = new OptionsPanel();
      myConfigurationsModel = new DefaultListModel<>();
      myConfigurations = new JBList<>(myConfigurationsModel);
      myIdeProfilerServices = ideProfilerServices;
      setUpConfigurationsList();
      selectConfiguration(myProfilerModel.getProfilingConfiguration());
    }

    @Nullable
    @Override
    public String getHelpTopic() {
      return AndroidWebHelpProvider.HELP_PREFIX + "r/studio-ui/cpu-recording-configurations-help-link.html";
    }

    private void setUpConfigurationsList() {
      myConfigurations.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      myConfigurations.setCellRenderer(new ProfilingConfigurationsListCellRenderer());
      myConfigurations.addListSelectionListener((e) -> {
        int index = myConfigurations.getSelectedIndex();
        // Task config fields are always editable
        myProfilersPanel.setOption(index < 0 ? null : myConfigurationsModel.get(index), false);
      });
      // Restore saved/default configurations
      for (ProfilingConfiguration configuration : myProfilerModel.getTaskProfilingConfigurations()) {
        // Exclude System Trace config from this list since it doesn't have any updatable attribute
        if (configuration.isEditable()) {
          myConfigurationsModel.addElement(configuration);
        }
      }
    }

    private void selectConfiguration(ProfilingConfiguration configuration) {
      for (int i = 0; i < myConfigurationsModel.size(); i++) {
        if (configuration.getName().equals(myConfigurationsModel.get(i).getName())) {
          myConfigurations.setSelectedIndex(i);
          return;
        }
      }
    }

    public ProfilingConfiguration getSelectedConfiguration() {
      return myConfigurations.getSelectedValue();
    }

    private JComponent createLeftPanel() {
      ToolbarDecorator toolbarDecorator = ToolbarDecorator.createDecorator(myConfigurations)
        .setToolbarPosition(ActionToolbarPosition.TOP)
        .setPanelBorder(JBUI.Borders.empty())
        .setMinimumSize(new JBDimension(200, 200))
        .setForcedDnD();

      // Make the icons invisible
      toolbarDecorator
        .disableRemoveAction()
        .disableDownAction()
        .disableUpAction()
        .disableAddAction();

      JComponent panel = toolbarDecorator.createPanel();
      JPanel taskTitlePanel = new JPanel(new VerticalFlowLayout());
      JPanel headerPanel = new JPanel(new BorderLayout());
      JLabel headerLabel = new JLabel("Tasks");
      headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
      headerPanel.add(headerLabel);
      taskTitlePanel.add(headerPanel);
      taskTitlePanel.add(panel);
      return taskTitlePanel;
    }

    @Nls
    @Override
    public String getDisplayName() {
      return "Task Settings";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
      JPanel mainComponent = new JPanel(new BorderLayout());
      mySplitter.setFirstComponent(createLeftPanel());
      mySplitter.setHonorComponentsMinimumSize(true);
      mySplitter.setSecondComponent(myProfilersPanel);
      mainComponent.add(mySplitter, BorderLayout.CENTER);
      mainComponent.setPreferredSize(new Dimension(800, 600));
      return mainComponent;
    }

    @Override
    public void apply() throws ConfigurationException {

      // Check for configs with repeated names
      Set<String> configNames = new HashSet<>();
      List<CpuProfilerConfig> configsToSave = new ArrayList<>();

      for (int i = 0; i < myConfigurationsModel.getSize(); i++) {
        ProfilingConfiguration config = myConfigurationsModel.getElementAt(i);
        String configName = config.getName();
        if (StringUtil.isEmpty(configName)) {
          throw new ConfigurationException("Empty configuration names are not allowed. Please rename or delete them before continuing.");
        }
        if (configNames.contains(configName)) {
          throw new ConfigurationException("Configuration with name \"" + configName + "\" already exists.");
        }
        configNames.add(configName);

        // Default configs are editable (except name) and hence always save to reflect latest update.
        configsToSave.add(CpuProfilerConfigConverter.fromProfilingConfiguration(config));
      }

      for (ProfilingConfiguration configuration : myProfilerModel.getTaskProfilingConfigurations()) {
        // Though not displaying the non-editable task config (system trace) in task settings, yet it needs to be
        // saved. If not, it will be skipped from recording options which shouldn't happen.
        if (!configuration.isEditable()) {
          configsToSave.add(CpuProfilerConfigConverter.fromProfilingConfiguration(configuration));
        }
      }
      CpuProfilerConfigsState.getInstance(myProject).setTaskConfigs(configsToSave);
    }

    private boolean isTaskConfigChanged() {
      // Latest config from dialog
      List<CpuProfilerConfig> latestConfigs = new ArrayList<>();
      for (int i = 0; i < myConfigurationsModel.getSize(); i++) {
        latestConfigs.add(CpuProfilerConfigConverter.fromProfilingConfiguration(myConfigurationsModel.getElementAt(i)));
      }
      List<CpuProfilerConfig> existingConfigs = CpuProfilerConfigsState.getInstance(myProject).getSavedTaskConfigsIfPresentOrDefault();
      Map<String, List<CpuProfilerConfig>>
        existingConfigGrouped = existingConfigs.stream().collect(Collectors.groupingBy(CpuProfilerConfig::getName));
      Map<String, List<CpuProfilerConfig>>
        latestConfigGrouped = latestConfigs.stream().collect(Collectors.groupingBy(CpuProfilerConfig::getName));
      for (String configName : latestConfigGrouped.keySet()) {
        // Fetch only first element of the list, since for task based no additional configs can be created.
        CpuProfilerConfig configNow = latestConfigGrouped.get(configName).get(0);
        CpuProfilerConfig configBefore = existingConfigGrouped.get(configName).get(0);
        if (!configNow.equals(configBefore)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean isModified() {
      return this.isTaskConfigChanged();
    }

    public void trackTaskSettingsOpenedMetric(boolean isConfigChanged) {
      myIdeProfilerServices.getFeatureTracker().trackTaskSettingsOpened(isConfigChanged);
    }

    private class ProfilingConfigurationsListCellRenderer implements ListCellRenderer<ProfilingConfiguration> {

      /**
       * Label to display the configuration name.
       */
      private JLabel myLabel;

      public ProfilingConfigurationsListCellRenderer() {
        myLabel = new JLabel();
        myLabel.putClientProperty("html.disable", Boolean.TRUE);
        Border marginLeft = new EmptyBorder(0, 10, 0, 0);
        myLabel.setBorder(marginLeft);
      }

      @Override
      public Component getListCellRendererComponent(JList<? extends ProfilingConfiguration> list,
                                                    ProfilingConfiguration value,
                                                    int index,
                                                    boolean isSelected,
                                                    boolean cellHasFocus) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(panel.getPreferredSize().width, 25));
        panel.setBackground(list.getBackground());
        String cellText = value.getName();

        // TODO(b/69367377): Update the design for features that are supported outside the current device level.
        if (!value.isDeviceLevelSupported(myDeviceLevel)) {
          cellText += String.format(Locale.US, " (API Level %d+)", value.getRequiredDeviceLevel());
        }
        myLabel.setText(cellText);
        myLabel.setForeground(isSelected ? Gray._255 : JBColor.BLACK);
        if (isSelected) {
          panel.setBackground(ProfilerColors.CPU_PROFILING_CONFIGURATIONS_SELECTED);
        }
        panel.add(myLabel, BorderLayout.CENTER);

        return panel;
      }
    }
  }
}
