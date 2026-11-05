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
private const val PARAMETER_NAMESPACE = "parameter"
private const val BACK_STACK_PARAMETER = "backStack"
private const val ENTRIES_PARAMETER = "entries"
private const val SCENE_STATE_PARAMETER = "sceneState"
private const val NAV_ENTRY_CLASS_NAME = "NavEntry"
private const val KEY_PARAMETER = "key"
private const val CONTENT_KEY_PARAMETER = "contentKey"

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
    // Locate the navigation stack parameter item (backStack, entries, or sceneState) from the selected component
    val property = findBackStackProperty(propertiesModel?.properties)
    if (property !is ParameterGroupItem) {
      // If there is no composite back stack property found, refresh immediately to show the empty state
      refreshFromProperty(property)
      return
    }

    // Resolve the target iterable list group (unwrapping SceneState.entries when using the sceneState overload)
    resolveBackStackListGroupAsync(property) { resolvedListGroup ->
      if (resolvedListGroup == null) {
        refreshFromProperty(property)
        return@resolveBackStackListGroupAsync
      }

      // Fetch all elements of the back stack list from the inspector agent if they are not fully loaded
      loadAllBackStackElements(resolvedListGroup) {
        // Expand child entries and nested route keys so destination arguments are resolved
        expandChildEntries(resolvedListGroup) {
          // Extract the formatted route strings from the resolved entries and notify listeners
          refreshFromProperty(property)
        }
      }
    }
  }

  /**
   * Resolves the iterable back stack list group from a matched `NavDisplay` property, expanding a `SceneState` wrapper first if its
   * children have not been fetched yet.
   */
  private fun resolveBackStackListGroupAsync(
    property: ParameterGroupItem,
    onResolved: (ParameterGroupItem?) -> Unit,
  ) {
    if (!isSceneStateGroup(property)) {
      onResolved(property)
      return
    }

    // Check if the SceneState group needs to be expanded to access its child entries list
    val requiresExpansion =
      synchronized(property) {
        property.children.isEmpty() && property.reference != null
      }

    if (!requiresExpansion) {
      onResolved(findChildGroupByName(property, ENTRIES_PARAMETER))
      return
    }

    // Expand the SceneState container first and then locate its child entries list
    property.expandWhenPossible {
      onResolved(findChildGroupByName(property, ENTRIES_PARAMETER))
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

  /** Expands any child parameter groups (such as `NavEntry` wrappers and destination objects) that have unexpanded references. */
  private fun expandChildEntries(property: ParameterGroupItem, onComplete: () -> Unit) {
    // Find all direct child items representing composite objects whose fields have not yet been fetched
    val unexpandedDirectChildren =
      synchronized(property) {
        property.children.filterIsInstance<ParameterGroupItem>().filter { childGroup ->
          childGroup.children.isEmpty() && childGroup.reference != null
        }
      }

    expandGroupItems(unexpandedDirectChildren) {
      // After direct children (such as NavEntry wrappers) are expanded, expand any nested composite route key groups
      val unexpandedNestedKeyGroups = collectUnexpandedNavEntryKeyGroups(property)
      expandGroupItems(unexpandedNestedKeyGroups, onComplete)
    }
  }

  /** Collects unexpanded `key` or `contentKey` parameter groups nested inside `NavEntry` elements. */
  private fun collectUnexpandedNavEntryKeyGroups(listProperty: ParameterGroupItem): List<ParameterGroupItem> {
    val navEntryGroups =
      synchronized(listProperty) {
        listProperty.children.filterIsInstance<ParameterGroupItem>().filter { childGroup ->
          isNavEntryGroup(childGroup)
        }
      }
    return navEntryGroups.flatMap { navEntryGroup ->
      synchronized(navEntryGroup) {
        navEntryGroup.children.filterIsInstance<ParameterGroupItem>().filter { nestedGroup ->
          (nestedGroup.name.equals(KEY_PARAMETER, ignoreCase = true) ||
            nestedGroup.name.equals(CONTENT_KEY_PARAMETER, ignoreCase = true)) &&
            nestedGroup.children.isEmpty() &&
            nestedGroup.reference != null
        }
      }
    }
  }

  /** Expands a list of parameter group items concurrently and invokes [onComplete] when all finish. */
  private fun expandGroupItems(groupItems: List<ParameterGroupItem>, onComplete: () -> Unit) {
    if (groupItems.isEmpty()) {
      onComplete()
      return
    }

    // Expand all groups concurrently and invoke onComplete once all async resolutions finish
    val remainingExpansions = AtomicInteger(groupItems.size)
    for (groupItem in groupItems) {
      groupItem.expandWhenPossible {
        if (remainingExpansions.decrementAndGet() == 0) {
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

    /**
     * Finds the back stack parameter item in a properties table across all `NavDisplay` overloads (`backStack`, `entries`, and
     * `sceneState`).
     */
    fun findBackStackProperty(properties: PropertiesTable<InspectorPropertyItem>?): InspectorPropertyItem? {
      if (properties == null) return null
      // Match the direct backStack parameter first, then fall back to entries and sceneState overloads
      return properties.getOrNull(PARAMETER_NAMESPACE, BACK_STACK_PARAMETER)
        ?: properties.values.find { propertyItem ->
          propertyItem.name.startsWith(BACK_STACK_PARAMETER, ignoreCase = true)
        }
        ?: properties.getOrNull(PARAMETER_NAMESPACE, ENTRIES_PARAMETER)
        ?: properties.values.find { propertyItem ->
          propertyItem.name.equals(ENTRIES_PARAMETER, ignoreCase = true)
        }
        ?: properties.getOrNull(PARAMETER_NAMESPACE, SCENE_STATE_PARAMETER)
        ?: properties.values.find { propertyItem ->
          propertyItem.name.equals(SCENE_STATE_PARAMETER, ignoreCase = true)
        }
    }

    /**
     * Extracts serialized string representations for back stack entries from a property item.
     *
     * @param prop The property item representing the back stack, entries, or sceneState parameter group.
     * @return The list of extracted back stack route strings in chronological order.
     */
    @VisibleForTesting
    fun extractBackStackItems(prop: InspectorPropertyItem?): List<String> {
      if (prop !is ParameterGroupItem) return emptyList()
      // Unwrap the child entries list when extracting from a SceneState parameter group
      val backStackListGroup =
        if (isSceneStateGroup(prop)) {
          findChildGroupByName(prop, ENTRIES_PARAMETER) ?: return emptyList()
        } else {
          prop
        }

      val childrenSnapshot = synchronized(backStackListGroup) { backStackListGroup.children.toList() }
      if (childrenSnapshot.isNotEmpty()) {
        return childrenSnapshot
          .asSequence()
          .filter { childItem -> childItem !is ShowMoreElementsItem }
          .map { childItem -> formatBackStackEntry(childItem) }
          .filter { formattedEntry -> formattedEntry.isNotBlank() }
          .toList()
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
          // Only unwrap the route "key" (or "contentKey" fallback) when the group is a NavEntry wrapper,
          // so user route classes that declare their own "key" property preserve their type and sibling fields
          if (isNavEntryGroup(item)) {
            val keyParameter = children.find { childItem -> childItem.name.equals(KEY_PARAMETER, ignoreCase = true) }
            if (keyParameter != null) {
              val formattedKey = formatNavEntryKey(keyParameter)
              if (formattedKey.isNotBlank()) return formattedKey
            }
            val contentKeyParameter = children.find { childItem -> childItem.name.equals(CONTENT_KEY_PARAMETER, ignoreCase = true) }
            if (contentKeyParameter != null) {
              val formattedContentKey = formatNavEntryKey(contentKeyParameter)
              if (formattedContentKey.isNotBlank()) return formattedContentKey
            }
            return ""
          }

          // Filter out inspector UI pagination placeholders ("...") to keep only the real property parameters.
          val parameters = children.filter { it !is ShowMoreElementsItem }
          if (parameters.isNotEmpty()) {
            // Extract the class/type name (e.g. "Product" or "User"), ignoring numeric list indices like "0", "1".
            val className = extractGroupClassName(item)

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

    /** Formats the route key item extracted from a `NavEntry` wrapper without falling back to the property name. */
    private fun formatNavEntryKey(keyItem: InspectorPropertyItem): String {
      if (keyItem is ParameterGroupItem) {
        val hasChildren = synchronized(keyItem) { keyItem.children.isNotEmpty() }
        if (hasChildren) {
          return formatBackStackEntry(keyItem)
        }
      }
      return keyItem.snapshotValue ?: keyItem.value ?: ""
    }

    /** Extracts the class or type name of a parameter group, ignoring numeric list indices. */
    private fun extractGroupClassName(groupItem: ParameterGroupItem): String {
      val nonIndexName = groupItem.name.takeIf { candidateName -> !candidateName.all { character -> character.isDigit() } }
      return groupItem.snapshotValue ?: groupItem.value ?: nonIndexName ?: ""
    }

    /** Checks whether a parameter group represents a Navigation 3 `NavEntry` wrapper. */
    private fun isNavEntryGroup(groupItem: ParameterGroupItem): Boolean {
      val groupClassName = extractGroupClassName(groupItem)
      return groupClassName.equals(NAV_ENTRY_CLASS_NAME, ignoreCase = true) ||
        groupClassName.substringAfterLast('.').equals(NAV_ENTRY_CLASS_NAME, ignoreCase = true)
    }

    /** Checks whether a parameter group represents a Navigation 3 `SceneState` container. */
    private fun isSceneStateGroup(groupItem: ParameterGroupItem): Boolean {
      val groupClassName = extractGroupClassName(groupItem)
      return groupItem.name.equals(SCENE_STATE_PARAMETER, ignoreCase = true) ||
        groupClassName.equals(SCENE_STATE_PARAMETER, ignoreCase = true) ||
        groupClassName.substringAfterLast('.').equals(SCENE_STATE_PARAMETER, ignoreCase = true)
    }

    /** Finds a child [ParameterGroupItem] matching [childName] case-insensitively. */
    private fun findChildGroupByName(parentGroup: ParameterGroupItem, childName: String): ParameterGroupItem? {
      return synchronized(parentGroup) {
        parentGroup.children.filterIsInstance<ParameterGroupItem>().find { childGroup ->
          childGroup.name.equals(childName, ignoreCase = true)
        }
      }
    }
  }
}
