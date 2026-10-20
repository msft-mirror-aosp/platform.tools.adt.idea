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
package com.android.tools.rendering.classloading

import java.io.InputStream
import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * Class loader that prevents the loading of any resources.
 *
 * This is meant to workaround b/151089727 and avoid the `ModuleClassLoader` accessing resources from the plugin class loader.
 */
class FirewalledResourcesClassLoader(parent: ClassLoader?) : ClassLoader(parent) {
  override fun loadClass(name: String, resolve: Boolean): Class<*> {
    // `FirewalledResourcesClassLoader` never defines classes of its own; its only role is blocking
    // non-`.class` resource lookups via `getResource*` / `findResource*`.
    // In `java.lang.ClassLoader.loadClass`, when `parent.loadClass(name, false)` throws
    // `ClassNotFoundException` (which happens for every project and external-library class not in
    // the parent `FilteringClassLoader`), `super.loadClass` catches that exception and calls
    // `this.findClass(name)`, which allocates and throws a second `ClassNotFoundException` (walking
    // the ~80-frame JVM call stack a second time per class load). Delegating directly to
    // `parent.loadClass(name)` lets the parent's single `ClassNotFoundException` propagate directly
    // to the child class loader without redundant locking, `findLoadedClass` checks, or stack walks.
    if (parent != null) {
      val c = parent.loadClass(name)
      if (resolve) {
        resolveClass(c)
      }
      return c
    }
    return super.loadClass(name, resolve)
  }

  override fun findResource(name: String): URL? = null

  override fun findResources(name: String): Enumeration<URL> = Collections.emptyEnumeration()

  override fun findResource(moduleName: String, name: String): URL? = null

  override fun getResource(name: String): URL? = if (name.endsWith(".class")) super.getResource(name) else null

  override fun getResources(name: String): Enumeration<URL> =
    if (name.endsWith(".class")) super.getResources(name) else Collections.emptyEnumeration()

  override fun getResourceAsStream(name: String): InputStream? = if (name.endsWith(".class")) super.getResourceAsStream(name) else null
}
