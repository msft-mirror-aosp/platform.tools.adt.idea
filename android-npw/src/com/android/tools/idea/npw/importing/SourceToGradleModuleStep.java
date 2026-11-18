/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.npw.importing;

import static com.android.tools.idea.wizard.ui.WizardUtils.WIZARD_BORDER.SMALL;
import static com.android.tools.idea.wizard.ui.WizardUtils.wrapWithVScroll;
import static com.intellij.openapi.project.ProjectUtil.guessProjectDir;
import static org.jetbrains.android.util.AndroidBundle.message;

import com.android.tools.adtui.util.FormScalingUtil;
import com.android.tools.adtui.validation.Validator;
import com.android.tools.adtui.validation.ValidatorPanel;
import com.android.tools.idea.gradle.project.importer.ModuleImporter;
import com.android.tools.idea.gradle.project.importer.ModuleToImport;
import com.android.tools.idea.observable.BindingsManager;
import com.android.tools.idea.observable.ListenerManager;
import com.android.tools.idea.observable.core.BoolProperty;
import com.android.tools.idea.observable.core.BoolValueProperty;
import com.android.tools.idea.observable.core.ObjectProperty;
import com.android.tools.idea.observable.core.ObjectValueProperty;
import com.android.tools.idea.observable.core.ObservableBool;
import com.android.tools.idea.observable.ui.TextProperty;
import com.android.tools.idea.util.FormatUtil;
import com.android.tools.idea.wizard.model.ModelWizard.Facade;
import com.android.tools.idea.wizard.model.ModelWizardStep;
import com.android.tools.idea.wizard.model.SkippableWizardStep;
import com.android.tools.idea.wizard.ui.WizardUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.awt.Dimension;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wizard Step that allows the user to point to an existing source directory (ADT or Gradle) to import as a new Android Gradle module.
 * Also allows selection of sub-modules to import. Most functionality is contained within existing {@link ModulesTable} class.
 */
public final class SourceToGradleModuleStep extends SkippableWizardStep<SourceToGradleModuleModel> {
  private final BindingsManager myBindings = new BindingsManager();
  private final ListenerManager myListeners = new ListenerManager();

  @NotNull private final JComponent myRootPanel;
  @NotNull private final ValidatorPanel myValidatorPanel;

  private final BoolProperty myCanGoForward = new BoolValueProperty();
  private final ObjectProperty<Validator.Result> myPathValidationResult = new ObjectValueProperty<>(Validator.Result.OK);
  private final AtomicLong myValidationGeneration = new AtomicLong();
  private boolean myHasValidPath;

  // Facade is initialised dynamically
  @Nullable private Facade myFacade;

  private JPanel myPanel;
  private TextFieldWithBrowseButton mySourceLocation;
  private JBScrollPane myModulesScroller;
  private ModulesTable myModulesPanel;
  private JLabel myRequiredModulesLabel;
  private JBLabel mySourceDirTitle;
  private PrimaryModuleImportSettings myPrimaryModel;
  private JBLabel myModuleNameLabel;
  private JTextField myModuleNameField;
  private JBLabel myPrimaryModuleState;

  @Nullable private VirtualFile myVFile;
  @Nullable private ModuleImporter myImporter;
  @Nullable private Collection<ModuleToImport> myModules;

  public SourceToGradleModuleStep(@NotNull SourceToGradleModuleModel model) {
    super(model, message("android.wizard.module.import.source.title"));

    setupUI();
    mySourceLocation.addBrowseFolderListener(getModel().getProject(), FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
      .withTitle(message("android.wizard.module.import.source.browse.title"))
      .withDescription(message("android.wizard.module.import.source.browse.description")));

    myBindings.bindTwoWay(new TextProperty(mySourceLocation.getTextField()), model.sourceLocation);

    myValidatorPanel = new ValidatorPanel(this, myPanel);
    myValidatorPanel.registerValidator(myPathValidationResult, result -> result);

    myModulesPanel.bindPrimaryModuleEntryComponents(myPrimaryModel, myRequiredModulesLabel);
    myModulesPanel.addPropertyChangeListener(ModulesTable.PROPERTY_SELECTED_MODULES, event -> {
      if (ModulesTable.PROPERTY_SELECTED_MODULES.equals(event.getPropertyName())) {
        updateForwardStatus(myHasValidPath);
      }
    });

    myListeners.listenAndFire(model.sourceLocation, this::validatePathAsync);

    myRootPanel = wrapWithVScroll(myValidatorPanel, SMALL);
    FormScalingUtil.scaleComponentTree(this.getClass(), myRootPanel);
  }

  @Override
  protected void onWizardStarting(@NotNull Facade wizard) {
    myFacade = wizard;
  }

  @Override
  protected void onProceeding() {
    getModel().setModulesToImport(myModulesPanel.getSelectedModulesMap());
  }

  @Override
  public void dispose() {
    myValidationGeneration.incrementAndGet();
    myBindings.releaseAll();
    myListeners.releaseAll();
  }

  @NotNull
  @Override
  protected ObservableBool canGoForward() {
    return myCanGoForward;
  }

  @NotNull
  @Override
  protected JComponent getComponent() {
    return myRootPanel;
  }

  @Nullable
  @Override
  protected JComponent getPreferredFocusComponent() {
    return mySourceLocation;
  }

  @NotNull
  @Override
  protected Collection<? extends ModelWizardStep<ModuleWizardStepAdapter.AdapterModel>> createDependentSteps() {
    WizardContext context = getModel().getContext();
    ArrayList<ModelWizardStep<ModuleWizardStepAdapter.AdapterModel>> wrappedSteps = new ArrayList<>();

    for (ModuleImporter importer : ModuleImporter.getAllImporters(context)) {
      for (ModuleWizardStep inputStep : importer.createWizardSteps()) {
        wrappedSteps.add(new ModuleWizardStepAdapter(context, inputStep));
      }
    }

    return wrappedSteps;
  }

  private void validatePathAsync(@NotNull String path) {
    long generation = resetValidationState();
    if (Strings.isNullOrEmpty(path)) {
      // Don't validate default empty input: jetbrains.github.io/ui/principles/validation_errors/#23
      return;
    }

    ReadAction.nonBlocking(() -> evaluatePath(path))
      .coalesceBy(this)
      .expireWith(this)
      .finishOnUiThread(ModalityState.any(), validationResult -> {
        if (generation == myValidationGeneration.get()) {
          applyValidationResult(validationResult);
        }
      })
      .submit(AppExecutorUtil.getAppExecutorService());
  }

  private long resetValidationState() {
    long generation = myValidationGeneration.incrementAndGet();
    // Hide modules UI. They will be enabled again if all validation is OK.
    myHasValidPath = false;
    myPrimaryModel.setVisible(false);
    myRequiredModulesLabel.setVisible(false);
    myModulesScroller.setVisible(false);
    myCanGoForward.set(false);
    myPathValidationResult.set(Validator.Result.OK);
    return generation;
  }

  private void applyValidationResult(@NotNull PathValidationResult validationResult) {
    myVFile = validationResult.vFile();
    myImporter = validationResult.importer();
    myModules = validationResult.modules();
    Validator.Result result = validationResult.result();
    myPathValidationResult.set(result);
    myHasValidPath = result.getSeverity() != Validator.Severity.ERROR;
    if (myHasValidPath) {
      updateModuleValidation();
    }
    updateForwardStatus(myHasValidPath);
  }

  @NotNull
  @VisibleForTesting
  Validator.Result updateForwardStatus(@NotNull String path) {
    resetValidationState();
    if (Strings.isNullOrEmpty(path)) {
      // Don't validate default empty input: jetbrains.github.io/ui/principles/validation_errors/#23
      return Validator.Result.OK;
    }
    PathValidationResult validationResult = evaluatePath(path);
    applyValidationResult(validationResult);
    return validationResult.result();
  }

  private void updateModuleValidation() {
    myModulesPanel.setModules(getModel().getProject(), myVFile, myModules);
    myModulesScroller.setVisible(myModulesPanel.getComponentCount() > 0);
    myRootPanel.revalidate(); // We may have added new UI
    myRootPanel.repaint();

    // Setting the active importer affects the visibility of other steps in the wizard so we need to call updateNavigationProperties
    // to make sure Finish / Next is displayed correctly
    ModuleImporter.setImporter(getModel().getContext(), myImporter);
    assert myFacade != null;
    myFacade.updateNavigationProperties();
  }

  private void updateForwardStatus(boolean hasValidPath) {
    // Validation of import location can be superseded by lack of modules selected for import
    myCanGoForward.set(hasValidPath && myModulesPanel.canImport() && !myModulesPanel.getSelectedModules().isEmpty());
  }

  @NotNull
  @VisibleForTesting
  Validator.Result checkPath(@NotNull String path) {
    PathValidationResult validationResult = evaluatePath(path);
    myVFile = validationResult.vFile();
    myImporter = validationResult.importer();
    myModules = validationResult.modules();
    return validationResult.result();
  }

  @NotNull
  private PathValidationResult evaluatePath(@NotNull String path) {
    return ReadAction.compute(() -> {
      VirtualFile vFile = VfsUtil.findFileByIoFile(new File(path), false);
      if (vFile == null || !vFile.exists()) {
        return new PathValidationResult(
          new Validator.Result(Validator.Severity.ERROR, message("android.wizard.module.import.source.browse.invalid.location")),
          null, null, null);
      }
      if (isProjectOrModule(vFile)) {
        return new PathValidationResult(
          new Validator.Result(Validator.Severity.ERROR, message("android.wizard.module.import.source.browse.taken.location")),
          vFile, null, null);
      }
      ModuleImporter importer = ModuleImporter.importerForLocation(getModel().getContext(), vFile);
      if (!importer.isValid()) {
        return new PathValidationResult(
          new Validator.Result(Validator.Severity.ERROR, message("android.wizard.module.import.source.browse.cant.import")),
          vFile, importer, null);
      }
      Collection<ModuleToImport> modules;
      try {
        modules = importer.findModules(vFile);
      }
      catch (IOException e) {
        Logger.getInstance(SourceToGradleModuleStep.class).error(e);
        return new PathValidationResult(
          new Validator.Result(Validator.Severity.ERROR, message("android.wizard.module.import.source.browse.error")),
          vFile, importer, null);
      }
      if (modules == null) {
        return new PathValidationResult(
          new Validator.Result(Validator.Severity.ERROR, message("android.wizard.module.import.source.browse.error")),
          vFile, importer, null);
      }
      Set<String> missingSourceModuleNames = Sets.newTreeSet();
      for (ModuleToImport module : modules) {
        if (module.location == null || !module.location.exists()) {
          missingSourceModuleNames.add(module.name);
        }
      }
      if (!missingSourceModuleNames.isEmpty()) {
        final String formattedMessage = FormatUtil.formatElementListString(
          missingSourceModuleNames,
          message("android.wizard.module.import.source.browse.bad.modules.1"),
          message("android.wizard.module.import.source.browse.bad.modules.2"),
          message("android.wizard.module.import.source.browse.bad.modules.more")
        );
        String htmlFormattedMessage = WizardUtils.toHtmlString(formattedMessage);
        return new PathValidationResult(
          new Validator.Result(Validator.Severity.WARNING, htmlFormattedMessage),
          vFile, importer, modules);
      }
      return new PathValidationResult(Validator.Result.OK, vFile, importer, modules);
    });
  }

  private record PathValidationResult(
    @NotNull Validator.Result result,
    @Nullable VirtualFile vFile,
    @Nullable ModuleImporter importer,
    @Nullable Collection<ModuleToImport> modules
  ) {}

  private boolean isProjectOrModule(@NotNull VirtualFile dir) {
    Project project = getModel().getProject();
    if (dir.equals(guessProjectDir(project))) {
      return true;
    }

    for (Module module : ModuleManager.getInstance(project).getModules()) {
      if (ModuleUtilCore.isModuleDir(module, dir)) {
        return true;
      }
    }

    return false;
  }

  private void createUIComponents() {
    myPrimaryModel = new PrimaryModuleImportSettings();
    myModuleNameLabel = myPrimaryModel.getModuleNameLabel();
    myModuleNameField = myPrimaryModel.getModuleNameField();
    myPrimaryModuleState = myPrimaryModel.getPrimaryModuleState();
  }

  private void setupUI() {
    createUIComponents();
    myPanel = new JPanel();
    myPanel.setLayout(new GridLayoutManager(7, 2, new Insets(0, 0, 0, 0), -1, -1));
    mySourceDirTitle = new JBLabel();
    mySourceDirTitle.setText("Gradle project");
    myPanel.add(mySourceDirTitle,
                new GridConstraints(0, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED,
                                    GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    final Spacer spacer1 = new Spacer();
    myPanel.add(spacer1, new GridConstraints(6, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1,
                                             GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null,
                                             new Dimension(1, 1), null, 0, false));
    final JBLabel jBLabel1 = new JBLabel();
    jBLabel1.setText("Source directory:");
    myPanel.add(jBLabel1,
                new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED,
                                    GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    mySourceLocation = new TextFieldWithBrowseButton();
    myPanel.add(mySourceLocation, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                                      GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null,
                                                      null, 0, false));
    final Spacer spacer2 = new Spacer();
    myPanel.add(spacer2,
                new GridConstraints(2, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, 1, new Dimension(-1, 16),
                                    new Dimension(-1, 40), new Dimension(-1, 40), 0, false));
    myRequiredModulesLabel = new JLabel();
    myRequiredModulesLabel.setText("Additional required modules:");
    myPanel.add(myRequiredModulesLabel,
                new GridConstraints(4, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED,
                                    GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myModulesScroller = new JBScrollPane();
    myModulesScroller.setHorizontalScrollBarPolicy(31);
    myModulesScroller.setVisible(false);
    myPanel.add(myModulesScroller, new GridConstraints(5, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                                                       GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                                                       GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null,
                                                       null, null, 0, false));
    myModulesPanel = new ModulesTable();
    myModulesScroller.setViewportView(myModulesPanel);
    final JPanel panel1 = new JPanel();
    panel1.setLayout(new GridLayoutManager(1, 3, new Insets(0, 0, 0, 0), -1, -1));
    myPanel.add(panel1, new GridConstraints(3, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                                            GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                            GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null,
                                            0, false));
    myModuleNameLabel.setHorizontalAlignment(2);
    myModuleNameLabel.setText("Module name");
    panel1.add(myModuleNameLabel,
               new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED,
                                   GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    panel1.add(myModuleNameField,
               new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED,
                                   GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    myPrimaryModuleState.setText("");
    panel1.add(myPrimaryModuleState, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                                         GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW,
                                                         GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
  }
}