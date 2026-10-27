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
package com.android.tools.adtui;


import com.google.common.annotations.VisibleForTesting;
import com.android.tools.adtui.common.AdtUiUtils;
import com.android.tools.adtui.model.filter.Filter;
import com.android.tools.adtui.model.filter.FilterModel;
import com.android.tools.adtui.stdui.CommonToggleButton;
import com.intellij.find.FindBundle;
import com.intellij.find.impl.RegExHelpPopup;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ShortcutSet;
import com.intellij.openapi.actionSystem.Toggleable;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.ex.TooltipDescriptionProvider;
import com.intellij.openapi.actionSystem.ex.TooltipLinkProvider;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.actionSystem.impl.FieldInplaceActionButtonLook;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.SearchFieldWithExtension;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import icons.StudioIcons;
import java.util.concurrent.CountDownLatch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.text.DecimalFormat;
import org.jetbrains.annotations.TestOnly;

/**
 * A modified version of IJ's FilterComponent that allows users to specify a custom delay between typing and
 * the filter box triggering a change event.
 */

public final class FilterComponent extends JPanel {
  static final String OPEN_AND_FOCUS_ACTION = "OpenAndFocusSearchAction";
  static final String CLOSE_ACTION = "CloseSearchAction";
  // The color of the search text when nothing matches, like in the editor's find bar.
  static final JBColor NO_MATCHES_COLOR = JBColor.namedColor("SearchField.errorForeground", NamedColorUtil.getErrorForeground());

  private final FilterModel myModel;

  private final OptionAction myRegexAction;
  private final OptionAction myMatchCaseAction;
  private JLabel myCountLabel;
  private final SearchTextField mySearchField;
  private final JComponent mySearchBar;
  private final Timer myTimer;
  private final Color mySearchFieldDefaultForeground;
  private int myMinTextFieldWidth;

  /**
   * For test code, we need to know when {@code myTimer} has finished firing, so we use a latch to detect this case.
   * Whenever the timer is running, the latch will be set to 1; otherwise, 0.
   */
  private CountDownLatch myTimerRunningLatch = new CountDownLatch(0);

  public FilterComponent(@NotNull Filter filter, int textFieldWidth, int historySize, int delayMs) {
    // The search bar takes the width left by the match count, so that it can shrink down to its minimum width.
    super(new TabularLayout("4px,*,20px,Fit-", "Fit-"));
    myMinTextFieldWidth = textFieldWidth;

    myModel = new FilterModel();
    myModel.setFilter(filter);

    addComponentListener(new ComponentAdapter() {
      @Override
      public void componentShown(ComponentEvent e) {
        super.componentShown(e);
        requestFocusInWindow();
      }
    });

    // Configure filter text field
    mySearchField = new SearchTextField() {
      @Override
      protected Runnable createItemChosenCallback(JList list) {
        final Runnable callback = super.createItemChosenCallback(list);
        return () -> {
          callback.run();
          updateModel();
        };
      }

      @Override
      protected Component getPopupLocationComponent() {
        return mySearchField;
      }

      @Override
      protected void onFocusLost() {
        addCurrentTextToHistory();
        super.onFocusLost();
      }
    };
    // The width of the search field when there is room for it, already scaled by the caller.
    mySearchField.setPreferredSize(new Dimension(textFieldWidth, mySearchField.getPreferredSize().height));
    if (!filter.isEmpty()) {
      mySearchField.setText(filter.getFilterString());
    }

    mySearchField.getTextEditor().addKeyListener(new KeyAdapter() {
      //to consume enter in combo box - do not process this event by default button from DialogWrapper
      @Override
      public void keyPressed(final KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER) {
          e.consume();
          mySearchField.addCurrentTextToHistory();
          updateModel();
        }
      }
    });

    myTimer = new Timer(delayMs, e -> {
      updateModel();
      myTimerRunningLatch.countDown();
    });
    myTimer.setRepeats(false);

    mySearchField.addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        onChanged();
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        onChanged();
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        onChanged();
      }

      private void onChanged() {
        myTimerRunningLatch = new CountDownLatch(1);
        myTimer.restart();
      }
    });

    mySearchField.setHistorySize(historySize);

    // Show the option buttons inside the search field, like in Logcat and the editor's find bar.
    myMatchCaseAction = new OptionAction(FindBundle.message("find.case.sensitive"), AllIcons.Actions.MatchCase,
                                         AllIcons.Actions.MatchCaseHovered, AllIcons.Actions.MatchCaseSelected, filter.isMatchCase());
    myRegexAction = new RegexAction(filter.isRegex());
    // Like in the find bar, Alt+<mnemonic> (Option on macOS) toggles an option, as its tooltip shows.
    for (OptionAction action : new OptionAction[]{myMatchCaseAction, myRegexAction}) {
      ShortcutSet shortcut = ActionUtil.getMnemonicAsShortcut(action);
      if (shortcut != null) {
        action.registerCustomShortcutSet(shortcut, this);
      }
    }
    JPanel optionButtons = new JPanel(new GridBagLayout());
    optionButtons.setOpaque(false);
    optionButtons.add(new OptionButton(myMatchCaseAction));
    optionButtons.add(Box.createHorizontalStrut(JBUI.scale(4)));
    optionButtons.add(new OptionButton(myRegexAction));
    mySearchBar = new SearchFieldWithExtension(optionButtons, mySearchField);
    add(mySearchBar, new TabularLayout.Constraint(0, 1));
    mySearchFieldDefaultForeground = mySearchField.getTextEditor().getForeground();

    myCountLabel = new JLabel();
    myCountLabel.setFont(myCountLabel.getFont().deriveFont(Font.BOLD));
    add(myCountLabel, new TabularLayout.Constraint(0, 3));

    Color countLabelDefaultForeground = myCountLabel.getForeground();
    myModel.addMatchResultListener(result -> {
      Color foreground = mySearchFieldDefaultForeground;
      Color countForeground = countLabelDefaultForeground;
      String text = "";
      if (result.isFilterEnabled()) {
        int count = result.getMatchCount();
        if (count == 0) {
          text = "No matches";
          foreground = NO_MATCHES_COLOR;
          countForeground = NO_MATCHES_COLOR;
        }
        else if (count == 1) {
          text = "One match";
        }
        else {
          text = new DecimalFormat("#,###").format(count) + " matches";
        }
      }
      mySearchField.getTextEditor().setForeground(foreground);
      myCountLabel.setForeground(countForeground);
      myCountLabel.setText(text);
    });
  }

  public FilterComponent(int textFieldWidth, int historySize, int delayMs) {
    this(Filter.EMPTY_FILTER, textFieldWidth, historySize, delayMs);
  }

  public void setFilterText(final String filterText) {
    mySearchField.setText(filterText);
  }

  @NotNull
  public FilterModel getModel() {
    return myModel;
  }

  @NotNull
  public FilterComponent setMatchCountVisibility(boolean value) {
    myCountLabel.setVisible(value);
    return this;
  }

  /**
   * Lets the search field shrink down to {@code minTextFieldWidth}, which is already scaled like the text field width, when this
   * component is laid out narrower than its preferred width.
   */
  @NotNull
  public FilterComponent setMinTextFieldWidth(int minTextFieldWidth) {
    myMinTextFieldWidth = minTextFieldWidth;
    revalidate();
    return this;
  }

  @Override
  public Dimension getMinimumSize() {
    // The layout leaves no room for the search bar, which can only shrink as much as its text field.
    Dimension size = super.getMinimumSize();
    size.width += mySearchBar.getPreferredSize().width - mySearchField.getPreferredSize().width + myMinTextFieldWidth;
    return size;
  }

  @Override
  public boolean requestFocusInWindow() {
    return mySearchField.requestFocusInWindow();
  }

  @VisibleForTesting
  @NotNull
  public SearchTextField getSearchField() {
    return mySearchField;
  }

  @VisibleForTesting
  @NotNull
  public JLabel getCountLabel() {
    return myCountLabel;
  }

  @TestOnly
  public void waitForFilterUpdated() throws InterruptedException {
    myTimerRunningLatch.await();
  }

  private void updateModel() {
    myModel.setFilter(new Filter(mySearchField.getText(), myMatchCaseAction.mySelected, myRegexAction.mySelected));
  }

  /** An option of the filter, toggled by a button inside the search field like in Logcat and the editor's find bar. */
  private class OptionAction extends DumbAwareToggleAction implements TooltipDescriptionProvider {
    private boolean mySelected;

    private OptionAction(@NotNull String text, @NotNull Icon icon, @NotNull Icon hoveredIcon, @NotNull Icon selectedIcon, boolean selected) {
      // Like the find bar's options, the tooltip says how to use the option from the keyboard.
      super(text, FindBundle.message("find.embedded.buttons.description"), icon);
      getTemplatePresentation().setHoveredIcon(hoveredIcon);
      getTemplatePresentation().setSelectedIcon(selectedIcon);
      mySelected = selected;
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
      return mySelected;
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
      mySelected = state;
      updateModel();
      // A shortcut toggles the option without going through its button, so refresh the buttons like the find bar does.
      UIUtil.findComponentsOfType(mySearchBar, ActionButton.class).forEach(ActionButton::update);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }
  }

  /** The regex option, whose tooltip links to help on regular expressions like in the editor's find bar. */
  private final class RegexAction extends OptionAction implements TooltipLinkProvider {
    private RegexAction(boolean selected) {
      super(FindBundle.message("find.regex"), AllIcons.Actions.Regex, AllIcons.Actions.RegexHovered, AllIcons.Actions.RegexSelected,
            selected);
    }

    @Override
    public @Nullable TooltipLink getTooltipLink(@Nullable JComponent owner) {
      return new TooltipLink(FindBundle.message("find.regex.help.link"), RegExHelpPopup.createRegExLinkRunnable(owner));
    }
  }

  /**
   * The button of an option, which looks like the find bar's option buttons: it shows the selected icon and background of the option
   * when it's on, and a border when focused.
   */
  private static final class OptionButton extends ActionButton {
    private OptionButton(@NotNull OptionAction action) {
      super(action, action.getTemplatePresentation().clone(), "FilterComponent", ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE);
      setLook(new FieldInplaceActionButtonLook());
      setFocusable(true);
      Toggleable.setSelected(getPresentation(), action.mySelected);
      updateIcon();
    }

    @Override
    public int getPopState() {
      return isSelected() ? SELECTED : super.getPopState();
    }

    @Override
    public @NotNull Icon getIcon() {
      Icon selectedIcon = getPresentation().getSelectedIcon();
      return isEnabled() && isSelected() && selectedIcon != null ? selectedIcon : super.getIcon();
    }
  }

  /**
   * A helper method for configuring the default key bindings and focus behavior for a {@link SearchComponent}.
   * Intended behavior:
   * Ctrl+F should make the SearchComponent visible and put focus on the filter textbox.
   * Esc should hide the searchComponent and clear the filter.
   * Clicking on the button should toggle the visibility of the SearchComponent, and in both caes, the filter textbox should be rest.
   *
   * @param containerComponent This is the component designated for handling the key events (Ctrl+F, Esc). It is expected that the key
   *                           bindings only work if this component (or one of its descendants) have focus
   *                           (See also JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT). Whenever the SearchComponent's visibility is
   *                           toggled, the containerComponent is revalidated to make sure the layout is up to date.
   * @param filterComponent    The SearchComponent instance that the containerComponent and showHideButton are associated with.
   * @param showHideButton     The toggle button used for showing/hiding the SearchComponent.
   */
  static public void configureKeyBindingAndFocusBehaviors(@NotNull JComponent containerComponent,
                                                          @NotNull FilterComponent filterComponent,
                                                          @NotNull JToggleButton showHideButton) {
    showHideButton.addActionListener(event -> {
      filterComponent.setVisible(showHideButton.isSelected());
      if (!showHideButton.isSelected()) {
        // Reset the filter content when dismissed
        filterComponent.setFilterText("");
      }
      else {
        filterComponent.requestFocusInWindow();
      }
      containerComponent.revalidate();
    });

    InputMap inputMap = containerComponent.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    ActionMap actionMap = containerComponent.getActionMap();

    inputMap.put(getFilterKeyStroke(), OPEN_AND_FOCUS_ACTION);
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLOSE_ACTION);
    actionMap.put(OPEN_AND_FOCUS_ACTION, new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        if (!showHideButton.isSelected()) {
          // Let the button's ActionListener handle the event.
          showHideButton.doClick(0);
        }
        else {
          // Otherwise, just reset the focus.
          filterComponent.requestFocusInWindow();
        }
      }
    });

    actionMap.put(CLOSE_ACTION, new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        if (!showHideButton.isSelected()) {
          // Do nothing since search component is not opened.
          return;
        }

        // Let the button's ActionListener handle the event.
        showHideButton.doClick(0);
        // Put the focus back on the container, so the key bindings still work.
        containerComponent.requestFocusInWindow();
      }
    });
  }

  @NotNull
  static public CommonToggleButton createFilterToggleButton() {
    CommonToggleButton filterButton = new CommonToggleButton("", AllIcons.General.Filter);
    filterButton.setToolTipText(String.format("Filter (%s)", KeymapUtil.getKeystrokeText(getFilterKeyStroke())));
    return filterButton;
  }

  private static KeyStroke getFilterKeyStroke() {
    return KeyStroke.getKeyStroke(KeyEvent.VK_F, AdtUiUtils.getActionMask());
  }
}

