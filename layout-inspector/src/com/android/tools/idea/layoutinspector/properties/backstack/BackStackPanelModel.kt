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
package com.android.tools.idea.layoutinspector.properties.backstack

import com.android.tools.idea.layoutinspector.LayoutInspector
import com.android.tools.idea.layoutinspector.model.InspectorModel.SelectionListener
import com.android.tools.idea.layoutinspector.model.ViewNode
import com.android.tools.idea.layoutinspector.pipeline.appinspection.compose.ParameterGroupItem
import com.android.tools.idea.layoutinspector.pipeline.appinspection.compose.ShowMoreElementsItem
import com.android.tools.idea.layoutinspector.properties.InspectorPropertiesModel
import com.android.tools.idea.layoutinspector.properties.InspectorPropertyItem
import com.android.tools.property.panel.api.PropertiesModel
import com.android.tools.property.panel.api.PropertiesModelListener
import com.android.tools.property.panel.api.PropertiesTable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting

private const val NAV_DISPLAY = "NavDisplay"

/**
 * Model responsible for managing the navigation back stack state, detecting NavDisplay composables, asynchronously loading back stack
 * entries from the inspector agent, and extracting route details.
 *
 * @param propertiesModel The properties model to listen to for live property value updates.
 * @param initialBackStackList The initial list of navigation entries in chronological order.
 */
class BackStackPanelModel(
  private var propertiesModel: InspectorPropertiesModel? = null,
  initialBackStackList: List<String> = emptyList(),
) {

  fun interface BackStackModelListener {
    fun onModelChanged()
  }

  /** Current list of back stack items in chronological order. */
  var backStackList: List<String> = initialBackStackList
    private set

  /** Current filter string applied to back stack items. */
  var filter: String = ""
    private set

  /** Whether the currently selected view node is a NavDisplay component. */
  var isVisible: Boolean = false
    private set

  private val listeners = CopyOnWriteArrayList<BackStackModelListener>()

  private var layoutInspector: LayoutInspector? = null

  // Listener observing selection changes in the inspector model to show/hide the back stack panel
  private val selectionListener = SelectionListener { _, newView, _ ->
    updateVisibility(newView)
  }

  // Listener observing property value changes and generation events on the inspector properties model
  private val propertiesListener = BackStackPropertiesModelListener(::updateBackStackFromModel)

  init {
    propertiesModel?.addListener(propertiesListener)
  }

  fun addListener(listener: BackStackModelListener) {
    listeners.add(listener)
  }

  fun removeListener(listener: BackStackModelListener) {
    listeners.remove(listener)
  }

  private fun notifyListeners() {
    for (listener in listeners) {
      listener.onModelChanged()
    }
  }

  fun setFilter(filter: String) {
    if (this.filter != filter) {
      this.filter = filter
      notifyListeners()
    }
  }

  fun setToolContext(toolContext: LayoutInspector?) {
    layoutInspector?.inspectorModel?.removeSelectionListener(selectionListener)
    layoutInspector = toolContext
    layoutInspector?.inspectorModel?.addSelectionListener(selectionListener)
    updateVisibility(toolContext?.inspectorModel?.selection)
    updateBackStackFromModel()
  }

  fun attachPropertiesListener() {
    propertiesModel?.removeListener(propertiesListener)
    propertiesModel?.addListener(propertiesListener)
  }

  fun detachPropertiesListener() {
    propertiesModel?.removeListener(propertiesListener)
  }

  fun dispose() {
    setToolContext(null)
    propertiesModel?.removeListener(propertiesListener)
    propertiesModel = null
    listeners.clear()
  }

  private fun updateVisibility(view: ViewNode?) {
    val shouldShowNavDisplay = isNavDisplay(view)
    if (isVisible != shouldShowNavDisplay) {
      isVisible = shouldShowNavDisplay
      notifyListeners()
    }
  }

  /** Refreshes the back stack entries from the current properties model and notifies listeners if changed. */
  private fun updateBackStackFromModel() {
    // Locate the backStack parameter item (e.g. on NavDisplay) from the currently selected component's properties.
    val property = findBackStackProperty(propertiesModel?.properties)
    if (property !is ParameterGroupItem) {
      // If there is no composite backStack property found, refresh immediately (e.g. to show the empty state).
      refreshFromProperty(property)
      return
    }

    // In Compose inspection, iterable properties are lazily fetched and paginated from the device agent.
    // 1. Fetch all elements of the back stack list from the inspector agent if they are not fully loaded.
    loadAllBackStackElements(property) {
      // 2. Expand child entries (e.g. destination objects) so that nested route arguments (e.g. "id=1") are resolved.
      expandChildEntries(property) {
        // 3. Extract the formatted route strings from the resolved entries and notify listeners.
        refreshFromProperty(property)
      }
    }
  }

  /** Ensures all elements of the back stack list are loaded from the inspector. */
  private fun loadAllBackStackElements(property: ParameterGroupItem, onComplete: () -> Unit) {
    // In Compose inspection, iterable collections on the device are referenced via a ParameterReference.
    val reference = property.reference

    // A group needs fetching if its items haven't been loaded yet (empty) or if only an initial page
    // was fetched, ending with a ShowMoreElementsItem placeholder indicating additional entries exist.
    val needsLoading =
      synchronized(property) {
        property.children.isEmpty() || property.children.any { it is ShowMoreElementsItem }
      }

    if (reference == null || !needsLoading) {
      onComplete()
      return
    }

    // Determine the next index to request: 0 if no elements are loaded, or right after the last real child.
    val startIndex =
      synchronized(property) {
        if (property.children.isEmpty()) 0 else property.lastRealChildReferenceIndex + 1
      }

    // Request the agent to resolve all remaining items in the iterable up to Int.MAX_VALUE.
    property.lookup.resolve(property.rootId, reference, startIndex, Int.MAX_VALUE) { replacement, _ ->
      if (replacement != null) {
        synchronized(property) {
          property.applyReplacement(replacement)
        }
      }
      onComplete()
    }
  }

  /** Expands any child parameter groups (such as destinations with parameters) that have unexpanded references. */
  private fun expandChildEntries(property: ParameterGroupItem, onComplete: () -> Unit) {
    // Find all child items representing composite objects (e.g. data classes or navigation destinations)
    // whose property fields/arguments have not yet been fetched from the device agent.
    val unexpanded =
      synchronized(property) {
        property.children.filterIsInstance<ParameterGroupItem>().filter { it.children.isEmpty() && it.reference != null }
      }

    if (unexpanded.isEmpty()) {
      onComplete()
      return
    }

    // Expand all child groups concurrently and invoke onComplete once all async resolutions finish.
    val remaining = AtomicInteger(unexpanded.size)
    for (child in unexpanded) {
      child.expandWhenPossible {
        if (remaining.decrementAndGet() == 0) {
          onComplete()
        }
      }
    }
  }

  /** Extracts items from property and notifies listeners if the list has changed. */
  private fun refreshFromProperty(property: InspectorPropertyItem?) {
    // Guard against out-of-order asynchronous callbacks from superseded selections or property updates.
    if (property !== findBackStackProperty(propertiesModel?.properties)) return

    val newItems = extractBackStackItems(property)
    if (newItems != backStackList) {
      backStackList = newItems
      notifyListeners()
    }
  }

  /** Listener that observes changes on the properties model and triggers a UI update callback. */
  private class BackStackPropertiesModelListener(private val onUpdate: () -> Unit) : PropertiesModelListener<InspectorPropertyItem> {
    override fun propertyValuesChanged(model: PropertiesModel<InspectorPropertyItem>, childElementChanges: Boolean) {
      onUpdate()
    }

    override fun propertiesGenerated(model: PropertiesModel<InspectorPropertyItem>) {
      onUpdate()
    }
  }

  @TestOnly
  fun updateBackStackForTesting() {
    updateBackStackFromModel()
  }

  companion object {
    /** Checks whether a view node represents a NavDisplay composable. */
    fun isNavDisplay(view: ViewNode?): Boolean {
      return view?.unqualifiedName == NAV_DISPLAY
    }

    /** Finds the back stack parameter item in a properties table, supporting various naming conventions. */
    fun findBackStackProperty(properties: PropertiesTable<InspectorPropertyItem>?): InspectorPropertyItem? {
      if (properties == null) return null
      return properties.getOrNull("parameter", "backStack")
        ?: properties.getOrNull("parameter", "backstack")
        ?: properties.values.find {
          it.name.startsWith("backStack", ignoreCase = true)
        }
    }

    /**
     * Extracts serialized string representations for back stack entries from a property item.
     *
     * @param prop The property item representing the back stack parameter group.
     * @return The list of extracted back stack route strings in chronological order.
     */
    @VisibleForTesting
    fun extractBackStackItems(prop: InspectorPropertyItem?): List<String> {
      if (prop == null) return emptyList()
      if (prop is ParameterGroupItem) {
        val childrenSnapshot = synchronized(prop) { prop.children.toList() }
        if (childrenSnapshot.isNotEmpty()) {
          return childrenSnapshot
            .asSequence()
            .filter { it !is ShowMoreElementsItem }
            .map { formatBackStackEntry(it) }
            .filter { it.isNotBlank() }
            .toList()
        }
      }
      return emptyList()
    }

    /**
     * Formats a back stack entry item into a readable string representation, including its arguments if present.
     *
     * For example:
     * - Simple routes: `Home`
     * - Routes with parameters: `Product(id=1)`, `User(name=Giovanni, surname=Banana)`
     *
     * @param item The property item representing a single navigation entry.
     */
    @VisibleForTesting
    fun formatBackStackEntry(item: InspectorPropertyItem): String {
      // If the item is a composite object (e.g. a data class like Product(id=1)), format it with its class name and parameters.
      if (item is ParameterGroupItem) {
        val children = synchronized(item) { item.children.toList() }
        if (children.isNotEmpty()) {
          // In navigation frameworks, a wrapper entry (like NavBackStackEntry) often holds the route inside a "key" field.
          // If a "key" parameter is present, unwrap and format it directly.
          val keyParam = children.find { it.name.equals("key", ignoreCase = true) }
          if (keyParam != null) {
            // Recursively format the key property, which may be a simple string (e.g. "Home") or another composite
            // data class (e.g. "Product(id=1)"), unwrapping the actual route from its parent entry container.
            val formattedKey = formatBackStackEntry(keyParam)
            if (formattedKey.isNotBlank()) return formattedKey
          }

          // Filter out inspector UI pagination placeholders ("...") to keep only the real property parameters.
          val parameters = children.filter { it !is ShowMoreElementsItem }
          if (parameters.isNotEmpty()) {
            // Extract the class/type name (e.g. "Product" or "User"), ignoring numeric list indices like "0", "1".
            val className = item.snapshotValue ?: item.value ?: item.name.takeIf { name -> !name.all { char -> char.isDigit() } } ?: ""

            // Format each parameter as "name=value" (e.g. "id=1") or just "value" if the parameter name is numeric/unnamed.
            val formattedParameters = parameters.mapNotNull { param ->
              val paramValue = param.snapshotValue ?: param.value ?: formatBackStackEntry(param).takeIf { it.isNotBlank() }

              if (!paramValue.isNullOrBlank()) {
                val isNumericIndex = param.name.all { char -> char.isDigit() }
                if (param.name.isNotBlank() && !isNumericIndex) {
                  "${param.name}=$paramValue"
                } else {
                  paramValue
                }
              } else {
                null
              }
            }

            // Combine the class name and parameters into "ClassName(param1=val1, param2=val2)".
            if (formattedParameters.isNotEmpty()) {
              if (className.isNotBlank() && !className.contains('(')) {
                return "$className(${formattedParameters.joinToString(", ")})"
              }
            }
          }
        }
      }

      // Fallback for primitive or singleton entries (e.g. "Home"): return the value or name, ignoring numeric list indices.
      val isNumericIndex = item.name.all { char -> char.isDigit() }
      return item.snapshotValue ?: item.value ?: item.name.takeIf { !isNumericIndex } ?: ""
    }
  }
}
