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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BaitManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final Map<String, Bait> baits = new ConcurrentHashMap<>();
    private final Map<ItemFixer.ItemIdentifier, Bait> baitItemCache = new ConcurrentHashMap<>();
    private final NamespacedKey baitKey;

    public BaitManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.baitKey = new NamespacedKey(plugin, "bait_id");
    }

    public void loadBaits() {
        baits.clear();
        baitItemCache.clear();

        File file = new File(plugin.getDataFolder(), "baits.yml");
        if (!file.exists()) {
            plugin.saveResource("baits.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("baits");
        if (section == null) {
            plugin.getLogger().warning("No 'baits' section found in baits.yml");
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec == null)
                continue;

            try {
                Component name = miniMessage.deserialize(sec.getString("display-name", "<white>Bait</white>"));
                int targetRarity = Math.max(1, sec.getInt("target-rarity", 1));
                double modifier = sec.getDouble("chance-modifier", 1.0);

                String materialStr = sec.getString("material", "STICK").toUpperCase();
                Material material = Material.matchMaterial(materialStr);
                if (material == null) {
                    plugin.getLogger().warning("Invalid material " + materialStr + " for bait " + key);
                    material = Material.STICK;
                }

                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.displayName(name);
                    meta.getPersistentDataContainer().set(baitKey, PersistentDataType.STRING, key);
                    ItemUtils.applyCustomModelData(meta, sec);

                    if (sec.contains("lore")) {
                        List<Component> loreComponents = new ArrayList<>();
                        for (String line : sec.getStringList("lore")) {
                            loreComponents.add(miniMessage.deserialize(line));
                        }
                        meta.lore(loreComponents);
                    }
                    item.setItemMeta(meta);
                }

                Bait bait = new Bait(key, targetRarity, modifier, item);
                baits.put(key, bait);

                int cmd = ItemUtils.getCMD(item);
                if (cmd > 0) {
                    baitItemCache.put(new ItemFixer.ItemIdentifier(material, cmd), bait);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load bait " + key + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + baits.size() + " baits.");
    }

    public Bait getBaitFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return null;

        int cmd = ItemUtils.getCMD(item);
        if (cmd <= 0)
            return null;

        return baitItemCache.get(new ItemFixer.ItemIdentifier(item.getType(), cmd));
    }

    public ItemStack getBaitItem(String id) {
        Bait bait = baits.get(id);
        return bait != null ? bait.item().clone() : null;
    }

    public NamespacedKey getBaitKey() {
        return baitKey;
    }

    public Map<String, Bait> getBaits() {
        return Collections.unmodifiableMap(baits);
    }

    public record Bait(String id, int targetRarity, double modifier, ItemStack item) {
    }
}