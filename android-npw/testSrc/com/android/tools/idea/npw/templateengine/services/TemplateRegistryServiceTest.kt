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
package com.android.tools.idea.npw.templateengine.services

import com.android.template.engine.DependencyInstaller
import com.android.template.engine.TemplateEngineFactory
import com.android.testutils.TestUtils
import com.android.tools.idea.npw.templateengine.api.ExternalTemplateSpec
import com.android.tools.idea.npw.templateengine.api.TemplateEngineProjectWizardContributor
import com.android.tools.idea.testing.AndroidProjectRule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.registerExtension
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TemplateRegistryServiceTest {

  @get:Rule val projectRule = AndroidProjectRule.inMemory()

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun testTemplatesLoaded() {
    val zipFile = TestUtils.resolveWorkspacePath("tools/vendor/google/android-cli/create/templates/android-project-templates.zip")
    val service = TemplateRegistryService.createForTest(zipPathProvider = { zipFile })
    assertThat(service).isNotNull()
    service.loadTemplatesAndResources()
    val templateDefinitions = service.getTemplateDefinitions()
    assertThat(templateDefinitions).isNotEmpty()

    // Verify we have loaded some expected templates
    val shortNames = templateDefinitions.map { it.metadata.shortName }
    assertThat(shortNames).contains("empty-activity")
  }

  @Test
  fun testFiltering() {
    var tempFile: Path? = null
    try {
      val jsonContent1 = """{"name": "Template 1", "short-name": "template-1", "tags": ["agp-9"]}"""
      val jsonContent2 = """{"name": "Template 2", "short-name": "template-2", "tags": ["gradle"]}"""
      val jsonContent3 = """{"name": "Template 3", "short-name": "template-3", "tags": ["agp-9", "other"]}"""
      val jsonContent4 = """{"name": "Template 4", "short-name": "template-4", "tags": ["lume"]}"""

      val zipBytes =
        createZipBytes(
          mapOf(
            "studio-template/.template/template-definition.json" to jsonContent1.toByteArray(),
            "cli-template/.template/template-definition.json" to jsonContent2.toByteArray(),
            "both-template/.template/template-definition.json" to jsonContent3.toByteArray(),
            "no-targets-template/.template/template-definition.json" to jsonContent4.toByteArray(),
          )
        )

      tempFile = Files.createTempFile("test_templates", ".zip")
      Files.write(tempFile, zipBytes)

      val customPath = tempFile
      val service = TemplateRegistryService.createForTest(zipPathProvider = { customPath })
      service.loadTemplatesAndResources()
      val templateDefinitions = service.getTemplateDefinitions()

      val names = templateDefinitions.map { it.metadata.shortName }
      assertThat(names).containsExactly("template-1", "template-2", "template-3")
    } finally {
      tempFile?.let { Files.deleteIfExists(it) }
    }
  }

  @Test
  fun testFailedInitialLoadAndSubsequentSuccessfulReload() {
    var shouldFail = true
    val zipFile = TestUtils.resolveWorkspacePath("tools/vendor/google/android-cli/create/templates/android-project-templates.zip")
    val service =
      TemplateRegistryService.createForTest(
        zipPathProvider = {
          if (shouldFail) throw IllegalStateException("Templates not available yet")
          zipFile
        }
      )

    service.loadTemplatesAndResources()
    assertThat(service.getTemplateDefinitions()).isEmpty()
    assertThat(service.getLastErrorMessage()).contains("Templates not available yet")

    // Simulate package becoming installed / available
    shouldFail = false
    service.loadTemplatesAndResources()
    assertThat(service.getTemplateDefinitions()).isNotEmpty()
    assertThat(service.getLastErrorMessage()).isNull()
  }

  @Test
  fun testExternalTemplatesFromZipFile() {
    val zipFile = writeZip(tempFolder.newFile("plugin-templates.zip").toPath(), pluginTemplateZipEntries("plugin-a", "plugin-b"))
    registerContributor("zip-contributor", ExternalTemplateSpec.ZipFile(zipFile))

    val service = createServiceWithoutSdkTemplates()
    service.loadTemplatesAndResources()

    assertThat(service.getTemplateDefinitions()).isEmpty()
    val external = service.getContributorExternalTemplates()
    assertThat(external.map { it.definition.shortName }).containsExactly("plugin-a", "plugin-b")
    assertThat(external.map { it.contributorId }.toSet()).containsExactly("zip-contributor")
  }

  @Test
  fun testBrokenExternalSourcesDoNotAffectOtherSources() {
    // A contributor that throws, a missing zip file, and a zip containing an invalid template definition.
    val throwingContributor =
      object : TemplateEngineProjectWizardContributor {
        override val id = "throwing-contributor"

        override fun getExternalTemplates(): List<ExternalTemplateSpec> = throw IllegalStateException("boom")
      }
    ApplicationManager.getApplication()
      .registerExtension(TemplateEngineProjectWizardContributor.EP_NAME, throwingContributor, projectRule.testRootDisposable)
    val invalidZip =
      writeZip(
        tempFolder.newFile("invalid.zip").toPath(),
        mapOf("invalid/.template/template-definition.json" to """{"name": "Missing short name", "tags": ["agp-9"]}""".toByteArray()),
      )
    val validZip = writeZip(tempFolder.newFile("valid.zip").toPath(), pluginTemplateZipEntries("valid-template"))
    registerContributor(
      "mixed-contributor",
      ExternalTemplateSpec.ZipFile(tempFolder.root.toPath().resolve("does-not-exist.zip")),
      ExternalTemplateSpec.ZipFile(invalidZip),
      ExternalTemplateSpec.ZipFile(validZip),
    )

    val service = createServiceWithoutSdkTemplates()
    service.loadExternalTemplates()

    assertThat(service.getContributorExternalTemplates().map { it.definition.shortName }).containsExactly("valid-template")
  }

  @Test
  fun testIncompatibleContributorDoesNotAffectOtherContributors() {
    // A contributor built against an older version of the API fails with a LinkageError rather than an Exception.
    val incompatibleContributor =
      object : TemplateEngineProjectWizardContributor {
        override val id = "incompatible-contributor"

        override fun getExternalTemplates(): List<ExternalTemplateSpec> = throw NoClassDefFoundError("com/example/RemovedApi")
      }
    ApplicationManager.getApplication()
      .registerExtension(TemplateEngineProjectWizardContributor.EP_NAME, incompatibleContributor, projectRule.testRootDisposable)
    val validZip = writeZip(tempFolder.newFile("valid.zip").toPath(), pluginTemplateZipEntries("valid-template"))
    registerContributor("valid-contributor", ExternalTemplateSpec.ZipFile(validZip))

    val service = createServiceWithoutSdkTemplates()
    service.loadExternalTemplates()

    assertThat(service.getContributorExternalTemplates().map { it.contributorId }).containsExactly("valid-contributor")
  }

  @Test
  fun testCancellationIsNotSwallowed() {
    // Loading runs in coroutines: a cancellation raised while calling a contributor must propagate, not be logged as a failure.
    val cancellingContributor =
      object : TemplateEngineProjectWizardContributor {
        override val id = "cancelling-contributor"

        override fun getExternalTemplates(): List<ExternalTemplateSpec> = throw CancellationException("cancelled")
      }
    ApplicationManager.getApplication()
      .registerExtension(TemplateEngineProjectWizardContributor.EP_NAME, cancellingContributor, projectRule.testRootDisposable)

    val service = createServiceWithoutSdkTemplates()
    assertThrows(CancellationException::class.java) { service.loadExternalTemplates() }
  }

  @Test
  fun testExternalTemplatesRemovedWhenContributorIsUnloaded() {
    val zipFile = writeZip(tempFolder.newFile("plugin-templates.zip").toPath(), pluginTemplateZipEntries("plugin-a"))
    val contributorDisposable = Disposer.newDisposable(projectRule.testRootDisposable, "contributor")
    registerContributor("zip-contributor", ExternalTemplateSpec.ZipFile(zipFile), parentDisposable = contributorDisposable)

    val service = createServiceWithoutSdkTemplates()
    service.loadExternalTemplates()
    assertThat(service.getContributorExternalTemplates()).hasSize(1)

    Disposer.dispose(contributorDisposable)
    service.loadExternalTemplates()
    assertThat(service.getContributorExternalTemplates()).isEmpty()
  }

  @Test
  fun testExternalTemplateIsRenderedByTemplateEngine() {
    val zipFile = writeZip(tempFolder.newFile("renderable.zip").toPath(), pluginTemplateZipEntries("renderable-template"))
    registerContributor("zip-contributor", ExternalTemplateSpec.ZipFile(zipFile))
    val service = createServiceWithoutSdkTemplates()
    service.loadExternalTemplates()
    val template = service.getContributorExternalTemplates().single().definition

    val destination = tempFolder.root.toPath().resolve("generated-project")
    val engine =
      TemplateEngineFactory.createDefault()
        .createDefaultEngine(
          messageSink = TemplateRegistryService.messageSink,
          dependencyInstaller =
            object : DependencyInstaller {
              override fun installAndroidSdkPackage(packagePath: String) {}
            },
          destinationPathProvider = { destination },
        )
    engine.processTemplate(template, emptyMap(), emptyMap())

    assertThat(destination.resolve("app/README.md").readText()).isEqualTo("Generated from renderable-template")
  }

  private fun createServiceWithoutSdkTemplates(): TemplateRegistryService =
    TemplateRegistryService.createForTest(
      coroutineScope = inactiveScope(),
      zipPathProvider = { throw IllegalStateException("No SDK templates in this test") },
    )

  /** A cancelled scope, so that asynchronous (re)loads triggered by the service don't race with the explicit loads of the tests. */
  private fun inactiveScope(): CoroutineScope = CoroutineScope(Job().apply { cancel() })

  private fun registerContributor(
    contributorId: String,
    vararg sources: ExternalTemplateSpec,
    priority: Int = 0,
    parentDisposable: Disposable = projectRule.testRootDisposable,
  ) {
    val contributorPriority = priority
    val contributor =
      object : TemplateEngineProjectWizardContributor {
        override val id = contributorId
        override val priority = contributorPriority

        override fun getExternalTemplates() = sources.toList()
      }
    ApplicationManager.getApplication().registerExtension(TemplateEngineProjectWizardContributor.EP_NAME, contributor, parentDisposable)
  }

  private fun pluginTemplateZipEntries(vararg shortNames: String, name: String = "Plugin"): Map<String, ByteArray> =
    shortNames
      .flatMap { shortName ->
        listOf(
          "$shortName/.template/template-definition.json" to
            """{"name": "$name $shortName", "short-name": "$shortName", "tags": ["agp-9"]}""".toByteArray(),
          "$shortName/app/README.md" to "Generated from $shortName".toByteArray(),
        )
      }
      .toMap()

  private fun writeZip(path: Path, files: Map<String, ByteArray>): Path {
    Files.write(path, createZipBytes(files))
    return path
  }

  private fun createZipBytes(files: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
      for ((name, content) in files) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content)
        zos.closeEntry()
      }
    }
    return out.toByteArray()
  }
}
