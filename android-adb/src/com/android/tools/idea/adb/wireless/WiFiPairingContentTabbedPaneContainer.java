/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.adb.wireless;

import com.android.annotations.concurrency.UiThread;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import java.awt.BorderLayout;
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

/**
 * Top level component in each pairing tab. Contains a center panel for a custom component.
 */
@UiThread
public class WiFiPairingContentTabbedPaneContainer {
  @NotNull private JPanel myRootComponent;
  @NotNull private JPanel myCenterRow;

  public WiFiPairingContentTabbedPaneContainer() {
    setupUI();
  }

  public void setContent(@NotNull JComponent component) {
    myCenterRow.removeAll();
    myCenterRow.add(component, BorderLayout.CENTER);
  }

  private void setupUI() {
    myRootComponent = new JPanel();
    myRootComponent.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
    myCenterRow = new JPanel();
    myCenterRow.setLayout(new BorderLayout(0, 0));
    myRootComponent.add(myCenterRow, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                                                         GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                                         GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null,
                                                         null, null, 0, false));
  }

  public JComponent getRootComponent() { return myRootComponent; }
}
