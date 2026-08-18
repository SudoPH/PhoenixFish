package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class CraftingManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;

    private Method cachedRegisterMethod;
    private Object craftAPIInstance;

    private final NamespacedKey LUCK_KEY;
    private final NamespacedKey BAIT_KEY;

    public CraftingManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.LUCK_KEY = new NamespacedKey(plugin, "luck_multiplier");
        this.BAIT_KEY = new NamespacedKey(plugin, "bait_id");
    }

    public void loadRecipes() {
        Plugin phoenixCraft = Bukkit.getPluginManager().getPlugin("PhoenixCraft");
        if (phoenixCraft == null) {
            plugin.getLogger().warning(plugin.getMessageManager().getPlainMessage("craft_api_not_found"));
            return;
        }

        try {
            Class<?> craftAPIClass = Class.forName("com.phoenix.craft.CraftAPI");
            if (craftAPIClass.isAssignableFrom(phoenixCraft.getClass())) {
                this.craftAPIInstance = phoenixCraft;
                try {
                    this.cachedRegisterMethod = craftAPIClass.getMethod("registerRecipe", ItemStack[].class,
                            ItemStack.class, int.class, int.class);
                } catch (NoSuchMethodException e) {
                    plugin.getLogger().warning("CraftAPI 4 parametreli metod bulunamadı! PhoenixCraft güncel mi?");
                    return;
                }
                plugin.getLogger().info("CraftAPI hooked successfully via Reflection!");
            } else {
                plugin.getLogger().warning("PhoenixCraft does not implement CraftAPI!");
                return;
            }
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("CraftAPI class not found! Is PhoenixCraft installed?");
            return;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to hook CraftAPI: " + e.getMessage());
            return;
        }

        File file = new File(plugin.getDataFolder(), "custom_recipes.yml");
        if (!file.exists()) {
            plugin.saveResource("custom_recipes.yml", false);
            file = new File(plugin.getDataFolder(), "custom_recipes.yml");
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection recipes = config.getConfigurationSection("recipes");
        if (recipes == null) {
            plugin.getLogger().warning(plugin.getMessageManager().getPlainMessage("craft_recipes_section_not_found"));
            return;
        }

        int count = 0;
        for (String recipeId : recipes.getKeys(false)) {
            ConfigurationSection recipeSec = recipes.getConfigurationSection(recipeId);
            if (recipeSec == null)
                continue;

            try {
                ConfigurationSection resultSec = recipeSec.getConfigurationSection("result");
                if (resultSec == null) {
                    plugin.getLogger().warning("Recipe " + recipeId + " missing result section");
                    continue;
                }

                ItemStack resultItem = createItem(resultSec);
                if (resultItem == null) {
                    plugin.getLogger().warning("Recipe " + recipeId + " result item creation failed");
                    continue;
                }

                ItemStack[] matrix = parseShape(recipeSec);
                if (matrix != null) {
                    int craftingLevelReq = recipeSec.getInt("crafting-level-requirement", 0);
                    int xpReward = recipeSec.getInt("xp-reward", 10);

                    cachedRegisterMethod.invoke(craftAPIInstance, matrix, resultItem, craftingLevelReq, xpReward);
                    count++;
                } else {
                    plugin.getLogger().warning("Recipe " + recipeId + " has invalid shape");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error loading recipe " + recipeId + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info(plugin.getMessageManager().getPlainMessage("craft_recipes_loaded")
                .replace("%amount%", String.valueOf(count)));
    }

    private ItemStack[] parseShape(ConfigurationSection recipeSec) {
        List<String> shapeList = recipeSec.getStringList("shape");
        ConfigurationSection ings = recipeSec.getConfigurationSection("ingredients");

        if (ings == null || shapeList.size() != 3)
            return null;

        ItemStack[] matrix = new ItemStack[9];
        ItemStack airItem = new ItemStack(Material.AIR);

        for (int row = 0; row < 3; row++) {
            String rowStr = shapeList.get(row);
            for (int col = 0; col < 3; col++) {
                if (col < rowStr.length()) {
                    char letter = rowStr.charAt(col);
                    if (letter == ' ') {
                        matrix[row * 3 + col] = airItem;
                    } else {
                        ConfigurationSection ingSec = ings.getConfigurationSection(String.valueOf(letter));
                        if (ingSec != null) {
                            matrix[row * 3 + col] = createItem(ingSec);
                        } else {
                            matrix[row * 3 + col] = airItem;
                        }
                    }
                } else {
                    matrix[row * 3 + col] = airItem;
                }
            }
        }
        return matrix;
    }

    private ItemStack createItem(ConfigurationSection sec) {
        String matStr = sec.getString("material", "STONE").toUpperCase();
        Material mat = Material.matchMaterial(matStr);
        if (mat == null) {
            plugin.getLogger().warning("Invalid material: " + matStr);
            mat = Material.STONE;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return item;

        if (sec.contains("display-name")) {
            meta.displayName(miniMessage.deserialize(sec.getString("display-name")));
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
            meta.getPersistentDataContainer().set(BAIT_KEY, PersistentDataType.STRING, sec.getString("bait-id"));
        }

        item.setItemMeta(meta);
        return item;
    }
}