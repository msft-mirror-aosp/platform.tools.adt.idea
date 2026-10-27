/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.ide.common.rendering.api.ResourceNamespace.RES_AUTO;
import static com.android.tools.idea.res.ResourcesTestsUtil.getSingleItem;
import static com.android.tools.idea.testing.AndroidTestUtils.waitForUpdates;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.ide.common.resources.ResourceItem;
import com.android.resources.AarTestUtils;
import com.android.resources.ResourceType;
import com.android.resources.aar.AarSourceResourceRepository;
import com.android.tools.idea.projectsystem.AndroidModuleSystem;
import com.android.tools.idea.projectsystem.AndroidProjectSystem;
import com.android.tools.idea.projectsystem.ProjectSystemService;
import com.android.tools.idea.projectsystem.TestResourceResolutionToken;
import com.android.tools.res.LocalResourceRepository;
import com.google.common.collect.ImmutableList;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import org.jetbrains.android.AndroidTestCase;

/**
 * Tests for {@link AppResourceRepository}.
 */
public class AppResourceRepositoryTest extends AndroidTestCase {
  private static final String LAYOUT = "resourceRepository/layout.xml";
  private static final String VALUES = "resourceRepository/values.xml";
  private static final String VALUES_OVERLAY1 = "resourceRepository/valuesOverlay1.xml";
  private static final String VALUES_OVERLAY2 = "resourceRepository/valuesOverlay2.xml";
  private static final String VALUES_OVERLAY2_NO = "resourceRepository/valuesOverlay2No.xml";

  public void testStable() {
    assertSame(StudioResourceRepositoryManager.getAppResources(myFacet), StudioResourceRepositoryManager.getAppResources(myFacet));
    assertSame(StudioResourceRepositoryManager.getAppResources(myFacet), StudioResourceRepositoryManager.getAppResources(myModule));
  }

  public void testStringOrder() {
    myFixture.copyFileToProject(VALUES, "res/values/values.xml");
    LocalResourceRepository<VirtualFile> appResources = StudioResourceRepositoryManager.getInstance(myFacet).getAppResources();

    assertThat(appResources.getResources(RES_AUTO, ResourceType.STRING).keySet()).containsExactly(
      "app_name", "title_crossfade", "title_card_flip", "title_screen_slide", "title_zoom",
      "title_layout_changes", "title_template_step", "ellipsis").inOrder();
  }

  /**
   * Like {@link ModuleResourceRepositoryTest#testOverlayUpdates1}, but rather than testing changes to layout
   * resources (file-based resource) performs document edits in value-documents.
   */
  public void testMerging() throws Exception {
    VirtualFile layoutFile = myFixture.copyFileToProject(LAYOUT, "res/layout/layout1.xml");

    VirtualFile res1 = myFixture.copyFileToProject(VALUES, "res/values/values.xml").getParent().getParent();
    VirtualFile res2 = myFixture.copyFileToProject(VALUES_OVERLAY1, "res2/values/values.xml").getParent().getParent();
    VirtualFile res3 = myFixture.copyFileToProject(VALUES_OVERLAY2, "res3/values/nameDoesNotMatter.xml").getParent().getParent();
    myFixture.copyFileToProject(VALUES_OVERLAY2_NO, "res3/values-no/values.xml");

    assertNotSame(res1, res2);
    assertNotSame(res1, res3);
    assertNotSame(res2, res3);

    // res3 is not used as an overlay here; instead we use it to simulate an AAR library below
    ModuleResourceRepository moduleRepository = ModuleResourceRepository.createForTest(myFacet, ImmutableList.of(res1, res2));
    ProjectResourceRepository projectResources = ProjectResourceRepository.createForTest(
        myFacet, Collections.singletonList(moduleRepository));

    AppResourceRepository appResources = AppResourceRepository.createForTest(
        myFacet, Collections.singletonList(projectResources), Collections.emptyList());

    assertTrue(appResources.hasResources(RES_AUTO, ResourceType.STRING, "title_card_flip"));
    assertFalse(appResources.hasResources(RES_AUTO, ResourceType.STRING, "non_existent_title_card_flip"));

    assertTrue(projectResources.hasResources(RES_AUTO, ResourceType.STRING, "title_card_flip"));
    assertFalse(projectResources.hasResources(RES_AUTO, ResourceType.STRING, "non_existent_title_card_flip"));

    assertTrue(moduleRepository.hasResources(RES_AUTO, ResourceType.STRING, "title_card_flip"));
    assertFalse(moduleRepository.hasResources(RES_AUTO, ResourceType.STRING, "non_existent_title_card_flip"));

    AarSourceResourceRepository aar1 = AarSourceResourceRepository.create(VfsUtilCore.virtualToIoFile(res3).toPath(), "aar1");
    appResources.refreshChildren(ImmutableList.of(projectResources), ImmutableList.of(aar1));

    assertTrue(appResources.hasResources(RES_AUTO, ResourceType.STRING, "another_unique_string"));
    assertTrue(aar1.hasResources(RES_AUTO, ResourceType.STRING, "another_unique_string"));
    assertFalse(projectResources.hasResources(RES_AUTO, ResourceType.STRING, "another_unique_string"));
    assertFalse(moduleRepository.hasResources(RES_AUTO, ResourceType.STRING, "another_unique_string"));
    assertTrue(appResources.hasResources(RES_AUTO, ResourceType.STRING, "title_card_flip"));
    assertFalse(appResources.hasResources(RES_AUTO, ResourceType.STRING, "non_existent_title_card_flip"));

    // Update module resource repository and assert that changes make it all the way up
    PsiFile layoutPsiFile = PsiManager.getInstance(getProject()).findFile(layoutFile);
    assertNotNull(layoutPsiFile);
    assertTrue(moduleRepository.hasResources(RES_AUTO, ResourceType.ID, "btn_title_refresh"));
    ResourceItem item = getSingleItem(moduleRepository, ResourceType.ID, "btn_title_refresh");

    long generation = moduleRepository.getModificationCount();
    int rescans = moduleRepository.getFileRescans();
    long projectGeneration = projectResources.getModificationCount();
    long appGeneration = appResources.getModificationCount();
    PsiDocumentManager documentManager = PsiDocumentManager.getInstance(getProject());
    Document document = documentManager.getDocument(layoutPsiFile);
    assertNotNull(document);
    WriteCommandAction.runWriteCommandAction(getProject(), () -> {
      String string = "<ImageView style=\"@style/TitleBarSeparator\" />";
      int offset = document.getText().indexOf(string);
      document.deleteString(offset, offset + string.length());
      documentManager.commitDocument(document);
    });
    waitForUpdates(moduleRepository);
    assertTrue(generation < moduleRepository.getModificationCount());
    assertTrue(projectGeneration < projectResources.getModificationCount());
    assertTrue(appGeneration < appResources.getModificationCount());
    assertThat(moduleRepository.getFileRescans()).isEqualTo(rescans + 1); // First edit is not incremental (file -> Psi).
    // Should still be defined:
    assertTrue(moduleRepository.hasResources(RES_AUTO, ResourceType.ID, "btn_title_refresh"));
    assertTrue(appResources.hasResources(RES_AUTO, ResourceType.ID, "btn_title_refresh"));
    assertTrue(projectResources.hasResources(RES_AUTO, ResourceType.ID, "btn_title_refresh"));
    ResourceItem newItem = getSingleItem(appResources, ResourceType.ID, "btn_title_refresh");
    assertNotNull(newItem.getSource());
    // However, should be a different item.
    assertNotSame(item, newItem);
  }

  public void testGetResourceDirs() {
    VirtualFile res1 = myFixture.copyFileToProject(VALUES, "res/values/values.xml").getParent().getParent();
    VirtualFile res2 = myFixture.copyFileToProject(VALUES_OVERLAY1, "res2/values/values.xml").getParent().getParent();

    assertNotSame(res1, res2);

    // res2 is not used as an overlay here; instead we use it to simulate an AAR library below
    ModuleResourceRepository moduleRepository1 = ModuleResourceRepository.createForTest(myFacet, Collections.singletonList(res1));
    ModuleResourceRepository moduleRepository2 = ModuleResourceRepository.createForTest(myFacet, Collections.singletonList(res2));
    ProjectResourceRepository projectResources =
        ProjectResourceRepository.createForTest(myFacet, ImmutableList.of(moduleRepository1, moduleRepository2));
    AppResourceRepository appResources =
        AppResourceRepository.createForTest(myFacet, Collections.singletonList(projectResources), Collections.emptyList());

    Set<VirtualFile> folders = appResources.getResourceDirs();
    assertSameElements(folders, res1, res2);
  }

  public void testGetItemsOfTypeIdIncludeAar() {
    VirtualFile res1 = myFixture.copyFileToProject(LAYOUT, "res/layout/some_layout.xml").getParent().getParent();
    LocalResourceRepository<VirtualFile> moduleRepository = ModuleResourceRepository.createForTest(myFacet, ImmutableList.of(res1));
    LocalResourceRepository<VirtualFile> projectResources = ProjectResourceRepository.createForTest(myFacet, ImmutableList.of(moduleRepository));

    AarSourceResourceRepository aar = AarTestUtils.getTestAarRepositoryFromExplodedAar();
    AppResourceRepository appResources =
        AppResourceRepository.createForTest(myFacet, ImmutableList.of(projectResources), ImmutableList.of(aar));

    Collection<String> idResources = appResources.getResources(RES_AUTO, ResourceType.ID).keySet();
    assertContainsElements(idResources, "btn_title_refresh");
  }

  public void testTokenAdditionalResourceDependencies() {
    AndroidProjectSystem projectSystem = ProjectSystemService.getInstance(getProject()).getProjectSystem();
    AndroidModuleSystem moduleSystem = projectSystem.getModuleSystem(myModule);
    TestResourceResolutionToken<AndroidProjectSystem> mockToken = mock(TestResourceResolutionToken.class);
    when(mockToken.isApplicable(projectSystem)).thenReturn(true);
    when(mockToken.computeAdditionalLocalResourceDependencies(moduleSystem))
        .thenReturn(ImmutableList.of());
    ApplicationManager.getApplication().getExtensionArea()
        .getExtensionPoint(TestResourceResolutionToken.getEP_NAME())
        .registerExtension(mockToken, getTestRootDisposable());

    LocalResourceRepository<?> appResources = StudioResourceRepositoryManager.getAppResources(myFacet);

    verify(mockToken).computeAdditionalLocalResourceDependencies(moduleSystem);
  }
}
