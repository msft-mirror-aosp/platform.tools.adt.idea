/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.screenshottest.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.android.screenshottest.util.ImageData
import com.android.screenshottest.util.copyReferenceImages
import com.android.tools.idea.testartifacts.instrumented.testsuite.model.AndroidTestCaseResult
import com.android.tools.idea.testartifacts.instrumented.testsuite.util.logScreenshotTestEvent
import com.android.tools.idea.testartifacts.instrumented.testsuite.view.ScreenshotViewType
import com.google.common.annotations.VisibleForTesting
import com.google.wireless.android.sdk.stats.ScreenshotTestComposePreviewEvent
import com.intellij.accessibility.AccessibilityUtils
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.accessibility.AccessibleContextUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.io.File
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.ui.component.SegmentedControl
import org.jetbrains.jewel.ui.component.SegmentedControlButtonData
import org.jetbrains.jewel.ui.component.Text

/**
 * A dialog for selecting and viewing screenshot test previews. It features a two-pane layout with a tree of previews on the left and a
 * live-updating image viewer on the right.
 *
 * While the screenshots are generated, the dialog reports the current phase: building the project, then rendering previews with a running
 * count as results arrive.
 */
class UpdateReferenceImagesDialog(
  val project: Project?,
  private val logger: Logger = Logger.getInstance(UpdateReferenceImagesDialog::class.java),
) : DialogWrapper(project) {

  private val centerPanelCardLayout = CardLayout()
  private val centerPanel = JPanel(centerPanelCardLayout)
  private val loadingLabel = JBLabel(BUILDING_PROJECT_TEXT, AnimatedIcon.Default(), JBLabel.CENTER)
  private val progressLabel =
    JBLabel(renderingProgressText(0), AnimatedIcon.Default(), JBLabel.LEFT).apply {
      border = JBUI.Borders.empty(4, 8)
    }
  private var isFirstTestDiscovered = false
  private var isTestSuiteFinished = false
  private var renderedCount = 0
  private lateinit var tree: CheckboxTree
  private val placeholderLabel = JBLabel("Select a node from the left to see its previews.", JBLabel.CENTER)
  private val classNodeMap = mutableMapOf<String, CheckedTreeNode>()
  private val methodNodeMap = mutableMapOf<String, MutableMap<String, CheckedTreeNode>>()
  private lateinit var previewToolbar: JComponent
  private var selectedViewType by mutableStateOf(ScreenshotViewType.NEW)
  private lateinit var previewDetailsPanel: PreviewDetailsPanel
  private lateinit var rightPaneContent: JPanel
  private lateinit var rightPaneCardLayout: CardLayout
  private var isLeafSelected by mutableStateOf(false)
  private lateinit var rightPaneWrapper: JPanel

  private var buildProcessHandler: ProcessHandler? = null
  private var isCancelled = false

  /** The text shown while no preview has been rendered yet. */
  @get:VisibleForTesting
  val loadingText: String
    get() = loadingLabel.text

  /** The progress text shown below the preview tree, or `null` if it is hidden. */
  @get:VisibleForTesting
  val progressText: String?
    get() = progressLabel.text.takeIf { isFirstTestDiscovered && progressLabel.isVisible }

  fun setBuildProcessHandler(handler: ProcessHandler) {
    if (isCancelled) {
      handler.destroyProcess()
    } else {
      buildProcessHandler = handler
    }
  }

  override fun doCancelAction() {
    isCancelled = true
    buildProcessHandler?.destroyProcess()
    // Log the SCREENSHOT_DIALOG_CLOSE event
    logScreenshotTestEvent(ScreenshotTestComposePreviewEvent.Type.SCREENSHOT_DIALOG_CLOSE, project)
    super.doCancelAction()
  }

  init {
    isModal = false
    title = "Add/Update Reference Images"
    setOKButtonText("Add")
    okAction.isEnabled = false // The "Add" button is disabled until an image loads.
    setCancelButtonText("Cancel")
    isResizable = true
    init()
  }

  override fun getDimensionServiceKey(): String {
    return "com.android.screenshottest.ui.UpdateReferenceImagesDialog"
  }

  /** Reports that the project was built and the previews are being rendered. */
  fun onRenderingStarted() {
    ApplicationManager.getApplication().invokeLater {
      if (!isFirstTestDiscovered) {
        loadingLabel.text = RENDERING_PREVIEWS_TEXT
      }
    }
  }

  fun updateDialogWithTestResult(previewDetails: PreviewDetails, isChecked: Boolean) {
    ApplicationManager.getApplication().invokeLater {
      val className = previewDetails.className
      val methodName = previewDetails.methodName
      val previewName = previewDetails.previewName
      val testId = previewDetails.testId
      val srcImagePath = previewDetails.srcImagePath

      if (methodName.isNotBlank() && previewName.isNotBlank()) {
        if (!isFirstTestDiscovered) {
          isFirstTestDiscovered = true
          populateCenterPanel()
        }

        val root = tree.model.root as CheckedTreeNode
        val model = tree.model as DefaultTreeModel

        val classNode =
          classNodeMap.getOrPut(className) {
            val newNode = CheckedTreeNode(className.substringAfterLast('.'))
            newNode.isEnabled = true
            model.insertNodeInto(newNode, root, root.childCount)
            tree.expandPath(TreePath(root.path))
            newNode
          }

        val methodMap = methodNodeMap.getOrPut(className) { mutableMapOf() }
        val methodNode =
          methodMap.getOrPut(methodName) {
            val newNode = CheckedTreeNode(methodName)
            newNode.isEnabled = true
            model.insertNodeInto(newNode, classNode, classNode.childCount)
            tree.expandPath(TreePath(classNode.path))
            newNode
          }

        val leafNode = CheckedTreeNode(previewDetails)
        leafNode.isChecked = isChecked
        model.insertNodeInto(leafNode, methodNode, methodNode.childCount)
        tree.expandPath(TreePath(methodNode.path))

        if (srcImagePath == null) {
          logger.warn("Source image path missing. Test did not produce an image for testId: $testId")
        }
        renderedCount++
        progressLabel.text = renderingProgressText(renderedCount)
        updateRightPane(tree)
      } else {
        logger.warn("Missing methodName or previewName for tests in class $className")
      }
    }
  }

  /** Marks the run as over: stops reporting progress and lets the user add the previews that were rendered. */
  private fun finishRendering() {
    isTestSuiteFinished = true
    progressLabel.isVisible = false
    updateOkButtonState()
  }

  fun onTestSuiteFinished() {
    // If no tests were ever discovered by the time the suite finishes, it indicates a build
    // failure or that no tests were found to run. Close the dialog and show an error.
    ApplicationManager.getApplication().invokeLater {
      if (!isFirstTestDiscovered) {
        // Log the SCREENSHOT_DIALOG_TEST_RESULTS_EMPTY event
        logScreenshotTestEvent(
          ScreenshotTestComposePreviewEvent.Type.SCREENSHOT_DIALOG_TEST_RESULTS_EMPTY,
          project,
        )
        logger.error("No tests were discovered in the test suite")
        close(CANCEL_EXIT_CODE)
        Messages.showErrorDialog(
          project,
          "Error while generating screenshots",
          "Failed to generate screenshots",
        )
      } else {
        logger.debug("TestSuite finished. Enabling the 'Add' button.")
        finishRendering()
      }
    }
  }

  /**
   * Handles a failed build or test run.
   *
   * If the run fails before any preview is rendered, closes the dialog and opens the Run tool window to show errors. Otherwise, no more
   * results will arrive, so the previews rendered so far can be added.
   */
  fun onBuildFailed() {
    ApplicationManager.getApplication().invokeLater {
      if (isFirstTestDiscovered) {
        logger.warn("Build or execution failed after some previews were rendered.")
        finishRendering()
        return@invokeLater
      }
      // Only act if we haven't discovered any tests yet (meaning the failure happened during build
      // or startup)
      if (!isCancelled) {
        logger.warn("Build or execution failed. Closing dialog.")

        // Log the SCREENSHOT_DIALOG_BUILD_FAILURE event when build fails
        logScreenshotTestEvent(
          ScreenshotTestComposePreviewEvent.Type.SCREENSHOT_DIALOG_BUILD_FAILURE,
          project,
        )

        close(CANCEL_EXIT_CODE)

        // Open the Run window so the user can see the build error
        project?.let {
          ToolWindowManager.getInstance(it).getToolWindow(ToolWindowId.RUN)?.activate(null)
        }
      }
    }
  }

  private fun populateCenterPanel() {
    val splitter = OnePixelSplitter(false, 0.3f)

    rightPaneCardLayout = CardLayout()
    rightPaneContent = JPanel(rightPaneCardLayout)
    previewDetailsPanel = PreviewDetailsPanel(project)

    rightPaneContent.add(placeholderLabel, CARD_PLACEHOLDER)
    rightPaneContent.add(previewDetailsPanel, CARD_DETAILS)
    rightPaneCardLayout.show(rightPaneContent, CARD_PLACEHOLDER)

    rightPaneWrapper = JPanel(BorderLayout())
    previewToolbar = createPreviewToolbar()
    previewToolbar.isVisible = false
    rightPaneWrapper.add(rightPaneContent, BorderLayout.CENTER)
    rightPaneWrapper.add(previewToolbar, BorderLayout.SOUTH)

    val treeHeadingLabel =
      object : JBLabel("Preview Tree") {
          override fun getAccessibleContext(): AccessibleContext {
            if (accessibleContext == null) {
              accessibleContext =
                object : AccessibleJLabel() {
                  override fun getAccessibleRole() =
                    if (SystemInfoRt.isMac) {
                      AccessibilityUtils.GROUPED_ELEMENTS
                    } else {
                      AccessibleRole.LABEL
                    }
                }
            }
            return accessibleContext
          }
        }
        .apply {
          isFocusable = true
          AccessibleContextUtil.setName(this, "Heading: Preview Tree")
        }

    tree = createPreviewTree()
    val treeScrollPane =
      JBScrollPane(tree).apply {
        border = BorderFactory.createEmptyBorder()
        accessibleContext.accessibleName = "Preview Tree"
      }

    val treeContainer =
      JPanel(BorderLayout()).apply {
        add(treeHeadingLabel, BorderLayout.NORTH)
        add(treeScrollPane, BorderLayout.CENTER)
        add(progressLabel, BorderLayout.SOUTH)
      }

    splitter.firstComponent = treeContainer
    splitter.secondComponent = rightPaneWrapper
    centerPanel.add(splitter, CARD_CONTENT)
    centerPanelCardLayout.show(centerPanel, CARD_CONTENT)
  }

  override fun createCenterPanel(): JComponent {
    centerPanel.add(loadingLabel, CARD_LOADING)
    centerPanelCardLayout.show(centerPanel, CARD_LOADING)
    centerPanel.preferredSize = Dimension(800, 600)
    centerPanel.minimumSize = Dimension(550, 400)
    return centerPanel
  }

  private fun createPreviewTree(): CheckboxTree {
    val rootNode = CheckedTreeNode("Select previews to update")

    val renderer =
      object : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(
          tree: JTree,
          value: Any,
          selected: Boolean,
          expanded: Boolean,
          leaf: Boolean,
          row: Int,
          hasFocus: Boolean,
        ) {
          val userObject = (value as? CheckedTreeNode)?.userObject
          val displayText =
            when (userObject) {
              is PreviewDetails -> userObject.previewName
              else -> userObject?.toString() ?: ""
            }
          textRenderer.append(displayText)
        }
      }

    return CheckboxTree(renderer, rootNode).apply {
      isRootVisible = true
      addCheckboxTreeListener(
        object : CheckboxTreeListener {
          override fun nodeStateChanged(node: CheckedTreeNode) {
            updateOkButtonState()
          }
        }
      )
      addTreeSelectionListener { updateRightPane(this) }
      // Select the root node by default when the dialog opens.
      // The tree will be expanded dynamically as nodes are added.
      selectionPath = TreePath(rootNode.path)
    }
  }

  private fun createPreviewToolbar(): JComponent {
    return ComposePanel().apply {
      isFocusable = true
      setContent {
        SwingBridgeTheme {
          val availableViews =
            if (isLeafSelected) {
              ScreenshotViewType.values().toList()
            } else {
              ScreenshotViewType.values().filter { it != ScreenshotViewType.ALL }
            }
          val buttonData =
            remember(selectedViewType, isLeafSelected) {
              availableViews.map { viewId ->
                SegmentedControlButtonData(
                  selected = viewId == selectedViewType,
                  content = { _ ->
                    Text(
                      text = viewId.displayText,
                      modifier =
                        Modifier.selectable(
                            selected = viewId == selectedViewType,
                            onClick = {
                              selectedViewType = viewId
                              updateRightPane(tree)
                            },
                            role = Role.RadioButton,
                          )
                          .focusable(true),
                    )
                  },
                  onSelect = {
                    selectedViewType = viewId
                    updateRightPane(tree)
                  },
                )
              }
            }
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            SegmentedControl(buttons = buttonData, enabled = true)
          }
        }
      }
    }
  }

  private fun collectPreviews(startNode: CheckedTreeNode): List<PreviewDetails> {
    val previews = mutableListOf<PreviewDetails>()
    val nodesToVisit = ArrayDeque<CheckedTreeNode>().apply { add(startNode) }
    while (nodesToVisit.isNotEmpty()) {
      val currentNode = nodesToVisit.removeFirst()
      (currentNode.userObject as? PreviewDetails)?.let { previews.add(it) }
      for (child in currentNode.children()) {
        if (child is CheckedTreeNode) {
          nodesToVisit.add(child)
        }
      }
    }
    return previews
  }

  private fun updateRightPane(tree: CheckboxTree) {
    val selectedNode = tree.selectionPath?.lastPathComponent as? CheckedTreeNode
    if (selectedNode == null) {
      rightPaneCardLayout.show(rightPaneContent, CARD_PLACEHOLDER)
      previewToolbar.isVisible = false
      return
    }

    val previewsToShow = collectPreviews(selectedNode)
    isLeafSelected = selectedNode.isLeaf && selectedNode.userObject is PreviewDetails
    previewToolbar.isVisible = previewsToShow.isNotEmpty()

    if (previewsToShow.isEmpty()) {
      rightPaneCardLayout.show(rightPaneContent, CARD_PLACEHOLDER)
    } else {
      if (isLeafSelected) {
        rightPaneWrapper.remove(previewToolbar)
        previewToolbar.border = null
        previewDetailsPanel.displayPreviews(previewsToShow, selectedViewType, previewToolbar)
      } else {
        rightPaneWrapper.add(previewToolbar, BorderLayout.SOUTH)
        previewToolbar.border = BorderFactory.createMatteBorder(1, 0, 1, 0, JBColor.border())
        if (selectedViewType == ScreenshotViewType.ALL) {
          selectedViewType = ScreenshotViewType.NEW
        }
        previewDetailsPanel.displayPreviews(previewsToShow, selectedViewType, null)
      }

      rightPaneCardLayout.show(rightPaneContent, CARD_DETAILS)
    }
    rightPaneWrapper.revalidate()
    rightPaneWrapper.repaint()
  }

  private fun collectCheckedPreviews(): List<PreviewDetails> {
    val root = tree.model.root as CheckedTreeNode
    return TreeUtil.treeNodeTraverser(root)
      .mapNotNull { node ->
        val checkedNode = node as? CheckedTreeNode
        if (checkedNode != null && checkedNode.isLeaf && checkedNode.isChecked) {
          checkedNode.userObject as? PreviewDetails
        } else {
          null
        }
      }
      .toList()
  }

  private fun updateOkButtonState() {
    val hasCheckedPreviews = collectCheckedPreviews().isNotEmpty()
    okAction.isEnabled = hasCheckedPreviews && isTestSuiteFinished
  }

  override fun doOKAction() {
    val projectBasePath = project?.basePath
    if (projectBasePath.isNullOrBlank()) {
      logger.error("Project base path is missing. Reference image copy aborted for safety.")
      Messages.showErrorDialog(
        project,
        "Project base path is missing. Cannot add reference images.",
        "Error",
      )
      return
    }

    val checkedPreviews = collectCheckedPreviews()
    if (checkedPreviews.isEmpty()) {
      close(OK_EXIT_CODE)
      return
    }

    val imagesToCopy = checkedPreviews.map { previewDetails ->
      val sourceImageMap = mutableMapOf<String, String>()
      val simpleClassName = previewDetails.className.substringAfterLast('.')
      previewDetails.srcImagePath?.let { sourceImageMap[it] = simpleClassName }
      ImageData(previewDetails, sourceImageMap)
    }

    val missingFiles = imagesToCopy.filter {
      it.previewData.srcImagePath == null || !File(it.previewData.srcImagePath).exists()
    }
    if (missingFiles.isNotEmpty()) {
      val failedNames =
        missingFiles.joinToString(separator = "\n") {
          "- ${it.previewData.methodName}.${it.previewData.previewName}"
        }
      logger.error("The following selected previews have no source image: $failedNames")
      Messages.showErrorDialog(
        project,
        "The following selected previews have no source image. Please uncheck them to proceed:\n\n$failedNames",
        "Cannot Add Reference Images",
      )
      return
    }

    val okButton = getButton(okAction)
    val cancelButton = getButton(cancelAction)
    val originalText = okButton?.text
    val progressIcon = AnimatedIcon.Default()

    okButton?.text = "Updating..."
    okButton?.icon = progressIcon
    okButton?.isEnabled = false
    cancelButton?.isEnabled = false

    AppExecutorUtil.getAppExecutorService().submit {
      val failures = copyReferenceImages(imagesToCopy, projectBasePath)

      ApplicationManager.getApplication().invokeLater {
        if (failures.isEmpty()) {
          // Log the UPDATE_CLICKED event for analytics on successful copy of reference images.
          logScreenshotTestEvent(ScreenshotTestComposePreviewEvent.Type.UPDATE_CLICKED, project)
          close(OK_EXIT_CODE)
          logger.info("Reference images were updated successfully")
          Messages.showInfoMessage(
            project,
            "Reference images were updated successfully.",
            "Update Successful",
          )
        } else {
          // Log the SCREENSHOT_DIALOG_UPDATE_ACTION_FAILURE event for analytics
          // on failure to copy reference images
          logScreenshotTestEvent(
            ScreenshotTestComposePreviewEvent.Type.SCREENSHOT_DIALOG_UPDATE_ACTION_FAILURE,
            project,
          )
          val failedNames =
            failures.joinToString(separator = "\n") {
              "- ${it.previewData.methodName}.${it.previewData.previewName}"
            }
          logger.error("Failed to copy the following previews: $failedNames")
          Messages.showErrorDialog(
            project,
            "Failed to copy the following previews:\n\n$failedNames",
            "Copy Failed",
          )
          okButton?.text = originalText
          okButton?.icon = null
          okButton?.isEnabled = true
          cancelButton?.isEnabled = true
        }
      }
    }
  }
}

data class PreviewDetails(
  val testId: String,
  val className: String,
  val methodName: String,
  val previewName: String,
  val testResult: AndroidTestCaseResult? = null,
  val destImagePath: String? = null,
  val srcImagePath: String? = null,
  val diffImagePath: String? = null,
  val diffPercent: String? = null,
  val isSizeMismatch: Boolean = false,
  val sizeMismatchMessage: String? = null,
)

data class MethodGroup(
  val className: String,
  val methodName: String,
  val labelText: String,
  val previews: List<PreviewDetails>,
)
