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
package com.android.tools.idea.streaming.emulator

import com.android.sdklib.deviceprovisioner.DeviceType
import com.android.sdklib.internal.avd.AvdInfo
import com.android.sdklib.internal.avd.BootMode
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.testutils.ProcessHandleProviderRule
import com.android.tools.idea.avdmanager.AvdManagerConnection
import com.android.tools.idea.io.grpc.CallOptions
import com.android.tools.idea.io.grpc.Channel
import com.android.tools.idea.io.grpc.ClientCall
import com.android.tools.idea.io.grpc.ClientInterceptor
import com.android.tools.idea.io.grpc.ForwardingChannelBuilder2
import com.android.tools.idea.io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import com.android.tools.idea.io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener
import com.android.tools.idea.io.grpc.ManagedChannel
import com.android.tools.idea.io.grpc.ManagedChannelBuilder
import com.android.tools.idea.io.grpc.Metadata
import com.android.tools.idea.io.grpc.MethodDescriptor
import com.android.tools.idea.io.grpc.Status
import com.android.tools.idea.io.grpc.inprocess.InProcessChannelBuilder
import com.android.tools.idea.io.grpc.internal.GrpcUtil
import com.android.tools.idea.protobuf.MessageLite
import com.android.tools.idea.sdk.AndroidSdks
import com.android.tools.idea.sdk.AndroidSdksImpl
import com.android.tools.idea.sdk.IdeAvdManagers
import com.android.tools.idea.testing.AnalyticsPathsRule
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.TemporaryDirectory
import com.intellij.testFramework.registerOrReplaceServiceInstance
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import org.junit.rules.ExternalResource
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Allows tests to use [FakeEmulator] instead of the real one. */
class FakeEmulatorRule : TestRule {

  val avdRoot: Path by lazy { Files.createDirectories(userHome.resolve(".android/avd")) }
  private val emulators = mutableListOf<FakeEmulator>()
  private var availableGrpcPort = 8554 + instanceCounter++ // Don't reuse port numbers between tests to avoid interference.
  private var registrationDirectory: Path? = null
  private val savedUserHome = System.getProperty("user.home")
  private val tempDirectory = TemporaryDirectory()
  private var disposable: Disposable? = null
  private val root by lazy { Files.createDirectories(tempDirectory.newPath()) }
  private val userHome by lazy { Files.createDirectories(root.resolve("home")) }

  private val emulatorResource =
    object : ExternalResource() {
      override fun before() {
        val disposable = Disposer.newDisposable("FakeEmulatorRule").also { disposable = it }
        val grpcFactory =
          object : GrpcChannelBuilderFactory {
            override fun newGrpcChannelBuilder(host: String, port: Int): ManagedChannelBuilder<*> =
              TestGrpcChannelBuilder(FakeEmulator.grpcServerName(port))
          }
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(GrpcChannelBuilderFactory::class.java, grpcFactory, disposable)
        val emulatorCatalog = RunningEmulatorCatalog.getInstance()
        System.setProperty("user.home", userHome.toString())
        Files.createDirectories(userHome.resolve("Desktop"))
        registrationDirectory = Files.createDirectories(root.resolve("avd/running"))
        emulatorCatalog.overrideRegistrationDirectory(registrationDirectory)
        AvdManagerConnection.setConnectionFactory { sdkHandler, _ -> TestAvdManagerConnection(sdkHandler, avdRoot) }
        ApplicationManager.getApplication()?.registerOrReplaceServiceInstance(AndroidSdks::class.java, TestAndroidSdks(avdRoot), disposable)
      }

      override fun after() {
        val emulatorCatalog = RunningEmulatorCatalog.getInstance()
        val emulatorControllers = emulatorCatalog.emulators
        try {
          for (emulator in emulatorControllers) {
            emulator.shutdown()
          }
          for (emulator in emulators) {
            emulator.stop()
          }
        } finally {
          emulators.clear()
          disposable?.let { Disposer.dispose(it) }
          for (emulator in emulatorControllers) {
            emulator.awaitTermination(1.seconds)
          }
          System.setProperty("user.home", savedUserHome)
          registrationDirectory = null
          emulatorCatalog.overrideRegistrationDirectory(null)
          AvdManagerConnection.resetConnectionFactory()
        }
      }
    }

  override fun apply(base: Statement, description: Description): Statement {
    return RuleChain(
        AnalyticsPathsRule(),
        ApplicationRule(),
        tempDirectory,
        emulatorResource,
        ProcessHandleProviderRule(),
      )
      .apply(base, description)
  }

  fun newPath(): Path = tempDirectory.newPath()

  fun newEmulator(avdFolder: Path): FakeEmulator {
    val dir = registrationDirectory ?: throw IllegalStateException()
    val emulator = FakeEmulator(avdFolder, availableGrpcPort++, dir)
    emulators.add(emulator)
    return emulator
  }

  private class TestAndroidSdks(avdRoot: Path) : AndroidSdksImpl() {
    private val sdkHandler by lazy {
      val sdkRoot = FakeEmulator.getSdkFolder(avdRoot)
      AndroidSdkHandler(sdkRoot, sdkRoot)
    }

    override fun tryToChooseSdkHandler(): AndroidSdkHandler = sdkHandler
  }

  private inner class TestAvdManagerConnection(sdkHandler: AndroidSdkHandler, avdHomeFolder: Path) :
    AvdManagerConnection(sdkHandler, IdeAvdManagers.getAvdManager(sdkHandler, avdHomeFolder), Dispatchers.Unconfined) {

    override fun getAvds(forceRefresh: Boolean): List<AvdInfo> {
      return super.getAvds(true) // Always refresh in tests.
    }

    override fun findAvdWithFolder(avdFolder: Path): AvdInfo? {
      super.getAvds(true) // Refresh AVDs in tests.
      return super.findAvdWithFolder(avdFolder)
    }

    override suspend fun startAvd(project: Project?, avd: AvdInfo, forceLaunchInToolWindow: Boolean, bootMode: BootMode) {
      val emulator =
        emulators.firstOrNull { it.avdFolder == avd.dataFolderPath } ?: throw IllegalArgumentException("Unknown AVD: ${avd.id}")
      emulator.start(standalone = !forceLaunchInToolWindow)
      if (emulator.deviceType == DeviceType.AI_GLASSES) {
        val paired = emulator.pairedDevice
        if (paired != null && !paired.isRunning) {
          paired.start(standalone = !forceLaunchInToolWindow)
        }
      }
    }
  }

  /** Wraps [InProcessChannelBuilder] to enforce [maxInboundMessageSize], which `InProcessTransport` ignores by default. */
  private class TestGrpcChannelBuilder(name: String) : ForwardingChannelBuilder2<TestGrpcChannelBuilder>() {
    private val delegate = InProcessChannelBuilder.forName(name)
    private var maxInboundMessageSize = GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE

    override fun delegate(): ManagedChannelBuilder<*> = delegate

    override fun maxInboundMessageSize(bytes: Int): TestGrpcChannelBuilder {
      maxInboundMessageSize = bytes
      delegate.maxInboundMessageSize(bytes)
      return this
    }

    override fun build(): ManagedChannel {
      val channelMaxInboundMessageSize = maxInboundMessageSize
      delegate.intercept(
        object : ClientInterceptor {
          override fun <ReqT, RespT> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: Channel,
          ): ClientCall<ReqT, RespT> {
            val maxSize = callOptions.maxInboundMessageSize ?: channelMaxInboundMessageSize
            val call = next.newCall(method, callOptions)
            return object : SimpleForwardingClientCall<ReqT, RespT>(call) {
              override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                super.start(
                  object : SimpleForwardingClientCallListener<RespT>(responseListener) {
                    private val closed = AtomicBoolean(false)

                    override fun onMessage(message: RespT) {
                      val size = (message as? MessageLite)?.serializedSize ?: 0
                      if (size > maxSize) {
                        if (closed.compareAndSet(false, true)) {
                          val status = Status.RESOURCE_EXHAUSTED.withDescription("gRPC message exceeds maximum size $maxSize: $size")
                          call.cancel(status.description, null)
                          delegate().onClose(status, Metadata())
                        }
                      } else if (!closed.get()) {
                        super.onMessage(message)
                      }
                    }

                    override fun onClose(status: Status, trailers: Metadata) {
                      if (closed.compareAndSet(false, true)) {
                        super.onClose(status, trailers)
                      }
                    }
                  },
                  headers,
                )
              }
            }
          }
        }
      )
      return delegate.build()
    }
  }

  companion object {
    private var instanceCounter: Int = 0
  }
}
