package com.phoenix.fish.api;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;

/**
 * Cross-plugin data transfer object representing a crafting recipe.
 * PhoenixCraft (or any external consumer) reads recipes from PhoenixFish
 * via {@link PhoenixFishAPI#getCraftingRecipes()}.
 *
 * <p>Immutable — fields are final and collections are unmodifiable views.</p>
 */
public record CraftRecipe(
        String id,
        String category,
        ItemStack resultItem,
        List<Ingredient> ingredients,
        int craftingLevelRequirement,
        int xpReward) {

    /**
     * Single ingredient slot definition.
     *
     * @param item        template item (material + optional custom-model-data + display info)
     * @param amount      how many of this item the recipe consumes
     */
    public record Ingredient(ItemStack item, int amount) {
    }

    public CraftRecipe {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("recipe id must not be blank");
        }
        if (resultItem == null) {
            throw new IllegalArgumentException("result item must not be null");
        }
        if (category == null || category.isBlank()) {
            category = "misc";
        }
        ingredients = ingredients == null ? List.of() : Collections.unmodifiableList(ingredients);
    }
}
