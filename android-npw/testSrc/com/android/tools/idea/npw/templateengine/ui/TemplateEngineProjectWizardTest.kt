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
package com.android.tools.idea.npw.templateengine.ui

import com.android.sdklib.AndroidApiLevel
import com.android.template.engine.DependencyInstaller
import com.android.template.engine.TemplateDefinition
import com.android.template.engine.TemplateEngineFactory
import com.android.testutils.TestUtils
import com.android.tools.adtui.device.FormFactor
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.npw.NewProjectWizardTestUtils.getAgpVersion
import com.android.tools.idea.npw.startup.PromotionTemplateStateService
import com.android.tools.idea.npw.template.PluginPromotionTemplate
import com.android.tools.idea.npw.template.WizardPluginPromotionTemplateProvider
import com.android.tools.idea.npw.templateengine.WizardConstants
import com.android.tools.idea.npw.templateengine.api.ExternalTemplateSpec
import com.android.tools.idea.npw.templateengine.api.TemplateEngineProjectWizardContributor
import com.android.tools.idea.npw.templateengine.services.TemplateEngineProjectParameters
import com.android.tools.idea.npw.templateengine.services.TemplateRegistryService
import com.android.tools.idea.npw.templateengine.services.buildExplicitArguments
import com.android.tools.idea.npw.templateengine.viewmodel.ChooseProjectViewModel
import com.android.tools.idea.sdk.IdeSdks
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.idea.testing.AndroidGradleTests
import com.android.tools.idea.testing.TestProjectPaths
import com.android.tools.idea.testing.resolve
import com.android.tools.idea.wizard.template.FormFactor as TemplateFormFactor
import com.android.tools.idea.wizard.template.Thumb
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.registerExtension
import com.intellij.testFramework.replaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TemplateEngineProjectWizardTest {

  private lateinit var disposable: Disposable
  private var originalNewTemplateEngineFlag: Boolean = false

  @Before
  fun setUp() {
    disposable = Disposer.newDisposable()
    ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "Reference Cleaner")
    originalNewTemplateEngineFlag = StudioFlags.NPW_NEW_TEMPLATE_ENGINE.get()
    StudioFlags.NPW_NEW_TEMPLATE_ENGINE.override(true)
    val zipFile = TestUtils.resolveWorkspacePath("tools/vendor/google/android-cli/create/templates/android-project-templates.zip")
    val testRegistry = TemplateRegistryService.createForTest(zipPathProvider = { zipFile })
    ApplicationManager.getApplication().replaceService(TemplateRegistryService::class.java, testRegistry, disposable)
  }

  @After
  fun tearDown() {
    StudioFlags.NPW_NEW_TEMPLATE_ENGINE.override(originalNewTemplateEngineFlag)
    Disposer.dispose(disposable)
  }

  @get:Rule val projectRule = AndroidGradleProjectRule(agpVersionSoftwareEnvironment = getAgpVersion())

  @Test
  @RunsInEdt
  fun testCreateNewProjectFromTemplate() {
    // 1. Get the template definition from the registry
    val registry = TemplateRegistryService.getInstance()
    registry.loadTemplatesAndResources()
    val templateDefinitions = registry.getTemplateDefinitions()
    assertThat(templateDefinitions).isNotEmpty()
    val viewModel = ChooseProjectViewModel(registry)
    assertThat(viewModel.categories).isNotEmpty()
    val firstCategory = viewModel.categories.filterIsInstance<TemplateEngineTemplateGridProjectEntry>().first()
    viewModel.selectedCategory = firstCategory
    assertThat(firstCategory.categoryTitle).isEqualTo("Phone and Large screens")
    assertThat(firstCategory.templates).isNotEmpty()
    val firstTemplate = firstCategory.templates[0]
    assertThat(viewModel.selectedTemplate).isEqualTo(firstTemplate)

    val emptyComposeTemplate = templateDefinitions.first { it.metadata.shortName == "empty-activity" }
    val lightThumbnail = getTemplateThumbnail(emptyComposeTemplate, isDark = false)
    assertThat(lightThumbnail).isNotNull()
    val darkThumbnail = getTemplateThumbnail(emptyComposeTemplate, isDark = true)
    assertThat(darkThumbnail).isNotNull()

    // 2. Load NO_MODULES, but run the TemplateEngine in preLoad to populate the project
    // directory
    projectRule.load(
      TestProjectPaths.NO_MODULES,
      preLoad = { rootFile ->
        // Clear out anything in the temp directory (e.g. from NO_MODULES template)
        rootFile.listFiles()?.forEach { it.deleteRecursively() }

        val factory = TemplateEngineFactory.createDefault()
        val engine =
          factory.createDefaultEngine(
            messageSink = TemplateRegistryService.messageSink,
            dependencyInstaller =
              object : DependencyInstaller {
                override fun installAndroidSdkPackage(packagePath: String) {}
              },
            destinationPathProvider = { rootFile.toPath() },
          )

        val explicitArgs =
          mapOf(
            "name" to "MyNewProject",
            "applicationId" to "com.example.mynewproject",
            "namespace" to "com.example.mynewproject",
            "minSdk" to "24",
            "compileSdk" to "36",
          )

        val sdkPath = IdeSdks.getInstance().androidSdkPath?.absolutePath ?: ""
        val implicitArgs = mapOf("sdkPath" to sdkPath)

        engine.processTemplate(emptyComposeTemplate, implicitArgs, explicitArgs)

        // Run default patch to create the Gradle wrapper, local.properties,
        // gradle.properties, and patch build files
        val resolvedAgp = getAgpVersion().resolve()
        AndroidGradleTests.defaultPatchPreparedProject(rootFile, resolvedAgp, null, true)

        // Now patch Compose, Material3, and Kotlin versions which are not handled by
        // defaultPatchPreparedProject
        TemplateEngineTestUtils.patchAdditionalVersions(rootFile, resolvedAgp)
      },
    )

    // Assert that the project was synced successfully
    assertThat(projectRule.project).isNotNull()

    // Assert build works
    val result = projectRule.invokeTasks("assembleDebug")
    if (!result.isBuildSuccessful) {
      System.err.println("WIZARD_TEST: build failed!")
      result.buildError?.printStackTrace()
    }
    assertThat(result.isBuildSuccessful).isTrue()
  }

  @Test
  @RunsInEdt
  fun testBuildExplicitArgumentsWithMinorSdk() {
    val parameters =
      TemplateEngineProjectParameters(name = "TestApp", packageName = "com.example.test", location = "/tmp/test", minSdk = "24")

    val originalFlagValue = StudioFlags.NPW_COMPILE_SDK_VERSION.get()
    try {
      // Test with minor version (e.g. 36.1)
      StudioFlags.NPW_COMPILE_SDK_VERSION.override(AndroidApiLevel(36, 1))
      val args = buildExplicitArguments(parameters)
      assertThat(args[WizardConstants.KEY_COMPILE_SDK]).isEqualTo("36")
      assertThat(args[WizardConstants.KEY_COMPILE_SDK_MINOR]).isEqualTo("1")

      // Test with major version only (e.g. 36.0)
      StudioFlags.NPW_COMPILE_SDK_VERSION.override(AndroidApiLevel(36, 0))
      val argsMajorOnly = buildExplicitArguments(parameters)
      assertThat(argsMajorOnly[WizardConstants.KEY_COMPILE_SDK]).isEqualTo("36")
      assertThat(argsMajorOnly[WizardConstants.KEY_COMPILE_SDK_MINOR]).isEqualTo("")

      // Test with older version (e.g. 35)
      StudioFlags.NPW_COMPILE_SDK_VERSION.override(AndroidApiLevel(35, 0))
      val argsOlder = buildExplicitArguments(parameters)
      assertThat(argsOlder[WizardConstants.KEY_COMPILE_SDK]).isEqualTo("35")
      assertThat(argsOlder[WizardConstants.KEY_COMPILE_SDK_MINOR]).isEqualTo("")
    } finally {
      StudioFlags.NPW_COMPILE_SDK_VERSION.override(originalFlagValue)
    }
  }

  @Test
  fun testDynamicFormFactorGrouping() {
    val t1 = createTemplate("Template Mobile", listOf("project"))
    val t2 = createTemplate("Template Wear", listOf("wear"))
    val t3 = createTemplate("Template Custom B", listOf("category:Custom Category B"))
    val t4 = createTemplate("Template Custom A", listOf("category:Custom Category A"))

    val mockRegistry = mock<TemplateRegistryService>().apply { whenever(this.getTemplateDefinitions()).thenReturn(listOf(t1, t2, t3, t4)) }
    ApplicationManager.getApplication().replaceService(TemplateRegistryService::class.java, mockRegistry, disposable)

    val viewModel = ChooseProjectViewModel(mockRegistry)
    val gridEntries = viewModel.categories.filterIsInstance<TemplateEngineTemplateGridProjectEntry>()

    assertThat(gridEntries).hasSize(2)
    assertThat(gridEntries[0].categoryTitle).isEqualTo("Phone and Large screens")
    assertThat(gridEntries[0].templates).containsExactly(t1, t4, t3)

    assertThat(gridEntries[1].categoryTitle).isEqualTo("Wear OS")
    assertThat(gridEntries[1].templates).containsExactly(t2)
  }

  @Test
  fun testGalleryItemsSortingAndPromotionOrdering() {
    registerPromotionTemplates(
      FakePluginPromotionTemplate("Promo Plugin 1", "promo-1", TemplateFormFactor.Mobile),
      FakePluginPromotionTemplate("Gemini AI Starter", "gemini-ai-starter-sample", TemplateFormFactor.Mobile),
    )
    val externalZip =
      createTemplateZip(
        mapOf(
          "external-b" to """{"name": "External Template B", "short-name": "external-b", "tags": ["agp-9"]}""",
          "external-a" to """{"name": "External Template A", "short-name": "external-a", "tags": ["agp-9"]}""",
          "external-wear" to """{"name": "External Wear", "short-name": "external-wear", "tags": ["agp-9", "wear"]}""",
        )
      )

    val mockContributor =
      object : TemplateEngineProjectWizardContributor {
        override val id = "test-contributor"
        // A very high priority must not move external templates ahead of built-in templates or promotion cards
        override val priority = 5000

        override fun getExternalTemplates() = listOf(ExternalTemplateSpec.ZipFile(externalZip))
      }
    val lowPriorityZip =
      createTemplateZip(mapOf("external-c" to """{"name": "External Template C", "short-name": "external-c", "tags": ["agp-9"]}"""))
    val lowPriorityContributor =
      object : TemplateEngineProjectWizardContributor {
        override val id = "low-priority-contributor"
        override val priority = 0

        override fun getExternalTemplates() = listOf(ExternalTemplateSpec.ZipFile(lowPriorityZip))
      }

    ApplicationManager.getApplication().registerExtension(TemplateEngineProjectWizardContributor.EP_NAME, mockContributor, disposable)
    ApplicationManager.getApplication()
      .registerExtension(TemplateEngineProjectWizardContributor.EP_NAME, lowPriorityContributor, disposable)

    val registry = TemplateRegistryService.getInstance()
    registry.loadTemplatesAndResources()
    val viewModel = ChooseProjectViewModel(registry)
    val gridEntries = viewModel.categories.filterIsInstance<TemplateEngineTemplateGridProjectEntry>()

    val mobileTab = gridEntries.first { it.formFactor == FormFactor.MOBILE }

    // MOBILE must have promotions, standard templates, and external templates
    assertThat(mobileTab.items.size).isAtLeast(4)

    // Verify Promotions are present
    val promotions = mobileTab.items.filterIsInstance<TemplateGalleryItem.Promotion>()
    assertThat(promotions).hasSize(2)
    assertThat(promotions.map { it.spec.id }).containsExactly("promo-1", "gemini-ai-starter-sample")

    // Verify External templates are at the end, by contributor priority then alphabetically, and backed by a real template definition
    val externals = mobileTab.items.filterIsInstance<TemplateGalleryItem.External>()
    assertThat(externals.map { it.definition.shortName }).containsExactly("external-a", "external-b", "external-c").inOrder()
    assertThat(mobileTab.items.takeLast(3)).containsExactlyElementsIn(externals).inOrder()
    assertThat(externals.map { it.template.contributorId })
      .containsExactly("test-contributor", "test-contributor", "low-priority-contributor")
      .inOrder()

    // External templates are grouped by form factor using their tags, like built-in templates
    val wearTab = gridEntries.first { it.formFactor == FormFactor.WEAR }
    assertThat(wearTab.templates.map { it.shortName }).contains("external-wear")

    // The default selection must point to the empty compose activity (first built-in template)
    val selectedItem = mobileTab.selectedItem
    assertThat(selectedItem).isInstanceOf(TemplateGalleryItem.Standard::class.java)
    assertThat((selectedItem as TemplateGalleryItem.Standard).definition.shortName).isEqualTo("empty-activity")

    // Selecting an external template makes it the template rendered by the wizard
    mobileTab.selectedItem = externals.first()
    viewModel.selectedCategory = mobileTab
    assertThat(viewModel.selectedTemplate?.shortName).isEqualTo("external-a")
  }

  @Test
  fun testEmptyFormFactorsAreFilteredOut() {
    val tTv = createTemplate("TV Template", listOf("tv"), shortName = "tv-blank-activity")

    val mockRegistry = mock<TemplateRegistryService>().apply { whenever(this.getTemplateDefinitions()).thenReturn(listOf(tTv)) }
    ApplicationManager.getApplication().replaceService(TemplateRegistryService::class.java, mockRegistry, disposable)

    val viewModel = ChooseProjectViewModel(mockRegistry)
    val gridEntries = viewModel.categories.filterIsInstance<TemplateEngineTemplateGridProjectEntry>()

    assertThat(gridEntries).hasSize(1)
    assertThat(gridEntries[0].formFactor).isEqualTo(FormFactor.TV)
  }

  @Test
  fun testPluginPromotionTemplateInGalleryGrid() {
    registerPromotionTemplates(FakePluginPromotionTemplate(PROMOTION_NAME, PROMOTION_PLUGIN_ID, TemplateFormFactor.Mobile))

    val registry = TemplateRegistryService.getInstance()
    if (registry.getTemplateDefinitions().isEmpty()) {
      registry.loadTemplatesAndResources()
    }
    val viewModel = ChooseProjectViewModel(registry)
    val gridEntries = viewModel.categories.filterIsInstance<TemplateEngineTemplateGridProjectEntry>()

    val mobileTab = gridEntries.first { it.formFactor == FormFactor.MOBILE }
    val promoItem =
      mobileTab.items.filterIsInstance<TemplateGalleryItem.Promotion>().firstOrNull { it.spec.pluginId == PROMOTION_PLUGIN_ID }

    assertThat(promoItem).isNotNull()
    assertThat(promoItem!!.title).contains(PROMOTION_NAME)
    assertThat(promoItem.spec.formFactor).isEqualTo(FormFactor.MOBILE)
  }

  @Test
  fun testPluginPromotionTemplateFormFactorFiltering() {
    registerPromotionTemplates(FakePluginPromotionTemplate(PROMOTION_NAME, PROMOTION_PLUGIN_ID, TemplateFormFactor.Wear))

    val registry = TemplateRegistryService.getInstance()
    if (registry.getTemplateDefinitions().isEmpty()) {
      registry.loadTemplatesAndResources()
    }
    val viewModel = ChooseProjectViewModel(registry)
    val gridEntries = viewModel.categories.filterIsInstance<TemplateEngineTemplateGridProjectEntry>()

    val mobileTab = gridEntries.first { it.formFactor == FormFactor.MOBILE }
    val wearTab = gridEntries.first { it.formFactor == FormFactor.WEAR }

    assertThat(mobileTab.items.filterIsInstance<TemplateGalleryItem.Promotion>().filter { it.spec.pluginId == PROMOTION_PLUGIN_ID })
      .isEmpty()
    val wearPromo = wearTab.items.filterIsInstance<TemplateGalleryItem.Promotion>().firstOrNull { it.spec.pluginId == PROMOTION_PLUGIN_ID }
    assertThat(wearPromo).isNotNull()
    assertThat(wearPromo!!.title).contains(PROMOTION_NAME)
  }

  @Test
  fun testPromotionTemplateStateRestoresSelection() {
    registerPromotionTemplates(FakePluginPromotionTemplate(PROMOTION_NAME, PROMOTION_PLUGIN_ID, TemplateFormFactor.Mobile))
    PromotionTemplateStateService.getInstance()
      .requestNpwReopenOnNextStartup(PROMOTION_PLUGIN_ID, "Empty Views Activity", TemplateFormFactor.Mobile)

    val registry = TemplateRegistryService.getInstance()
    if (registry.getTemplateDefinitions().isEmpty()) {
      registry.loadTemplatesAndResources()
    }
    val viewModel = ChooseProjectViewModel(registry)

    assertThat(viewModel.selectedCategory).isNotNull()
    val selectedEntry = viewModel.selectedCategory as TemplateEngineTemplateGridProjectEntry
    assertThat(selectedEntry.formFactor).isEqualTo(FormFactor.MOBILE)
  }

  @Test
  fun testPromotionTemplateStateSelectsExternalTemplate() {
    // After installing a promoted plugin, the wizard reopens with the template it contributes selected.
    val externalZip =
      createTemplateZip(mapOf("promoted" to """{"name": "Promoted Template", "short-name": "promoted", "tags": ["agp-9"]}"""))
    val contributor =
      object : TemplateEngineProjectWizardContributor {
        override val id = PROMOTION_PLUGIN_ID

        override fun getExternalTemplates() = listOf(ExternalTemplateSpec.ZipFile(externalZip))
      }
    ApplicationManager.getApplication().registerExtension(TemplateEngineProjectWizardContributor.EP_NAME, contributor, disposable)
    PromotionTemplateStateService.getInstance()
      .requestNpwReopenOnNextStartup(PROMOTION_PLUGIN_ID, "Promoted Template", TemplateFormFactor.Mobile)

    val registry = TemplateRegistryService.getInstance()
    registry.loadTemplatesAndResources()
    val viewModel = ChooseProjectViewModel(registry)

    val selectedEntry = viewModel.selectedCategory as TemplateEngineTemplateGridProjectEntry
    assertThat(selectedEntry.selectedItem).isInstanceOf(TemplateGalleryItem.External::class.java)
    assertThat(selectedEntry.selectedTemplate?.shortName).isEqualTo("promoted")
  }

  private fun registerPromotionTemplates(vararg templates: PluginPromotionTemplate) {
    ExtensionTestUtil.maskExtensions(
      PROMOTION_EP_NAME,
      listOf(FakeWizardPluginPromotionTemplateProvider(templates.toList())),
      disposable,
    )
  }

  private fun createTemplate(name: String, tags: List<String>, shortName: String = name.lowercase().replace(" ", "-")): TemplateDefinition {
    return TemplateEngineTestUtils.createTestTemplateDefinition(name = name, shortName = shortName, tags = tags)
  }

  /** Creates a template zip, deleted on [disposable] disposal, containing one template per entry of [definitionJsonByDir]. */
  private fun createTemplateZip(definitionJsonByDir: Map<String, String>): Path {
    val zipFile = Files.createTempFile("external_templates", ".zip")
    Disposer.register(disposable) { Files.deleteIfExists(zipFile) }
    ZipOutputStream(Files.newOutputStream(zipFile)).use { zos ->
      for ((dir, json) in definitionJsonByDir) {
        zos.putNextEntry(ZipEntry("$dir/.template/template-definition.json"))
        zos.write(json.toByteArray())
        zos.closeEntry()
      }
    }
    return zipFile
  }
}

private const val PROMOTION_NAME = "Test Promotion Plugin"
private const val PROMOTION_PLUGIN_ID = "com.example.testpromotion"

private val PROMOTION_EP_NAME =
  ExtensionPointName<WizardPluginPromotionTemplateProvider>("com.android.tools.idea.npw.template.wizardPluginPromotionTemplateProvider")

private val fakeThumbUrl by lazy {
  val file = java.io.File.createTempFile("fake_thumb", ".png").apply { deleteOnExit() }
  javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", file)
  file.toURI().toURL()
}

private class FakePluginPromotionTemplate(
  override val name: String,
  override val pluginId: String,
  override val formFactor: TemplateFormFactor,
) : PluginPromotionTemplate {
  override fun thumb(): Thumb = Thumb { fakeThumbUrl }
}

private class FakeWizardPluginPromotionTemplateProvider(private val templates: List<PluginPromotionTemplate>) :
  WizardPluginPromotionTemplateProvider() {
  override fun getTemplates(): List<PluginPromotionTemplate> = templates
}
