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
package org.jetbrains.android.uipreview.classloading

import com.android.ide.common.resources.AndroidManifestPackageNameUtils
import com.android.projectmodel.ExternalAndroidLibrary
import com.android.tools.idea.projectsystem.DependencyScopeType
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.res.ResourceClassRegistry
import com.android.tools.idea.res.StudioResourceIdManager
import com.android.tools.idea.res.StudioResourceRepositoryManager
import com.android.tools.rendering.classloading.loaders.DelegatingClassLoader
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.regex.Pattern
import org.jetbrains.android.facet.AndroidFacet

/** Finds the `packageName` for a given external library. */
private fun ExternalAndroidLibrary.getResolvedPackageName(): String? {
  if (packageName != null) {
    return packageName
  }
  return manifestFile?.let {
    return try {
      AndroidManifestPackageNameUtils.getPackageNameFromManifestFile(it)
    } catch (ignore: IOException) {
      null
    }
  }
}

/**
 * Registers all the [Module] resources, including libraries and dependencies, with the [ResourceClassRegistry] and returns the registered
 * package names.
 */
private fun registerResources(module: Module): Set<String> {
  val androidFacet: AndroidFacet = AndroidFacet.getInstance(module) ?: return emptySet()
  val idManager = StudioResourceIdManager.get(module)

  // If final ids are used, we will read the real class from disk later (in loadAndParseRClass), using this class loader. So we
  // can't treat it specially here, or we will read the wrong bytecode later.
  val modulePackageNames =
    if (idManager.finalIdsUsed) emptyList()
    else runReadAction { androidFacet.getModuleSystem().moduleDependencies.getResourcePackageNames(false) }
  val libraryPackageNames =
    module
      .getModuleSystem()
      .getAndroidLibraryDependencies(DependencyScopeType.MAIN)
      .filter { it.hasResources }
      .mapNotNull { it.getResolvedPackageName() }
  val packageNames = (modulePackageNames + libraryPackageNames).filter { it.isNotEmpty() }.toSet()

  // Only create the app resources repository if there is something to register.
  if (packageNames.isNotEmpty()) {
    val appResources = StudioResourceRepositoryManager.getInstance(androidFacet).appResources
    ResourceClassRegistry.get(module.project).addLibraries(appResources, idManager, packageNames)
  }
  return packageNames
}

// matches foo.bar.R or foo.bar.R$baz
private val RESOURCE_CLASS_NAME = Pattern.compile(".+\\.R(\\$[^.]+)?$")

private fun isResourceClassName(className: String): Boolean = RESOURCE_CLASS_NAME.matcher(className).matches()

/** [ClassLoader] responsible for loading the `R` class from libraries and dependencies of the given module. */
class LibraryResourceClassLoader(parent: ClassLoader?, module: Module?, private val childLoader: DelegatingClassLoader.Loader) :
  ClassLoader(parent) {
  private val moduleRef = WeakReference(module)

  /**
   * Packages registered in the [ResourceClassRegistry] when this class loader was created. The registry is project wide and can be cleared
   * at any time, even while this class loader is in use (b/584171735). For example, a build running in the background creates generated
   * source folders, and the resulting project roots change refreshes the app resources of every module, which clears the registry. Missing
   * R classes of these packages are then registered again.
   */
  private val registeredPackages: Set<String> = getModule()?.let { registerResources(it) } ?: emptySet()

  /** Returns the [Module] to be used for the library class loading if it still exists and is not disposed. */
  private fun getModule(): Module? {
    val module = moduleRef.get() ?: return null
    if (module.isDisposed) return null
    return module
  }

  private fun findResourceClass(name: String): Class<*> {
    val module = getModule() ?: throw ClassNotFoundException(name)
    if (!isResourceClassName(name)) {
      throw ClassNotFoundException(name)
    }

    if (StudioResourceIdManager.get(module).finalIdsUsed) {
      // If final IDs are used, we check to see if the child loader will load the class.  If so, throw a ClassNotFoundException
      // here and let the R classes be loaded by the child class loader.
      //
      // If compiled classes are not available, there are two possible scenarios:
      //     1) We are looking for a resource class available in the ResourceClassRegistry
      //     2) We are looking for a resource class that's not available in the ResourceClassRegistry
      //
      // In the first scenario, we'll load the R class using this class loader. This covers the case where users are opening a project
      // before compiling and want to view an XML resource file, which should work.  In the second, the resource class is not available
      // anywhere, so the class loader will (correctly) fail.
      if (childLoader.loadClass(name) != null) {
        throw ClassNotFoundException(name)
      }
    }

    val facet: AndroidFacet = AndroidFacet.getInstance(module)?.takeUnless { it.isDisposed } ?: throw ClassNotFoundException(name)
    val repositoryManager = StudioResourceRepositoryManager.getInstance(facet)
    val data = findClassDefinition(module, name, repositoryManager) ?: throw ClassNotFoundException(name)
    Logger.getInstance(LibraryResourceClassLoader::class.java).debug("  Defining class from AAR registry")
    return defineClass(name, data, 0, data.size)
  }

  private fun findClassDefinition(module: Module, name: String, repositoryManager: StudioResourceRepositoryManager): ByteArray? {
    val classRegistry = ResourceClassRegistry.get(module.project)
    classRegistry.findClassDefinition(name, repositoryManager)?.let {
      return it
    }
    // Not an R class of the registered packages, so there is nothing to register again.
    if (name.substringBeforeLast('.', "") !in registeredPackages) return null
    // The registry was cleared since this class loader registered the packages: register them again. The class is looked up in the same
    // atomic step, so that the registry can not be cleared again in between.
    val idManager = StudioResourceIdManager.get(module)
    return classRegistry.addLibrariesAndFindClassDefinition(repositoryManager.appResources, idManager, registeredPackages, name)
  }

  override fun findClass(name: String): Class<*> =
    try {
      super.findClass(name)
    } catch (e: ClassNotFoundException) {
      findResourceClass(name)
    }
}
