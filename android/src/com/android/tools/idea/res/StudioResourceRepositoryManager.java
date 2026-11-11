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
package com.android.tools.idea.res;

import com.android.ProgressManagerAdapter;
import com.android.annotations.concurrency.GuardedBy;
import com.android.annotations.concurrency.Slow;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.resources.Locale;
import com.android.ide.common.resources.ResourceRepository;
import com.android.ide.common.resources.ResourceRepositoryUtil;
import com.android.ide.common.resources.configuration.LocaleQualifier;
import com.android.projectmodel.ExternalAndroidLibrary;
import com.android.resources.aar.AarResourceRepository;
import com.android.tools.concurrency.AndroidIoManager;
import com.android.tools.idea.AndroidProjectModelUtils;
import com.android.tools.idea.configurations.ConfigurationManager;
import com.android.tools.res.CacheableResourceRepository;
import com.android.tools.res.FrameworkOverlay;
import com.android.tools.res.LocalResourceRepository;
import com.android.tools.res.LocalResourceRepository.EmptyRepository;
import com.android.tools.res.ResourceRepositoryManager;
import com.android.tools.sdk.AndroidPlatform;
import com.android.tools.sdk.AndroidTargetData;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.sdk.AndroidPlatforms;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.annotations.VisibleForTesting;

public final class StudioResourceRepositoryManager implements Disposable, ResourceRepositoryManager {
  private static final Key<StudioResourceRepositoryManager> KEY = Key.create(StudioResourceRepositoryManager.class.getName());

  private static final Object MODULE_RESOURCES_LOCK = new Object();
  private static final Object TEST_RESOURCES_LOCK = new Object();

  @NotNull private final AndroidFacet myFacet;

  /**
   * Incremented every time cached repositories or the library resource map are invalidated. Values built outside
   * their lock are only cached if this generation did not change while they were being built.
   */
  private final AtomicLong myGeneration = new AtomicLong();

  private final ResettableValue<AppResourceRepository> myAppResources = new ResettableValue<>();
  private final ResettableValue<ProjectResourceRepository> myProjectResources = new ResettableValue<>();
  private final ResettableValue<TestAppResourceRepository> myTestAppResources = new ResettableValue<>();
  private final ResettableValue<SampleDataResourceRepository> mySampleDataResources = new ResettableValue<>();

  /** Libraries and their corresponding resource repositories. */
  private final ResettableValue<Map<ExternalAndroidLibrary, AarResourceRepository>> myLibraryResourceMap = new ResettableValue<>();

  @GuardedBy("MODULE_RESOURCES_LOCK")
  private LocalResourceRepository<VirtualFile> myModuleResources;

  @GuardedBy("TEST_RESOURCES_LOCK")
  private LocalResourceRepository<VirtualFile> myTestModuleResources;

  private volatile CachedValue<LocalesAndLanguages> myLocalesAndLanguages;

  /** Called at the start of every library resource map computation. Only set in tests. */
  @Nullable
  private volatile Runnable myLibraryResourceMapComputationListenerForTests;


  @Nullable
  public static StudioResourceRepositoryManager getInstanceIfCreated(@NotNull AndroidFacet facet) {
    return facet.getUserData(KEY);
  }

  @NotNull
  public static StudioResourceRepositoryManager getInstance(@NotNull AndroidFacet facet) {
    StudioResourceRepositoryManager instance = facet.getUserData(KEY);

    if (instance == null) {
      StudioResourceRepositoryManager manager = new StudioResourceRepositoryManager(facet, facet);
      instance = facet.putUserDataIfAbsent(KEY, manager);
      if (instance == manager) {
        // Our object ended up stored in the facet.
        AndroidProjectRootListener.ensureSubscribed(manager.getProject());
      } else {
        Disposer.dispose(manager);
      }
    }

    return instance;
  }

  @Nullable
  public static StudioResourceRepositoryManager getInstance(@NotNull Module module) {
    AndroidFacet facet = AndroidFacet.getInstance(module);
    return facet == null ? null : getInstance(facet);
  }

  @Nullable
  public static StudioResourceRepositoryManager getInstance(@NotNull PsiElement element) {
    Module module = ModuleUtilCore.findModuleForPsiElement(element);
    if (module == null) {
      return null;
    }

    AndroidFacet facet = AndroidFacet.getInstance(module);
    if (facet == null) {
      return null;
    }

    return getInstance(facet);
  }

  /**
   * Computes and returns the app resources.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time, or block waiting for a read
   * action lock.
   *
   * @return the resource repository or null if the module is not an Android module
   * @see #getAppResources()
   */
  @Slow
  @Nullable
  public static LocalResourceRepository<VirtualFile> getAppResources(@NotNull Module module) {
    AndroidFacet facet = AndroidFacet.getInstance(module);
    return facet != null ? getAppResources(facet) : null;
  }

  /**
   * Computes and returns the app resources.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time, or block waiting for a read
   * action lock.
   *
   * @see #getAppResources()
   */
  @Slow
  @NotNull
  public static LocalResourceRepository<VirtualFile> getAppResources(@NotNull AndroidFacet facet) {
    return getInstance(facet).getAppResources();
  }

  /**
   * Computes and returns the project resources.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time, or block waiting for a read
   * action lock.
   *
   * @return the resource repository or null if the module is not an Android module
   * @see #getProjectResources()
   */
  @Slow
  @Nullable
  public static LocalResourceRepository<VirtualFile> getProjectResources(@NotNull Module module) {
    AndroidFacet facet = AndroidFacet.getInstance(module);
    return facet != null ? getProjectResources(facet) : null;
  }

  /**
   * Computes and returns the project resources.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time, or block waiting for a read
   * action lock.
   *
   * @see #getProjectResources()
   */
  @Slow
  @NotNull
  public static LocalResourceRepository<VirtualFile> getProjectResources(@NotNull AndroidFacet facet) {
    return getInstance(facet).getProjectResources();
  }

  /**
   * Computes and returns the module resources.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time, or block waiting for a read
   * action lock.
   *
   * @return the resource repository or null if the module is not an Android module
   * @see #getModuleResources()
   */
  @Slow
  @Nullable
  public static LocalResourceRepository<VirtualFile> getModuleResources(@NotNull Module module) {
    AndroidFacet facet = AndroidFacet.getInstance(module);
    return facet != null ? getModuleResources(facet) : null;
  }

  /**
   * Computes and returns the module resources.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time, or block waiting for a read
   * action lock.
   *
   * @see #getModuleResources()
   */
  @Slow
  @NotNull
  public static LocalResourceRepository<VirtualFile> getModuleResources(@NotNull AndroidFacet facet) {
    return getInstance(facet).getModuleResources();
  }

  private StudioResourceRepositoryManager(@NotNull AndroidFacet facet,
                                          @NotNull Disposable parentDisposable) {
    Disposer.register(parentDisposable, this);
    myFacet = facet;
    myLocalesAndLanguages = CachedValuesManager.getManager(getProject()).createCachedValue(this::newLocalesAndLanguagesCachedValue);
  }

  /**
   * Returns the repository for the given namespace.
   *
   * @return the computed repository, or null if the namespace is {@link ResourceNamespace#ANDROID}
   *     and the framework resources cannot be determined for the module
   * @see #getAppResources()
   * @see #getFrameworkResources(Set)
   */
  @Slow
  @Nullable
  public ResourceRepository getResourcesForNamespace(@NotNull ResourceNamespace namespace) {
    return namespace.equals(ResourceNamespace.ANDROID) ? getFrameworkResources(Collections.emptySet()) : getAppResources();
  }

  /**
   * Returns the repository with all non-framework resources available to a given module (in the current variant).
   * This includes not just the resources defined in this module, but in any other modules that this module depends
   * on, as well as any libraries those modules may depend on (e.g. appcompat). This repository also contains sample
   * data resources associated with the {@link ResourceNamespace#TOOLS} namespace.
   *
   * <p>When a layout is rendered in the layout editor, it is getting resources from the app resource repository:
   * it should see all the resources just like the app does.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time.
   *
   * @return the computed repository
   * @see #getCachedAppResources()
   */
  @Override
  @Slow
  @NotNull
  public LocalResourceRepository<VirtualFile> getAppResources() {
    return getOrCreateRepository(myAppResources, () -> {
      getLibraryResources(); // Precompute library resources to do less work inside the read action below.
      return AppResourceRepository.create(myFacet, this);
    });
  }

  /**
   * Returns the previously computed repository with all non-framework resources available to a given module
   * (in the current variant). This includes not just the resources defined in this module, but in any other
   * modules that this module depends on, as well as any AARs those modules depend on (e.g. appcompat). This
   * repository also contains sample data resources associated with the {@link ResourceNamespace#TOOLS} namespace.
   *
   * @return the repository, or null if the repository hasn't been created yet
   * @see #getAppResources()
   */
  @Nullable
  public LocalResourceRepository<VirtualFile> getCachedAppResources() {
    return myAppResources.getCached();
  }

  /**
   * Returns the resource repository for a module along with all its (local) module dependencies.
   * The repository doesn't contain resources from AAR dependencies.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time.
   *
   * @return the computed repository
   * @see #getCachedProjectResources()
   */
  @Override
  @Slow
  @NotNull
  public LocalResourceRepository<VirtualFile> getProjectResources() {
    return getOrCreateRepository(myProjectResources, () -> ProjectResourceRepository.create(myFacet, this));
  }

  /**
   * Returns the previously computed resource repository for a module along with all its (local) module dependencies.
   * The repository doesn't contain resources from AAR dependencies.
   *
   * @return the repository, or null if the repository hasn't been created yet
   * @see #getProjectResources()
   */
  @Nullable
  public LocalResourceRepository<VirtualFile> getCachedProjectResources() {
    return myProjectResources.getCached();
  }

  /**
   * Returns the resource repository for a single module (which can possibly have multiple resource folders).
   * Does not include resources from any dependencies.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time,
   * or block waiting for a read action lock.
   *
   * @return the computed repository
   * @see #getCachedModuleResources()
   */
  @Slow
  @NotNull
  @Override
  public LocalResourceRepository<VirtualFile> getModuleResources() {
    LocalResourceRepository<VirtualFile> moduleResources = getCachedModuleResources();
    if (moduleResources != null) {
      return moduleResources;
    }

    synchronized (MODULE_RESOURCES_LOCK) {
      if (myModuleResources == null) {
        if (myFacet.isDisposed()) {
          return new EmptyRepository<>();
        }
        myModuleResources = ModuleResourceRepository.forMainResources(myFacet, this);
      }
      moduleResources = myModuleResources;
    }

    // We need to load the repositories outside the MODULE_RESOURCES_LOCK lock to prevent deadlocks due to the following scenario:
    // Thread 1:
    //   1. writeAction
    //   2. getModuleResources
    //   3. acquire MODULE_RESOURCES_LOCK lock <- waiting for lock
    // Thread 2:
    //   1. getModuleResources
    //   2. acquire MODULE_RESOURCES_LOCK lock
    //   3. ensureLoaded
    //   4. readAction <- waiting for read lock
    if (moduleResources instanceof ModuleResourceRepository) {
      ((ModuleResourceRepository)moduleResources).ensureLoaded();
    }

    return moduleResources;
  }

  /**
   * Returns the previously computed resource repository for a single module (which can possibly have multiple
   * resource folders). Does not include resources from any dependencies.
   *
   * @return the repository, or null if the repository hasn't been created yet
   * @see #getModuleResources()
   */
  @Nullable
  public LocalResourceRepository<VirtualFile> getCachedModuleResources() {
    LocalResourceRepository<VirtualFile> moduleResources;
    synchronized (MODULE_RESOURCES_LOCK) {
      moduleResources = myModuleResources;
    }

    // The loading is done outside the MODULE_RESOURCES_LOCK to avoid deadlocks. If the repository hasn't been
    // loaded yet, we consider it's not yet ready and return null to ensure this method remains fast.
    if (moduleResources instanceof ModuleResourceRepository && !((ModuleResourceRepository)moduleResources).isLoaded()) {
      Logger.getInstance(StudioResourceRepositoryManager.class).warn("ModuleResourceRepository has not finished loading yet. Returning null.");
      return null;
    }
    return moduleResources;
  }

  /**
   * Returns the resource repository with all non-framework test resources available to a given module.
   */
  @NotNull
  public LocalResourceRepository<VirtualFile> getTestAppResources() {
    return getOrCreateRepository(myTestAppResources, () -> TestAppResourceRepository.create(myFacet, this));
  }

  @Nullable
  private LocalResourceRepository<VirtualFile> getCachedTestAppResources() {
    return myTestAppResources.getCached();
  }

  /**
   * Returns the resource repository with test resources defined in the given module.
   */
  @NotNull
  public LocalResourceRepository<VirtualFile> getTestModuleResources() {
    LocalResourceRepository<VirtualFile> testModuleResources;
    synchronized (TEST_RESOURCES_LOCK) {
      if (myTestModuleResources == null) {
        if (myFacet.isDisposed()) {
          return new EmptyRepository<>();
        }
        myTestModuleResources = ModuleResourceRepository.forTestResources(myFacet, this);
      }
      testModuleResources = myTestModuleResources;
    }

    // We need to load the repositories outside the lock to prevent deadlocks
    if (testModuleResources instanceof ModuleResourceRepository) {
      ((ModuleResourceRepository)testModuleResources).ensureLoaded();
    }
    return testModuleResources;
  }

  @Slow
  @NotNull
  public LocalResourceRepository<VirtualFile> getSampleDataResources() {
    return getOrCreateRepository(mySampleDataResources, () -> new SampleDataResourceRepository(myFacet, this));
  }

  @Nullable
  public LocalResourceRepository<VirtualFile> getCachedSampleDataResources() {
    return mySampleDataResources.getCached();
  }

  public void reloadSampleResources() {
    SampleDataResourceRepository sampleResources = mySampleDataResources.getCached();
    if (sampleResources != null) {
      sampleResources.reload();
    }
  }

  /**
   * Returns the resource repository with Android framework resources, for the module's compile SDK.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time.
   *
   * @param languages the set of ISO 639 language codes determining the subset of resources to load.
   *     May be empty to load only the language-neutral resources. The returned repository may contain resources
   *     for more languages than was requested.
   * @param overlays a list of overlays to add to the base framework resources
   * @return the framework repository, or null if the SDK resources directory cannot be determined for the module
   */
  @Slow
  @Nullable
  @Override
  public ResourceRepository getFrameworkResources(@NotNull Set<String> languages, @NotNull List<? extends FrameworkOverlay> overlays) {
    AndroidPlatform androidPlatform = AndroidPlatforms.getInstance(myFacet.getModule());
    if (androidPlatform == null) {
      return null;
    }

    return AndroidTargetData.get(androidPlatform.getSdkData(), androidPlatform.getTarget()).getFrameworkResources(languages, overlays);
  }

  public void resetResources() {
    List<LocalResourceRepository<VirtualFile>> removedRepositories = new ArrayList<>(6);

    // Reset leaves before parents and bump myGeneration inside each reset so a parent repository cannot snapshot
    // a post-reset generation and still read a pre-reset child.
    myLibraryResourceMap.reset();

    synchronized (MODULE_RESOURCES_LOCK) {
      if (myModuleResources != null) {
        removedRepositories.add(myModuleResources);
        myModuleResources = null;
      }
      myGeneration.incrementAndGet();
    }

    if (addIfNotNull(removedRepositories, myProjectResources.reset())) {
      myLocalesAndLanguages = CachedValuesManager.getManager(getProject()).createCachedValue(this::newLocalesAndLanguagesCachedValue);
    }
    addIfNotNull(removedRepositories, mySampleDataResources.reset());
    addIfNotNull(removedRepositories, myAppResources.reset());

    synchronized (TEST_RESOURCES_LOCK) {
      if (myTestModuleResources != null) {
        removedRepositories.add(myTestModuleResources);
        myTestModuleResources = null;
      }
      myGeneration.incrementAndGet();
    }
    addIfNotNull(removedRepositories, myTestAppResources.reset());

    // Reset is done separately from disposal, since reset isn't needed in all disposal scenarios. Specifically,
    // there are 2 ways these repositories can be disposed:
    //  1. This reset method.
    //  2. When the owning facet is disposed.
    // In the second case, a "roots updated" notification will be sent to any dependent modules, which will cause
    // them to recalculate their children and remove any outdated references. So only the first case (this reset
    // method) requires explicitly notifying those same parent repositories that their children are out of date and
    // need to be refreshed.
    DisposeAndRefreshService disposeAndRefreshService = DisposeAndRefreshService.getInstance();
    for (LocalResourceRepository<VirtualFile> repository : removedRepositories) {
      disposeAndRefreshService.disposeAndNotifyParents(repository);
    }
  }

  private static boolean addIfNotNull(@NotNull List<LocalResourceRepository<VirtualFile>> list,
                                      @Nullable LocalResourceRepository<VirtualFile> repository) {
    if (repository != null) {
      list.add(repository);
      return true;
    }
    return false;
  }

  @Override
  public void dispose() {
    // Remove resource folders from the registry when the owning facet is disposed.
    Project project = getProject();
    if (!project.isDisposed()) {
      ResourceFolderRegistry.getInstance(getProject()).reset(myFacet);
    }
  }

  public void resetAllCaches() {
    // Remove resource folders from the registry before clearing the local resource. This prevents
    // race conditions where one of the resource sets in this class is requested again before the
    // registry is cleared, ending up with a stale ResourceFolderRepository.
    ResourceFolderRegistry.getInstance(getProject()).reset(myFacet);
    // Same for the AAR repositories. The library resource map is computed outside its lock, so clearing the cache
    // after resetResources() would let a computation that starts in between cache a map built from stale repositories.
    AarResourceRepositoryCache.getInstance().clear();

    resetResources();
    ConfigurationManager.getOrCreateInstance(myFacet.getModule()).getResolverCache().reset();
  }

  @NotNull
  private Project getProject() {
    return myFacet.getModule().getProject();
  }

  void updateRootsAndLibraries() {
    if (myFacet.isDisposed()) {
      return;
    }
    try {
      // Preserve the old library resources during update to prevent them from being garbage collected prematurely.
      // Resetting first bumps myGeneration before sampling cached repositories below so any build started before this
      // update either published already (and is refreshed below) or sees the generation change and rebuilds.
      Map<ExternalAndroidLibrary, AarResourceRepository> oldLibraryResourceMap = myLibraryResourceMap.reset();

      ProjectResourceRepository projectResources = myProjectResources.getCached();
      if (projectResources != null) {
        projectResources.refreshChildren();
        // Bump the generation after refreshing projectResources so an in-flight AppResourceRepository build that read
        // projectResources before refreshChildren() finished cannot publish stale leaf repositories afterwards.
        myGeneration.incrementAndGet();
      }

      AppResourceRepository appResources = myAppResources.getCached();
      if (appResources != null) {
        appResources.refreshChildren();
      }

      // Access oldLibraryResourceMap to make sure that it is still in scope at this point.
      // This condition should never be true, but exists to prevent the compiler from optimizing
      // away oldLibraryResourceMap.
      if (oldLibraryResourceMap != null && oldLibraryResourceMap.size() == Integer.MAX_VALUE) {
        throw new AssertionError();
      }

      TestAppResourceRepository testAppResources = myTestAppResources.getCached();
      if (testAppResources != null) {
        testAppResources.refreshChildren();
      }
    }
    catch (IllegalStateException e) {
      if (!myFacet.isDisposed()) {
        throw e;
      }
      // Ignore exceptions caused by the facet disposal (b/162211246).
    }
  }

  /**
   * Returns all resource directories.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time,
   * or block waiting for a read action lock.
   */
  @Slow
  @NotNull
  public Collection<VirtualFile> getAllResourceDirs() {
    // TODO(b/76128326): manage the set of directories here.
    return ((AppResourceRepository)getAppResources()).getAllResourceDirs();
  }

  /**
   * Looks up a resource repository for the given library.
   *
   * @param library the library to get resources for
   * @return the corresponding resource repository, or null if not found
   */
  @Nullable
  public AarResourceRepository findLibraryResources(@NotNull ExternalAndroidLibrary library) {
    return getLibraryResourceMap().get(library);
  }

  /**
   * Returns resource repositories for all libraries the app depends upon directly or indirectly.
   *
   * <p><b>Note:</b> This method should not be called on the event dispatch thread since it may take long time,
   * or block waiting for a read action lock.
   */
  @Slow
  @NotNull
  public Collection<AarResourceRepository> getLibraryResources() {
    return getLibraryResourceMap().values();
  }

  @NotNull
  private Map<ExternalAndroidLibrary, AarResourceRepository> getLibraryResourceMap() {
    // Compute the map outside myLibraryResourceMap's lock. Loading the AAR repositories can take a long time, and holding
    // the lock while doing it blocks resetResources, which is called on the EDT after the first successful sync (b/503267386).
    // Concurrent callers may compute the map in parallel, but AarResourceRepositoryCache shares the underlying repositories.
    return myLibraryResourceMap.getOrCompute(this::computeLibraryResourceMap);
  }

  @NotNull
  private Map<ExternalAndroidLibrary, AarResourceRepository> computeLibraryResourceMap() {
    Collection<ExternalAndroidLibrary> libraries = AndroidProjectModelUtils.findDependenciesWithResources(myFacet.getModule()).values();

    AarResourceRepositoryCache aarResourceRepositoryCache = AarResourceRepositoryCache.getInstance();
    Function<ExternalAndroidLibrary, AarResourceRepository> factory = aarResourceRepositoryCache::getSourceRepository;

    ExecutorService executor = AndroidIoManager.getInstance().getBackgroundDiskIoExecutor();

    // Construct the repositories in parallel.
    Map<ExternalAndroidLibrary, Future<AarResourceRepository>> futures = Maps.newHashMapWithExpectedSize(libraries.size());
    for (ExternalAndroidLibrary library : libraries) {
      futures.put(library, executor.submit(() -> factory.apply(library)));
    }

    // Gather all the results.
    ImmutableMap.Builder<ExternalAndroidLibrary, AarResourceRepository> map = ImmutableMap.builder();
    try {
      for (Map.Entry<ExternalAndroidLibrary, Future<AarResourceRepository>> entry : futures.entrySet()) {
        map.put(entry.getKey(), ProgressIndicatorUtils.awaitWithCheckCanceled(entry.getValue()));
      }
    }
    catch (Throwable t) {
      cancelPendingTasks(futures.values());
      throw t;
    }

    Runnable listener = myLibraryResourceMapComputationListenerForTests;
    if (listener != null) {
      listener.run();
    }

    return map.build();
  }

  private static void cancelPendingTasks(Collection<Future<AarResourceRepository>> futures) {
    // Do not interrupt running tasks. The AAR repositories are shared through AarResourceRepositoryCache, and interrupting
    // a load makes NIO throw ClosedByInterruptException, which could leave a partially loaded repository in the cache for
    // other callers. Tasks that have not started are dropped; at most one load per disk-I/O pool thread is already running,
    // and queued tasks read the current cache instance when they start rather than when submitted.
    futures.forEach(f -> f.cancel(false));
  }

  @TestOnly
  void setLibraryResourceMapComputationListenerForTests(@Nullable Runnable listener) {
    myLibraryResourceMapComputationListenerForTests = listener;
  }

  /**
   * Returns all locales of the project resources.
   */
  @Override
  @NotNull
  public ImmutableList<Locale> getLocalesInProject() {
    return getLocalesAndLanguages().locales;
  }

  /**
   * Returns a set of ISO 639 language codes derived from locales of the project resources.
   */
  @Override
  @NotNull
  public ImmutableSet<String> getLanguagesInProject() { return getLocalesAndLanguages().languages; }

  /**
   * Returns a new {@link com.intellij.psi.util.CachedValueProvider.Result} for obtaining {@link LocalesAndLanguages}.
   * Calling this method does not retrieve the actual value. It will not block and should be fairly quick.
   */
  @NotNull
  private CachedValueProvider.Result<LocalesAndLanguages> newLocalesAndLanguagesCachedValue() {
    // Get locales from modules, but not libraries.
    CacheableResourceRepository projectResources = getProjectResources(myFacet);
    ModificationTracker tracker = projectResources::getModificationCount;
    SortedSet<LocaleQualifier> localeQualifiers = ResourceRepositoryUtil.getLocales(projectResources);
    ImmutableList.Builder<Locale> localesBuilder = ImmutableList.builderWithExpectedSize(localeQualifiers.size());
    ImmutableSortedSet.Builder<String> languagesBuilder = ImmutableSortedSet.naturalOrder();
    for (LocaleQualifier localeQualifier : localeQualifiers) {
      localesBuilder.add(Locale.create(localeQualifier));
      String language = localeQualifier.getLanguage();
      if (language != null) {
        languagesBuilder.add(language);
      }
    }
    return CachedValueProvider.Result.create(new LocalesAndLanguages(localesBuilder.build(), languagesBuilder.build()), tracker);
  }

  @NotNull
  private LocalesAndLanguages getLocalesAndLanguages() {
    return myLocalesAndLanguages.getValue();
  }

  private record LocalesAndLanguages(@NotNull ImmutableList<Locale> locales, @NotNull ImmutableSortedSet<String> languages) {
  }

  @NotNull
  private <T extends LocalResourceRepository<VirtualFile> & Disposable>
  LocalResourceRepository<VirtualFile> getOrCreateRepository(@NotNull ResettableValue<T> holder,
                                                             @NotNull Supplier<@NotNull T> create) {
    while (true) {
      long generation;
      synchronized (holder.lock) {
        if (holder.value != null) {
          return holder.value;
        }
        if (myFacet.isDisposed()) {
          return new EmptyRepository<>();
        }
        generation = myGeneration.get();
      }

      T created = create.get();

      LocalResourceRepository<VirtualFile> published = null;
      boolean facetDisposed;
      synchronized (holder.lock) {
        facetDisposed = myFacet.isDisposed();
        if (!facetDisposed) {
          if (holder.value == null && myGeneration.get() == generation) {
            holder.value = created;
          }
          published = holder.value;
        }
      }

      if (published == created) {
        return created;
      }
      // Either the facet was disposed, another caller published first, or a reset invalidated this build.
      // Always dispose the unused repository because its parent disposable is this manager.
      Disposer.dispose(created);
      if (facetDisposed) {
        return new EmptyRepository<>();
      }
      if (published != null) {
        return published;
      }
      ProgressManagerAdapter.checkCanceled();
    }
  }

  /**
   * Lazily cached value computed outside its lock and guarded against concurrent resets via {@link #myGeneration}.
   * <p>
   * Computing resource repositories and the library resource map can take a long time (disk I/O, AAR loading, and
   * loading dependent modules), so holding the field lock across computation would block {@link #resetResources()}
   * and {@link #updateRootsAndLibraries()} on the EDT. Computing outside the lock without a generation check would
   * allow a slow computation started before a reset to publish stale data (or embed pre-reset child repositories)
   * after the reset completes. Pairing a short lock critical section with the manager-wide {@link #myGeneration}
   * counter keeps resets non-blocking while rejecting stale publications across dependent fields.
   */
  private final class ResettableValue<T> {
    private final Object lock = new Object();
    @GuardedBy("lock")
    @Nullable
    private T value;

    @Nullable
    T getCached() {
      synchronized (lock) {
        return value;
      }
    }

    @NotNull
    T getOrCompute(@NotNull Supplier<@NotNull T> compute) {
      long generation;
      synchronized (lock) {
        if (value != null) {
          return value;
        }
        generation = myGeneration.get();
      }

      T computed = compute.get();

      synchronized (lock) {
        if (value != null) {
          // Another caller published its value first. Return that one so all callers see the same instance.
          return value;
        }
        if (myGeneration.get() == generation) {
          value = computed;
        }
        // If invalidated while computing, return to this caller without caching so the next call recomputes it.
        return computed;
      }
    }

    @Nullable
    T reset() {
      synchronized (lock) {
        T old = value;
        value = null;
        myGeneration.incrementAndGet();
        return old;
      }
    }
  }

  /**
   * Service responsible for disposing repositories that have been reset and notifying their parents, so that the
   * parents can refresh themselves.
   * <p>
   * Disposal and notification is done on a single background thread to ensure that various repositories aren't
   * resetting concurrently, which may lead to deadlock. The service is APP-level rather than SERVICE-level since many
   * of the locks involved are static objects, and thus application-wide.
   */
  @Service
  @VisibleForTesting
  final static class DisposeAndRefreshService implements Disposable {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void dispose() {
      executor.shutdown();
    }

    public void disposeAndNotifyParents(LocalResourceRepository<VirtualFile> repository) {
      if (repository instanceof Disposable disposable) {
        // Take over ownership of the disposable.
        Disposer.register(this, disposable);
      }

      // Store the repository and schedule cleanup.
      executor.submit(() -> doDisposeAndNotify(repository));
    }

    private void doDisposeAndNotify(LocalResourceRepository<VirtualFile> repository) {
      if (repository instanceof Disposable disposable) {
        Disposer.dispose(disposable);
      }

      repository.notifyParentsOfReset();
    }

    @TestOnly
    public boolean waitForRunningTasks(long timeout, TimeUnit unit) throws InterruptedException {
      Semaphore semaphore = new Semaphore(0);
      executor.submit((Runnable)semaphore::release);

      return semaphore.tryAcquire(timeout, unit);
    }

    public static DisposeAndRefreshService getInstance() {
      return ApplicationManager.getApplication().getService(DisposeAndRefreshService.class);
    }
  }
}
