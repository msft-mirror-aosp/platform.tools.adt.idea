/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.tools.rendering.RenderService
import java.awt.DefaultKeyboardFocusManager
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.dnd.DragSource
import java.awt.print.PrinterJob
import java.beans.Expression
import java.beans.Statement
import java.beans.XMLDecoder
import java.beans.XMLEncoder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.RandomAccessFile
import java.lang.invoke.MethodHandles
import java.lang.ref.Cleaner
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.spi.FileSystemProvider
import java.util.ServiceLoader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ServiceRegistry
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.print.PrintServiceLookup
import javax.script.ScriptEngineManager
import javax.swing.JEditorPane
import javax.swing.LayoutStyle
import javax.swing.MenuSelectionManager
import javax.swing.PopupFactory
import javax.swing.RepaintManager
import javax.swing.Timer as SwingTimer
import javax.swing.UIDefaults
import javax.swing.UIManager
import javax.swing.text.Keymap
import javax.swing.text.LayoutQueue
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod
import org.jetbrains.annotations.TestOnly
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import sun.misc.Signal
import sun.misc.Unsafe

private val tmpDir = File(System.getProperty("java.io.tmpdir"))

private fun checkFileRead(absolutePath: String) = RenderSandbox.getRenderSandbox().checkFileRead(absolutePath)

private fun checkFileWrite(absolutePath: String) = RenderSandbox.getRenderSandbox().checkFileWrite(absolutePath)

private fun checkExit(): Unit = RenderSandbox.getRenderSandbox().checkSystemExit()

private fun checkPropertyAccess(): Unit = RenderSandbox.getRenderSandbox().checkPropertyAccess()

private fun checkEnvAccess(): Unit = RenderSandbox.getRenderSandbox().checkEnvAccess()

private fun checkSystemIoSet(): Unit = RenderSandbox.getRenderSandbox().checkSystemIoSet()

private fun checkSetFactory(): Unit = RenderSandbox.getRenderSandbox().checkSetFactory()

@Suppress("UNUSED_PARAMETER")
private fun checkPropertyRead(propertyName: String) = RenderSandbox.getRenderSandbox().checkPropertyRead(propertyName)

private fun checkPropertyWrite(propertyName: String) = RenderSandbox.getRenderSandbox().checkPropertyWrite(propertyName)

@Suppress("UNUSED_PARAMETER")
private fun checkCreateTemp(owner: String, args: Array<Any>?) {
  val parentTmpDir = args?.getOrElse(2) { tmpDir } as File
  checkFileWrite(parentTmpDir.absolutePath)
}

private fun checkConnection() {
  RenderSandbox.getRenderSandbox().checkConnection()
}

private fun checkProcessExec() {
  RenderSandbox.getRenderSandbox().checkProcessExec()
}

private fun checkConcurrency() {
  RenderSandbox.getRenderSandbox().checkConcurrency()
}

private fun checkLoadLibrary(library: String) {
  RenderSandbox.getRenderSandbox().checkLoadLibrary(library)
}

/** For `Files.createTemp*` */
@Suppress("UNUSED_PARAMETER")
private fun checkCreateTempFromFiles(owner: String, args: Array<Any>?) {
  val parentTmpDir = args?.getOrElse(0) { tmpDir.toPath() } as Path
  checkFileWrite(parentTmpDir.toString())
}

private fun checkClassLoad(classFqn: String) = RenderSandbox.getRenderSandbox().checkClassLoad(classFqn)

@Suppress("UNUSED_PARAMETER")
private fun checkForNameCalls(owner: String, args: Array<Any>?): Unit {
  // In Class.forName the class path can be in the first or second argument
  val className = args?.firstOrNull() as? String ?: args?.getOrNull(1) as? String ?: return
  RenderSandbox.getRenderSandbox().checkClassLoad(className)
}

@Suppress("UNUSED_PARAMETER")
private fun checkResourceLoad(owner: Class<*>, args: Array<Any>?): Unit {
  val resourceName = args?.firstOrNull() as? String ?: return
  RenderSandbox.getRenderSandbox().checkResourceLoad(resourceName)
}

private fun checkGetClassLoader(clazz: Class<*>, @Suppress("UNUSED_PARAMETER") args: Array<Any>?) {
  RenderSandbox.getRenderSandbox().checkGetClassLoader(clazz)
}

private fun checkClassLoaderAccess() {
  RenderSandbox.getRenderSandbox().checkClassLoaderAccess()
}

private fun checkCreateClassLoader() = RenderSandbox.getRenderSandbox().checkCreateClassLoader()

private fun checkClipboard() = RenderSandbox.getRenderSandbox().checkClipboard()

private fun checkEventQueue() = RenderSandbox.getRenderSandbox().checkEventQueue()

private fun checkPrintJob() = RenderSandbox.getRenderSandbox().checkPrintJob()

private fun checkImageIo() = RenderSandbox.getRenderSandbox().checkImageIo()

private fun checkCleaner() = RenderSandbox.getRenderSandbox().checkCleaner()

private fun checkSignal() = RenderSandbox.getRenderSandbox().checkSignal()

private fun checkRenderExecutor() = RenderSandbox.getRenderSandbox().checkRenderExecutor()

private fun checkThreadConstruct(owner: String, args: Array<Any>?) {
  if (args != null && args.size == 5 && args[4] == false) {
    RenderSandbox.getRenderSandbox().checkProcessExec()
  }
}

private fun checkMethodInvoke(method: Method, args: Array<Any>?) {
  val target = args?.getOrNull(0)
  if (target != null) {
    RenderSandbox.getRenderSandbox().checkReflectionInvoke(target.javaClass, method.name)
  }
  RenderSandbox.getRenderSandbox().checkReflectionInvoke(method.declaringClass, method.name)
}

private fun checkConstructorNewInstance(ctor: Constructor<*>, args: Array<Any>?) {
  if (ctor.declaringClass == Thread::class.java) {
    val initArgs = args?.getOrNull(0) as? Array<*>
    if (initArgs != null && initArgs.size == 5 && initArgs[4] == false) {
      RenderSandbox.getRenderSandbox().checkProcessExec()
    }
  }
  RenderSandbox.getRenderSandbox().checkReflectionInvoke(ctor.declaringClass, "<init>")
}

private fun checkClassNewInstance(clazz: Class<*>, @Suppress("UNUSED_PARAMETER") args: Array<Any>?) {
  RenderSandbox.getRenderSandbox().checkReflectionInvoke(clazz, "<init>")
}

private fun checkFieldAccess(field: Field, args: Array<Any>?) {
  RenderSandbox.getRenderSandbox().checkFieldAccess(field.declaringClass, field.name)
}

private fun checkAccessibleObjectAccess(accessibleObject: AccessibleObject, args: Array<Any>?) {
  if (accessibleObject is Field) {
    RenderSandbox.getRenderSandbox().checkFieldAccess(accessibleObject.declaringClass, accessibleObject.name)
  } else if (accessibleObject is Member) {
    val name = if (accessibleObject is Constructor<*>) "<init>" else accessibleObject.name
    RenderSandbox.getRenderSandbox().checkReflectionInvoke(accessibleObject.declaringClass, name)
  }
}

private fun checkStaticAccessibleObjectAccess(accessibleObjects: Array<AccessibleObject>, args: Array<Any>?) {
  for (accessibleObject in accessibleObjects) {
    if (accessibleObject is Field) {
      RenderSandbox.getRenderSandbox().checkFieldAccess(accessibleObject.declaringClass, accessibleObject.name)
    } else if (accessibleObject is Member) {
      val name = if (accessibleObject is Constructor<*>) "<init>" else accessibleObject.name
      RenderSandbox.getRenderSandbox().checkReflectionInvoke(accessibleObject.declaringClass, name)
    }
  }
}

private fun checkUnsafeAccess() {
  RenderSandbox.getRenderSandbox().checkUnsafeAccess()
}

private fun checkFindMember(owner: Any, args: Array<Any>?) {
  val first = args?.getOrNull(0) ?: return
  val refc = if (first is Class<*>) first else first.javaClass
  val name = args.getOrNull(1) as? String ?: return
  RenderSandbox.getRenderSandbox().checkReflectionInvoke(refc, name)
}

private fun checkPrivateLookupIn(ownerClass: String, args: Array<Any>?) {
  val target = args?.getOrNull(0) as? Class<*> ?: return
  RenderSandbox.getRenderSandbox().checkReflectionInvoke(target, "<privateLookup>")
}

private fun checkLookupAccessClass(owner: Any, args: Array<Any>?) {
  val target = args?.getOrNull(0) as? Class<*> ?: return
  RenderSandbox.getRenderSandbox().checkReflectionInvoke(target, "<access>")
}

private fun checkFindField(owner: Any, args: Array<Any>?) {
  val refc = args?.getOrNull(0) as? Class<*> ?: return
  val name = args.getOrNull(1) as? String ?: return
  RenderSandbox.getRenderSandbox().checkFieldAccess(refc, name)
}

private fun checkFindConstructor(owner: Any, args: Array<Any>?) {
  val refc = args?.getOrNull(0) as? Class<*> ?: return
  RenderSandbox.getRenderSandbox().checkReflectionInvoke(refc, "<init>")
}

private fun checkUnreflectConstructor(owner: Any, args: Array<Any>?) {
  val ctor = args?.getOrNull(0) as? Constructor<*> ?: return
  RenderSandbox.getRenderSandbox().checkReflectionInvoke(ctor.declaringClass, "<init>")
}

private fun checkUnreflectField(owner: Any, args: Array<Any>?) {
  val field = args?.getOrNull(0) as? Field ?: return
  RenderSandbox.getRenderSandbox().checkFieldAccess(field.declaringClass, field.name)
}

private fun checkStatementExecute(@Suppress("UNUSED_PARAMETER") statement: Statement, @Suppress("UNUSED_PARAMETER") args: Array<Any>?) {
  RenderSandbox.getRenderSandbox().checkReflectionInvoke("java/beans/Statement", "execute")
}

private fun checkExpressionGetValue(
  @Suppress("UNUSED_PARAMETER") expression: Expression,
  @Suppress("UNUSED_PARAMETER") args: Array<Any>?,
) {
  RenderSandbox.getRenderSandbox().checkReflectionInvoke("java/beans/Expression", "getValue")
}

private fun checkUnreflect(owner: Any, args: Array<Any>?) {
  val method = args?.getOrNull(0) as? Method ?: return
  RenderSandbox.getRenderSandbox().checkReflectionInvoke(method.declaringClass, method.name)
}

private fun checkDefineClass() {
  RenderSandbox.getRenderSandbox().checkDefineClass()
}

private fun checkScriptEngine() {
  RenderSandbox.getRenderSandbox().checkScriptEngine()
}

private fun checkServiceLoader() {
  RenderSandbox.getRenderSandbox().checkServiceLoader()
}

private fun checkXmlDecoder() {
  RenderSandbox.getRenderSandbox().checkXmlDecoder()
}

private fun checkReadObject(owner: Any, args: Array<Any>?) {
  val ois = owner as? ObjectInputStream ?: return
  RenderSandbox.getRenderSandbox().checkObjectInputStream(ois)
}

@Suppress("UNUSED_PARAMETER")
private fun checkRandomAccessFileInit(owner: String, args: Array<Any>?) {
  val mode = args?.getOrNull(1) as? String ?: return
  val pathObj = args.getOrNull(0)
  val path =
    when (pathObj) {
      is File -> pathObj.absolutePath
      is String -> pathObj
      else -> return
    }
  if (mode.contains("w")) {
    checkFileWrite(path)
  } else {
    checkFileRead(path)
  }
}

private fun isWriteOpenOption(option: Any?): Boolean =
  option == StandardOpenOption.WRITE ||
    option == StandardOpenOption.APPEND ||
    option == StandardOpenOption.CREATE ||
    option == StandardOpenOption.CREATE_NEW ||
    option == StandardOpenOption.TRUNCATE_EXISTING ||
    option == StandardOpenOption.DELETE_ON_CLOSE

private fun hasWriteOptions(options: Any?): Boolean =
  when (options) {
    is Array<*> -> options.any { isWriteOpenOption(it) }
    is Iterable<*> -> options.any { isWriteOpenOption(it) }
    else -> isWriteOpenOption(options)
  }

@Suppress("UNUSED_PARAMETER")
private fun <T> checkFileChannelOpen(owner: T, args: Array<Any>?) {
  val path =
    when (val p = args?.getOrNull(0)) {
      is Path -> p.toString()
      is File -> p.absolutePath
      is String -> p
      else -> return
    }
  val isWrite = args.drop(1).any { hasWriteOptions(it) }
  if (isWrite) {
    checkFileWrite(path)
  } else {
    checkFileRead(path)
  }
}

@Suppress("UNUSED_PARAMETER")
private fun <T> checkNewInputStream(owner: T, args: Array<Any>?) {
  val path =
    when (val p = args?.getOrNull(0)) {
      is Path -> p.toString()
      is File -> p.absolutePath
      is String -> p
      else -> return
    }
  val isWrite = args.drop(1).any { hasWriteOptions(it) }
  if (isWrite) {
    checkFileWrite(path)
  } else {
    checkFileRead(path)
  }
}

private fun <T> checkAsyncFileChannelOpen(owner: T, args: Array<Any>?) {
  checkConcurrency()
  checkFileChannelOpen(owner, args)
}

private fun <T> checkFileAttributeView(owner: T, args: Array<Any>?) {
  val path =
    when (val p = args?.getOrNull(0)) {
      is Path -> p.toAbsolutePath().normalize().toString()
      is File -> p.absolutePath
      is String -> p
      else -> return
    }
  checkFileRead(path)
  checkFileWrite(path)
}

private fun checkFileInstance(call: (String) -> Unit): (File, Array<Any>?) -> Unit {
  return { file: File, args: Array<Any>? -> call(file.absolutePath) }
}

private fun checkPathInstance(call: (String) -> Unit): (Path, Array<Any>?) -> Unit {
  return { path: Path, _: Array<Any>? -> call(path.toString()) }
}

/** Calls [call] with the path given in the nth parameter of the call if it's a file. */
private fun <T> checkNthFileArgument(n: Int, call: (String) -> Unit): (T, Array<Any>?) -> Unit {
  return { _: T, args: Array<Any>? -> (args?.drop(n)?.firstOrNull() as? File)?.let { file -> call(file.absolutePath) } }
}

private fun <T> checkFirstFileArgument(call: (String) -> Unit): (T, Array<Any>?) -> Unit = checkNthFileArgument(0, call)

private fun checkFirstFileOrStringArgument(call: (String) -> Unit): (Any, Array<Any>?) -> Unit {
  return { _: Any, args: Array<Any>? ->
    when (val first = args?.firstOrNull()) {
      is File -> call(first.absolutePath)
      is String -> call(first)
    }
  }
}

private fun <T> checkNthPathArgument(@Suppress("SameParameterValue") n: Int, call: (String) -> Unit): (T, Array<Any>?) -> Unit {
  return { _: T, args: Array<Any>? -> (args?.drop(n)?.firstOrNull() as? Path)?.let { file -> call(file.toString()) } }
}

private fun <T> checkFirstPathArgument(call: (String) -> Unit): (T, Array<Any>?) -> Unit = checkNthPathArgument(0, call)

private fun <T> checkSourceAndDestinationPaths(sourceCall: (String) -> Unit, destinationCall: (String) -> Unit): (T, Array<Any>?) -> Unit {
  return { _: T, args: Array<Any>? ->
    val source = args?.getOrNull(0)
    val destination = args?.getOrNull(1)

    source?.let { sourceCall(it.toString()) }
    destination?.let { destinationCall(it.toString()) }
  }
}

private fun <T> checkAllPathArguments(call: (String) -> Unit): (T, Array<Any>?) -> Unit {
  return { _: T, args: Array<Any>? -> args?.filterIsInstance<Path>()?.forEach { file -> call(file.toString()) } }
}

private fun <T> checkFirstStringArgument(call: (String) -> Unit): (T, Array<Any>?) -> Unit {
  return { _: T, args: Array<Any>? -> (args?.firstOrNull() as? String)?.let { string -> call(string) } }
}

private fun checkStaticPath(call: (String) -> Unit): (String, Array<Any>?) -> Unit {
  return { _, args -> (args?.firstOrNull() as? String)?.let { path -> call(path) } }
}

/** Calls [call] ignoring the arguments of the call. */
private fun <T> checkInstanceCallIgnoreArgs(call: () -> Unit): (T, Array<Any>?) -> Unit {
  return { _: T, _: Array<Any>? -> call() }
}

/** Calls [call] ignoring the arguments of the call. */
private fun checkStaticNoArgsCall(call: () -> Unit): (String, Array<Any>?) -> Unit {
  return { _: String, _: Array<Any>? -> call() }
}

/** Base class to define interceptors for certain operations in the render sandbox. */
internal sealed class Intercept {
  abstract val classInternalName: String
  abstract val methodName: String

  /**
   * [Intercept] representing a virtual call intercept. When it happens [intercept] will be called with the first parameter being the
   * instance of the class being invoked and the second the arguments of the call.
   */
  internal class VirtualIntercept(
    val clazz: Class<*>,
    override val classInternalName: String,
    override val methodName: String,
    val intercept: (Any, Array<Any>?) -> Unit,
  ) : Intercept()

  /**
   * [Intercept] representing a static call intercept. When it happens [intercept] will be called with the first parameter being the
   * internal name of the class being invoked and the second the arguments of the call.
   */
  internal class StaticIntercept(
    override val classInternalName: String,
    override val methodName: String,
    val intercept: (String, Array<Any>?) -> Unit,
  ) : Intercept()

  companion object {
    /**
     * Creates an interceptor for the given instance [methodName]. When the [methodName] is invoked, [intercept] will be invoked giving the
     * opportunity to stop the operation. [intercept] will be called with the instance of the class being invoked and the arguments of the
     * call as arguments.
     */
    inline fun <reified T> instance(methodName: String, noinline intercept: (T, Array<Any>?) -> Unit): Intercept {
      return VirtualIntercept(T::class.java, Type.getInternalName(T::class.java), methodName) { type, args -> intercept(type as T, args) }
    }

    /**
     * Creates an interceptor for the given instance [method]. When the [method] is invoked, [intercept] will be invoked giving the
     * opportunity to stop the operation. [intercept] will be called with the instance of the class being invoked and the arguments of the
     * call as arguments.
     */
    inline fun <reified T> instance(method: KFunction<*>, noinline intercept: (T, Array<Any>?) -> Unit): Intercept =
      instance(method.name, intercept)

    /**
     * Creates an interceptor for the given static [methodName]. When the [methodName] is invoked, [intercept] will be invoked giving the
     * opportunity to stop the operation. [intercept] will be called with the name of the class being invoked and the arguments of the call
     * as arguments.
     */
    inline fun <reified T> static(methodName: String, noinline intercept: (String, Array<Any>?) -> Unit): Intercept {
      return StaticIntercept(Type.getInternalName(T::class.java), methodName) { owner, args -> intercept(owner, args) }
    }

    fun static(classInternalName: String, methodName: String, intercept: (String, Array<Any>?) -> Unit): Intercept {
      return StaticIntercept(classInternalName, methodName, intercept)
    }

    /**
     * Creates an interceptor for the given static [method]. When the [method] is invoked, [intercept] will be invoked giving the
     * opportunity to stop the operation. [intercept] will be called with the name of the class being invoked and the arguments of the call
     * as arguments.
     */
    inline fun <reified T> static(method: KFunction<*>, noinline intercept: (String, Array<Any>?) -> Unit): Intercept =
      static<T>(method.name, intercept)

    /**
     * Creates an interceptor for the given Kotlin static [method] (e.g. a top-level function). Uses reflection to extract the compiled Java
     * class name containing the method.
     */
    inline fun kotlinStatic(method: KFunction<*>, noinline intercept: (String, Array<Any>?) -> Unit): Array<Intercept> {
      val javaMethod = method.javaMethod ?: throw IllegalArgumentException("Method has no Java representation")
      val ownerClass = javaMethod.declaringClass
      var owner = Type.getInternalName(ownerClass)

      // Handle Kotlin multi-file facade parts (e.g., FilesKt__FileReadWriteKt -> FilesKt)
      val doubleUnderscoreIndex = owner.indexOf("__")
      if (doubleUnderscoreIndex != -1) {
        owner = owner.substring(0, doubleUnderscoreIndex)
      }

      val result = mutableListOf<Intercept>()
      result.add(StaticIntercept(owner, javaMethod.name, intercept))

      if (method.parameters.any { it.isOptional }) {
        result.add(StaticIntercept(owner, javaMethod.name + "\$default", intercept))
      }

      return result.toTypedArray()
    }
  }
}

/**
 * A key used to look up interceptors in the flattened map.
 *
 * This class is designed to be mutable so it can be reused via a [ThreadLocal] to avoid allocating a new key object on every lookup. This
 * reduces garbage collection pressure and improves performance for high-frequency intercepted calls.
 */
private class CallKey(var owner: String = "", var method: String = "", var isStatic: Boolean = false) {
  fun set(o: String, m: String, s: Boolean): CallKey {
    owner = o
    method = m
    isStatic = s
    return this
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CallKey) return false
    return owner == other.owner && method == other.method && isStatic == other.isStatic
  }

  override fun hashCode(): Int {
    var result = owner.hashCode()
    result = result * 31 + method.hashCode()
    result = result * 31 + isStatic.hashCode()
    return result
  }
}

/**
 * Static class invoked from user code to check the number of allocations per render action. Every new render action will increment
 * `RenderAsyncActionExecutor#executedRenderActionCount` so this class can check if a new action has started executing.
 */
object RenderSandboxTransformTrampoline {
  /** Validates the input interceptors list to ensure that there are no duplicate entries that were added by accident. */
  private fun assertValidInterceptorListOf(vararg interceptors: Intercept): List<Intercept> {
    assert(
      interceptors
        .groupingBy { "${it.classInternalName}#${it.methodName}#${it is Intercept.StaticIntercept}" }
        .eachCount()
        .all { it.value == 1 }
    ) {
      "Interceptors should only be defined once per class, method and type"
    }

    return interceptors.toList()
  }

  private fun forEachBlockRef(f: kotlin.reflect.KFunction2<File, (ByteArray, Int) -> Unit, Unit>) = f

  private val defaultInterceptors: List<Intercept> =
    assertValidInterceptorListOf(
      // File operations
      Intercept.instance<File>(File::createNewFile, checkFileInstance(::checkFileWrite)),
      Intercept.instance<File>(File::canWrite, checkFileInstance(::checkFileWrite)),
      Intercept.instance<File>(File::canRead, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::canExecute, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::isDirectory, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::isFile, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::isHidden, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::isAbsolute, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::lastModified, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::length, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::delete, checkFileInstance(::checkFileWrite)),
      Intercept.instance<File>(File::deleteOnExit, checkFileInstance(::checkFileWrite)),
      Intercept.instance<File>(File::getName, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::getAbsoluteFile, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::getAbsolutePath, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::getCanonicalFile, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::getCanonicalPath, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::getParent, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::getPath, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::toPath, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::compareTo, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::exists, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>("list", checkFileInstance(::checkFileRead)),
      Intercept.instance<File>("listFiles", checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::mkdir, checkFileInstance(::checkFileWrite)),
      Intercept.instance<File>(File::mkdirs, checkFileInstance(::checkFileWrite)),
      Intercept.instance<File>(File::renameTo, checkFirstFileArgument(::checkFileWrite)),
      Intercept.instance<File>(File::getParentFile, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::setLastModified, checkFileInstance(::checkFileWrite)),
      Intercept.instance<File>(File::setReadOnly, checkFileInstance(::checkFileWrite)),
      Intercept.instance<File>("setWritable", checkFileInstance(::checkFileWrite)),
      Intercept.instance<File>("setReadable", checkFileInstance(::checkFileWrite)),
      Intercept.instance<File>("setExecutable", checkFileInstance(::checkFileWrite)),
      Intercept.instance<File>(File::getTotalSpace, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::getFreeSpace, checkFileInstance(::checkFileRead)),
      Intercept.instance<File>(File::getUsableSpace, checkFileInstance(::checkFileRead)),
      Intercept.static<File>("createTempFile", ::checkCreateTemp),

      // Kotlin Stdlib operations
      *Intercept.kotlinStatic(File::readText, checkFirstFileArgument(::checkFileRead)),
      // Reads
      *Intercept.kotlinStatic(File::readBytes, checkFirstFileArgument(::checkFileRead)),
      *Intercept.kotlinStatic(forEachBlockRef(File::forEachBlock), checkFirstFileArgument(::checkFileRead)),
      *Intercept.kotlinStatic(File::forEachLine, checkFirstFileArgument(::checkFileRead)),
      *Intercept.kotlinStatic(File::readLines, checkFirstFileArgument(::checkFileRead)),
      // Writes
      *Intercept.kotlinStatic(File::writeBytes, checkFirstFileArgument(::checkFileWrite)),
      *Intercept.kotlinStatic(File::appendBytes, checkFirstFileArgument(::checkFileWrite)),
      *Intercept.kotlinStatic(File::writeText, checkFirstFileArgument(::checkFileWrite)),
      *Intercept.kotlinStatic(File::appendText, checkFirstFileArgument(::checkFileWrite)),
      // Utils.kt
      *Intercept.kotlinStatic(File::copyRecursively, checkSourceAndDestinationPaths(::checkFileRead, ::checkFileWrite)),
      *Intercept.kotlinStatic(File::deleteRecursively, checkFirstFileArgument(::checkFileWrite)),
      // FileTreeWalk.kt
      *Intercept.kotlinStatic(File::walk, checkFirstFileArgument(::checkFileRead)),
      *Intercept.kotlinStatic(File::walkTopDown, checkFirstFileArgument(::checkFileRead)),
      *Intercept.kotlinStatic(File::walkBottomUp, checkFirstFileArgument(::checkFileRead)),

      // FileInputStream/FileOutputStream constructors
      Intercept.static<FileInputStream>("<init>", checkFirstFileOrStringArgument(::checkFileRead)),
      Intercept.static<FileOutputStream>("<init>", checkFirstFileOrStringArgument(::checkFileWrite)),
      Intercept.static<RandomAccessFile>("<init>", ::checkRandomAccessFileInit),

      // URI/URL operations
      Intercept.instance<URL>("openConnection", checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<URL>("getContent", checkInstanceCallIgnoreArgs(::checkConnection)),

      // Socket operations
      Intercept.instance<Socket>(Socket::bind, checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<Socket>("connect", checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<SSLSocket>(Socket::bind, checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<SSLSocket>("connect", checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<ServerSocket>(ServerSocket::accept, checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<ServerSocket>(ServerSocket::getChannel, checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<ServerSocket>("bind", checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<SSLServerSocket>(ServerSocket::accept, checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<SSLServerSocket>(ServerSocket::getChannel, checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<SSLServerSocket>("bind", checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<HttpURLConnection>(HttpURLConnection::connect, checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<HttpURLConnection>("getContent", checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<HttpURLConnection>(HttpURLConnection::getInputStream, checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<HttpURLConnection>(HttpURLConnection::getErrorStream, checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<HttpsURLConnection>(HttpsURLConnection::connect, checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<HttpsURLConnection>("getContent", checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<HttpsURLConnection>(HttpsURLConnection::getInputStream, checkInstanceCallIgnoreArgs(::checkConnection)),
      Intercept.instance<HttpsURLConnection>(HttpsURLConnection::getErrorStream, checkInstanceCallIgnoreArgs(::checkConnection)),

      // DatagramSocket operations
      Intercept.static<DatagramSocket>("<init>", checkStaticNoArgsCall(::checkConnection)),
      Intercept.static<MulticastSocket>("<init>", checkStaticNoArgsCall(::checkConnection)),

      // Path operations
      Intercept.static<Paths>("get", checkStaticPath(::checkFileRead)),
      Intercept.static<Files>(Files::newOutputStream, checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>("newBufferedReader", checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::newInputStream, ::checkNewInputStream),
      Intercept.static<Files>("newBufferedWriter", checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>("newByteChannel", checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>("newDirectoryStream", checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::createFile, checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>(Files::createDirectory, checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>(Files::createDirectories, checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>("createTempFile", ::checkCreateTempFromFiles),
      Intercept.static<Files>("createTempDirectory", ::checkCreateTempFromFiles),
      Intercept.static<Files>(Files::createLink, checkSourceAndDestinationPaths(::checkFileWrite, ::checkFileRead)),
      Intercept.static<Files>(Files::createSymbolicLink, checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>(Files::delete, checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>(Files::deleteIfExists, checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>(Files::exists, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>("copy", checkSourceAndDestinationPaths(::checkFileRead, ::checkFileWrite)),
      Intercept.static<Files>(Files::move, checkSourceAndDestinationPaths(::checkFileRead, ::checkFileWrite)),
      Intercept.static<Files>(Files::mismatch, checkAllPathArguments(::checkFileRead)),
      Intercept.static<Files>(Files::isHidden, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::isReadable, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::isWritable, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::isExecutable, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::isSameFile, checkAllPathArguments(::checkFileRead)),
      Intercept.static<Files>(Files::probeContentType, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>("getFileAttributeView", checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>("readAttributes", checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::getAttribute, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::setAttribute, checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>(Files::getPosixFilePermissions, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::setPosixFilePermissions, checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>(Files::getOwner, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::setOwner, checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>(Files::isSymbolicLink, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::isDirectory, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::isRegularFile, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::getLastModifiedTime, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::setLastModifiedTime, checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>("lines", checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::notExists, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::readAllBytes, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>("readAllLines", checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::size, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>("write", checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>("writeString", checkFirstPathArgument(::checkFileWrite)),
      Intercept.static<Files>("readString", checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::find, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>("walk", checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>("walkFileTree", checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::list, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::readSymbolicLink, checkFirstPathArgument(::checkFileRead)),
      Intercept.static<Files>(Files::getFileStore, checkFirstPathArgument(::checkFileRead)),
      Intercept.instance<Path>(Path::toRealPath, checkPathInstance(::checkFileRead)),
      Intercept.instance<Path>(Path::getParent, checkPathInstance(::checkFileRead)),
      Intercept.instance<Path>(Path::toAbsolutePath, checkPathInstance(::checkFileRead)),

      // System operations
      Intercept.static<System>(System::exit, checkStaticNoArgsCall(::checkExit)),
      Intercept.static<System>("getProperty", checkFirstStringArgument(::checkPropertyRead)),
      Intercept.static<System>("setProperty", checkFirstStringArgument(::checkPropertyWrite)),
      Intercept.static<System>(System::clearProperty, checkFirstStringArgument(::checkPropertyWrite)),
      Intercept.static<System>(System::getProperties, checkStaticNoArgsCall(::checkPropertyAccess)),
      Intercept.static<System>(System::setProperties, checkStaticNoArgsCall(::checkPropertyAccess)),
      Intercept.static<System>("getenv", checkStaticNoArgsCall(::checkEnvAccess)),
      Intercept.static<System>(System::setIn, checkStaticNoArgsCall(::checkSystemIoSet)),
      Intercept.static<System>(System::setOut, checkStaticNoArgsCall(::checkSystemIoSet)),
      Intercept.static<System>(System::setErr, checkStaticNoArgsCall(::checkSystemIoSet)),

      // Class loading
      Intercept.instance<ClassLoader>(ClassLoader::loadClass, checkFirstStringArgument(::checkClassLoad)),
      Intercept.static<Class<*>>("forName", ::checkForNameCalls),
      Intercept.instance<Class<*>>("getClassLoader", ::checkGetClassLoader),
      Intercept.instance<ClassLoader>("getParent", checkInstanceCallIgnoreArgs(::checkClassLoaderAccess)),
      Intercept.instance<Thread>("getContextClassLoader", checkInstanceCallIgnoreArgs(::checkClassLoaderAccess)),
      Intercept.static<ClassLoader>("getSystemClassLoader", checkStaticNoArgsCall(::checkClassLoaderAccess)),
      Intercept.static<ClassLoader>("getPlatformClassLoader", checkStaticNoArgsCall(::checkClassLoaderAccess)),
      Intercept.instance<Class<*>>(Class<*>::getResource, ::checkResourceLoad),
      Intercept.instance<Class<*>>(Class<*>::getResourceAsStream, ::checkResourceLoad),

      // ClassLoader creation & definition
      Intercept.static<ClassLoader>("<init>", checkStaticNoArgsCall(::checkCreateClassLoader)),
      Intercept.static<java.net.URLClassLoader>("<init>", checkStaticNoArgsCall(::checkCreateClassLoader)),
      Intercept.static<java.security.SecureClassLoader>("<init>", checkStaticNoArgsCall(::checkCreateClassLoader)),
      Intercept.instance<ClassLoader>("defineClass", checkInstanceCallIgnoreArgs(::checkDefineClass)),
      Intercept.instance<java.security.SecureClassLoader>("defineClass", checkInstanceCallIgnoreArgs(::checkDefineClass)),

      // Process execution
      Intercept.instance<Runtime>(Runtime::exit, checkInstanceCallIgnoreArgs(::checkExit)),
      Intercept.instance<Runtime>(Runtime::halt, checkInstanceCallIgnoreArgs(::checkExit)),
      Intercept.instance<Runtime>("addShutdownHook", checkInstanceCallIgnoreArgs(::checkExit)),
      Intercept.instance<Runtime>("removeShutdownHook", checkInstanceCallIgnoreArgs(::checkExit)),
      Intercept.instance<Runtime>("exec", checkInstanceCallIgnoreArgs(::checkProcessExec)),
      Intercept.instance<ProcessBuilder>(ProcessBuilder::start, checkInstanceCallIgnoreArgs(::checkProcessExec)),
      Intercept.static<ProcessBuilder>("startPipeline", checkStaticNoArgsCall(::checkProcessExec)),
      Intercept.static("java/lang/ProcessImpl", "start", checkInstanceCallIgnoreArgs(::checkProcessExec)),
      Intercept.instance<Process>("destroy", checkInstanceCallIgnoreArgs(::checkProcessExec)),
      Intercept.instance<Process>("destroyForcibly", checkInstanceCallIgnoreArgs(::checkProcessExec)),
      Intercept.instance<Process>("waitFor", checkInstanceCallIgnoreArgs(::checkProcessExec)),
      Intercept.instance<ProcessHandle>("destroy", checkInstanceCallIgnoreArgs(::checkProcessExec)),
      Intercept.instance<ProcessHandle>("destroyForcibly", checkInstanceCallIgnoreArgs(::checkProcessExec)),
      Intercept.static("java/lang/Shutdown", "exit", checkInstanceCallIgnoreArgs(::checkExit)),
      Intercept.static("java/lang/Shutdown", "halt", checkInstanceCallIgnoreArgs(::checkExit)),

      // Library loading
      Intercept.instance<Runtime>(Runtime::load, checkFirstStringArgument(::checkLoadLibrary)),
      Intercept.instance<Runtime>(Runtime::loadLibrary, checkFirstStringArgument(::checkLoadLibrary)),

      // AWT operations
      Intercept.instance<Toolkit>("getSystemClipboard", checkInstanceCallIgnoreArgs(::checkClipboard)),
      Intercept.instance<Toolkit>("getSystemEventQueue", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.static<JEditorPane>("registerEditorKitForContentType", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.instance<KeyboardFocusManager>("addKeyEventDispatcher", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<KeyboardFocusManager>("removeKeyEventDispatcher", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<KeyboardFocusManager>("addKeyEventPostProcessor", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<KeyboardFocusManager>("removeKeyEventPostProcessor", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.static<KeyboardFocusManager>("setCurrentKeyboardFocusManager", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.instance<DefaultKeyboardFocusManager>("addKeyEventDispatcher", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<DefaultKeyboardFocusManager>("removeKeyEventDispatcher", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<DefaultKeyboardFocusManager>("addKeyEventPostProcessor", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<DefaultKeyboardFocusManager>("removeKeyEventPostProcessor", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<Keymap>("setDefaultAction", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<Keymap>("addActionForKeyStroke", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.static<PopupFactory>("setSharedInstance", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.static<LayoutStyle>("setInstance", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.static<UIManager>("put", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.static<UIManager>("setLookAndFeel", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.instance<UIDefaults>("put", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.static<RepaintManager>("setCurrentManager", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.static<Window>("getWindows", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.static<Window>("getOwnerlessWindows", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.static<Frame>("getFrames", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.static<DragSource>("getDefaultDragSource", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.instance<DragSource>("addDragSourceListener", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<DragSource>("removeDragSourceListener", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<DragSource>("addDragSourceMotionListener", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<DragSource>("removeDragSourceMotionListener", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<MenuSelectionManager>("addChangeListener", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<MenuSelectionManager>("removeChangeListener", checkInstanceCallIgnoreArgs(::checkEventQueue)),

      // Print operations
      Intercept.static<PrinterJob>("getPrinterJob", checkStaticNoArgsCall(::checkPrintJob)),
      Intercept.static<PrinterJob>("lookupPrintServices", checkStaticNoArgsCall(::checkPrintJob)),
      Intercept.static<PrinterJob>("lookupStreamPrintServices", checkStaticNoArgsCall(::checkPrintJob)),
      Intercept.static<PrintServiceLookup>("registerServiceProvider", checkStaticNoArgsCall(::checkPrintJob)),
      Intercept.static<PrintServiceLookup>("registerService", checkStaticNoArgsCall(::checkPrintJob)),
      Intercept.static<PrintServiceLookup>("lookupPrintServices", checkStaticNoArgsCall(::checkPrintJob)),
      Intercept.static<PrintServiceLookup>("lookupDefaultPrintService", checkStaticNoArgsCall(::checkPrintJob)),
      Intercept.static<PrintServiceLookup>("lookupMultiDocPrintServices", checkStaticNoArgsCall(::checkPrintJob)),

      // ImageIO SPI operations
      Intercept.static<IIORegistry>("getDefaultInstance", checkStaticNoArgsCall(::checkImageIo)),
      Intercept.instance<IIORegistry>("registerApplicationClasspathSpis", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<IIORegistry>("registerServiceProvider", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<IIORegistry>("registerServiceProviders", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<IIORegistry>("deregisterServiceProvider", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<IIORegistry>("deregisterServiceProviders", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<IIORegistry>("deregisterAll", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<IIORegistry>("setOrdering", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<IIORegistry>("unsetOrdering", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.static<ServiceRegistry>("lookupProviders", checkStaticNoArgsCall(::checkImageIo)),
      Intercept.instance<ServiceRegistry>("registerServiceProvider", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<ServiceRegistry>("registerServiceProviders", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<ServiceRegistry>("deregisterServiceProvider", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<ServiceRegistry>("deregisterServiceProviders", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<ServiceRegistry>("deregisterAll", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<ServiceRegistry>("setOrdering", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.instance<ServiceRegistry>("unsetOrdering", checkInstanceCallIgnoreArgs(::checkImageIo)),
      Intercept.static<ImageIO>("scanForPlugins", checkStaticNoArgsCall(::checkImageIo)),

      // FileChannel
      Intercept.static<FileChannel>("open", ::checkFileChannelOpen),
      Intercept.static<AsynchronousFileChannel>("open", ::checkAsyncFileChannelOpen),

      // ZipFile
      Intercept.static<ZipFile>("<init>", checkFirstFileOrStringArgument(::checkFileRead)),

      // URL.openStream
      Intercept.instance<URL>("openStream", checkInstanceCallIgnoreArgs(::checkConnection)),

      // Concurrency/Async operations
      Intercept.static<CompletableFuture<*>>("supplyAsync", checkStaticNoArgsCall(::checkConcurrency)),
      Intercept.static<CompletableFuture<*>>("runAsync", checkStaticNoArgsCall(::checkConcurrency)),
      Intercept.static<ForkJoinPool>("commonPool", checkStaticNoArgsCall(::checkConcurrency)),
      Intercept.instance<ForkJoinPool>("submit", checkInstanceCallIgnoreArgs(::checkConcurrency)),
      Intercept.instance<ForkJoinPool>("execute", checkInstanceCallIgnoreArgs(::checkConcurrency)),
      Intercept.static<Executors>("newSingleThreadExecutor", checkStaticNoArgsCall(::checkConcurrency)),
      Intercept.static<Executors>("newCachedThreadPool", checkStaticNoArgsCall(::checkConcurrency)),
      Intercept.static<Executors>("newFixedThreadPool", checkStaticNoArgsCall(::checkConcurrency)),
      Intercept.static<Executors>("newScheduledThreadPool", checkStaticNoArgsCall(::checkConcurrency)),
      Intercept.static<ThreadPoolExecutor>("<init>", checkStaticNoArgsCall(::checkConcurrency)),
      Intercept.static<ScheduledThreadPoolExecutor>("<init>", checkStaticNoArgsCall(::checkConcurrency)),
      Intercept.static<ForkJoinPool>("<init>", checkStaticNoArgsCall(::checkConcurrency)),

      // Cleaner operations
      Intercept.static<Cleaner>("create", checkStaticNoArgsCall(::checkCleaner)),
      Intercept.instance<Cleaner>("register", checkInstanceCallIgnoreArgs(::checkCleaner)),

      // Signal operations
      Intercept.static<Signal>("handle", checkStaticNoArgsCall(::checkSignal)),
      Intercept.static<Signal>("raise", checkStaticNoArgsCall(::checkSignal)),

      // Timer operations
      Intercept.instance<SwingTimer>("start", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.instance<SwingTimer>("restart", checkInstanceCallIgnoreArgs(::checkEventQueue)),
      Intercept.static<java.util.Timer>("<init>", checkStaticNoArgsCall(::checkConcurrency)),
      Intercept.instance<java.util.Timer>("schedule", checkInstanceCallIgnoreArgs(::checkConcurrency)),
      Intercept.instance<java.util.Timer>("scheduleAtFixedRate", checkInstanceCallIgnoreArgs(::checkConcurrency)),

      // LayoutQueue operations
      Intercept.static<LayoutQueue>("getDefaultQueue", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.static<LayoutQueue>("setDefaultQueue", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.static<LayoutQueue>("<init>", checkStaticNoArgsCall(::checkEventQueue)),
      Intercept.instance<LayoutQueue>("addTask", checkInstanceCallIgnoreArgs(::checkEventQueue)),

      // RenderService internal operations
      Intercept.static<RenderService>("initializeRenderExecutor", checkStaticNoArgsCall(::checkRenderExecutor)),
      Intercept.static<RenderService>("shutdownRenderExecutor", checkStaticNoArgsCall(::checkRenderExecutor)),

      // Thread operations
      Intercept.static<Thread>("<init>", ::checkThreadConstruct),

      // Reflection
      Intercept.instance<Method>("invoke", ::checkMethodInvoke),
      Intercept.instance<Constructor<*>>("newInstance", ::checkConstructorNewInstance),
      Intercept.instance<Class<*>>("newInstance", ::checkClassNewInstance),
      Intercept.instance<Field>("set", ::checkFieldAccess),
      Intercept.instance<Field>("get", ::checkFieldAccess),
      Intercept.instance<AccessibleObject>("setAccessible", ::checkAccessibleObjectAccess),
      Intercept.static<AccessibleObject>("setAccessible") { _: String, args: Array<Any>? ->
        val firstArg = args?.firstOrNull() as? Array<AccessibleObject>
        if (firstArg != null) {
          checkStaticAccessibleObjectAccess(firstArg, args)
        }
      },

      // Unsafe
      Intercept.static<Unsafe>("getUnsafe", checkStaticNoArgsCall(::checkUnsafeAccess)),

      // MethodHandles.Lookup (b/557286904)
      Intercept.instance<MethodHandles.Lookup>("findStatic", ::checkFindMember),
      Intercept.instance<MethodHandles.Lookup>("findVirtual", ::checkFindMember),
      Intercept.instance<MethodHandles.Lookup>("findConstructor", ::checkFindConstructor),
      Intercept.instance<MethodHandles.Lookup>("findSpecial", ::checkFindMember),
      Intercept.instance<MethodHandles.Lookup>("bind", ::checkFindMember),
      Intercept.instance<MethodHandles.Lookup>("findGetter", ::checkFindField),
      Intercept.instance<MethodHandles.Lookup>("findSetter", ::checkFindField),
      Intercept.instance<MethodHandles.Lookup>("findStaticGetter", ::checkFindField),
      Intercept.instance<MethodHandles.Lookup>("findStaticSetter", ::checkFindField),
      Intercept.instance<MethodHandles.Lookup>("findVarHandle", ::checkFindField),
      Intercept.instance<MethodHandles.Lookup>("findStaticVarHandle", ::checkFindField),
      Intercept.instance<MethodHandles.Lookup>("findClass", checkFirstStringArgument(::checkClassLoad)),
      Intercept.instance<MethodHandles.Lookup>("accessClass", ::checkLookupAccessClass),
      Intercept.instance<MethodHandles.Lookup>("ensureInitialized", ::checkLookupAccessClass),
      Intercept.instance<MethodHandles.Lookup>("unreflect", ::checkUnreflect),
      Intercept.instance<MethodHandles.Lookup>("unreflectConstructor", ::checkUnreflectConstructor),
      Intercept.instance<MethodHandles.Lookup>("unreflectGetter", ::checkUnreflectField),
      Intercept.instance<MethodHandles.Lookup>("unreflectSetter", ::checkUnreflectField),
      Intercept.instance<MethodHandles.Lookup>("unreflectVarHandle", ::checkUnreflectField),
      Intercept.instance<MethodHandles.Lookup>("unreflectSpecial", ::checkUnreflect),
      Intercept.instance<MethodHandles.Lookup>("defineClass", checkInstanceCallIgnoreArgs(::checkDefineClass)),
      Intercept.instance<MethodHandles.Lookup>("defineHiddenClass", checkInstanceCallIgnoreArgs(::checkDefineClass)),
      Intercept.instance<MethodHandles.Lookup>("defineHiddenClassWithClassData", checkInstanceCallIgnoreArgs(::checkDefineClass)),
      Intercept.static<MethodHandles>("privateLookupIn", ::checkPrivateLookupIn),

      // ObjectInputStream
      Intercept.instance<ObjectInputStream>("readObject", ::checkReadObject),

      // System.load and System.loadLibrary (checkLink bypass)
      Intercept.static<System>("load", checkFirstStringArgument(::checkLoadLibrary)),
      Intercept.static<System>("loadLibrary", checkFirstStringArgument(::checkLoadLibrary)),

      // FileSystemProvider operations (b/557278228)
      Intercept.instance<FileSystemProvider>("newOutputStream", checkFirstPathArgument(::checkFileWrite)),
      Intercept.instance<FileSystemProvider>("newInputStream", ::checkNewInputStream),
      Intercept.instance<FileSystemProvider>("newFileChannel", ::checkFileChannelOpen),
      Intercept.instance<FileSystemProvider>("newAsynchronousFileChannel", ::checkAsyncFileChannelOpen),
      Intercept.instance<FileSystemProvider>("newByteChannel", ::checkFileChannelOpen),
      Intercept.instance<FileSystemProvider>("createDirectory", checkFirstPathArgument(::checkFileWrite)),
      Intercept.instance<FileSystemProvider>("createSymbolicLink", checkFirstPathArgument(::checkFileWrite)),
      Intercept.instance<FileSystemProvider>("createLink", checkSourceAndDestinationPaths(::checkFileWrite, ::checkFileRead)),
      Intercept.instance<FileSystemProvider>("delete", checkFirstPathArgument(::checkFileWrite)),
      Intercept.instance<FileSystemProvider>("deleteIfExists", checkFirstPathArgument(::checkFileWrite)),
      Intercept.instance<FileSystemProvider>("copy", checkSourceAndDestinationPaths(::checkFileRead, ::checkFileWrite)),
      Intercept.instance<FileSystemProvider>("move", checkSourceAndDestinationPaths(::checkFileRead, ::checkFileWrite)),
      Intercept.instance<FileSystemProvider>("checkAccess", checkFirstPathArgument(::checkFileRead)),
      Intercept.instance<FileSystemProvider>("readAttributes", checkFirstPathArgument(::checkFileRead)),
      Intercept.instance<FileSystemProvider>("readAttributesIfExists", checkFirstPathArgument(::checkFileRead)),
      Intercept.instance<FileSystemProvider>("readSymbolicLink", checkFirstPathArgument(::checkFileRead)),
      Intercept.instance<FileSystemProvider>("getFileAttributeView", ::checkFileAttributeView),
      Intercept.instance<FileSystemProvider>("getFileStore", checkFirstPathArgument(::checkFileRead)),
      Intercept.instance<FileSystemProvider>("isHidden", checkFirstPathArgument(::checkFileRead)),
      Intercept.instance<FileSystemProvider>("setAttribute", checkFirstPathArgument(::checkFileWrite)),
      Intercept.instance<FileSystemProvider>("newDirectoryStream", checkFirstPathArgument(::checkFileRead)),

      // java.beans.Statement / Expression / XMLDecoder operations (b/557278850)
      Intercept.instance<Statement>("execute", ::checkStatementExecute),
      Intercept.instance<Expression>("getValue", ::checkExpressionGetValue),
      Intercept.static<XMLDecoder>("<init>", checkStaticNoArgsCall(::checkXmlDecoder)),
      Intercept.instance<XMLDecoder>("readObject", checkInstanceCallIgnoreArgs(::checkXmlDecoder)),
      Intercept.instance<XMLDecoder>("close", checkInstanceCallIgnoreArgs(::checkXmlDecoder)),
      Intercept.static<XMLEncoder>("<init>", checkStaticNoArgsCall(::checkXmlDecoder)),

      // ScriptEngineManager & ServiceLoader operations (b/557285779)
      Intercept.static<ScriptEngineManager>("<init>", checkStaticNoArgsCall(::checkScriptEngine)),
      Intercept.instance<ScriptEngineManager>("getClassLoader", checkInstanceCallIgnoreArgs(::checkScriptEngine)),
      Intercept.static<ServiceLoader<*>>("load", checkStaticNoArgsCall(::checkServiceLoader)),
      Intercept.static<ServiceLoader<*>>("loadInstalled", checkStaticNoArgsCall(::checkServiceLoader)),

      // URL.setURLStreamHandlerFactory (b/557286705)
      Intercept.static<URL>("setURLStreamHandlerFactory", checkStaticNoArgsCall(::checkSetFactory)),
      Intercept.static<URLConnection>("setContentHandlerFactory", checkStaticNoArgsCall(::checkSetFactory)),
    )

  private val localKey = ThreadLocal.withInitial { CallKey() }

  /** Index by class name and method name to allow for quick lookup of interceptors. */
  private val interceptorIndex: Map<CallKey, Intercept> = defaultInterceptors.associateBy {
    CallKey(it.classInternalName, it.methodName, it is Intercept.StaticIntercept)
  }

  private val virtualMethodToInterceptors: Map<String, List<Intercept.VirtualIntercept>> =
    defaultInterceptors
      .filterIsInstance<Intercept.VirtualIntercept>()
      .filter { (it.clazz.modifiers and java.lang.reflect.Modifier.FINAL) == 0 }
      .groupBy { it.methodName }

  private val virtualMethodNames: Set<String> = virtualMethodToInterceptors.keys

  private val virtualMethodBytes: List<ByteArray> = virtualMethodNames.map { it.toByteArray(Charsets.UTF_8) }

  private val ownerStrings: Set<String> = defaultInterceptors.map { it.classInternalName }.toSet()

  private val ownerBytes: List<ByteArray> = ownerStrings.map { it.toByteArray(Charsets.UTF_8) }

  @get:TestOnly
  val classesToIntercept: Set<String>
    get() = ownerStrings

  fun shouldIntercept(owner: String, method: String): Boolean =
    interceptorIndex.containsKey(localKey.get().set(owner, method, true)) ||
      interceptorIndex.containsKey(localKey.get().set(owner, method, false))

  fun shouldInterceptVirtual(method: String): Boolean = virtualMethodToInterceptors.containsKey(method)

  private val sandboxInternalPrefixes =
    listOf(
      "com/android/tools/rendering/security/RenderSandbox",
      "com/android/tools/rendering/security/PreCheckRenderSandbox",
      "com/android/tools/rendering/security/AllowAllRenderSandbox",
      "com/android/tools/rendering/security/DenyAllRenderSandbox",
      "org/jetbrains/android/uipreview/StudioRenderSandbox",
      "com/android/tools/preview/BasicRenderSandbox",
    )

  private val restrictedReflectionPrefixes =
    listOf(
      "java/lang/Process",
      "java/lang/UNIXProcess",
      "java/lang/Shutdown",
      "jdk/internal/",
      "sun/misc/",
      "sun/reflect/",
      "java/beans/XMLDecoder",
      "java/beans/XMLEncoder",
      "java/beans/Statement",
      "java/beans/Expression",
    )

  private val restrictedReflectionPrefixBytes: List<ByteArray> =
    (restrictedReflectionPrefixes + sandboxInternalPrefixes).map { it.toByteArray(Charsets.UTF_8) }

  private fun isRestrictedReflectionTarget(owner: String): Boolean =
    sandboxInternalPrefixes.any { owner.startsWith(it) } || restrictedReflectionPrefixes.any { owner.startsWith(it) }

  private fun isRestrictedClass(clazz: Class<*>): Boolean =
    Process::class.java.isAssignableFrom(clazz) ||
      ProcessBuilder::class.java.isAssignableFrom(clazz) ||
      ProcessHandle::class.java.isAssignableFrom(clazz) ||
      XMLDecoder::class.java.isAssignableFrom(clazz) ||
      XMLEncoder::class.java.isAssignableFrom(clazz) ||
      Statement::class.java.isAssignableFrom(clazz) ||
      Expression::class.java.isAssignableFrom(clazz)

  fun shouldInterceptPolymorphic(owner: String, method: String): Boolean {
    if (isRestrictedReflectionTarget(owner)) return true
    if (shouldIntercept(owner, method)) return true
    val candidates = virtualMethodToInterceptors[method] ?: return false
    val fqcn = owner.replace('/', '.')
    val clazz =
      try {
        Class.forName(fqcn, false, Thread.currentThread().contextClassLoader)
      } catch (_: Throwable) {
        try {
          Class.forName(fqcn, false, RenderSandboxTransformTrampoline::class.java.classLoader)
        } catch (_: Throwable) {
          null
        }
      } ?: return true
    if (isRestrictedClass(clazz)) return true
    return candidates.any { it.clazz.isAssignableFrom(clazz) }
  }

  fun shouldInterceptPolymorphic(clazz: Class<*>, method: String): Boolean {
    val owner = clazz.name.replace('.', '/')
    if (isRestrictedReflectionTarget(owner)) return true
    if (isRestrictedClass(clazz)) return true
    if (shouldIntercept(owner, method)) return true
    val candidates = virtualMethodToInterceptors[method] ?: return false
    return candidates.any { it.clazz.isAssignableFrom(clazz) }
  }

  private val restrictedFieldSuperclasses: List<Class<*>> by lazy {
    defaultInterceptors
      .filterIsInstance<Intercept.VirtualIntercept>()
      .map { it.clazz }
      .filter { (it.modifiers and java.lang.reflect.Modifier.FINAL) == 0 }
      .distinct()
  }

  fun shouldInterceptField(owner: String, fieldName: String): Boolean {
    if (isRestrictedReflectionTarget(owner)) return true
    if (ownerStrings.contains(owner)) return true
    if (
      owner.startsWith("java/lang/reflect/") ||
        owner.startsWith("java/lang/invoke/") ||
        owner.startsWith("sun/misc/") ||
        owner.startsWith("jdk/internal/")
    ) {
      return true
    }
    val fqcn = owner.replace('/', '.')
    val clazz =
      try {
        Class.forName(fqcn, false, Thread.currentThread().contextClassLoader)
      } catch (_: Throwable) {
        try {
          Class.forName(fqcn, false, RenderSandboxTransformTrampoline::class.java.classLoader)
        } catch (_: Throwable) {
          null
        }
      } ?: return true
    if (isRestrictedClass(clazz)) return true
    return restrictedFieldSuperclasses.any { it.isAssignableFrom(clazz) }
  }

  fun shouldInterceptField(clazz: Class<*>, fieldName: String): Boolean {
    val owner = clazz.name.replace('.', '/')
    if (isRestrictedReflectionTarget(owner)) return true
    if (isRestrictedClass(clazz)) return true
    if (ownerStrings.contains(owner)) return true
    if (
      owner.startsWith("java/lang/reflect/") ||
        owner.startsWith("java/lang/invoke/") ||
        owner.startsWith("sun/misc/") ||
        owner.startsWith("jdk/internal/")
    ) {
      return true
    }
    return restrictedFieldSuperclasses.any { it.isAssignableFrom(clazz) }
  }

  fun shouldIntercept(opcode: Int, owner: String, method: String): Boolean =
    when (opcode) {
      org.objectweb.asm.Opcodes.INVOKESTATIC -> interceptorIndex.containsKey(localKey.get().set(owner, method, true))
      org.objectweb.asm.Opcodes.INVOKESPECIAL ->
        if (method == "<init>") {
          interceptorIndex.containsKey(localKey.get().set(owner, method, true))
        } else {
          interceptorIndex.containsKey(localKey.get().set(owner, method, false)) || virtualMethodToInterceptors.containsKey(method)
        }
      org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
      org.objectweb.asm.Opcodes.INVOKEINTERFACE ->
        interceptorIndex.containsKey(localKey.get().set(owner, method, false)) || virtualMethodToInterceptors.containsKey(method)
      else -> false
    }

  /**
   * Returns whether any of the methods called by the class defined in [classData] could be intercepted.
   *
   * This is an optimization to avoid expensive ASM transformations for classes that don't need them. It performs a fast scan of the class's
   * constant pool to see if it references any of the "interesting" owner classes tracked by the sandbox or virtual methods that could be
   * intercepted polymorphically.
   *
   * This method is optimized to be zero-allocation by comparing UTF8 bytes directly in the constant pool instead of allocating String
   * objects for every class reference.
   */
  fun couldIntercept(classData: ByteArray): Boolean {
    val reader = ClassReader(classData)
    // Scan the constant pool for Class references and NameAndType references
    for (i in 1 until reader.itemCount) {
      val offset = reader.getItem(i)
      if (offset > 0) {
        val tag = reader.readByte(offset - 1)
        if (tag == 7) { // CONSTANT_Class
          // Read 2 bytes for name index
          val b1 = reader.readByte(offset)
          val b2 = reader.readByte(offset + 1)
          val nameIndex = ((b1.toInt() and 0xFF) shl 8) or (b2.toInt() and 0xFF)

          val utf8Offset = reader.getItem(nameIndex)
          // Read 2 bytes for length
          val l1 = reader.readByte(utf8Offset)
          val l2 = reader.readByte(utf8Offset + 1)
          val len = ((l1.toInt() and 0xFF) shl 8) or (l2.toInt() and 0xFF)

          for (targetBytes in ownerBytes) {
            if (targetBytes.size == len && matchBytes(reader, utf8Offset + 2, targetBytes)) {
              return true
            }
          }

          for (prefixBytes in restrictedReflectionPrefixBytes) {
            if (len >= prefixBytes.size && matchBytes(reader, utf8Offset + 2, prefixBytes)) {
              return true
            }
          }
        } else if (tag == 12) { // CONSTANT_NameAndType
          val b1 = reader.readByte(offset)
          val b2 = reader.readByte(offset + 1)
          val nameIndex = ((b1.toInt() and 0xFF) shl 8) or (b2.toInt() and 0xFF)

          val utf8Offset = reader.getItem(nameIndex)
          val l1 = reader.readByte(utf8Offset)
          val l2 = reader.readByte(utf8Offset + 1)
          val len = ((l1.toInt() and 0xFF) shl 8) or (l2.toInt() and 0xFF)

          for (targetBytes in virtualMethodBytes) {
            if (targetBytes.size == len && matchBytes(reader, utf8Offset + 2, targetBytes)) {
              return true
            }
          }
        }
      }
    }
    return false
  }

  private fun matchBytes(reader: ClassReader, offset: Int, target: ByteArray): Boolean {
    for (i in target.indices) {
      if (reader.readByte(offset + i) != target[i].toInt()) return false
    }
    return true
  }

  @TestOnly
  fun hasStaticIntercept(owner: String, method: String): Boolean =
    interceptorIndex[localKey.get().set(owner, method, true)] is Intercept.StaticIntercept

  @TestOnly
  fun hasInstanceIntercept(owner: String, method: String): Boolean =
    interceptorIndex[localKey.get().set(owner, method, false)] is Intercept.VirtualIntercept

  @JvmStatic
  fun invoke(owner: Any, ownerClass: String, method: String, params: Array<Any>?): Unit {
    val exactInterceptor = interceptorIndex[localKey.get().set(ownerClass, method, false)] as? Intercept.VirtualIntercept
    if (exactInterceptor != null) {
      exactInterceptor.intercept(owner, params)
      return
    }
    val candidates = virtualMethodToInterceptors[method] ?: return
    for (candidate in candidates) {
      if (candidate.clazz.isInstance(owner)) {
        candidate.intercept(owner, params)
        return
      }
    }
  }

  @JvmStatic
  fun invokeStatic(ownerClass: String, method: String, params: Array<Any>?): Unit {
    (interceptorIndex[localKey.get().set(ownerClass, method, true)] as? Intercept.StaticIntercept)?.intercept?.invoke(ownerClass, params)
  }

  @JvmStatic
  fun checkMemberAccess(ownerClass: String, method: String): Unit {
    RenderSandbox.getRenderSandbox().checkReflectionInvoke(ownerClass, method)
  }

  @JvmStatic
  fun checkFieldAccessTrampoline(ownerClass: String, fieldName: String): Unit {
    RenderSandbox.getRenderSandbox().checkFieldAccess(ownerClass, fieldName)
  }
}
