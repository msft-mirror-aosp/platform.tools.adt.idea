/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.tools.rendering.security

import com.android.tools.rendering.classloading.TestClassLoader
import com.android.tools.rendering.classloading.fromBinaryNameToPackageName
import com.android.tools.rendering.classloading.setupTestClassLoaderWithTransformation
import com.intellij.openapi.util.io.FileUtil
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.dnd.DragSource
import java.awt.print.PrinterJob
import java.beans.Expression
import java.beans.Statement
import java.beans.XMLDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.PrintStream
import java.io.RandomAccessFile
import java.io.Serializable
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.util.ServiceLoader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.spi.ServiceRegistry
import javax.print.PrintServiceLookup
import javax.script.ScriptEngineManager
import javax.swing.JEditorPane
import javax.swing.LayoutStyle
import javax.swing.MenuSelectionManager
import javax.swing.PopupFactory
import javax.swing.RepaintManager
import javax.swing.UIDefaults
import javax.swing.UIManager
import javax.swing.text.JTextComponent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import sun.misc.Unsafe

private val testingDirectory = Files.createTempDirectory("renderSandbox")
private val allowedDirectory = Files.createDirectory(testingDirectory.resolve("allowed"))
private val disallowedDirectory = Files.createDirectory(testingDirectory.resolve("disallowed"))
private val allowedFile = Files.createFile(allowedDirectory.resolve("file"))
private val disallowedFile = Files.createFile(disallowedDirectory.resolve("file"))
private val testingDirectoryPath = testingDirectory.toAbsolutePath().toString()
private val allowedFilePath = allowedFile.toAbsolutePath().toString()
private val disallowedFilePath = disallowedFile.toAbsolutePath().toString()

/** Common methods to be ignored when intercepting. */
private val commonMethods = setOf("toString", "equals", "hashCode", "getClass", "notify", "notifyAll", "wait", "clone", "finalize")

private fun removeTestingDirPrefix(path: String): String =
  FileUtil.toSystemIndependentName(path).replace(FileUtil.toSystemIndependentName(testingDirectoryPath), "")

class MaliciousSerializable : Serializable {
  private fun readObject(stream: ObjectInputStream) {
    stream.defaultReadObject()
    System.exit(0)
  }
}

class TestSupplier : Supplier<String> {
  override fun get(): String = "test"
}

class TestRunnable : Runnable {
  override fun run() {}
}

open class SubclassFile(pathname: String) : File(pathname) {
  fun customDelete(): Boolean = super.delete()
}

open class BenignModel {
  @JvmField var modelField: String = "test_val"

  fun getName(): String = "name"

  fun getValue(): String = "val"

  fun execute(): String = "ok"
}

open class MaliciousToctouStatement(private val realTarget: Any, private val realMethod: String) :
  Statement(Any(), "toString", emptyArray()) {
  private var accessCount = 0

  override fun getTarget(): Any = if (accessCount++ == 0) "benign" else realTarget

  override fun getMethodName(): String = if (accessCount++ <= 1) "toString" else realMethod
}

interface ClassToCheck {
  fun checkFileRead1()

  fun checkFileRead2()

  fun checkFileRead3()

  fun checkFileRead4()

  fun checkFileRead5()

  fun checkReadText()

  fun checkReadTextWithCharset()

  fun checkReadBytes()

  fun checkWriteText()

  fun checkFileWrite1()

  fun checkFileWrite2()

  fun checkFileWrite3()

  fun checkFileWrite4()

  fun checkFileWrite5()

  fun checkTempCreation()

  fun checkSystemExit1()

  fun checkSystemExit2()

  fun checkSystemExit3()

  fun checkPropertyRead()

  fun checkPropertyWrite()

  fun checkListProperties()

  fun checkGetEnv1()

  fun checkGetEnv2()

  fun checkIoSet1()

  fun checkIoSet2()

  fun checkIoSet3()

  fun checkClassForName1()

  fun checkClassForName2()

  fun checkResourceLoading1()

  fun checkResourceLoading2()

  fun checkProcessExec1()

  fun checkProcessExec2()

  fun checkProcessExec3()

  fun checkLoadLibrary1()

  fun checkLoadLibrary2()

  fun checkRandomAccessFile()

  fun checkDatagramSocket()

  fun checkClassLoaderCreation()

  fun checkAllowlistedPropertyWrite()

  fun checkClipboard()

  fun checkEventQueue()

  fun checkKeyboardFocusManager()

  fun checkKeymapDefaultAction()

  fun checkPopupFactory()

  fun checkLayoutStyle()

  fun checkUIManagerPut()

  fun checkRepaintManager()

  fun checkWindowGetWindows()

  fun checkFrameGetFrames()

  fun checkKeyboardFocusManagerSetCurrent()

  fun checkUIManagerSetLookAndFeel()

  fun checkUIDefaultsPut()

  fun checkDragSourceGetDefault()

  fun checkDragSourceAddListener(dragSource: DragSource)

  fun checkMenuSelectionManager()

  fun checkPrintJob()

  fun checkPrinterJobLookup()

  fun checkPrintServiceLookupRegister()

  fun checkPrintServiceLookup()

  fun checkIIORegistryGetDefault()

  fun checkIIORegistryDeregisterAll()

  fun checkServiceRegistry()

  fun checkServiceRegistryLookupProviders()

  fun checkImageIOScanForPlugins()

  fun checkJEditorPaneRegisterEditorKit()

  fun checkFileChannelOpen(path: Path)

  fun checkZipFile()

  fun checkURLOpenStream()

  fun checkReflectionInvoke()

  fun checkReflectionInvokePrintServiceLookup()

  fun checkReflectionInvokeIIORegistry()

  fun checkUnsafe()

  fun tryDefineClass(): Class<*>

  fun tryInvokeMethodHandle()

  fun checkFindStatic()

  fun checkUnreflect()

  fun tryDeserialization(bytes: ByteArray)

  fun tryConnect()

  fun checkCompletableFuture()

  fun checkForkJoinPool()

  fun checkThreadPoolExecutor()

  fun checkFileSystemProviderOutputStream(path: Path)

  fun checkFileSystemProviderDelete(path: Path)

  fun checkStatementExecute()

  fun checkStatementNew()

  fun checkExpressionGetValue()

  fun checkSubclassStatementExecute()

  fun checkScriptEngineManager()

  fun checkServiceLoaderLoad()

  fun checkURLSetFactory()

  fun checkMethodHandlesFindVirtual()

  fun checkMethodHandlesFindConstructor()

  fun checkMethodHandlesFindGetter()

  fun checkMethodHandlesUnreflectGetter()

  fun checkMethodHandlesFindVarHandle()

  fun checkMethodHandlesSandboxInternalField()

  fun checkSubclassFileDelete()

  fun checkSubclassFileSuperDelete()

  fun checkSubclassFileCreateNewFile()

  fun checkConstructorSetAccessible()

  fun checkFileChannelOpenWrite()

  fun checkBenignReflection(): String

  fun checkBenignMethodHandles(): String

  fun checkBenignFieldAccess(): String

  fun checkFileChannelOpenDeleteOnClose()

  fun checkFilesNewInputStreamDeleteOnClose()

  fun checkMethodHandlesDummyClassLoader(dummyLoader: ClassLoader)

  fun checkMethodHandlesFindGetterDummyClassLoader(dummyLoader: ClassLoader)

  fun checkXmlDecoderDirect()

  fun checkXmlDecoderReflection()

  fun checkXmlDecoderConstructorNewInstance()

  fun checkProcessImplStartReflection(clazz: Class<*>)

  fun checkMethodHandlesBind()

  fun checkFileSystemProviderGetFileAttributeView()

  fun checkMethodHandlesPrivateLookupIn()

  fun checkProcessBuilderStartPipeline()

  fun checkFilesCreateLink(link: Path, existing: Path)

  fun checkFileSystemProviderCreateLink(link: Path, existing: Path)
}

class ClassToCheckImpl : ClassToCheck {
  private val disallowedFile = File(disallowedFilePath)
  private val allowedFile = File(allowedFilePath)

  override fun checkFileRead1() {
    disallowedFile.isDirectory()
  }

  override fun checkFileRead2() {
    disallowedFile.isFile()
  }

  override fun checkFileRead3() {
    disallowedFile.length()
  }

  override fun checkFileRead4() {
    disallowedFile.list()
  }

  override fun checkFileRead5() {
    disallowedFile.parentFile
  }

  override fun checkReadText() {
    disallowedFile.readText()
  }

  override fun checkReadTextWithCharset() {
    disallowedFile.readText(Charset.defaultCharset())
  }

  override fun checkReadBytes() {
    disallowedFile.readBytes()
  }

  override fun checkWriteText() {
    disallowedFile.writeText("test")
  }

  override fun checkFileWrite1() {
    disallowedFile.mkdir()
  }

  override fun checkFileWrite2() {
    disallowedFile.mkdirs()
  }

  override fun checkFileWrite3() {
    disallowedFile.setLastModified(0)
  }

  override fun checkFileWrite4() {
    allowedFile.renameTo(disallowedFile)
  }

  override fun checkFileWrite5() {
    disallowedFile.createNewFile()
  }

  override fun checkTempCreation() {
    File.createTempFile("test", "a")
  }

  override fun checkSystemExit1() {
    System.exit(0)
  }

  override fun checkSystemExit2() {
    Runtime.getRuntime().exit(0)
  }

  override fun checkSystemExit3() {
    Runtime.getRuntime().halt(0)
  }

  override fun checkPropertyRead() {
    System.getProperty("property.test")
  }

  override fun checkPropertyWrite() {
    System.setProperty("property.test", "A")
  }

  override fun checkListProperties() {
    System.getProperties()
  }

  override fun checkGetEnv1() {
    System.getenv()
  }

  override fun checkGetEnv2() {
    System.getenv("env")
  }

  override fun checkIoSet1() {
    System.setIn(ByteArrayInputStream(ByteArray(1)))
  }

  override fun checkIoSet2() {
    System.setOut(PrintStream(ByteArrayOutputStream(1)))
  }

  override fun checkIoSet3() {
    System.setErr(PrintStream(ByteArrayOutputStream(1)))
  }

  override fun checkClassForName1() {
    Class.forName("java.lang.String")
  }

  override fun checkClassForName2() {
    Class.forName("java.lang.String", false, null)
  }

  override fun checkResourceLoading1() {
    this.javaClass.getResource("resource1")
  }

  override fun checkResourceLoading2() {
    this.javaClass.getResourceAsStream("resource2")
  }

  override fun checkProcessExec1() {
    @Suppress("DEPRECATION") Runtime.getRuntime().exec("ls")
  }

  override fun checkProcessExec2() {
    Runtime.getRuntime().exec(arrayOf("ls"))
  }

  override fun checkProcessExec3() {
    ProcessBuilder().command("ls").start()
  }

  override fun checkLoadLibrary1() {
    Runtime.getRuntime().load("library")
  }

  override fun checkLoadLibrary2() {
    Runtime.getRuntime().loadLibrary("library")
  }

  override fun checkRandomAccessFile() {
    RandomAccessFile(disallowedFilePath, "r")
  }

  override fun checkDatagramSocket() {
    DatagramSocket()
  }

  override fun checkClassLoaderCreation() {
    URLClassLoader(arrayOf())
  }

  override fun checkAllowlistedPropertyWrite() {
    System.setProperty("user.timezone", "GMT")
  }

  override fun checkClipboard() {
    Toolkit.getDefaultToolkit().systemClipboard
  }

  override fun checkEventQueue() {
    Toolkit.getDefaultToolkit().systemEventQueue
  }

  override fun checkKeyboardFocusManager() {
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(null)
  }

  override fun checkKeymapDefaultAction() {
    val keymap = JTextComponent.addKeymap("testKeymap", null)
    keymap.defaultAction = null
  }

  override fun checkPopupFactory() {
    PopupFactory.setSharedInstance(PopupFactory())
  }

  override fun checkLayoutStyle() {
    LayoutStyle.setInstance(null)
  }

  override fun checkUIManagerPut() {
    UIManager.put("Test.key", "Test.value")
  }

  override fun checkRepaintManager() {
    RepaintManager.setCurrentManager(RepaintManager())
  }

  override fun checkWindowGetWindows() {
    Window.getWindows()
  }

  override fun checkFrameGetFrames() {
    Frame.getFrames()
  }

  override fun checkKeyboardFocusManagerSetCurrent() {
    KeyboardFocusManager.setCurrentKeyboardFocusManager(null)
  }

  override fun checkUIManagerSetLookAndFeel() {
    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())
  }

  override fun checkUIDefaultsPut() {
    UIDefaults().put("test.key", "test.value")
  }

  override fun checkDragSourceGetDefault() {
    DragSource.getDefaultDragSource()
  }

  override fun checkDragSourceAddListener(dragSource: DragSource) {
    dragSource.addDragSourceListener(null)
  }

  override fun checkMenuSelectionManager() {
    MenuSelectionManager.defaultManager().addChangeListener(null)
  }

  override fun checkPrintJob() {
    PrinterJob.getPrinterJob()
  }

  override fun checkPrinterJobLookup() {
    PrinterJob.lookupPrintServices()
  }

  override fun checkPrintServiceLookupRegister() {
    PrintServiceLookup.registerServiceProvider(null)
  }

  override fun checkPrintServiceLookup() {
    PrintServiceLookup.lookupDefaultPrintService()
  }

  override fun checkIIORegistryGetDefault() {
    IIORegistry.getDefaultInstance()
  }

  override fun checkIIORegistryDeregisterAll() {
    val reg = ServiceRegistry(listOf<Class<*>>(ImageReaderSpi::class.java).iterator())
    reg.deregisterAll()
  }

  override fun checkServiceRegistry() {
    val reg = ServiceRegistry(listOf<Class<*>>(ImageReaderSpi::class.java).iterator())
    reg.registerServiceProvider(Any())
  }

  override fun checkServiceRegistryLookupProviders() {
    ServiceRegistry.lookupProviders(ImageReaderSpi::class.java)
  }

  override fun checkImageIOScanForPlugins() {
    ImageIO.scanForPlugins()
  }

  override fun checkJEditorPaneRegisterEditorKit() {
    JEditorPane.registerEditorKitForContentType("text/html", "EvilKit")
  }

  override fun checkFileChannelOpen(path: Path) {
    FileChannel.open(path)
  }

  override fun checkZipFile() {
    ZipFile(disallowedFilePath)
  }

  override fun checkURLOpenStream() {
    @Suppress("DEPRECATION") URL("http://localhost").openStream()
  }

  override fun checkReflectionInvoke() {
    val method = System::class.java.getMethod("exit", Int::class.javaPrimitiveType)
    method.invoke(null, 0)
  }

  override fun checkReflectionInvokePrintServiceLookup() {
    val method = PrintServiceLookup::class.java.getMethod("lookupDefaultPrintService")
    method.invoke(null)
  }

  override fun checkReflectionInvokeIIORegistry() {
    val method = IIORegistry::class.java.getMethod("getDefaultInstance")
    method.invoke(null)
  }

  override fun checkUnsafe() {
    Unsafe.getUnsafe()
  }

  override fun tryDefineClass(): Class<*> {
    val cw = ClassWriter(0)
    cw.visit(
      Opcodes.V1_7,
      Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
      "Malicious",
      null,
      "java/lang/Object",
      null,
    )
    val mv = cw.visitMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "run", "()V", null, null)
    mv.visitCode()
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "exit", "(I)V", false)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 1)
    mv.visitEnd()
    cw.visitEnd()
    val bytes = cw.toByteArray()
    return MethodHandles.lookup().defineClass(bytes)
  }

  override fun tryInvokeMethodHandle() {
    val lookup = MethodHandles.lookup()
    val type = MethodType.methodType(Void.TYPE, Int::class.javaPrimitiveType)
    val handle = lookup.findStatic(System::class.java, "exit", type)
    handle.invoke(0)
  }

  override fun checkFindStatic() {
    val lookup = MethodHandles.lookup()
    val type = MethodType.methodType(Void.TYPE, Int::class.javaPrimitiveType)
    lookup.findStatic(System::class.java, "exit", type)
  }

  override fun checkUnreflect() {
    val lookup = MethodHandles.lookup()
    val method = System::class.java.getMethod("exit", Int::class.javaPrimitiveType)
    lookup.unreflect(method)
  }

  override fun tryDeserialization(bytes: ByteArray) {
    val ois = ObjectInputStream(ByteArrayInputStream(bytes))
    ois.readObject()
  }

  override fun tryConnect() {
    val socket = Socket()
    socket.connect(InetSocketAddress("localhost", 8080), 100)
  }

  // Suppressed because we want to test that the sandbox intercepts implicit executors.
  @Suppress("ImplicitExecutor")
  override fun checkCompletableFuture() {
    CompletableFuture.supplyAsync(TestSupplier())
  }

  // Suppressed because we want to test that the sandbox intercepts common ForkJoinPool usage.
  @Suppress("CommonForkJoinPool")
  override fun checkForkJoinPool() {
    ForkJoinPool.commonPool().submit(TestRunnable())
  }

  override fun checkThreadPoolExecutor() {
    ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
  }

  override fun checkFileSystemProviderOutputStream(path: Path) {
    FileSystems.getDefault().provider().newOutputStream(path)
  }

  override fun checkFileSystemProviderDelete(path: Path) {
    FileSystems.getDefault().provider().delete(path)
  }

  override fun checkStatementExecute() {
    Statement(Runtime.getRuntime(), "exec", arrayOf("ls")).execute()
  }

  override fun checkStatementNew() {
    Statement(FileOutputStream::class.java, "new", arrayOf(disallowedFilePath)).execute()
  }

  override fun checkExpressionGetValue() {
    Expression(Runtime.getRuntime(), "exec", arrayOf("ls")).value
  }

  override fun checkSubclassStatementExecute() {
    MaliciousToctouStatement(Runtime.getRuntime(), "exec").execute()
  }

  override fun checkScriptEngineManager() {
    ScriptEngineManager()
  }

  override fun checkServiceLoaderLoad() {
    ServiceLoader.load(String::class.java)
  }

  override fun checkURLSetFactory() {
    URL.setURLStreamHandlerFactory(null)
  }

  override fun checkMethodHandlesFindVirtual() {
    val lookup = MethodHandles.lookup()
    val type = MethodType.methodType(Process::class.java)
    lookup.findVirtual(ProcessBuilder::class.java, "start", type)
  }

  override fun checkMethodHandlesFindConstructor() {
    val lookup = MethodHandles.lookup()
    val type = MethodType.methodType(Void.TYPE)
    lookup.findConstructor(ClassLoader::class.java, type)
  }

  override fun checkMethodHandlesFindGetter() {
    val lookup = MethodHandles.lookup()
    lookup.findGetter(File::class.java, "path", String::class.java)
  }

  override fun checkMethodHandlesUnreflectGetter() {
    val lookup = MethodHandles.lookup()
    val field = File::class.java.getDeclaredField("path")
    lookup.unreflectGetter(field)
  }

  override fun checkMethodHandlesFindVarHandle() {
    val lookup = MethodHandles.lookup()
    lookup.findVarHandle(File::class.java, "path", String::class.java)
  }

  override fun checkMethodHandlesSandboxInternalField() {
    val lookup = MethodHandles.lookup()
    lookup.findGetter(RenderSandboxTransformTrampoline::class.java, "defaultInterceptors", List::class.java)
  }

  override fun checkSubclassFileDelete() {
    val evil = SubclassFile(disallowedFilePath)
    evil.delete()
  }

  override fun checkSubclassFileSuperDelete() {
    val evil = SubclassFile(disallowedFilePath)
    evil.customDelete()
  }

  override fun checkSubclassFileCreateNewFile() {
    val evil = SubclassFile(disallowedFilePath)
    evil.createNewFile()
  }

  override fun checkConstructorSetAccessible() {
    val ctor = FileOutputStream::class.java.getDeclaredConstructor(String::class.java)
    ctor.isAccessible = true
  }

  override fun checkFileChannelOpenWrite() {
    FileChannel.open(Path.of(disallowedFilePath), StandardOpenOption.WRITE)
  }

  override fun checkBenignReflection(): String {
    val model = BenignModel()
    val m1 = BenignModel::class.java.getMethod("getName")
    val m2 = BenignModel::class.java.getMethod("getValue")
    val m3 = BenignModel::class.java.getMethod("execute")
    return "${m1.invoke(model)}_${m2.invoke(model)}_${m3.invoke(model)}"
  }

  override fun checkBenignMethodHandles(): String {
    val lookup = MethodHandles.lookup()
    val type = MethodType.methodType(String::class.java)
    val handle = lookup.findVirtual(BenignModel::class.java, "getName", type)
    return handle.invoke(BenignModel()) as String
  }

  override fun checkBenignFieldAccess(): String {
    val lookup = MethodHandles.lookup()
    val handle = lookup.findGetter(BenignModel::class.java, "modelField", String::class.java)
    val model = BenignModel()
    return handle.invoke(model) as String
  }

  override fun checkFileChannelOpenDeleteOnClose() {
    FileChannel.open(Path.of(disallowedFilePath), StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE)
  }

  override fun checkFilesNewInputStreamDeleteOnClose() {
    Files.newInputStream(Path.of(disallowedFilePath), StandardOpenOption.DELETE_ON_CLOSE)
  }

  override fun checkMethodHandlesDummyClassLoader(dummyLoader: ClassLoader) {
    val oldLoader = Thread.currentThread().contextClassLoader
    try {
      Thread.currentThread().contextClassLoader = dummyLoader
      val lookup = MethodHandles.lookup()
      val type = MethodType.methodType(Process::class.java)
      lookup.findVirtual(ProcessBuilder::class.java, "start", type)
    } finally {
      Thread.currentThread().contextClassLoader = oldLoader
    }
  }

  override fun checkMethodHandlesFindGetterDummyClassLoader(dummyLoader: ClassLoader) {
    val oldLoader = Thread.currentThread().contextClassLoader
    try {
      Thread.currentThread().contextClassLoader = dummyLoader
      val lookup = MethodHandles.lookup()
      lookup.findGetter(File::class.java, "path", String::class.java)
    } finally {
      Thread.currentThread().contextClassLoader = oldLoader
    }
  }

  override fun checkXmlDecoderDirect() {
    XMLDecoder(ByteArrayInputStream(ByteArray(0)))
  }

  override fun checkXmlDecoderReflection() {
    val m = XMLDecoder::class.java.getMethod("readObject")
    m.isAccessible = true
  }

  override fun checkXmlDecoderConstructorNewInstance() {
    val ctor = XMLDecoder::class.java.getConstructor(InputStream::class.java)
    ctor.newInstance(ByteArrayInputStream(ByteArray(0)))
  }

  override fun checkProcessImplStartReflection(clazz: Class<*>) {
    val m = clazz.declaredMethods.first { it.name == "start" }
    m.isAccessible = true
  }

  override fun checkMethodHandlesBind() {
    val pb = ProcessBuilder("echo")
    MethodHandles.lookup().bind(pb, "start", MethodType.methodType(Process::class.java))
  }

  override fun checkFileSystemProviderGetFileAttributeView() {
    val path = Paths.get(disallowedFilePath)
    path.fileSystem.provider().getFileAttributeView(path, BasicFileAttributeView::class.java)
  }

  override fun checkMethodHandlesPrivateLookupIn() {
    MethodHandles.privateLookupIn(ProcessBuilder::class.java, MethodHandles.lookup())
  }

  override fun checkProcessBuilderStartPipeline() {
    ProcessBuilder.startPipeline(listOf(ProcessBuilder("echo")))
  }

  override fun checkFilesCreateLink(link: Path, existing: Path) {
    Files.createLink(link, existing)
  }

  override fun checkFileSystemProviderCreateLink(link: Path, existing: Path) {
    link.fileSystem.provider().createLink(link, existing)
  }
}

class RenderSandboxTest {
  private lateinit var testClassLoader: TestClassLoader

  @Before
  fun setUp() {
    testClassLoader =
      setupTestClassLoaderWithTransformation(mapOf("Test" to ClassToCheckImpl::class.java, "SubclassFile" to SubclassFile::class.java)) {
        visitor ->
        RenderSandbox.getClassTransform(visitor)
      }
    RenderSandbox.setRenderSandbox(DenyAllRenderSandbox)
  }

  @After
  fun testDown() {
    RenderSandbox.setRenderSandbox(AllowAllRenderSandbox)
  }

  /** Verifies that the [block] throws a [SecurityException] with the given message. */
  private inline fun verifyThrowsSecurityException(expectedMessage: String, block: () -> Unit) {
    try {
      block()
      fail("Expected SecurityException('$expectedMessage')")
    } catch (e: SecurityException) {
      assertEquals(FileUtil.toSystemIndependentName(expectedMessage), removeTestingDirPrefix(e.message!!))
    }
  }

  private fun getInterceptedMethodsForClass(clazz: Class<*>) =
    clazz.methods
      .filter { it.name !in commonMethods }
      .map { Type.getType(clazz).internalName to it.name }
      .filter { (className, methodName) -> !RenderSandboxTransformTrampoline.shouldIntercept(className, methodName) }
      .map { (className, methodName) -> "$className#$methodName" }
      .toSet()
      .sorted()

  @Test
  fun `verify interceptor type`() {
    RenderSandboxTransformTrampoline.classesToIntercept
      .flatMap {
        val clazz = Class.forName(it.fromBinaryNameToPackageName())

        // Verify static intercepts
        clazz.methods
          .map { Type.getType(clazz).internalName to it }
          .filter { (className, method) -> RenderSandboxTransformTrampoline.shouldIntercept(className, method.name) }
      }
      .forEach { (className, method) ->
        val isStaticMethod = (method.modifiers and Modifier.STATIC) != 0
        if (isStaticMethod) {
          assertTrue(
            "$className#${method.name}  should have a STATIC interceptor but has a VIRTUAL interceptor",
            RenderSandboxTransformTrampoline.hasStaticIntercept(className, method.name),
          )
        } else {
          assertTrue(
            "$className#${method.name} should have a VIRTUAL interceptor but has a STATIC interceptor",
            RenderSandboxTransformTrampoline.hasInstanceIntercept(className, method.name),
          )
        }
      }
  }

  @Test
  fun `verify uncovered File method calls`() {
    assertEquals(
      """
      java/io/File#listRoots
      java/io/File#toURI
      java/io/File#toURL
      """
        .trimIndent(),
      getInterceptedMethodsForClass(File::class.java).joinToString("\n").trim(),
    )
  }

  @Test
  fun `verify uncovered Files method calls`() {
    assertTrue("All Files static methods should be intercepted", getInterceptedMethodsForClass(Files::class.java).isEmpty())
  }

  @Test
  fun `check file operations fail`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck

    verifyThrowsSecurityException("checkFileWrite ${System.getProperty("java.io.tmpdir")}") { methodIntercept.checkTempCreation() }

    // Read operations
    run {
      val readMsg = "checkFileRead ${removeTestingDirPrefix(disallowedFilePath)}"
      verifyThrowsSecurityException(readMsg) { methodIntercept.checkFileRead1() }
      verifyThrowsSecurityException(readMsg) { methodIntercept.checkFileRead2() }
      verifyThrowsSecurityException(readMsg) { methodIntercept.checkFileRead3() }
      verifyThrowsSecurityException(readMsg) { methodIntercept.checkFileRead4() }
      verifyThrowsSecurityException(readMsg) { methodIntercept.checkFileRead5() }
      verifyThrowsSecurityException(readMsg) { methodIntercept.checkReadText() }
      verifyThrowsSecurityException(readMsg) { methodIntercept.checkReadTextWithCharset() }
      verifyThrowsSecurityException(readMsg) { methodIntercept.checkReadBytes() }
    }

    // Write operations
    run {
      val writeMsg = "checkFileWrite ${removeTestingDirPrefix(disallowedFilePath)}"
      verifyThrowsSecurityException(writeMsg) { methodIntercept.checkFileWrite1() }
      verifyThrowsSecurityException(writeMsg) { methodIntercept.checkFileWrite2() }
      verifyThrowsSecurityException(writeMsg) { methodIntercept.checkFileWrite3() }
      verifyThrowsSecurityException(writeMsg) { methodIntercept.checkFileWrite4() }
      verifyThrowsSecurityException(writeMsg) { methodIntercept.checkFileWrite5() }
      verifyThrowsSecurityException(writeMsg) { methodIntercept.checkWriteText() }
    }
  }

  @Test
  fun `check system operations fail`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck

    verifyThrowsSecurityException("checkSystemExit") { methodIntercept.checkSystemExit1() }
    verifyThrowsSecurityException("checkSystemExit") { methodIntercept.checkSystemExit2() }
    verifyThrowsSecurityException("checkSystemExit") { methodIntercept.checkSystemExit3() }

    // Property reads should succeed
    // Property reads should fail in DenyAll
    verifyThrowsSecurityException("checkPropertyRead property.test") { methodIntercept.checkPropertyRead() }

    // Property writes for properties in the deny list should fail
    verifyThrowsSecurityException("checkPropertyWrite property.test") { methodIntercept.checkPropertyWrite() }

    // Allowlisted property write should also fail in DenyAll
    verifyThrowsSecurityException("checkPropertyWrite user.timezone") { methodIntercept.checkAllowlistedPropertyWrite() }

    // Listing all properties should fail
    verifyThrowsSecurityException("checkPropertyAccess") { methodIntercept.checkListProperties() }

    verifyThrowsSecurityException("checkEnvAccess") { methodIntercept.checkGetEnv1() }
    verifyThrowsSecurityException("checkEnvAccess") { methodIntercept.checkGetEnv2() }

    verifyThrowsSecurityException("checkSystemIoSet") { methodIntercept.checkIoSet1() }
    verifyThrowsSecurityException("checkSystemIoSet") { methodIntercept.checkIoSet2() }
    verifyThrowsSecurityException("checkSystemIoSet") { methodIntercept.checkIoSet3() }
  }

  @Test
  fun `check classloading fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck

    verifyThrowsSecurityException("checkClassLoad java.lang.String") { methodIntercept.checkClassForName1() }
    verifyThrowsSecurityException("checkClassLoad java.lang.String") { methodIntercept.checkClassForName2() }
    verifyThrowsSecurityException("checkResourceLoad resource1") { methodIntercept.checkResourceLoading1() }
    verifyThrowsSecurityException("checkResourceLoad resource2") { methodIntercept.checkResourceLoading2() }
  }

  @Test
  fun `check command execution`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck

    verifyThrowsSecurityException("checkProcessExec") { methodIntercept.checkProcessExec1() }
    verifyThrowsSecurityException("checkProcessExec") { methodIntercept.checkProcessExec2() }
  }

  @Test
  fun `check load library`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck

    verifyThrowsSecurityException("checkLoadLibrary library") { methodIntercept.checkLoadLibrary1() }
    verifyThrowsSecurityException("checkLoadLibrary library") { methodIntercept.checkLoadLibrary2() }
  }

  @Test
  fun `check RandomAccessFile fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkFileRead ${removeTestingDirPrefix(disallowedFilePath)}") { methodIntercept.checkRandomAccessFile() }
  }

  @Test
  fun `check DatagramSocket fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkConnection") { methodIntercept.checkDatagramSocket() }
  }

  @Test
  fun `check ClassLoader creation fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkCreateClassLoader") { methodIntercept.checkClassLoaderCreation() }
  }

  @Test
  fun `check multithreaded sandbox`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck

    // Set DenyAll in main thread
    RenderSandbox.setRenderSandbox(DenyAllRenderSandbox)

    var exceptionInThread: Throwable? = null
    val thread = Thread {
      try {
        // In another thread, it should also be DenyAll
        // We use a different check that doesn't trigger System.exit
        verifyThrowsSecurityException("checkPropertyWrite property.test") { methodIntercept.checkPropertyWrite() }
      } catch (t: Throwable) {
        exceptionInThread = t
      }
    }
    thread.start()
    thread.join()
    exceptionInThread?.let { throw it }

    // Change to AllowAll
    RenderSandbox.setRenderSandbox(AllowAllRenderSandbox)

    val thread2 = Thread {
      try {
        // Now it should be AllowAll
        methodIntercept.checkPropertyRead()
      } catch (t: Throwable) {
        exceptionInThread = t
      }
    }
    exceptionInThread = null
    thread2.start()
    thread2.join()
    exceptionInThread?.let { throw it }
  }

  @Test
  fun `check clipboard access fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkClipboard") { methodIntercept.checkClipboard() }
  }

  @Test
  fun `check event queue access fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkEventQueue() }
  }

  @Test
  fun `check print job access fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkPrintJob") { methodIntercept.checkPrintJob() }
  }

  @Test
  fun `check FileChannel open fails`() {
    val path = Paths.get(disallowedFilePath)
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkFileRead ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkFileChannelOpen(path)
    }
  }

  @Test
  fun `check ZipFile fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkFileRead ${removeTestingDirPrefix(disallowedFilePath)}") { methodIntercept.checkZipFile() }
  }

  @Test
  fun `check URL openStream fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkConnection") { methodIntercept.checkURLOpenStream() }
  }

  @Test
  fun `check critical section disables sandbox`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck

    val baseSandbox = RenderSandbox.getRenderSandbox()
    val testSandbox = PreCheckRenderSandboxDelegate(DenyAllRenderSandbox, { RenderSecurityManager.isEnabled() })

    val credential = Any()
    val rsm = RenderSecurityManager.createForTests(null, null, false) { true }
    rsm.setUseSandbox(true)
    rsm.setActive(true, credential)

    RenderSandbox.setRenderSandbox(testSandbox)
    try {
      // By default isEnabled is true, so it should fail in DenyAll
      verifyThrowsSecurityException("checkPropertyRead property.test") { methodIntercept.checkPropertyRead() }

      // Enter safe region (disables isEnabled)
      val token = RenderSecurityManager.enterSafeRegion(credential)
      try {
        // Now it should succeed because isEnabled is false!
        methodIntercept.checkPropertyRead()
      } finally {
        RenderSecurityManager.exitSafeRegion(token)
      }
    } finally {
      RenderSandbox.setRenderSandbox(baseSandbox)
      rsm.dispose(credential)
    }
  }

  @Test
  fun `check reflection invoke fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/lang/System#exit") {
      methodIntercept.checkReflectionInvoke()
    }
  }

  @Test
  fun `check Unsafe fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Access to sun.misc.Unsafe is denied") { methodIntercept.checkUnsafe() }
  }

  @Test
  fun `check defineClass fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkDefineClass") { methodIntercept.tryDefineClass() }
  }

  @Test
  fun `check MethodHandle invoke fails or succeeds`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck

    try {
      methodIntercept.tryInvokeMethodHandle()
      fail("Should have failed to invoke MethodHandle for restricted method")
    } catch (e: SecurityException) {
      println("Sandbox blocked it: ${e.message}")
    }
  }

  @Test
  fun `check findStatic fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/lang/System#exit") { methodIntercept.checkFindStatic() }
  }

  @Test
  fun `check unreflect fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/lang/System#exit") { methodIntercept.checkUnreflect() }
  }

  @Test
  fun `check deserialization fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck

    val obj = MaliciousSerializable()
    val baos = ByteArrayOutputStream()
    val oos = ObjectOutputStream(baos)
    oos.writeObject(obj)
    val bytes = baos.toByteArray()

    try {
      methodIntercept.tryDeserialization(bytes)
      fail("Should have failed to deserialize malicious object")
    } catch (e: SecurityException) {
      assertEquals("Access to ObjectInputStream is denied", e.message)
    }
  }

  @Test
  fun `check socket connect fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkConnection") { methodIntercept.tryConnect() }
  }

  @Test
  fun `check CompletableFuture fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkConcurrency") { methodIntercept.checkCompletableFuture() }
  }

  @Test
  fun `check ForkJoinPool fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkConcurrency") { methodIntercept.checkForkJoinPool() }
  }

  @Test
  fun `check ThreadPoolExecutor creation fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkConcurrency") { methodIntercept.checkThreadPoolExecutor() }
  }

  @Test
  fun `check PrintServiceLookup register fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkPrintJob") { methodIntercept.checkPrintServiceLookupRegister() }
  }

  @Test
  fun `check PrintServiceLookup lookup fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkPrintJob") { methodIntercept.checkPrintServiceLookup() }
  }

  @Test
  fun `check PrinterJob lookupPrintServices fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkPrintJob") { methodIntercept.checkPrinterJobLookup() }
  }

  @Test
  fun `check IIORegistry getDefaultInstance fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkImageIo") { methodIntercept.checkIIORegistryGetDefault() }
  }

  @Test
  fun `check ServiceRegistry registerServiceProvider fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkImageIo") { methodIntercept.checkServiceRegistry() }
  }

  @Test
  fun `check ServiceRegistry deregisterAll fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkImageIo") { methodIntercept.checkIIORegistryDeregisterAll() }
  }

  @Test
  fun `check ServiceRegistry lookupProviders fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkImageIo") { methodIntercept.checkServiceRegistryLookupProviders() }
  }

  @Test
  fun `check ImageIO scanForPlugins fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkImageIo") { methodIntercept.checkImageIOScanForPlugins() }
  }

  @Test
  fun `check JEditorPane registerEditorKitForContentType fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkJEditorPaneRegisterEditorKit() }
  }

  @Test
  fun `check reflection invoke on PrintServiceLookup fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: javax/print/PrintServiceLookup#lookupDefaultPrintService") {
      methodIntercept.checkReflectionInvokePrintServiceLookup()
    }
  }

  @Test
  fun `check reflection invoke on IIORegistry fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: javax/imageio/spi/IIORegistry#getDefaultInstance") {
      methodIntercept.checkReflectionInvokeIIORegistry()
    }
  }

  @Test
  fun `check KeyboardFocusManager addKeyEventDispatcher fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkKeyboardFocusManager() }
  }

  @Test
  fun `check Keymap setDefaultAction fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkKeymapDefaultAction() }
  }

  @Test
  fun `check PopupFactory setSharedInstance fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkPopupFactory() }
  }

  @Test
  fun `check LayoutStyle setInstance fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkLayoutStyle() }
  }

  @Test
  fun `check UIManager put fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkUIManagerPut() }
  }

  @Test
  fun `check RepaintManager setCurrentManager fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkRepaintManager() }
  }

  @Test
  fun `check Window getWindows fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkWindowGetWindows() }
  }

  @Test
  fun `check Frame getFrames fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkFrameGetFrames() }
  }

  @Test
  fun `check KeyboardFocusManager setCurrentKeyboardFocusManager fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkKeyboardFocusManagerSetCurrent() }
  }

  @Test
  fun `check UIManager setLookAndFeel fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkUIManagerSetLookAndFeel() }
  }

  @Test
  fun `check UIDefaults put fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkUIDefaultsPut() }
  }

  @Test
  fun `check DragSource getDefaultDragSource fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkDragSourceGetDefault() }
  }

  @Test
  fun `check DragSource addDragSourceListener fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = unsafeField.get(null) as Unsafe
    val dragSource = unsafe.allocateInstance(DragSource::class.java) as DragSource
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkDragSourceAddListener(dragSource) }
  }

  @Test
  fun `check MenuSelectionManager addChangeListener fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkEventQueue") { methodIntercept.checkMenuSelectionManager() }
  }

  @Test
  fun `check FileSystemProvider newOutputStream fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    val path = Paths.get(disallowedFilePath)
    verifyThrowsSecurityException("checkFileWrite ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkFileSystemProviderOutputStream(path)
    }
  }

  @Test
  fun `check FileSystemProvider delete fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    val path = Paths.get(disallowedFilePath)
    verifyThrowsSecurityException("checkFileWrite ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkFileSystemProviderDelete(path)
    }
  }

  @Test
  fun `check java beans Statement execute fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/beans/Statement#execute") {
      methodIntercept.checkStatementExecute()
    }
  }

  @Test
  fun `check java beans Statement new constructor fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/beans/Statement#execute") {
      methodIntercept.checkStatementNew()
    }
  }

  @Test
  fun `check java beans Expression getValue fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/beans/Expression#getValue") {
      methodIntercept.checkExpressionGetValue()
    }
  }

  @Test
  fun `check subclass java beans Statement execute fails to prevent TOCTOU bypass`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/beans/Statement#execute") {
      methodIntercept.checkSubclassStatementExecute()
    }
  }

  @Test
  fun `check invokedynamic method reference fails`() {
    val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "TestIndy", null, "java/lang/Object", null)
    val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "test", "()V", null, null)
    mv.visitCode()
    val bsm =
      Handle(
        Opcodes.H_INVOKESTATIC,
        "java/lang/invoke/LambdaMetafactory",
        "metafactory",
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
        false,
      )
    val targetHandle =
      Handle(
        Opcodes.H_INVOKEVIRTUAL,
        "java/lang/ProcessBuilder",
        "start",
        "()Ljava/lang/Process;",
        false,
      )
    mv.visitInsn(Opcodes.ACONST_NULL)
    mv.visitInvokeDynamicInsn(
      "get",
      "(Ljava/lang/ProcessBuilder;)Ljava/util/function/Supplier;",
      bsm,
      Type.getMethodType("()Ljava/lang/Object;"),
      targetHandle,
      Type.getMethodType("()Ljava/lang/Process;"),
    )
    mv.visitInsn(Opcodes.POP)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 1)
    mv.visitEnd()
    cw.visitEnd()
    val originalBytes = cw.toByteArray()

    val transformedWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    val transform = RenderSandbox.getClassTransform(transformedWriter)
    ClassReader(originalBytes).accept(transform, 0)
    val transformedBytes = transformedWriter.toByteArray()

    val loader =
      object : ClassLoader(RenderSandboxTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
      }
    val loadedClass = loader.define("TestIndy", transformedBytes)
    verifyThrowsSecurityException("Reflection access to restricted method: java/lang/ProcessBuilder#start") {
      try {
        loadedClass.getMethod("test").invoke(null)
      } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.targetException
      }
    }
  }

  @Test
  fun `check ScriptEngineManager fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkScriptEngine") {
      methodIntercept.checkScriptEngineManager()
    }
  }

  @Test
  fun `check ServiceLoader load fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkServiceLoader") {
      methodIntercept.checkServiceLoaderLoad()
    }
  }

  @Test
  fun `check URL setURLStreamHandlerFactory fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkSetFactory") {
      methodIntercept.checkURLSetFactory()
    }
  }

  @Test
  fun `check MethodHandles findVirtual fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/lang/ProcessBuilder#start") {
      methodIntercept.checkMethodHandlesFindVirtual()
    }
  }

  @Test
  fun `check MethodHandles findConstructor fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/lang/ClassLoader#<init>") {
      methodIntercept.checkMethodHandlesFindConstructor()
    }
  }

  @Test
  fun `check MethodHandles findGetter fails on restricted class`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted field: java/io/File#path") {
      methodIntercept.checkMethodHandlesFindGetter()
    }
  }

  @Test
  fun `check MethodHandles unreflectGetter fails on restricted class`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted field: java/io/File#path") {
      methodIntercept.checkMethodHandlesUnreflectGetter()
    }
  }

  @Test
  fun `check MethodHandles findVarHandle fails on restricted class`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted field: java/io/File#path") {
      methodIntercept.checkMethodHandlesFindVarHandle()
    }
  }

  @Test
  fun `check MethodHandles findGetter fails on sandbox internals`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException(
      "Reflection access to restricted field: com/android/tools/rendering/security/RenderSandboxTransformTrampoline#defaultInterceptors"
    ) {
      methodIntercept.checkMethodHandlesSandboxInternalField()
    }
  }

  @Test
  fun `check subclass File delete fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkFileWrite ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkSubclassFileDelete()
    }
  }

  @Test
  fun `check subclass File createNewFile fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkFileWrite ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkSubclassFileCreateNewFile()
    }
  }

  @Test
  fun `check benign reflection succeeds`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    assertEquals("name_val_ok", methodIntercept.checkBenignReflection())
  }

  @Test
  fun `check benign MethodHandles findVirtual succeeds`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    assertEquals("name", methodIntercept.checkBenignMethodHandles())
  }

  @Test
  fun `check benign field access succeeds`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    assertEquals("test_val", methodIntercept.checkBenignFieldAccess())
  }

  @Test
  fun `check subclass File super delete fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkFileWrite ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkSubclassFileSuperDelete()
    }
  }

  @Test
  fun `check Constructor setAccessible fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/io/FileOutputStream#<init>") {
      methodIntercept.checkConstructorSetAccessible()
    }
  }

  @Test
  fun `check FileChannel open write fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkFileWrite ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkFileChannelOpenWrite()
    }
  }

  @Test
  fun `check ldc field MethodHandle fails`() {
    val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "TestLdcFieldHandle", null, "java/lang/Object", null)
    val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "test", "()V", null, null)
    mv.visitCode()
    val targetHandle =
      Handle(
        Opcodes.H_GETFIELD,
        "java/io/File",
        "path",
        "Ljava/lang/String;",
        false,
      )
    mv.visitLdcInsn(targetHandle)
    mv.visitInsn(Opcodes.POP)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 1)
    mv.visitEnd()
    cw.visitEnd()
    val originalBytes = cw.toByteArray()

    val transformedWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    val transform = RenderSandbox.getClassTransform(transformedWriter)
    ClassReader(originalBytes).accept(transform, 0)
    val transformedBytes = transformedWriter.toByteArray()

    val loader =
      object : ClassLoader(RenderSandboxTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
      }
    val loadedClass = loader.define("TestLdcFieldHandle", transformedBytes)
    verifyThrowsSecurityException("Reflection access to restricted field: java/io/File#path") {
      try {
        loadedClass.getMethod("test").invoke(null)
      } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.targetException
      }
    }
  }

  @Test
  fun `verify couldIntercept detects virtual method calls in subclasses`() {
    val implBytes =
      RenderSandboxTest::class
        .java
        .classLoader
        .getResourceAsStream(ClassToCheckImpl::class.java.name.replace('.', '/') + ".class")
        ?.readBytes()
    assertNotNull(implBytes)
    assertTrue(RenderSandboxTransformTrampoline.couldIntercept(implBytes!!))
  }

  @Test
  fun `check FileChannel open with DELETE_ON_CLOSE fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkFileWrite ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkFileChannelOpenDeleteOnClose()
    }
  }

  @Test
  fun `check Files newInputStream with DELETE_ON_CLOSE fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkFileWrite ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkFilesNewInputStreamDeleteOnClose()
    }
  }

  @Test
  fun `check MethodHandles findVirtual with dummy classloader fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    val dummyLoader = URLClassLoader(emptyArray())
    verifyThrowsSecurityException("Reflection access to restricted method: java/lang/ProcessBuilder#start") {
      methodIntercept.checkMethodHandlesDummyClassLoader(dummyLoader)
    }
  }

  @Test
  fun `check MethodHandles findGetter with dummy classloader fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    val dummyLoader = URLClassLoader(emptyArray())
    verifyThrowsSecurityException("Reflection access to restricted field: java/io/File#path") {
      methodIntercept.checkMethodHandlesFindGetterDummyClassLoader(dummyLoader)
    }
  }

  @Test
  fun `check XMLDecoder direct instantiation fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkXmlDecoder") {
      methodIntercept.checkXmlDecoderDirect()
    }
  }

  @Test
  fun `check XMLDecoder reflection invocation fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/beans/XMLDecoder#readObject") {
      methodIntercept.checkXmlDecoderReflection()
    }
  }

  @Test
  fun `check XMLDecoder Constructor newInstance fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/beans/XMLDecoder#<init>") {
      methodIntercept.checkXmlDecoderConstructorNewInstance()
    }
  }

  @Test
  fun `check ProcessImpl start reflection fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    val processImplClass = RenderSandbox.computeWithoutSandbox {
      Class.forName("java.lang.ProcessImpl")
    }
    verifyThrowsSecurityException("Reflection access to restricted method: java/lang/ProcessImpl#start") {
      methodIntercept.checkProcessImplStartReflection(processImplClass)
    }
  }

  @Test
  fun `check MethodHandles bind with restricted receiver fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/lang/ProcessBuilder#start") {
      methodIntercept.checkMethodHandlesBind()
    }
  }

  @Test
  fun `check FileSystemProvider getFileAttributeView on disallowed file fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkFileRead ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkFileSystemProviderGetFileAttributeView()
    }
  }

  @Test
  fun `check MethodHandles privateLookupIn with restricted class fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("Reflection access to restricted method: java/lang/ProcessBuilder#<privateLookup>") {
      methodIntercept.checkMethodHandlesPrivateLookupIn()
    }
  }

  @Test
  fun `check ProcessBuilder startPipeline fails`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    verifyThrowsSecurityException("checkProcessExec") {
      methodIntercept.checkProcessBuilderStartPipeline()
    }
  }

  @Test
  fun `check Files createLink checks write on link and read on existing`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    val link = Paths.get(disallowedFilePath)
    val existing = Paths.get(allowedFilePath)

    verifyThrowsSecurityException("checkFileWrite ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkFilesCreateLink(link, existing)
    }

    val baseSandbox = RenderSandbox.getRenderSandbox()
    val allowWriteSandbox =
      object : RenderSandboxDelegate(DenyAllRenderSandbox) {
        override fun checkFileWrite(absolutePath: String) {}
      }
    RenderSandbox.setRenderSandbox(allowWriteSandbox)
    try {
      verifyThrowsSecurityException("checkFileRead ${removeTestingDirPrefix(disallowedFilePath)}") {
        methodIntercept.checkFilesCreateLink(existing, link)
      }
    } finally {
      RenderSandbox.setRenderSandbox(baseSandbox)
    }
  }

  @Test
  fun `check FileSystemProvider createLink checks write on link and read on existing`() {
    val methodIntercept = testClassLoader.loadClass("Test").getDeclaredConstructor().newInstance() as ClassToCheck
    val link = Paths.get(disallowedFilePath)
    val existing = Paths.get(allowedFilePath)

    verifyThrowsSecurityException("checkFileWrite ${removeTestingDirPrefix(disallowedFilePath)}") {
      methodIntercept.checkFileSystemProviderCreateLink(link, existing)
    }

    val baseSandbox = RenderSandbox.getRenderSandbox()
    val allowWriteSandbox =
      object : RenderSandboxDelegate(DenyAllRenderSandbox) {
        override fun checkFileWrite(absolutePath: String) {}
      }
    RenderSandbox.setRenderSandbox(allowWriteSandbox)
    try {
      verifyThrowsSecurityException("checkFileRead ${removeTestingDirPrefix(disallowedFilePath)}") {
        methodIntercept.checkFileSystemProviderCreateLink(existing, link)
      }
    } finally {
      RenderSandbox.setRenderSandbox(baseSandbox)
    }
  }

  @Test
  fun `verify couldIntercept detects restricted reflection prefixes`() {
    val cw = ClassWriter(0)
    cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "TestUnsafeRef", null, "java/lang/Object", null)
    val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "test", "()V", null, null)
    mv.visitCode()
    mv.visitInsn(Opcodes.ACONST_NULL)
    mv.visitTypeInsn(Opcodes.CHECKCAST, "sun/misc/Unsafe")
    mv.visitInsn(Opcodes.POP)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 0)
    mv.visitEnd()
    cw.visitEnd()
    val classBytes = cw.toByteArray()

    assertTrue(RenderSandboxTransformTrampoline.couldIntercept(classBytes))
  }

  @Test
  fun `check MethodHandle LDC targeting Unsafe fails`() {
    val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "TestUnsafeLdc", null, "java/lang/Object", null)
    val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "test", "()V", null, null)
    mv.visitCode()
    val handle = Handle(Opcodes.H_INVOKEVIRTUAL, "sun/misc/Unsafe", "allocateMemory", "(J)J", false)
    mv.visitLdcInsn(handle)
    mv.visitInsn(Opcodes.POP)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 0)
    mv.visitEnd()
    cw.visitEnd()
    val originalBytes = cw.toByteArray()

    val transformedWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    val transform = RenderSandbox.getClassTransform(transformedWriter)
    ClassReader(originalBytes).accept(transform, 0)
    val transformedBytes = transformedWriter.toByteArray()

    val loader =
      object : ClassLoader(RenderSandboxTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
      }
    val loadedClass = loader.define("TestUnsafeLdc", transformedBytes)
    verifyThrowsSecurityException("Reflection access to restricted method: sun/misc/Unsafe#allocateMemory") {
      try {
        loadedClass.getMethod("test").invoke(null)
      } catch (e: InvocationTargetException) {
        throw e.cause ?: e
      }
    }
  }

  @Test
  fun `check ClassTransform with couldIntercept transforms and blocks Unsafe MethodHandle LDC`() {
    val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "TestUnsafeTransform", null, "java/lang/Object", null)
    val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "test", "()V", null, null)
    mv.visitCode()
    val handle = Handle(Opcodes.H_INVOKEVIRTUAL, "sun/misc/Unsafe", "allocateMemory", "(J)J", false)
    mv.visitLdcInsn(handle)
    mv.visitInsn(Opcodes.POP)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 0)
    mv.visitEnd()
    cw.visitEnd()
    val originalBytes = cw.toByteArray()

    val classTransform = RenderSandbox.getClassTransform()
    assertTrue(classTransform.shouldRewrite(originalBytes))

    val transformedWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    val transform = classTransform.invoke(transformedWriter, originalBytes)
    ClassReader(originalBytes).accept(transform, 0)
    val transformedBytes = transformedWriter.toByteArray()

    val loader =
      object : ClassLoader(RenderSandboxTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
      }
    val loadedClass = loader.define("TestUnsafeTransform", transformedBytes)
    verifyThrowsSecurityException("Reflection access to restricted method: sun/misc/Unsafe#allocateMemory") {
      try {
        loadedClass.getMethod("test").invoke(null)
      } catch (e: InvocationTargetException) {
        throw e.cause ?: e
      }
    }
  }
}
