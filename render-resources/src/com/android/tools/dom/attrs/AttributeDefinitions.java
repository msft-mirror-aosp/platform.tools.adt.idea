package com.android.tools.dom.attrs;

import com.android.ide.common.rendering.api.ResourceReference;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Information about attr and styleable resources in an easy to consume form.
 */
public interface AttributeDefinitions {
  @Nullable
  StyleableDefinition getStyleableDefinition(@NotNull ResourceReference styleable);

  /**
   * Returns the {@link StyleableDefinition} for the given styleable name, checking project/library
   * ({@code RES_AUTO}) styleables before falling back to framework ({@code ANDROID}) styleables.
   */
  @Nullable
  StyleableDefinition getStyleableByName(@NotNull String name);

  @NotNull
  Set<ResourceReference> getAttrs();

  @Nullable
  AttributeDefinition getAttrDefinition(@NotNull ResourceReference attr);

  /**
   * Returns the {@link AttributeDefinition} for the given attribute name (an {@code "android:"}
   * prefix resolves to {@code ANDROID}; an unprefixed name resolves to {@code ANDROID} when this
   * instance contains only framework attributes, or {@code RES_AUTO} otherwise).
   */
  @Nullable
  AttributeDefinition getAttrDefByName(@NotNull String name);

  @Nullable
  String getAttrGroup(@NotNull ResourceReference attr);
}
