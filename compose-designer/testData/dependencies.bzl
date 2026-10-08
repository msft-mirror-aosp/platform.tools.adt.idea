"""Dependencies for Compose Gradle projects."""

COMPOSE_UI_VERSION = "1.8.0-alpha06"
LIFECYCLE_VERSION = "2.8.7"
APPCOMPAT_VERSION = "1.7.0"
ACTIVITY_COMPOSE_VERSION = "1.9.3"
CORE_KTX_VERSION = "1.13.1"
COLLECTION_VERSION = "1.5.0-alpha06"
EMOJI2_VIEWS_HELPER_VERSION = "1.4.0"

# Dependencies for SimpleComposeApplication
SIMPLE_COMPOSE_APPLICATION_DEPS = [
    # Direct dependencies
    "@maven//:androidx.appcompat.appcompat_" + APPCOMPAT_VERSION,
    "@maven//:androidx.activity.activity-compose_" + ACTIVITY_COMPOSE_VERSION,
    "@maven//:androidx.compose.ui.ui_" + COMPOSE_UI_VERSION,
    "@maven//:androidx.compose.material.material_" + COMPOSE_UI_VERSION,
    "@maven//:androidx.compose.ui.ui-tooling_" + COMPOSE_UI_VERSION,

    # Transitive dependencies
    "@maven//:androidx.core.core-ktx_" + CORE_KTX_VERSION,
    "@maven//:androidx.collection.collection_" + COLLECTION_VERSION,
    "@maven//:androidx.compose.foundation.foundation_" + COMPOSE_UI_VERSION,
    "@maven//:androidx.lifecycle.lifecycle-viewmodel-savedstate_" + LIFECYCLE_VERSION,
    "@maven//:androidx.lifecycle.lifecycle-livedata_" + LIFECYCLE_VERSION,
    "@maven//:androidx.lifecycle.lifecycle-process_" + LIFECYCLE_VERSION,
    "@maven//:androidx.emoji2.emoji2-views-helper_" + EMOJI2_VIEWS_HELPER_VERSION,
    "@maven//:androidx.collection.collection-ktx_" + COLLECTION_VERSION,
    "@maven//:androidx.compose.animation.animation_" + COMPOSE_UI_VERSION,
]

ANDROID_KOTLIN_MULTIPLATFORM_MULTI_PREVIEW_DEPS = [
    # Direct dependencies
    "@maven//:androidx.activity.activity-compose_" + ACTIVITY_COMPOSE_VERSION,
    "@maven//:org.jetbrains.compose.compose-gradle-plugin_1.10.1",
    "@maven//:org.jetbrains.kotlin.multiplatform.org.jetbrains.kotlin.multiplatform.gradle.plugin_2.2.10",
    "@maven//:org.jetbrains.kotlin.plugin.compose.org.jetbrains.kotlin.plugin.compose.gradle.plugin_2.2.10",

    # Transitive dependencies
    "@maven//:androidx.collection.collection-ktx_1.5.0",
    "@maven//:androidx.core.core-ktx_1.16.0",
    "@maven//:androidx.lifecycle.lifecycle-common-java8_2.9.4",
    "@maven//:androidx.lifecycle.lifecycle-livedata_2.9.4",
    "@maven//:androidx.lifecycle.lifecycle-process_2.9.4",
    "@maven//:androidx.lifecycle.lifecycle-viewmodel-ktx_2.9.4",
    "@maven//:androidx.savedstate.savedstate-ktx_1.3.3",
    "@maven//:androidx.window.window-core-android_1.5.0",
    "@maven//:org.jetbrains.compose.components.components-ui-tooling-preview_1.10.1",
    "@maven//:org.jetbrains.compose.components.components-ui-tooling-preview-android_1.10.1",
    "@maven//:org.jetbrains.compose.desktop.desktop_1.10.1",
    "@maven//:org.jetbrains.compose.material3.material3_1.9.0",
    "@maven//:org.jetbrains.compose.org.jetbrains.compose.gradle.plugin_1.10.1",
    "@maven//:org.jetbrains.compose.runtime.runtime_1.10.1",
    "@maven//:org.jetbrains.compose.ui.ui_1.10.1",
    "@maven//:org.jetbrains.compose.ui.ui-backhandler-android-debug_1.9.1",
    "@maven//:org.jetbrains.compose.ui.ui-backhandler_1.9.1",
    "@maven//:org.jetbrains.compose.ui.ui-desktop_1.10.1",
    "@maven//:org.jetbrains.compose.ui.ui-tooling-preview_1.10.1",
    "@maven//:org.jetbrains.compose.ui.ui-tooling_1.10.1",
    "@maven//:org.jetbrains.kotlin.kotlin-compose-compiler-plugin-embeddable_2.2.10",
    "@maven//:org.jetbrains.skiko.skiko-js-wasm-runtime_0.9.37.4",
]
