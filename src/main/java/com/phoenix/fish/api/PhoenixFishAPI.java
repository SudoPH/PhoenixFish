package com.phoenix.fish.api;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import com.phoenix.fish.event.FishCaughtEvent;
import com.phoenix.fish.event.FishSellEvent;
import com.phoenix.fish.manager.FishManager;
import com.phoenix.fish.model.CustomFish;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

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
}