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

    public record ItemIdentifier(Material material, int customModelData) {
    }

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final NamespacedKey rodKey;
    private final NamespacedKey baitKey;

    private final Map<ItemIdentifier, ConfigurationSection> rodCache;
    private final Map<String, ConfigurationSection> rodIdCache;

    public ItemFixer(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.rodKey = new NamespacedKey(plugin, "luck_multiplier");
        this.baitKey = new NamespacedKey(plugin, "bait_id");
        this.rodCache = new ConcurrentHashMap<>();
        this.rodIdCache = new ConcurrentHashMap<>();

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

            rodCache.put(new ItemIdentifier(mat, cmd), sec);
            rodIdCache.put(key.toLowerCase(), sec);
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

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir())
                continue;

            FixResult result = fixItem(item);
            if (result == FixResult.ROD) {
                rods++;
                if (inv != null)
                    inv.setItem(i, item);
            } else if (result == FixResult.BAIT) {
                baits++;
                if (inv != null)
                    inv.setItem(i, item);
            }
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
            return fixRod(itemInHand, meta);
        if (isBait)
            return fixBait(itemInHand, meta);

        if (itemInHand.getType() == Material.FISHING_ROD) {
            ConfigurationSection rodSec = findRodByItem(itemInHand);
            if (rodSec != null) {
                double luck = rodSec.getDouble("luck-multiplier", 1.0);
                meta.getPersistentDataContainer().set(rodKey, PersistentDataType.DOUBLE, luck);
                return fixRod(itemInHand, meta);
            }
        }

        BaitManager.Bait detectedBait = plugin.getBaitManager().getBaitFromItem(itemInHand);
        if (detectedBait != null) {
            meta.getPersistentDataContainer().set(baitKey, PersistentDataType.STRING, detectedBait.id());
            return fixBait(itemInHand, meta);
        }

        return FixResult.NONE;
    }

    private ConfigurationSection findRodByItem(ItemStack item) {
        int itemCmd = ItemUtils.getCMD(item);
        ItemIdentifier identifier = new ItemIdentifier(item.getType(), itemCmd);
        return rodCache.get(identifier);
    }

    private FixResult fixRod(ItemStack itemInHand, ItemMeta meta) {
        ConfigurationSection rodSec = findRodByItem(itemInHand);

        if (rodSec == null)
            return FixResult.FAILED;

        String defaultRodName = plugin.getMessageManager().getPlainMessage("item_default_rod_name");
        meta.displayName(miniMessage.deserialize(rodSec.getString("display-name", defaultRodName)));
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

    private FixResult fixBait(ItemStack itemInHand, ItemMeta meta) {
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

        int cmd = ItemUtils.getCMD(bait.item());
        if (cmd > 0) {
            meta.getCustomModelDataComponent().setFloats(List.of((float) cmd));
        }

        itemInHand.setItemMeta(meta);
        return FixResult.BAIT;
    }

    public ConfigurationSection getRodConfig(String rodId) {
        return rodIdCache.get(rodId.toLowerCase());
    }

    public Map<String, ConfigurationSection> getRodConfigs() {
        return rodIdCache;
    }

    public ItemStack createRodItem(ConfigurationSection rodSec) {
        String matStr = rodSec.getString("material", "FISHING_ROD").toUpperCase();
        Material mat = Material.matchMaterial(matStr);
        if (mat == null)
            return null;

        ItemStack rod = new ItemStack(mat);
        ItemMeta meta = rod.getItemMeta();
        if (meta == null)
            return rod;

        String defaultRodName = plugin.getMessageManager().getPlainMessage("item_default_rod_name");
        meta.displayName(miniMessage.deserialize(rodSec.getString("display-name", defaultRodName)));

        ItemUtils.applyCustomModelData(meta, rodSec);

        if (rodSec.contains("lore")) {
            List<Component> loreComponents = new ArrayList<>();
            for (String line : rodSec.getStringList("lore")) {
                loreComponents.add(miniMessage.deserialize(line));
            }
            meta.lore(loreComponents);
        }

        double luck = rodSec.getDouble("luck-multiplier", 1.0);
        meta.getPersistentDataContainer().set(rodKey, PersistentDataType.DOUBLE, luck);

        rod.setItemMeta(meta);
        return rod;
    }
}