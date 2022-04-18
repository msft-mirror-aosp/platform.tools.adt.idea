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
package com.android.tools.idea.devicemanager.virtualtab;

import com.android.sdklib.internal.avd.AvdInfo;
import com.android.tools.idea.avdmanager.AvdManagerConnection;
import com.android.tools.idea.devicemanager.Device;
import com.android.tools.idea.devicemanager.DeviceTableCellRenderer;
import com.android.tools.idea.devicemanager.physicaltab.Key;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.concurrency.EdtExecutorService;
import java.awt.Component;
import javax.swing.JTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class VirtualDeviceTableCellRenderer extends DeviceTableCellRenderer<VirtualDevice> {
  private final @NotNull ListeningExecutorService myService;

  VirtualDeviceTableCellRenderer() {
    super(VirtualDevice.class);
    myService = MoreExecutors.listeningDecorator(AppExecutorUtil.getAppExecutorService());
  }

  @Override
  public @NotNull Component getTableCellRendererComponent(@NotNull JTable table,
                                                          @NotNull Object value,
                                                          boolean selected,
                                                          boolean focused,
                                                          int viewRowIndex,
                                                          int viewColumnIndex) {
    AvdInfo avd = (AvdInfo)value;
    Device device = VirtualDevices.build(avd, false);

    // noinspection UnstableApiUsage, ConstantConditions
    FluentFuture.from(myService.submit(AvdManagerConnection::getDefaultAvdManagerConnection))
      .transformAsync(connection -> connection.isAvdRunningAsync(avd), myService)
      .addCallback(new SetOnline((VirtualDeviceTableModel)table.getModel(), device.getKey()), EdtExecutorService.getInstance());

    return super.getTableCellRendererComponent(table, device, selected, focused, viewRowIndex, viewColumnIndex);
  }

  private static final class SetOnline implements FutureCallback<Boolean> {
    private final @NotNull VirtualDeviceTableModel myModel;
    private final @NotNull Key myKey;

    private SetOnline(@NotNull VirtualDeviceTableModel model, @NotNull Key key) {
      myModel = model;
      myKey = key;
    }

    @Override
    public void onSuccess(@Nullable Boolean online) {
      assert online != null;
      myModel.setOnline(myKey, online);
    }

    @Override
    public void onFailure(@NotNull Throwable throwable) {
      Logger.getInstance(VirtualDeviceTableCellRenderer.class).warn(throwable);
    }
  }

  @Override
  protected boolean isOnline(@NotNull VirtualDevice device, @NotNull JTable table) {
    return ((VirtualDeviceTableModel)table.getModel()).isOnline(device.getKey());
  }

  @Override
  protected @NotNull String getLine2(@NotNull VirtualDevice device) {
    return device.getTarget() + " | " + device.getCpuArchitecture();
  }
}
