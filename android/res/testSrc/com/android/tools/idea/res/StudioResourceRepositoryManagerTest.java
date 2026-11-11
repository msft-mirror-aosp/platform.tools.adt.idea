/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.tools.idea.res.ResourcesTestsUtil.addAarDependency;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.android.AndroidProjectTypes;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.resources.ResourceRepository;
import com.android.ide.common.resources.SingleNamespaceResourceRepository;
import com.android.resources.ResourceType;
import com.android.tools.idea.model.AndroidModel;
import com.android.tools.idea.projectsystem.ProjectSystemSyncManager;
import com.android.tools.idea.projectsystem.ProjectSystemSyncUtil;
import com.android.tools.res.LocalResourceRepository;
import com.android.tools.res.MultiResourceRepository;
import com.google.common.collect.Iterables;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import org.jetbrains.android.AndroidTestCase;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tests for {@link StudioResourceRepositoryManager}.
 */
public class StudioResourceRepositoryManagerTest extends AndroidTestCase {
  /**
   * Checks that adding an AAR dependency is reflected in the result returned by {@link StudioResourceRepositoryManager#getLibraryResources()}.
   */
  public void testLibraryResources() {
    StudioResourceRepositoryManager repositoryManager = StudioResourceRepositoryManager.getInstance(myFacet);
    assertThat(repositoryManager.getLibraryResources()).isEmpty();
    addAarDependency(myFixture, myModule, "mylibrary", "com.example.mylibrary", resDir -> Unit.INSTANCE);
    assertThat(repositoryManager.getLibraryResources()).hasSize(1);
    assertThat(Iterables.getOnlyElement(repositoryManager.getLibraryResources()).getNamespace()).isEqualTo(ResourceNamespace.RES_AUTO);
  }

  public void testDisposal() {
    myFacet.getProperties().ALLOW_USER_CONFIGURATION = false;
    assertThat(AndroidModel.isRequired(myFacet)).named("module uses a model").isTrue();

    StudioResourceRepositoryManager repositoryManager = StudioResourceRepositoryManager.getInstance(myFacet);
    repositoryManager.getAppResources();
    repositoryManager.resetResources(); // This can be triggered from the layout editor UI.
    for (SingleNamespaceResourceRepository repository : repositoryManager.getAppResources().getLeafResourceRepositories()) {
      if (repository instanceof Disposable) {
        assertWithMessage("%s has already been disposed but is returned from ResourceRepositoryManager", repository)
          .that(Disposer.isDisposed((Disposable)repository))
          .isFalse();
      }
    }
  }

  public void testDisposedFacetIgnoredByDynamicValueRepositoryAndUpdateRoots() {
    myFacet.getProperties().ALLOW_USER_CONFIGURATION = false;
    assertThat(AndroidModel.isRequired(myFacet)).named("module uses a model").isTrue();

    StudioResourceRepositoryManager repositoryManager = StudioResourceRepositoryManager.getInstance(myFacet);
    DynamicValueResourceRepository dynamicRepo =
      DynamicValueResourceRepository.create(myFacet, getTestRootDisposable());

    Disposer.dispose(myFacet);
    assertThat(myFacet.isDisposed()).isTrue();

    // Sync notifications, resource queries, and root updates after facet disposal should be no-ops.
    getProject()
      .getMessageBus()
      .syncPublisher(ProjectSystemSyncUtil.PROJECT_SYSTEM_SYNC_TOPIC)
      .syncEnded(ProjectSystemSyncManager.SyncResult.SUCCESS);
    assertThat(dynamicRepo.getResources(ResourceNamespace.RES_AUTO, ResourceType.STRING)).isEmpty();
    repositoryManager.updateRootsAndLibraries();
  }

  /**
   * Checks that {@link StudioResourceRepositoryManager#resetResources()}, which runs on the EDT after the first successful
   * sync, does not wait for an in-progress library resource map computation, and that the map computed before the reset
   * is not cached (b/503267386).
   */
  public void testResetResourcesDoesNotBlockOnLibraryResourceMapComputation() throws Exception {
    StudioResourceRepositoryManager repositoryManager = StudioResourceRepositoryManager.getInstance(myFacet);
    checkInvalidationDuringLibraryResourceMapComputation(repositoryManager, repositoryManager::resetResources);
  }

  /**
   * Checks that {@link StudioResourceRepositoryManager#updateRootsAndLibraries()} does not wait for an in-progress library
   * resource map computation, and that the map computed before the update is not cached.
   */
  public void testUpdateRootsAndLibrariesDoesNotBlockOnLibraryResourceMapComputation() throws Exception {
    StudioResourceRepositoryManager repositoryManager = StudioResourceRepositoryManager.getInstance(myFacet);
    checkInvalidationDuringLibraryResourceMapComputation(repositoryManager, repositoryManager::updateRootsAndLibraries);
  }

  /**
   * Runs {@code invalidate} while a library resource map computation is in progress on another thread, and checks that
   * {@code invalidate} does not wait for it and that the map being computed is not cached.
   */
  private static void checkInvalidationDuringLibraryResourceMapComputation(@NotNull StudioResourceRepositoryManager repositoryManager,
                                                                           @NotNull Runnable invalidate) throws Exception {
    AtomicInteger computationCount = new AtomicInteger();
    CountDownLatch computationStarted = new CountDownLatch(1);
    CountDownLatch releaseComputation = new CountDownLatch(1);
    repositoryManager.setLibraryResourceMapComputationListenerForTests(() -> {
      if (computationCount.incrementAndGet() == 1) {
        computationStarted.countDown();
        try {
          releaseComputation.await();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });

    Future<?> libraries = null;
    Future<?> invalidation = null;
    try {
      libraries = ApplicationManager.getApplication().executeOnPooledThread(repositoryManager::getLibraryResources);
      assertThat(computationStarted.await(10, TimeUnit.SECONDS)).isTrue();

      // Before the fix for b/503267386, this blocked until the library resource map computation above finished.
      invalidation = ApplicationManager.getApplication().executeOnPooledThread(invalidate);
      invalidation.get(10, TimeUnit.SECONDS);

      releaseComputation.countDown();
      libraries.get(10, TimeUnit.SECONDS);

      // The map computed before the invalidation is stale, so it must be recomputed once, and then cached.
      int computationsBefore = computationCount.get();
      repositoryManager.getLibraryResources();
      assertThat(computationCount.get()).isEqualTo(computationsBefore + 1);
      repositoryManager.getLibraryResources();
      assertThat(computationCount.get()).isEqualTo(computationsBefore + 1);
    }
    finally {
      releaseComputation.countDown();
      // Let the background work finish before the facet is disposed in tearDown. Failures are already reported above.
      awaitQuietly(invalidation);
      awaitQuietly(libraries);
      repositoryManager.setLibraryResourceMapComputationListenerForTests(null);
    }
  }

  public void testResetResourcesRebuildsInFlightAppResources() throws Exception {
    StudioResourceRepositoryManager repositoryManager = StudioResourceRepositoryManager.getInstance(myFacet);
    checkInvalidationRebuildsInFlightAppResources(repositoryManager, repositoryManager::resetResources);
  }

  public void testUpdateRootsAndLibrariesRebuildsInFlightAppResources() throws Exception {
    StudioResourceRepositoryManager repositoryManager = StudioResourceRepositoryManager.getInstance(myFacet);
    checkInvalidationRebuildsInFlightAppResources(repositoryManager, repositoryManager::updateRootsAndLibraries);
  }

  private void checkInvalidationRebuildsInFlightAppResources(@NotNull StudioResourceRepositoryManager repositoryManager,
                                                             @NotNull Runnable invalidate) throws Exception {
    AtomicInteger computationCount = new AtomicInteger();
    CountDownLatch appRepoReadLibraries = new CountDownLatch(1);
    CountDownLatch releaseComputation = new CountDownLatch(1);
    repositoryManager.setLibraryResourceMapComputationListenerForTests(() -> {
      int count = computationCount.incrementAndGet();
      if (count == 1) {
        // Prevent the precompute step in getAppResources() from caching the map so AppResourceRepository.create()
        // computes it again below.
        repositoryManager.resetResources();
      }
      else if (count == 2) {
        appRepoReadLibraries.countDown();
        try {
          releaseComputation.await();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });

    Future<LocalResourceRepository<VirtualFile>> appResourcesFuture = null;
    try {
      appResourcesFuture = ApplicationManager.getApplication()
        .executeOnPooledThread((Callable<LocalResourceRepository<VirtualFile>>)repositoryManager::getAppResources);
      assertThat(appRepoReadLibraries.await(10, TimeUnit.SECONDS)).isTrue();

      addAarDependency(myFixture, myModule, "mylibrary", "com.example.mylibrary",
                       resDir -> {
                         try {
                           Path values = resDir.toPath().resolve("values");
                           Files.createDirectories(values);
                           Files.writeString(values.resolve("strings.xml"), "<resources><string name=\"lib_str\">hi</string></resources>");
                         }
                         catch (IOException e) {
                           throw new UncheckedIOException(e);
                         }
                         return Unit.INSTANCE;
                       });
      invalidate.run();

      releaseComputation.countDown();
      LocalResourceRepository<VirtualFile> appResources = appResourcesFuture.get(10, TimeUnit.SECONDS);

      assertThat(repositoryManager.getAppResources()).isSameAs(appResources);
      assertThat(Disposer.isDisposed((Disposable)appResources)).isFalse();
      assertThat(appResources.getResources(ResourceNamespace.RES_AUTO, ResourceType.STRING, "lib_str")).hasSize(1);
    }
    finally {
      releaseComputation.countDown();
      awaitQuietly(appResourcesFuture);
      repositoryManager.setLibraryResourceMapComputationListenerForTests(null);
    }
  }

  private static void awaitQuietly(@Nullable Future<?> future) {
    if (future == null) {
      return;
    }
    try {
      future.get(10, TimeUnit.SECONDS);
    }
    catch (Exception ignored) {
    }
  }

  public void testConcurrentGetAppResources() throws Exception {
    StudioResourceRepositoryManager repositoryManager = StudioResourceRepositoryManager.getInstance(myFacet);

    @SuppressWarnings("unchecked")
    LocalResourceRepository<VirtualFile>[] repos = new LocalResourceRepository[2];
    Thread thread1 = new Thread(() -> repos[0] = repositoryManager.getAppResources());
    Thread thread2 = new Thread(() -> repos[1] = repositoryManager.getAppResources());

    thread1.start();
    thread2.start();
    thread1.join();
    thread2.join();

    assertThat(repos[0]).isNotNull();
    assertThat(repos[1]).isNotNull();
    assertThat(repos[0]).isSameAs(repos[1]);
  }

  public static class AllRepositoriesDisposedTest extends AndroidTestCase {
    Set<ResourceRepository> repositoriesToDispose = new HashSet<>();

    public void testClosingProject() {
      StudioResourceRepositoryManager repositoryManager = StudioResourceRepositoryManager.getInstance(myFacet);
      repositoriesToDispose.add(repositoryManager.getAppResources());
      repositoriesToDispose.add(repositoryManager.getProjectResources());
      repositoriesToDispose.add(repositoryManager.getModuleResources());
      repositoriesToDispose.add(repositoryManager.getTestAppResources());
      repositoriesToDispose.addAll(repositoryManager.getTestAppResources().getLeafResourceRepositories());
      repositoriesToDispose.addAll(repositoryManager.getAppResources().getLeafResourceRepositories());
    }

    @Override
    protected void tearDown() throws Exception {
      // Copy the list of repositories before super.tearDown() nulls it out via UsefulTestCase.clearDeclaredFields().
      List<ResourceRepository> repositories = new ArrayList<>(repositoriesToDispose);

      Project project;
      try {
        project = getProject();
      } finally {
        super.tearDown();
      }

      assertThat(project.isDisposed()).isTrue();

      for (ResourceRepository repository : repositories) {
        if (repository instanceof Disposable) {
          assertThat(Disposer.isDisposed((Disposable)repository)).named(repository + " disposed").isTrue();
        }
      }
    }
  }

  public static class DisposingAcrossFacets extends AndroidTestCase {
    @Override
    protected void configureAdditionalModules(@NotNull TestFixtureBuilder<IdeaProjectTestFixture> projectBuilder,
                                              @NotNull List<MyAdditionalModuleData> modules) {
      super.configureAdditionalModules(projectBuilder, modules);
      addModuleWithAndroidFacet(projectBuilder, modules, "myLibrary", AndroidProjectTypes.PROJECT_TYPE_LIBRARY);
    }

    public void testDependentModuleReset() throws Exception {
      // Regression test for b/313309846
      // StudioResourceRepositoryManager.resetResources() causes the contained repositories to be disposed.
      // Any other modules' StudioResourceRepositoryManagers that reference those disposed repositories need to
      // get rid of the references they're holding.
      StudioResourceRepositoryManager mainFacetManager = StudioResourceRepositoryManager.getInstance(myFacet);

      Module myLibraryModule = getAdditionalModuleByName("myLibrary");
      assertThat(myLibraryModule).isNotNull();
      AndroidFacet myLibraryFacet = AndroidFacet.getInstance(myLibraryModule);
      assertThat(myLibraryFacet).isNotNull();

      StudioResourceRepositoryManager libraryFacetManager = StudioResourceRepositoryManager.getInstance(myLibraryFacet);

      LocalResourceRepository<VirtualFile> mainModuleAppResources = mainFacetManager.getAppResources();
      LocalResourceRepository<VirtualFile> mainModuleProjectResources = mainFacetManager.getProjectResources();
      LocalResourceRepository<VirtualFile> mainModuleTestResources = mainFacetManager.getTestModuleResources();
      LocalResourceRepository<VirtualFile> mainModuleModuleResources = mainFacetManager.getModuleResources();

      assertChildrenNotDisposed(mainModuleAppResources);
      assertChildrenNotDisposed(mainModuleProjectResources);
      assertChildrenNotDisposed(mainModuleTestResources);
      assertChildrenNotDisposed(mainModuleModuleResources);

      // Get one of the library facet's repositories, so we can ensure it's been reset and disposed.
      LocalResourceRepository<VirtualFile> libraryModuleAppResources = libraryFacetManager.getAppResources();

      // Resetting the resources will cause the library's repositories to become disposed.
      libraryFacetManager.resetResources();
      assertThat(
        StudioResourceRepositoryManager.DisposeAndRefreshService.getInstance()
          .waitForRunningTasks(10000, TimeUnit.MILLISECONDS)).isTrue();

      // Verify that the library module's repositories have been updated, and the old ones disposed.
      LocalResourceRepository<VirtualFile> libraryModuleAppResourcesAfterReset = libraryFacetManager.getAppResources();
      assertThat(libraryModuleAppResourcesAfterReset).isNotSameAs(libraryModuleAppResources);
      assertThat(Disposer.isDisposed((Disposable)libraryModuleAppResources)).isTrue();

      // The main module's repositories should still be the same objects; but their children (including the
      // library module's repositories) should have changed and should not be disposed.
      assertThat(mainFacetManager.getAppResources()).isSameAs(mainModuleAppResources);
      assertChildrenNotDisposed(mainModuleAppResources);

      assertThat(mainFacetManager.getProjectResources()).isSameAs(mainModuleProjectResources);
      assertChildrenNotDisposed(mainModuleProjectResources);

      assertThat(mainFacetManager.getTestModuleResources()).isSameAs(mainModuleTestResources);
      assertChildrenNotDisposed(mainModuleTestResources);

      assertThat(mainFacetManager.getModuleResources()).isSameAs(mainModuleModuleResources);
      assertChildrenNotDisposed(mainModuleModuleResources);
    }

    private void assertChildrenNotDisposed(LocalResourceRepository<VirtualFile> repository) {
      MultiResourceRepository<VirtualFile> multiRepository = (MultiResourceRepository<VirtualFile>)repository;
      for (ResourceRepository child : multiRepository.getChildren()) {
        if (child instanceof Disposable disposableChild) {
          String displayName;
          if (child instanceof LocalResourceRepository<?> localRepo) {
            displayName = localRepo.getDisplayName();
          } else {
            displayName = child.toString();
          }

          assertThat(Disposer.isDisposed(disposableChild)).named(displayName + " disposed").isFalse();
        }
      }
    }
  }
}
