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
package com.android.tools.idea.gradle.project.sync

import java.io.Serializable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.SortedMap
import java.util.SortedSet
import java.util.TreeMap
import java.util.TreeSet
import org.gradle.tooling.model.DomainObjectSet

/**
 * Returns this model with every call on a Gradle Tooling API view in it synchronized on that view.
 *
 * Models fetched from a [org.gradle.tooling.BuildController] are views: proxies that the Tooling API creates over the objects built by the
 * model builders. A view caches the values of its properties in a `HashMap` that is not thread-safe. The nested actions run in parallel by
 * [org.gradle.tooling.BuildController.run] share their views, so an object always has the same view. AGP shares objects such as `Edge`,
 * `Library` and `ProjectInfo` between the dependency models of different projects, so actions running in parallel can call the same view at
 * the same time. A property that is never null can then read as null (b/371925845).
 *
 * The returned model is a proxy that calls the view while holding the view's monitor. Views returned by these calls are wrapped the same
 * way, and collections are copied with their elements wrapped. Each view is therefore called by one thread at a time, while different
 * views, e.g. the models of different projects, are still read in parallel. A monitor is only held while the Tooling API handles one call,
 * which does not call wrapped objects, so a thread never holds two of these monitors and they cannot deadlock.
 *
 * Values that are not views are returned as they are. Use this right after fetching a model, and only use the returned model: a view does
 * not compare as equal to a wrapper of the same view.
 */
// TODO(b/371925845): Only synchronize for affected Tooling API versions (or revert once fixed in Gradle Tooling API).
internal fun <T> T.synchronizedViews(): T {
  @Suppress("UNCHECKED_CAST")
  return wrap(this) as T
}

/** Exposes the underlying Tooling API view on a synchronized wrapper proxy. */
internal interface SynchronizedView {
  fun originalView(): Any
}

@Suppress("UNCHECKED_CAST")
private fun wrap(value: Any?): Any? {
  if (value != null && Proxy.isProxyClass(value.javaClass)) return wrapView(value)
  // Collections are copied into the same kinds of collections that the Tooling API creates for model properties.
  return when (value) {
    // DomainObjectSet extends Set, so match it before Set; it has no public implementation to copy to and is not used by wrapped models.
    null,
    is DomainObjectSet<*> -> value
    is SortedMap<*, *> ->
      value.entries.associateTo(TreeMap<Any?, Any?>(value.comparator() as Comparator<Any?>?)) { wrap(it.key) to wrap(it.value) }
    is Map<*, *> -> value.entries.associateTo(LinkedHashMap<Any?, Any?>()) { wrap(it.key) to wrap(it.value) }
    is SortedSet<*> -> value.mapTo(TreeSet<Any?>(value.comparator() as Comparator<Any?>?)) { wrap(it) }
    is Set<*> -> value.mapTo(LinkedHashSet<Any?>()) { wrap(it) }
    is Collection<*> -> value.map { wrap(it) }
    else -> value
  }
}

private fun wrapView(view: Any): Any {
  if (view is SynchronizedView) return view
  val viewHandler = Proxy.getInvocationHandler(view)
  val interfaces = view.javaClass.interfaces + SynchronizedView::class.java
  return Proxy.newProxyInstance(proxyClassLoader(view.javaClass.classLoader), interfaces, SynchronizedViewHandler(view, viewHandler))
}

private fun proxyClassLoader(viewClassLoader: ClassLoader?): ClassLoader? {
  val ownClassLoader = SynchronizedView::class.java.classLoader
  if (viewClassLoader === ownClassLoader) return ownClassLoader
  val viewLoaderSeesSynchronizedView = runCatching {
    Class.forName(SynchronizedView::class.java.name, false, viewClassLoader) === SynchronizedView::class.java
  }
    .getOrDefault(false)
  return if (viewLoaderSeesSynchronizedView) viewClassLoader else ownClassLoader
}

/** Serializable, like the views it wraps. */
private class SynchronizedViewHandler(private val originalView: Any, private val viewHandler: InvocationHandler) :
  InvocationHandler, Serializable {
  override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
    if (method.declaringClass == SynchronizedView::class.java) return originalView
    val viewArgs = args?.map { if (it is SynchronizedView) it.originalView() else it }?.toTypedArray()
    // Calls the view's handler directly, as the view itself would.
    val result = synchronized(originalView) { viewHandler.invoke(originalView, method, viewArgs) }
    // Wrap any returned view or collection of views after releasing `originalView`'s monitor, so that a thread never holds two of these
    // monitors at the same time.
    return wrap(result)
  }
}
