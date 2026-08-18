package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.model.CustomFish;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

public class EconomyManager {

    private final PhoenixFish plugin;
    private final NamespacedKey weightKey;
    private final MiniMessage miniMessage;

    public static class SellGUIHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public EconomyManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.weightKey = new NamespacedKey(plugin, "fish_weight");
        this.miniMessage = MiniMessage.miniMessage();
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("economy.enabled", false);
    }

    public double getBalance(Player player) {
        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        return data != null ? data.getBalance() : 0.0;
    }

    public double getWeight(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return 0.0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return 0.0;
        return meta.getPersistentDataContainer().getOrDefault(weightKey, PersistentDataType.DOUBLE, 0.0);
    }

    public double calculatePrice(ItemStack item, CustomFish fish) {
        if (fish == null)
            return 0.0;
        double basePrice = Math.max(0.0, fish.price());
        double weight = getWeight(item);
        if (weight <= 0)
            weight = 1.0;
        return basePrice * weight * item.getAmount();
    }

    private String getFishId(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return null;
        return meta.getPersistentDataContainer().get(plugin.getFishManager().getFishKey(), PersistentDataType.STRING);
    }

    public void sellAll(Player player) {
        if (!isEnabled()) {
            player.sendMessage(plugin.getMessageManager().getMessage("economy_disabled", true));
            return;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null)
            return;

        double total = 0.0;
        int count = 0;

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir())
                continue;
            String fishId = getFishId(item);
            if (fishId != null) {
                CustomFish fish = plugin.getFishManager().getFishById(fishId);
                if (fish != null) {
                    total += calculatePrice(item, fish);
                    count += item.getAmount();
                    item.setAmount(0);
                }
            }
        }

        processSale(player, data, total, count);
    }

    public void openSellGUI(Player player) {
        if (!isEnabled()) {
            player.sendMessage(plugin.getMessageManager().getMessage("economy_disabled", true));
            return;
        }

        Inventory inv = Bukkit.createInventory(new SellGUIHolder(), 54,
                miniMessage.deserialize(plugin.getMessageManager().getPlainMessage("sell_gui_title")));

        ItemStack confirmButton = createButton(Material.GOLD_BLOCK,
                plugin.getMessageManager().getPlainMessage("sell_gui_confirm"), "sell_confirm");
        inv.setItem(49, confirmButton);

        ItemStack cancelButton = createButton(Material.BARRIER,
                plugin.getMessageManager().getPlainMessage("sell_gui_cancel"), "sell_cancel");
        inv.setItem(45, cancelButton);

        player.openInventory(inv);
    }

    public void confirmSell(Player player, Inventory inv) {
        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null)
            return;

        double total = 0.0;
        int count = 0;

        for (int i = 0; i < 45; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir())
                continue;

            String fishId = getFishId(item);
            if (fishId != null) {
                CustomFish fish = plugin.getFishManager().getFishById(fishId);
                if (fish != null) {
                    total += calculatePrice(item, fish);
                    count += item.getAmount();
                    inv.setItem(i, null);
                }
            } else {
                player.getInventory().addItem(item);
                inv.setItem(i, null);
            }
        }

        player.closeInventory();
        processSale(player, data, total, count);
    }

    public void sellHand(Player player) {
        if (!isEnabled()) {
            player.sendMessage(plugin.getMessageManager().getMessage("economy_disabled", true));
            return;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null)
            return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            player.sendMessage(plugin.getMessageManager().getMessage("sell_no_fish", true));
            return;
        }

        String fishId = getFishId(item);
        if (fishId == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("sell_no_fish", true));
            return;
        }

        CustomFish fish = plugin.getFishManager().getFishById(fishId);
        if (fish == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("sell_no_fish", true));
            return;
        }

        double price = calculatePrice(item, fish);
        int amount = item.getAmount();
        item.setAmount(0);

        processSale(player, data, price, amount);
    }

    private void processSale(Player player, FishingData data, double total, int count) {
        if (total > 0) {
            data.addBalance(total);

            Map<String, String> ph = Map.of(
                    "%amount%", String.valueOf(count),
                    "%price%", String.format("%.2f", total));
            player.sendMessage(plugin.getMessageManager().getMessage("sell_all_success", true, ph));

            playSoundFromConfig(player, "sounds.sell_success", Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);

            if (plugin.getApi() != null) {
                try {
                    plugin.getApi().triggerFishSell(player, total, count);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error triggering fish sell event via API: " + e.getMessage());
                }
            }
        } else {
            player.sendMessage(plugin.getMessageManager().getMessage("sell_no_fish", true));
        }
    }

    private ItemStack createButton(Material mat, String name, String action) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(name));
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, "gui_action"),
                    PersistentDataType.STRING,
                    action);
            item.setItemMeta(meta);
        }
        return item;
    }

    @SuppressWarnings("removal")
    private void playSoundFromConfig(Player player, String path, Sound defaultSound, float volume, float pitch) {
        String soundName = plugin.getConfig().getString(path);
        Sound soundToPlay = defaultSound;

        if (soundName != null && !soundName.isEmpty()) {
            try {
                soundToPlay = Sound.valueOf(soundName.toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger()
                        .warning("Invalid sound in config: " + soundName + " at path: " + path + " - Using default");
            }
        }

        player.playSound(player, soundToPlay, volume, pitch);
    }
}