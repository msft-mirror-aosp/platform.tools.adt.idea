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
package com.android.tools.idea.compose.preview.interactive

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationInfoParserTest {

  /** Simulates `androidx.navigationevent.NavigationEventInfo`. */
  private open class FakeNavigationEventInfo

  /**
   * Simulates `androidx.navigation3.scene.Scene`, which all Navigation 3 scenes (`SinglePaneScene`, `TwoPaneScene`, `ListDetailScene`,
   * `DialogScene`, etc.) implement.
   */
  private interface FakeScene<T : Any> {
    val entries: List<FakeNavEntry<T>>
  }

  /** Simulates `androidx.navigation3.runtime.NavEntry`, where `key` is a `private val` backing field rather than a public getter. */
  private class FakeNavEntry<T : Any>(
    @Suppress("unused") // Accessed via reflection in parseNavigationItem
    private val key: T
  )

  /** Simulates `androidx.navigation3.scene.SceneInfo`, which extends `NavigationEventInfo` and holds the active `scene: Scene<T>`. */
  private class FakeSceneInfo<T : Any>(
    @Suppress("unused") // Accessed via reflection in parseNavigationItem
    val scene: FakeScene<T>
  ) : FakeNavigationEventInfo()

  /** Simulates `androidx.navigation3.scene.SinglePaneScene`. */
  private class FakeSinglePaneScene<T : Any>(entry: FakeNavEntry<T>) : FakeScene<T> {
    override val entries: List<FakeNavEntry<T>> = listOf(entry)
  }

  /**
   * Simulates a two-pane scene (such as `TwoPaneScene` or `ListDetailScene`) where `entries` contains two side-by-side `NavEntry`
   * instances.
   */
  private class FakeTwoPaneScene<T : Any>(
    firstEntry: FakeNavEntry<T>,
    secondEntry: FakeNavEntry<T>,
  ) : FakeScene<T> {
    override val entries: List<FakeNavEntry<T>> = listOf(firstEntry, secondEntry)
  }

  /**
   * Simulates a multi-pane or dialog scene (such as `DialogScene` or a 3-pane adaptive layout) with an arbitrary list of active `entries`.
   */
  private class FakeMultiPaneScene<T : Any>(override val entries: List<FakeNavEntry<T>>) : FakeScene<T>

  // Plain Kotlin object without `data` modifier: inherits `java.lang.Object.toString()` ("...HomeRoute@hex").
  private object HomeRoute

  // Kotlin `data object`: overrides `toString()` to return `"ProfileRoute"`.
  private data object ProfileRoute

  // Parameterized `data class`: overrides `toString()` to return `"ProductRoute(id=1, category=Laptops)"`.
  private data class ProductRoute(val id: Int, val category: String = "General")

  // Lowercase class names to verify reflection does not depend on uppercase regex conventions.
  @Suppress("ClassName") private class lowercaseHomeRoute

  @Suppress("ClassName") private data class lowercaseProductRoute(val itemId: Int)

  @Test
  fun testParsePlainStringScreenName() {
    // Verifies that a plain String key is preserved as-is.
    val parsedItems = parseNavigationItem("HomeScreen")
    assertEquals(listOf(NavigationItemInfo(navKey = "HomeScreen")), parsedItems)
  }

  @Test
  fun testParsePlainObjectAndDataClassKeysDirectly() {
    // A plain Kotlin object (`object HomeRoute`) does not override `Object.toString()`.
    // Reflection detects that `toString()` is declared on `java.lang.Object` and uses `simpleName`.
    val parsedPlainObjectItems = parseNavigationItem(HomeRoute)
    assertEquals(listOf(NavigationItemInfo(navKey = "HomeRoute")), parsedPlainObjectItems)

    // A `data object` overrides `toString()` and returns its simple name directly.
    val parsedDataObjectItems = parseNavigationItem(ProfileRoute)
    assertEquals(listOf(NavigationItemInfo(navKey = "ProfileRoute")), parsedDataObjectItems)

    // A `data class` overrides `toString()` and includes its property names and values.
    val parsedDataClassItems = parseNavigationItem(ProductRoute(id = 42, category = "Books"))
    assertEquals(
      listOf(NavigationItemInfo(navKey = "ProductRoute(id=42, category=Books)")),
      parsedDataClassItems,
    )
  }

  @Test
  fun testParseLowercaseClassNames() {
    // Verifies that classes with lowercase names are extracted accurately via reflection without
    // relying on uppercase naming conventions.
    val parsedLowercaseClassItems = parseNavigationItem(lowercaseHomeRoute())
    assertEquals(listOf(NavigationItemInfo(navKey = "lowercaseHomeRoute")), parsedLowercaseClassItems)

    val parsedLowercaseDataClassItems = parseNavigationItem(lowercaseProductRoute(itemId = 7))
    assertEquals(
      listOf(NavigationItemInfo(navKey = "lowercaseProductRoute(itemId=7)")),
      parsedLowercaseDataClassItems,
    )
  }

  @Test
  fun testParseSinglePaneSceneInfoViaReflection() {
    // Simulates a SinglePaneScene wrapped in SceneInfo.
    // Reflection traverses SceneInfo.scene -> Scene.entries -> NavEntry.key (private field).
    val activeProductEntry = FakeNavEntry<Any>(key = ProductRoute(id = 2))
    val singlePaneSceneInfo = FakeSceneInfo(scene = FakeSinglePaneScene(entry = activeProductEntry))

    val parsedItems = parseNavigationItem(singlePaneSceneInfo)
    assertEquals(
      listOf(NavigationItemInfo(navKey = "ProductRoute(id=2, category=General)")),
      parsedItems,
    )
  }

  @Test
  fun testParseTwoPaneSceneInfoViaReflection() {
    // Simulates a TwoPaneScene where the first pane is a plain Kotlin object (inheriting Object.toString())
    // and the second pane is a parameterized data class. Both keys must be extracted cleanly in order.
    val firstPaneEntry = FakeNavEntry<Any>(key = HomeRoute)
    val secondPaneEntry = FakeNavEntry<Any>(key = ProductRoute(id = 1, category = "Phones"))
    val twoPaneSceneInfo =
      FakeSceneInfo(
        scene =
          FakeTwoPaneScene(
            firstEntry = firstPaneEntry,
            secondEntry = secondPaneEntry,
          )
      )

    val parsedItems = parseNavigationItem(twoPaneSceneInfo)
    assertEquals(
      listOf(
        NavigationItemInfo(navKey = "HomeRoute"),
        NavigationItemInfo(navKey = "ProductRoute(id=1, category=Phones)"),
      ),
      parsedItems,
    )
  }

  @Test
  fun testParseMultiPaneAndDialogScenesViaReflection() {
    // Verifies that any Scene implementation (such as DialogScene, ListDetailScene, or a 3-pane adaptive layout)
    // is supported automatically through Scene.entries regardless of the concrete Scene class name or pane count.
    val paneEntries =
      listOf(
        FakeNavEntry<Any>(key = HomeRoute),
        FakeNavEntry<Any>(key = ProductRoute(id = 10, category = "Tablets")),
        FakeNavEntry<Any>(key = ProfileRoute),
      )
    val threePaneSceneInfo = FakeSceneInfo(scene = FakeMultiPaneScene(entries = paneEntries))

    val parsedItems = parseNavigationItem(threePaneSceneInfo)
    assertEquals(
      listOf(
        NavigationItemInfo(navKey = "HomeRoute"),
        NavigationItemInfo(navKey = "ProductRoute(id=10, category=Tablets)"),
        NavigationItemInfo(navKey = "ProfileRoute"),
      ),
      parsedItems,
    )
  }

  @Test
  fun testParseCustomNavigationEventInfoSubclasses() {
    // Verifies that custom NavigationEventInfo subclasses (such as a plain object or data class
    // passed to NavigationBackHandler) are formatted cleanly via simpleName or toString().
    val plainCustomEventInfo = object : FakeNavigationEventInfo() {}
    // Anonymous object uses its enclosing/simple class name fallback.
    class CustomSettingsEventInfo : FakeNavigationEventInfo()
    data class CustomProfileEventInfo(val userId: String) : FakeNavigationEventInfo()

    assertEquals(
      listOf(NavigationItemInfo(navKey = "CustomSettingsEventInfo")),
      parseNavigationItem(CustomSettingsEventInfo()),
    )
    assertEquals(
      listOf(NavigationItemInfo(navKey = "CustomProfileEventInfo(userId=alice)")),
      parseNavigationItem(CustomProfileEventInfo(userId = "alice")),
    )
    assertEquals(1, parseNavigationItem(plainCustomEventInfo).size)
  }

  @Test
  fun testParseBlankStrings() {
    // Verifies that empty or whitespace strings return an empty list rather than an empty UI card.
    assertEquals(emptyList<NavigationItemInfo>(), parseNavigationItem(""))
    assertEquals(emptyList<NavigationItemInfo>(), parseNavigationItem("   "))
  }
}
