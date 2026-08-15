package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ItemFixer {

    public enum FixResult {
        NONE, ROD, BAIT, FAILED
    }

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final NamespacedKey rodKey;
    private final NamespacedKey baitKey;

    private final Map<RodIdentifier, ConfigurationSection> rodCache;
    private final Map<Double, ConfigurationSection> rodLuckCache;

    private record RodIdentifier(Material material, int customModelData) {
    }

    public ItemFixer(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.rodKey = new NamespacedKey(plugin, "luck_multiplier");
        this.baitKey = new NamespacedKey(plugin, "bait_id");
        this.rodCache = new ConcurrentHashMap<>();
        this.rodLuckCache = new ConcurrentHashMap<>();

        cacheRodConfigurations();
    }

    private void cacheRodConfigurations() {
        File rodFile = new File(plugin.getDataFolder(), "rods.yml");
        if (!rodFile.exists())
            return;

        FileConfiguration rodConfig = YamlConfiguration.loadConfiguration(rodFile);
        ConfigurationSection rodsSec = rodConfig.getConfigurationSection("rods");
        if (rodsSec == null)
            return;

        for (String key : rodsSec.getKeys(false)) {
            ConfigurationSection sec = rodsSec.getConfigurationSection(key);
            if (sec == null)
                continue;

            String matStr = sec.getString("material", "FISHING_ROD").toUpperCase();
            Material mat = Material.matchMaterial(matStr);
            if (mat == null)
                continue;

            int cmd = sec.getInt("custom-model-data", 0);
            double luck = sec.getDouble("luck-multiplier", 1.0);

            rodCache.put(new RodIdentifier(mat, cmd), sec);
            rodLuckCache.put(luck, sec);
        }
    }

    public int[] fixInventory(Inventory inv) {
        if (inv == null)
            return new int[] { 0, 0 };
        return fixContents(inv.getContents(), inv);
    }

    public int[] fixContents(ItemStack[] contents, Inventory inv) {
        int rods = 0, baits = 0;
        if (contents == null)
            return new int[] { 0, 0 };

        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir())
                continue;

            FixResult result = fixItem(item);
            if (result == FixResult.ROD) {
                rods++;
                changed = true;
            } else if (result == FixResult.BAIT) {
                baits++;
                changed = true;
            }
        }

        if (changed && inv != null) {
            inv.setContents(contents);
        }

        return new int[] { rods, baits };
    }

    public FixResult fixItem(ItemStack itemInHand) {
        if (itemInHand == null || itemInHand.getType().isAir())
            return FixResult.NONE;

        ItemMeta meta = itemInHand.getItemMeta();
        if (meta == null)
            return FixResult.NONE;

        boolean isRod = meta.getPersistentDataContainer().has(rodKey, PersistentDataType.DOUBLE);
        boolean isBait = meta.getPersistentDataContainer().has(baitKey, PersistentDataType.STRING);

        if (isRod)
            return fixRod(itemInHand, meta, rodKey);
        if (isBait)
            return fixBait(itemInHand, meta, baitKey);

        if (!isRod && itemInHand.getType() == Material.FISHING_ROD) {
            ConfigurationSection rodSec = findRodByItem(itemInHand);
            if (rodSec != null) {
                double luck = rodSec.getDouble("luck-multiplier", 1.0);
                meta.getPersistentDataContainer().set(rodKey, PersistentDataType.DOUBLE, luck);
                return fixRod(itemInHand, meta, rodKey);
            }
        }

        if (!isBait) {
            BaitManager.Bait detectedBait = findBaitByItem(itemInHand);
            if (detectedBait != null) {
                meta.getPersistentDataContainer().set(baitKey, PersistentDataType.STRING, detectedBait.id());
                return fixBait(itemInHand, meta, baitKey);
            }
        }

        return FixResult.NONE;
    }

    private ConfigurationSection findRodByItem(ItemStack item) {
        int itemCmd = getCMD(item);
        RodIdentifier identifier = new RodIdentifier(item.getType(), itemCmd);
        return rodCache.get(identifier);
    }

    private BaitManager.Bait findBaitByItem(ItemStack item) {
        int itemCmd = getCMD(item);
        if (itemCmd == 0)
            return null;

        for (BaitManager.Bait bait : plugin.getBaitManager().getBaits().values()) {
            if (bait.item().getType() == item.getType()) {
                int baitCmd = getCMD(bait.item());
                if (baitCmd == itemCmd)
                    return bait;
            }
        }
        return null;
    }

    private FixResult fixRod(ItemStack itemInHand, ItemMeta meta, NamespacedKey rodKey) {
        Double luckValue = meta.getPersistentDataContainer().get(rodKey, PersistentDataType.DOUBLE);

        ConfigurationSection rodSec = (luckValue != null) ? rodLuckCache.get(luckValue) : findRodByItem(itemInHand);

        if (rodSec == null)
            return FixResult.FAILED;

        meta.displayName(miniMessage.deserialize(rodSec.getString("display-name", "<white>Fishing Rod</white>")));
        ItemUtils.applyCustomModelData(meta, rodSec);

        if (rodSec.contains("lore")) {
            List<Component> loreComponents = new ArrayList<>();
            for (String line : rodSec.getStringList("lore")) {
                loreComponents.add(miniMessage.deserialize(line));
            }
            meta.lore(loreComponents);
        } else {
            meta.lore(null);
        }

        itemInHand.setItemMeta(meta);
        return FixResult.ROD;
    }

    private FixResult fixBait(ItemStack itemInHand, ItemMeta meta, NamespacedKey baitKey) {
        String baitId = meta.getPersistentDataContainer().get(baitKey, PersistentDataType.STRING);
        if (baitId == null)
            return FixResult.FAILED;

        BaitManager.Bait bait = plugin.getBaitManager().getBaits().get(baitId);
        if (bait == null)
            return FixResult.FAILED;

        ItemMeta freshMeta = bait.item().getItemMeta();
        if (freshMeta == null)
            return FixResult.FAILED;

        meta.displayName(freshMeta.displayName());
        meta.lore(freshMeta.lore());

        int cmd = getCMD(bait.item());
        if (cmd > 0) {
            CustomModelDataComponent cmdComponent = meta.getCustomModelDataComponent();
            cmdComponent.setFloats(List.of((float) cmd));
            meta.setCustomModelDataComponent(cmdComponent);
        }

        itemInHand.setItemMeta(meta);
        return FixResult.BAIT;
    }

    private int getCMD(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return 0;
        try {
            CustomModelDataComponent cmdComponent = item.getItemMeta().getCustomModelDataComponent();
            if (cmdComponent != null && !cmdComponent.getFloats().isEmpty()) {
                return cmdComponent.getFloats().get(0).intValue();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    public ConfigurationSection getRodConfig(String rodId) {
        for (ConfigurationSection sec : rodCache.values()) {
            if (sec.getName().equalsIgnoreCase(rodId)) {
                return sec;
            }
        }
        return null;
    }
}