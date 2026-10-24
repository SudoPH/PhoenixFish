package com.phoenix.fish.api;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.event.FishCaughtEvent;
import com.phoenix.fish.event.FishSellEvent;
import com.phoenix.fish.manager.CraftingManager;
import com.phoenix.fish.manager.FishManager;
import com.phoenix.fish.manager.ItemFixer;
import com.phoenix.fish.model.CustomFish;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

public class PhoenixFishAPI {

    private final PhoenixFish plugin;

    public PhoenixFishAPI(PhoenixFish plugin) {
        this.plugin = plugin;
    }

    public CustomFish getFishById(String id) {
        if (id == null)
            return null;
        FishManager manager = plugin.getFishManager();
        return manager != null ? manager.getFishById(id) : null;
    }

    public CustomFish getFishFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return null;

        ItemMeta meta = item.getItemMeta();
        FishManager manager = plugin.getFishManager();
        if (manager == null)
            return null;

        NamespacedKey fishKey = manager.getFishKey();
        if (fishKey == null)
            return null;

        String fishId = meta.getPersistentDataContainer().get(fishKey, PersistentDataType.STRING);
        if (fishId == null)
            return null;

        return manager.getFishById(fishId);
    }

    public double getBalance(UUID uuid) {
        if (uuid == null || plugin.getCacheManager() == null)
            return 0.0;
        FishingData data = plugin.getCacheManager().getData(uuid);
        return data != null ? data.getBalance() : 0.0;
    }

    public boolean depositPlayer(UUID uuid, double amount) {
        if (uuid == null || amount <= 0 || plugin.getCacheManager() == null)
            return false;

        FishingData data = plugin.getCacheManager().getData(uuid);
        if (data != null) {
            data.addBalance(amount);
            return true;
        }
        return false;
    }

    public boolean withdrawPlayer(UUID uuid, double amount) {
        if (uuid == null || amount <= 0 || plugin.getCacheManager() == null)
            return false;

        FishingData data = plugin.getCacheManager().getData(uuid);
        if (data != null) {
            return data.withdrawBalance(amount);
        }
        return false;
    }

    public void triggerFishCaught(Player player, CustomFish fish, double weight) {
        if (player == null || fish == null)
            return;
        FishCaughtEvent event = new FishCaughtEvent(player, fish, weight);
        Bukkit.getPluginManager().callEvent(event);
    }

    public void triggerFishSell(Player player, double amount, int fishCount) {
        if (player == null || amount <= 0 || fishCount <= 0)
            return;
        FishSellEvent event = new FishSellEvent(player, amount, fishCount);
        Bukkit.getPluginManager().callEvent(event);
    }

    // =====================================================================
    //  Crafting API — consumed by PhoenixCraft (or any external plugin)
    // =====================================================================

    /**
     * @return true if the XP system is enabled and crafting is meaningful.
     */
    public boolean isCraftingAvailable() {
        return plugin.isXpSystemEnabled() && plugin.getCraftingManager() != null;
    }

    /**
     * If crafting is unavailable, return a stable machine-readable reason code.
     * PhoenixCraft (and other consumers) can use this to display a meaningful
     * message to the player, instead of silently showing an empty filter result.
     *
     * <p>Possible return values:</p>
     * <ul>
     *   <li><b>{@code null}</b> — crafting is available (i.e.
     *       {@link #isCraftingAvailable()} returns true).</li>
     *   <li><b>{@code "xp-system-disabled"}</b> — the XP system is disabled in
     *       PhoenixFish's {@code config.yml} ({@code xp-system.enabled: false}).
     *       This is the most common cause: every {@code canCraft()} call will
     *       return {@code false}, so a CRAFTABLE filter will silently exclude
     *       every recipe.</li>
     *   <li><b>{@code "no-crafting-manager"}</b> — the CraftingManager failed
     *       to initialize (e.g. {@code custom_recipes.yml} parse error). The
     *       recipe list itself is empty.</li>
     * </ul>
     *
     * @return reason code, or {@code null} if crafting is fully available.
     * @since 1.3.4
     */
    public String getCraftingUnavailableReason() {
        if (plugin.getCraftingManager() == null) {
            return "no-crafting-manager";
        }
        if (!plugin.isXpSystemEnabled()) {
            return "xp-system-disabled";
        }
        return null;
    }

    /**
     * Get all valid recipe category strings produced by this PhoenixFish build.
     * Useful for external plugins (e.g. PhoenixCraft) that want to render
     * filter buttons dynamically instead of hardcoding {@code "rod"}/
     * {@code "bait"}/{@code "misc"}.
     *
     * <p>Categories are derived from the distinct {@code category()} values
     * across all currently-loaded recipes — so the list reflects what is
     * actually defined in {@code custom_recipes.yml}, not a hardcoded set.
     * If no recipes are loaded, returns a list containing only the default
     * categories: {@code ["bait", "rod", "misc"]}.</p>
     *
     * @return unmodifiable list of category strings, never empty, never null.
     * @since 1.3.4
     */
    public List<String> getCraftingCategories() {
        CraftingManager mgr = plugin.getCraftingManager();
        if (mgr == null || mgr.getAllRecipes().isEmpty()) {
            return List.of("bait", "rod", "misc");
        }
        java.util.LinkedHashSet<String> cats = new java.util.LinkedHashSet<>();
        for (CraftRecipe r : mgr.getAllRecipes()) {
            if (r.category() != null && !r.category().isBlank()) {
                cats.add(r.category());
            }
        }
        if (cats.isEmpty()) {
            return List.of("bait", "rod", "misc");
        }
        return List.copyOf(cats);
    }

    /**
     * Get all loaded crafting recipes.
     */
    public List<CraftRecipe> getCraftingRecipes() {
        CraftingManager mgr = plugin.getCraftingManager();
        return mgr != null ? mgr.getAllRecipes() : List.of();
    }

    /**
     * Get all recipes in a given category (e.g. "rod", "bait").
     */
    public List<CraftRecipe> getCraftingRecipesByCategory(String category) {
        CraftingManager mgr = plugin.getCraftingManager();
        return mgr != null ? mgr.getRecipesByCategory(category) : List.of();
    }

    /**
     * Look up a single recipe by id (case-insensitive).
     */
    public CraftRecipe getCraftingRecipe(String id) {
        CraftingManager mgr = plugin.getCraftingManager();
        return mgr != null ? mgr.getRecipe(id) : null;
    }

    /**
     * Check whether the player can craft this recipe right now
     * (level + inventory check). Does NOT consume anything.
     */
    public boolean canCraft(Player player, CraftRecipe recipe) {
        CraftingManager mgr = plugin.getCraftingManager();
        return mgr != null && mgr.canCraft(player, recipe);
    }

    /**
     * Attempt to craft. On SUCCESS, ingredients are consumed and the
     * result is added to the player's inventory (overflow dropped).
     * XP is awarded and crafting level-ups are processed automatically.
     */
    public CraftResult craft(Player player, CraftRecipe recipe) {
        CraftingManager mgr = plugin.getCraftingManager();
        return mgr != null ? mgr.craft(player, recipe) : CraftResult.NO_DATA;
    }

    /**
     * Compute the maximum number of times this recipe can be crafted
     * with the player's current inventory (and crafting level).
     *
     * @since 1.3.3
     */
    public int maxCraftable(Player player, CraftRecipe recipe) {
        CraftingManager mgr = plugin.getCraftingManager();
        return mgr != null ? mgr.maxCraftable(player, recipe) : 0;
    }

    /**
     * Attempt to craft up to {@code maxCount} times in a single operation.
     * Bulk crafting is more efficient than looping {@link #craft} — XP is
     * awarded once and only one level-up notification fires.
     *
     * @return a {@link CraftBulkResult} carrying the actual count crafted,
     *         total XP, and levels gained.
     * @since 1.3.3
     */
    public CraftBulkResult craftBulk(Player player, CraftRecipe recipe, int maxCount) {
        CraftingManager mgr = plugin.getCraftingManager();
        return mgr != null ? mgr.craftBulk(player, recipe, maxCount) : CraftBulkResult.empty(CraftResult.NO_DATA);
    }

    /**
     * Player's current crafting level (1 if XP system disabled).
     */
    public int getCraftingLevel(UUID uuid) {
        if (uuid == null || plugin.getCacheManager() == null) return 1;
        FishingData data = plugin.getCacheManager().getData(uuid);
        return data != null ? data.getCraftingLevel() : 1;
    }

    /**
     * Player's current crafting XP within the current level.
     */
    public int getCraftingXp(UUID uuid) {
        if (uuid == null || plugin.getCacheManager() == null) return 0;
        FishingData data = plugin.getCacheManager().getData(uuid);
        return data != null ? data.getCraftingXp() : 0;
    }

    /**
     * XP required for the player to reach the next crafting level.
     * Faz 0.9.0: Uses exponential formula from config.
     */
    public int getCraftingXpRequired(UUID uuid) {
        if (uuid == null || plugin.getCacheManager() == null) return 100;
        FishingData data = plugin.getCacheManager().getData(uuid);
        if (data == null) return 100;
        // Use the new exponential formula from FishingData
        return data.getCraftingXpRequired();
    }

    /**
     * Add crafting XP to a player (level-ups processed automatically).
     */
    public void addCraftingXp(Player player, int amount) {
        if (player == null || amount <= 0) return;
        if (plugin.getCacheManager() == null) return;
        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null) return;
        data.addCraftingXp(amount);
        int leveled = data.checkAllCraftingLevelUps();
        if (leveled > 0) {
            player.sendMessage(plugin.getMessageManager().getMessage("crafting_level_up", true,
                    java.util.Map.of("%level%", String.valueOf(data.getCraftingLevel()))));
            try {
                player.playSound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Create a rod item by rod-id (reads rods.yml definition).
     */
    public ItemStack createRodItem(String rodId) {
        if (rodId == null || plugin.getFishingListener() == null) return null;
        ItemFixer fixer = plugin.getFishingListener().getItemFixer();
        if (fixer == null) return null;
        ConfigurationSection sec = fixer.getRodConfig(rodId);
        if (sec == null) return null;
        return fixer.createRodItem(sec);
    }

    /**
     * Create a bait item by bait-id (reads baits.yml definition).
     */
    public ItemStack createBaitItem(String baitId) {
        if (baitId == null || plugin.getBaitManager() == null) return null;
        return plugin.getBaitManager().getBaitItem(baitId);
    }
}
