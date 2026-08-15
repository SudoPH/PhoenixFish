package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class CraftingManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private boolean craftAPIExists;
    private Method cachedRegisterMethod;

    private final NamespacedKey LUCK_KEY;
    private final NamespacedKey BAIT_KEY;

    public CraftingManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.craftAPIExists = plugin.getServer().getPluginManager().getPlugin("PhoenixCraft") != null;

        this.LUCK_KEY = new NamespacedKey(plugin, "luck_multiplier");
        this.BAIT_KEY = new NamespacedKey(plugin, "bait_id");
    }

    public void loadRecipes() {
        if (!craftAPIExists) {
            plugin.getLogger().warning("PhoenixCraft is not installed! Custom recipes are disabled.");
            return;
        }

        try {
            Class<?> craftAPIClass = Class.forName("com.phoenix.craft.CraftAPI");
            this.cachedRegisterMethod = craftAPIClass.getMethod("registerRecipe", ItemStack[].class, ItemStack.class);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to hook into PhoenixCraft API: " + e.getMessage());
            this.craftAPIExists = false;
            return;
        }

        File file = new File(plugin.getDataFolder(), "custom_recipes.yml");
        if (!file.exists()) {
            plugin.saveResource("custom_recipes.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection recipes = config.getConfigurationSection("recipes");
        if (recipes == null || cachedRegisterMethod == null)
            return;

        int count = 0;
        for (String recipeId : recipes.getKeys(false)) {
            ConfigurationSection recipeSec = recipes.getConfigurationSection(recipeId);
            if (recipeSec == null)
                continue;

            try {
                ConfigurationSection resultSec = recipeSec.getConfigurationSection("result");
                if (resultSec == null)
                    continue;

                ItemStack resultItem = createItem(resultSec);
                ItemStack[] matrix = parseShape(recipeSec);

                if (matrix != null) {
                    cachedRegisterMethod.invoke(null, matrix, resultItem);
                    count++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error while loading recipe '" + recipeId + "': " + e.getMessage());
            }
        }

        plugin.getLogger().info(count + " custom recipes have been sent to PhoenixCraft successfully.");
    }

    private ItemStack[] parseShape(ConfigurationSection recipeSec) {
        List<String> shapeList = recipeSec.getStringList("shape");
        ConfigurationSection ings = recipeSec.getConfigurationSection("ingredients");

        if (ings == null || shapeList.size() != 3)
            return null;

        ItemStack[] matrix = new ItemStack[9];
        for (int row = 0; row < 3; row++) {
            String rowStr = shapeList.get(row);
            for (int col = 0; col < 3; col++) {
                if (col < rowStr.length()) {
                    char letter = rowStr.charAt(col);
                    ConfigurationSection ingSec = ings.getConfigurationSection(String.valueOf(letter));
                    if (ingSec != null) {
                        matrix[row * 3 + col] = createItem(ingSec);
                    }
                }
            }
        }
        return matrix;
    }

    private ItemStack createItem(ConfigurationSection sec) {
        String matStr = sec.getString("material", "STONE").toUpperCase();
        Material mat = Material.matchMaterial(matStr);
        if (mat == null) {
            plugin.getLogger().warning("Invalid material '" + matStr + "' in recipe. Defaulting to STONE.");
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