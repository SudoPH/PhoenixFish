package com.phoenix.fish.api;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.manager.FishManager;
import com.phoenix.fish.model.CustomFish;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Public API for other plugins to interact with PhoenixFish.
 */
public class PhoenixFishAPI {

    private static PhoenixFish plugin;

    /**
     * Initializes the API. Called internally by PhoenixFish on enable.
     */
    public static void init(PhoenixFish main) {
        plugin = main;
    }

    /**
     * Shuts down the API and clears static references.
     * Called internally by PhoenixFish on disable to prevent memory leaks during
     * /reload.
     */
    public static void shutdown() {
        plugin = null;
    }

    /**
     * Gets a CustomFish by its unique ID.
     *
     * @param id The ID of the fish (e.g., "shark", "sea_bass").
     * @return The {@link CustomFish} object, or null if not found or API is
     *         disabled.
     */
    public static CustomFish getFishById(String id) {
        if (plugin == null || id == null)
            return null;
        return plugin.getFishManager().getFishById(id);
    }

    /**
     * Checks if an ItemStack is a custom PhoenixFish.
     *
     * @param item The item to check.
     * @return true if the item is a custom fish, false otherwise.
     */
    public static boolean isCustomFish(ItemStack item) {
        return getFishFromItem(item) != null;
    }

    /**
     * Gets the CustomFish data directly from an ItemStack.
     * Reads the hidden PersistentDataContainer tag.
     *
     * @param item The item to check.
     * @return The {@link CustomFish} object, or null if it's not a custom fish.
     */
    public static CustomFish getFishFromItem(ItemStack item) {
        if (plugin == null || item == null || !item.hasItemMeta())
            return null;

        ItemMeta meta = item.getItemMeta();
        FishManager manager = plugin.getFishManager();
        if (manager == null)
            return null;

        NamespacedKey fishKey = manager.getFishKey();
        String fishId = meta.getPersistentDataContainer().get(fishKey, PersistentDataType.STRING);

        if (fishId == null)
            return null;

        return manager.getFishById(fishId);
    }
}