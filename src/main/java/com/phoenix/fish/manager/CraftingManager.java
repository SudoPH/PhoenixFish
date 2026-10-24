package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.api.CraftBulkResult;
import com.phoenix.fish.api.CraftRecipe;
import com.phoenix.fish.api.CraftResult;
import com.phoenix.fish.data.FishingData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CraftingManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;

    private final NamespacedKey LUCK_KEY;
    private final NamespacedKey BAIT_KEY;

    private final Map<String, CraftRecipe> recipes = new ConcurrentHashMap<>();

    public CraftingManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.LUCK_KEY = new NamespacedKey(plugin, "luck_multiplier");
        this.BAIT_KEY = new NamespacedKey(plugin, "bait_id");
    }

    public void loadRecipes() {
        recipes.clear();

        File file = new File(plugin.getDataFolder(), "custom_recipes.yml");
        if (!file.exists()) {
            plugin.saveResource("custom_recipes.yml", false);
            file = new File(plugin.getDataFolder(), "custom_recipes.yml");
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection recipesSec = config.getConfigurationSection("recipes");
        if (recipesSec == null) {
            plugin.getLogger().warning(plugin.getMessageManager().getPlainMessage("craft_recipes_section_not_found"));
            return;
        }

        int count = 0;
        for (String recipeId : recipesSec.getKeys(false)) {
            ConfigurationSection recipeSec = recipesSec.getConfigurationSection(recipeId);
            if (recipeSec == null) continue;

            try {
                CraftRecipe recipe = parseRecipe(recipeId.toLowerCase(), recipeSec);
                if (recipe != null) {
                    recipes.put(recipeId.toLowerCase(), recipe);
                    count++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error loading recipe " + recipeId + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + count + " crafting recipes from custom_recipes.yml.");
    }

    private CraftRecipe parseRecipe(String id, ConfigurationSection recipeSec) {
        ConfigurationSection resultSec = recipeSec.getConfigurationSection("result");
        if (resultSec == null) {
            plugin.getLogger().warning("Recipe " + id + " missing result section");
            return null;
        }

        ItemStack resultItem = createItem(resultSec);
        if (resultItem == null) {
            plugin.getLogger().warning("Recipe " + id + " result item creation failed");
            return null;
        }

        List<CraftRecipe.Ingredient> ingredients = parseIngredients(recipeSec);
        if (ingredients == null) {
            plugin.getLogger().warning("Recipe " + id + " has invalid shape");
            return null;
        }

        int craftingLevelReq = recipeSec.getInt("crafting-level-requirement", 0);
        int xpReward = recipeSec.getInt("xp-reward", 10);
        String category = detectCategory(resultSec, resultItem);

        return new CraftRecipe(id, category, resultItem, ingredients, craftingLevelReq, xpReward);
    }

    private List<CraftRecipe.Ingredient> parseIngredients(ConfigurationSection recipeSec) {
        List<String> shapeList = new ArrayList<>(recipeSec.getStringList("shape"));
        ConfigurationSection ings = recipeSec.getConfigurationSection("ingredients");

        if (ings == null || shapeList.isEmpty() || shapeList.size() > 3) {
            return null;
        }

        while (shapeList.size() < 3) {
            shapeList.add("   ");
        }

        // Count how many of each character are needed
        Map<Character, Integer> charCounts = new LinkedHashMap<>();
        for (String row : shapeList) {
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                if (c != ' ') {
                    charCounts.merge(c, 1, Integer::sum);
                }
            }
        }

        List<CraftRecipe.Ingredient> ingredients = new ArrayList<>();
        for (Map.Entry<Character, Integer> entry : charCounts.entrySet()) {
            ConfigurationSection ingSec = ings.getConfigurationSection(String.valueOf(entry.getKey()));
            if (ingSec == null) {
                plugin.getLogger().warning("Recipe ingredient '" + entry.getKey() + "' not defined in ingredients section");
                return null;
            }
            ItemStack template = createItem(ingSec);
            if (template == null) {
                return null;
            }
            ingredients.add(new CraftRecipe.Ingredient(template, entry.getValue()));
        }

        return ingredients;
    }

    private String detectCategory(ConfigurationSection resultSec, ItemStack resultItem) {
        // If the result has a bait-id, it's a bait
        if (resultSec.contains("bait-id")) {
            return "bait";
        }
        // If the result is a fishing rod, it's a rod
        if (resultItem.getType() == Material.FISHING_ROD) {
            return "rod";
        }
        return "misc";
    }

    @SuppressWarnings("deprecation")
    private ItemStack createItem(ConfigurationSection sec) {
        String matStr = sec.getString("material", "STONE").toUpperCase();
        Material mat = Material.matchMaterial(matStr);
        if (mat == null) {
            plugin.getLogger().warning("Invalid material: " + matStr);
            mat = Material.STONE;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (sec.contains("display-name")) {
            String displayNameStr = sec.getString("display-name");
            if (displayNameStr != null) {
                meta.displayName(miniMessage.deserialize(displayNameStr));
            }
        }

        if (sec.contains("lore")) {
            List<Component> loreComponents = new ArrayList<>();
            for (String line : sec.getStringList("lore")) {
                loreComponents.add(miniMessage.deserialize(line));
            }
            meta.lore(loreComponents);
        }

        ItemUtils.applyCustomModelData(meta, sec);

        if (sec.contains("luck-multiplier")) {
            meta.getPersistentDataContainer().set(LUCK_KEY, PersistentDataType.DOUBLE,
                    sec.getDouble("luck-multiplier"));
        }

        if (sec.contains("bait-id")) {
            String baitId = sec.getString("bait-id");
            if (baitId != null) {
                meta.getPersistentDataContainer().set(BAIT_KEY, PersistentDataType.STRING, baitId);
            }
        }

        item.setItemMeta(meta);
        return item;
    }

    // =====================================================================
    //  Public API — also called externally via PhoenixFishAPI
    // =====================================================================

    public CraftRecipe getRecipe(String id) {
        if (id == null) return null;
        return recipes.get(id.toLowerCase());
    }

    public List<CraftRecipe> getAllRecipes() {
        return List.copyOf(recipes.values());
    }

    public List<CraftRecipe> getRecipesByCategory(String category) {
        if (category == null) return List.of();
        List<CraftRecipe> out = new ArrayList<>();
        for (CraftRecipe r : recipes.values()) {
            if (category.equalsIgnoreCase(r.category())) {
                out.add(r);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Check whether the player can craft this recipe right now
     * (level + inventory check). Does NOT consume anything.
     */
    public boolean canCraft(Player player, CraftRecipe recipe) {
        if (player == null || recipe == null) return false;

        if (!plugin.isXpSystemEnabled() || plugin.getCacheManager() == null) {
            return false;
        }
        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null) return false;

        if (data.getCraftingLevel() < recipe.craftingLevelRequirement()) {
            return false;
        }

        return hasIngredients(player, recipe);
    }

    /**
     * Check if the player has all required ingredients.
     * Faz 0.9.0: single-pass optimization — counts all materials in one
     * inventory scan instead of N separate scans.
     */
    private boolean hasIngredients(Player player, CraftRecipe recipe) {
        // Build a map of needed materials
        java.util.Map<ItemFixer.ItemIdentifier, Integer> needed = new java.util.HashMap<>();
        for (CraftRecipe.Ingredient ing : recipe.ingredients()) {
            ItemFixer.ItemIdentifier id = new ItemFixer.ItemIdentifier(
                    ing.item().getType(), ItemUtils.getCMD(ing.item()));
            needed.merge(id, ing.amount(), Integer::sum);
        }

        // Single inventory scan
        java.util.Map<ItemFixer.ItemIdentifier, Integer> owned = new java.util.HashMap<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i <= 35 && i < contents.length; i++) {
            ItemStack inv = contents[i];
            if (inv == null || inv.getType().isAir()) continue;
            ItemFixer.ItemIdentifier id = new ItemFixer.ItemIdentifier(
                    inv.getType(), ItemUtils.getCMD(inv));
            owned.merge(id, inv.getAmount(), Integer::sum);
        }

        // Check if we have enough of each
        for (Map.Entry<ItemFixer.ItemIdentifier, Integer> entry : needed.entrySet()) {
            Integer have = owned.get(entry.getKey());
            if (have == null || have < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compute the maximum number of times this recipe can be crafted with the
     * player's current inventory. Returns 0 if level requirement is not met
     * or any ingredient is missing.
     *
     * @since 1.3.3 (Faz 0.0.3 — bulk craft support)
     */
    public int maxCraftable(Player player, CraftRecipe recipe) {
        if (player == null || recipe == null) return 0;

        if (!plugin.isXpSystemEnabled() || plugin.getCacheManager() == null) {
            return 0;
        }
        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null) return 0;

        if (data.getCraftingLevel() < recipe.craftingLevelRequirement()) {
            return 0;
        }

        // Faz 0.9.0: single-pass inventory scan
        java.util.Map<ItemFixer.ItemIdentifier, Integer> owned = new java.util.HashMap<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i <= 35 && i < contents.length; i++) {
            ItemStack inv = contents[i];
            if (inv == null || inv.getType().isAir()) continue;
            ItemFixer.ItemIdentifier id = new ItemFixer.ItemIdentifier(
                    inv.getType(), ItemUtils.getCMD(inv));
            owned.merge(id, inv.getAmount(), Integer::sum);
        }

        int max = Integer.MAX_VALUE;
        for (CraftRecipe.Ingredient ing : recipe.ingredients()) {
            ItemFixer.ItemIdentifier id = new ItemFixer.ItemIdentifier(
                    ing.item().getType(), ItemUtils.getCMD(ing.item()));
            int have = owned.getOrDefault(id, 0);
            int perCraft = ing.amount();
            if (perCraft <= 0) continue;
            max = Math.min(max, have / perCraft);
            if (max == 0) return 0;
        }
        return max;
    }

    /**
     * Attempt to craft a recipe up to {@code maxCount} times in one operation.
     *
     * <p>Bulk crafting is more efficient than calling {@link #craft} in a loop:
     * ingredients are consumed per-iteration but XP is awarded once at the end,
     * and only one level-up notification fires (rather than N).</p>
     *
     * @param maxCount upper bound on how many crafts to perform. The actual
     *                 count may be lower if the inventory runs out.
     * @return a {@link CraftBulkResult} carrying the actual crafted count,
     *         total XP awarded, and levels gained.
     * @since 1.3.3 (Faz 0.0.3)
     */
    public CraftBulkResult craftBulk(Player player, CraftRecipe recipe, int maxCount) {
        if (player == null || recipe == null || maxCount <= 0) {
            return CraftBulkResult.empty(CraftResult.UNKNOWN_RECIPE);
        }

        if (!plugin.isXpSystemEnabled() || plugin.getCacheManager() == null) {
            return CraftBulkResult.empty(CraftResult.NO_DATA);
        }
        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null) return CraftBulkResult.empty(CraftResult.NO_DATA);

        if (data.getCraftingLevel() < recipe.craftingLevelRequirement()) {
            return CraftBulkResult.failure(CraftResult.LOW_LEVEL);
        }

        // Compute the actual number we can craft
        int maxByIngredients = maxCraftable(player, recipe);
        if (maxByIngredients <= 0) {
            return CraftBulkResult.failure(CraftResult.MISSING_MATERIALS);
        }
        int toCraft = Math.min(maxCount, maxByIngredients);

        // Hard cap to prevent integer overflow / runaway loops
        // (1000 crafts at once is already a lot)
        if (toCraft > 1000) toCraft = 1000;

        try {
            // Consume ingredients `toCraft` times
            for (CraftRecipe.Ingredient ing : recipe.ingredients()) {
                int totalNeeded = ing.amount() * toCraft;
                if (!consume(player, ing.item(), totalNeeded)) {
                    // Should not happen since maxCraftable checked, but defensive
                    plugin.getLogger().warning("craftBulk(): consume failed for "
                            + player.getName() + " recipe=" + recipe.id()
                            + " needed=" + totalNeeded);
                    return CraftBulkResult.failure(CraftResult.INTERNAL_ERROR);
                }
            }

            // Give result items (stack-aware — handle overflow)
            ItemStack resultTemplate = recipe.resultItem();
            int resultPerCraft = resultTemplate.getMaxStackSize() > 0 ? 1 : 1;
            int totalItems = toCraft * resultPerCraft;
            int maxStack = resultTemplate.getMaxStackSize();
            if (maxStack <= 0) maxStack = 64;

            int remaining = totalItems;
            while (remaining > 0) {
                int stackSize = Math.min(remaining, maxStack);
                ItemStack stack = resultTemplate.clone();
                stack.setAmount(stackSize);
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
                for (ItemStack leftover : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
                remaining -= stackSize;
            }

            // Award XP once (bulk) — level-up checks fire for all accumulated XP
            int xpAwarded = recipe.xpReward() * toCraft;
            int levelsGained = 0;
            if (xpAwarded > 0) {
                data.addCraftingXp(xpAwarded);
                levelsGained = data.checkAllCraftingLevelUps();
                if (levelsGained > 0) {
                    notifyCraftingLevelUp(player, data.getCraftingLevel());
                }
            }

            return new CraftBulkResult(CraftResult.SUCCESS, toCraft, xpAwarded, levelsGained);
        } catch (Exception e) {
            plugin.getLogger().severe("craftBulk() internal error for " + player.getName()
                    + " recipe=" + recipe.id() + ": " + e.getMessage());
            return CraftBulkResult.failure(CraftResult.INTERNAL_ERROR);
        }
    }

    /**
     * Count items in the player's inventory that match the template.
     * Only counts main inventory (9..35) and hotbar (0..8) — armor & offhand excluded
     * for consistency with {@link #consume}.
     */
    private int countMatching(Player player, ItemStack template) {
        int total = 0;
        ItemStack[] contents = player.getInventory().getContents();
        // Hotbar (0..8) + main inventory (9..35)
        for (int i = 0; i <= 35 && i < contents.length; i++) {
            ItemStack inv = contents[i];
            if (inv == null || inv.getType().isAir()) continue;
            if (matchesTemplate(inv, template)) {
                total += inv.getAmount();
            }
        }
        return total;
    }

    private boolean matchesTemplate(ItemStack inv, ItemStack template) {
        if (inv.getType() != template.getType()) return false;
        int invCmd = ItemUtils.getCMD(inv);
        int tplCmd = ItemUtils.getCMD(template);
        return invCmd == tplCmd;
    }

    /**
     * Attempt to craft. Returns the result code; on SUCCESS, items are
     * consumed and the result item is given to the player.
     */
    public CraftResult craft(Player player, CraftRecipe recipe) {
        if (player == null || recipe == null) return CraftResult.UNKNOWN_RECIPE;

        if (!plugin.isXpSystemEnabled() || plugin.getCacheManager() == null) {
            return CraftResult.NO_DATA;
        }
        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null) return CraftResult.NO_DATA;

        if (data.getCraftingLevel() < recipe.craftingLevelRequirement()) {
            return CraftResult.LOW_LEVEL;
        }

        if (!hasIngredients(player, recipe)) {
            return CraftResult.MISSING_MATERIALS;
        }

        try {
            // Consume ingredients
            for (CraftRecipe.Ingredient ing : recipe.ingredients()) {
                if (!consume(player, ing.item(), ing.amount())) {
                    // Should not happen since hasIngredients returned true,
                    // but defensive: abort.
                    plugin.getLogger().warning("craft(): consume failed for " + player.getName()
                            + " recipe=" + recipe.id());
                    return CraftResult.INTERNAL_ERROR;
                }
            }

            // Give result
            ItemStack result = recipe.resultItem().clone();
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(result);
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }

            // Award XP + level-up checks
            if (recipe.xpReward() > 0) {
                data.addCraftingXp(recipe.xpReward());
                int leveled = data.checkAllCraftingLevelUps();
                if (leveled > 0) {
                    notifyCraftingLevelUp(player, data.getCraftingLevel());
                }
            }

            return CraftResult.SUCCESS;
        } catch (Exception e) {
            plugin.getLogger().severe("craft() internal error for " + player.getName()
                    + " recipe=" + recipe.id() + ": " + e.getMessage());
            return CraftResult.INTERNAL_ERROR;
        }
    }

    /**
     * Consume materials from the player's inventory.
     *
     * <p>Slot order (Faz 1 / 0.0.2 — Madde 4):
     * <ol>
     *   <li>Main inventory slots (9..35) consumed first — keeps the hotbar stable
     *       so players don't lose track of their active items.</li>
     *   <li>Hotbar slots (0..8) consumed as fallback.</li>
     *   <li>Armor & offhand slots (36..40) are never touched.</li>
     * </ol>
     *
     * <p>Within the main inventory, lowest slot index is consumed first
     * (deterministic — same input always produces same result).</p>
     */
    private boolean consume(Player player, ItemStack template, int needed) {
        if (needed <= 0) return true;

        ItemStack[] contents = player.getInventory().getContents();
        int remaining = needed;

        // Pass 1: main inventory slots 9..35
        for (int i = 9; i <= 35 && remaining > 0; i++) {
            remaining -= takeFromSlot(player, contents, i, template, remaining);
        }
        // Pass 2: hotbar slots 0..8
        for (int i = 0; i <= 8 && remaining > 0; i++) {
            remaining -= takeFromSlot(player, contents, i, template, remaining);
        }

        return remaining == 0;
    }

    /**
     * Take up to {@code max} items from slot {@code slot} if it matches {@code template}.
     * Returns how many were actually taken. Writes back to the inventory.
     */
    private int takeFromSlot(Player player, ItemStack[] contents, int slot,
                              ItemStack template, int max) {
        if (slot >= contents.length) return 0;
        ItemStack inv = contents[slot];
        if (inv == null || inv.getType().isAir()) return 0;
        if (!matchesTemplate(inv, template)) return 0;

        int take = Math.min(inv.getAmount(), max);
        if (take >= inv.getAmount()) {
            player.getInventory().setItem(slot, null);
            contents[slot] = null;
        } else {
            inv.setAmount(inv.getAmount() - take);
            player.getInventory().setItem(slot, inv);
            // contents[slot] already references the same ItemStack, but be explicit:
            contents[slot] = inv;
        }
        return take;
    }

    private void notifyCraftingLevelUp(Player player, int newLevel) {
        if (plugin.getMessageManager() == null) return;

        Map<String, String> ph = Map.of("%level%", String.valueOf(newLevel));

        // 1. Chat message (existing behavior)
        player.sendMessage(plugin.getMessageManager().getMessage("crafting_level_up", true, ph));

        // 2. Title + subtitle (Faz 1 / 0.0.2 — Madde 5)
        try {
            net.kyori.adventure.title.Title.Times times = net.kyori.adventure.title.Title.Times.times(
                    java.time.Duration.ofMillis(300),
                    java.time.Duration.ofMillis(2500),
                    java.time.Duration.ofMillis(800));
            net.kyori.adventure.title.Title title = net.kyori.adventure.title.Title.title(
                    plugin.getMessageManager().getMessage("crafting_level_up_title", false, ph),
                    plugin.getMessageManager().getMessage("crafting_level_up_subtitle", false, ph),
                    times);
            player.showTitle(title);
        } catch (Exception ignored) {
            // Title API failure should not break crafting
        }

        // 3. Sound — main level-up sound + a celebratory chime for higher levels
        try {
            player.playSound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
            if (newLevel >= 10) {
                // Bonus chime for milestone levels
                player.playSound(player, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.0f);
            }
        } catch (Exception ignored) {
        }
    }
}
