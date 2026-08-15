package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.model.CustomFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class FishManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;

    private volatile List<CustomFish> fishList = Collections.emptyList();
    private final NamespacedKey fishKey;

    public FishManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.fishKey = new NamespacedKey(plugin, "fish_id");
    }

    public MiniMessage getMiniMessage() {
        return miniMessage;
    }

    public NamespacedKey getFishKey() {
        return fishKey;
    }

    public List<CustomFish> getFishList() {
        return fishList;
    }

    public CustomFish getFishById(String id) {
        for (CustomFish fish : fishList) {
            if (fish.id().equalsIgnoreCase(id))
                return fish;
        }
        return null;
    }

    public void loadFish() {
        File fishFile = new File(plugin.getDataFolder(), "fish.yml");
        if (!fishFile.exists()) {
            plugin.getLogger().warning("fish.yml does not exist! Cannot load custom fish.");
            return;
        }

        FileConfiguration fishConfig = YamlConfiguration.loadConfiguration(fishFile);
        ConfigurationSection section = fishConfig.getConfigurationSection("fishes");
        if (section == null) {
            plugin.getLogger().warning("No 'fishes' section found in fish.yml.");
            return;
        }

        List<CustomFish> newList = new ArrayList<>();

        for (String key : section.getKeys(false)) {
            ConfigurationSection fishSec = section.getConfigurationSection(key);
            if (fishSec == null)
                continue;

            try {
                int rarity = Math.max(1, fishSec.getInt("rarity", 1));
                String rarityColor = getRarityColor(rarity);
                String rarityName = plugin.getMessageManager().getPlainMessage("rarity_" + rarity);

                String baseNameStr = fishSec.getString("display-name", "Fish");
                Component baseName = miniMessage.deserialize(baseNameStr);
                String plainName = PlainTextComponentSerializer.plainText().serialize(baseName);
                Component finalName = miniMessage.deserialize(rarityColor + plainName);

                String materialStr = fishSec.getString("material", "COD").toUpperCase();
                Material material = Material.matchMaterial(materialStr);
                if (material == null) {
                    plugin.getLogger().warning(
                            "Invalid material '" + materialStr + "' for fish: " + key + ". Defaulting to COD.");
                    material = Material.COD;
                }

                int weight = fishSec.getInt("weight", 10);
                double speed = fishSec.getDouble("base-speed", 1.0);
                double strength = fishSec.getDouble("fight-strength", 1.0);
                int xp = fishSec.getInt("xp-reward", 0);

                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.displayName(finalName);
                    meta.getPersistentDataContainer().set(fishKey, PersistentDataType.STRING, key);
                    ItemUtils.applyCustomModelData(meta, fishSec);

                    List<Component> loreComponents = new ArrayList<>();
                    loreComponents
                            .add(miniMessage.deserialize(rarityColor + "<bold>Rarity: " + rarityName + "</bold>"));

                    if (fishSec.contains("lore")) {
                        for (String line : fishSec.getStringList("lore")) {
                            loreComponents.add(miniMessage.deserialize(line));
                        }
                    }
                    meta.lore(loreComponents);
                    item.setItemMeta(meta);
                }

                newList.add(new CustomFish(key, finalName, rarity, weight, speed, strength, xp, item));
            } catch (Exception e) {
                plugin.getLogger().warning("Error while loading fish '" + key + "': " + e.getMessage());
            }
        }
        this.fishList = Collections.unmodifiableList(newList);
        plugin.getLogger().info("Loaded " + fishList.size() + " custom fish successfully.");
    }

    private String getRarityColor(int rarity) {
        return switch (rarity) {
            case 1 -> "<gray>";
            case 2 -> "<aqua>";
            case 3 -> "<red>";
            case 4 -> "<gold>";
            case 5 -> "<light_purple>";
            default -> "<white>";
        };
    }

    public CustomFish rollRandomFish(double luckMultiplier, BaitManager.Bait bait) {
        List<CustomFish> currentFish = this.fishList;
        if (currentFish.isEmpty())
            return null;

        double[] dynamicWeights = new double[currentFish.size()];
        double totalDynamicWeight = 0.0;

        for (int i = 0; i < currentFish.size(); i++) {
            CustomFish fish = currentFish.get(i);
            double baseLuckMultiplier = Math.pow(luckMultiplier, fish.rarity() - 1);
            double baitMultiplier = (bait != null && bait.targetRarity() == fish.rarity()) ? bait.modifier() : 1.0;

            double weight = fish.weight() * baseLuckMultiplier * baitMultiplier;
            dynamicWeights[i] = weight;
            totalDynamicWeight += weight;
        }

        if (totalDynamicWeight <= 0.0)
            return currentFish.get(0);

        double roll = ThreadLocalRandom.current().nextDouble() * totalDynamicWeight;
        double currentWeight = 0.0;

        for (int i = 0; i < dynamicWeights.length; i++) {
            currentWeight += dynamicWeights[i];
            if (roll <= currentWeight) {
                return currentFish.get(i);
            }
        }

        return currentFish.get(currentFish.size() - 1);
    }
}