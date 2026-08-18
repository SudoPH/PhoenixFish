package com.phoenix.fish.api;

import org.bukkit.inventory.ItemStack;

public interface CraftAPI {

    void registerRecipe(ItemStack[] matrix, ItemStack result);

    boolean isCraftingEnabled();
}