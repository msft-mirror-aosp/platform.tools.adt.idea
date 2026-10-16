/*
 * Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.base.qsync;

import com.google.idea.blaze.base.bazel.BuildSystem;
import com.google.idea.blaze.base.bazel.BuildSystem.BuildInvoker;
import com.google.idea.blaze.base.command.info.BlazeInfo;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.sync.SyncScope.SyncFailedException;
import com.google.idea.blaze.exception.BuildException;
import java.util.Optional;

/**
 * Provides the output of {@code bazel info} for query sync: the Bazel version, and the other values
 * that sync needs, such as the output base. It gets them from one {@link BuildInvoker}, which runs
 * {@code bazel info} once and keeps the output. Handle exception casting to avoid sync runner to
 * handle too many exceptions.
 */
public class BazelInfoProvider {
  private final BuildSystem buildSystem;
  private final BuildInvoker buildInvoker;

  public BazelInfoProvider(BuildSystem buildSystem, BuildInvoker buildInvoker) {
    this.buildSystem = buildSystem;
    this.buildInvoker = buildInvoker;
  }

  public Optional<String> getBazelVersion(BlazeContext blazeContext) throws BuildException {
    return buildSystem.getBazelVersionString(getBlazeInfo(blazeContext));
  }

  /** Returns the output of {@code bazel info}. */
  public BlazeInfo getBlazeInfo(BlazeContext blazeContext) throws BuildException {
    try {
      return buildInvoker.getBlazeInfo(blazeContext);
    } catch (SyncFailedException e) {
      throw new BuildException("Could not get bazel info", e);
    }
  }
}
