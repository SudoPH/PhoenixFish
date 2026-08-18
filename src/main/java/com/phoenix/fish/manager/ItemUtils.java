package com.phoenix.fish.manager;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ItemUtils {

    private static final Logger LOGGER = Logger.getLogger(ItemUtils.class.getName());

    private ItemUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    @SuppressWarnings("deprecation")
    public static void applyCustomModelData(ItemMeta meta, ConfigurationSection config) {
        if (meta == null || config == null)
            return;
        if (!config.contains("custom-model-data")) {
            meta.setCustomModelData(null);
            return;
        }
        if (config.isInt("custom-model-data")) {
            int cmd = config.getInt("custom-model-data");
            meta.setCustomModelData(cmd > 0 ? cmd : null);
        }
    }

    @SuppressWarnings("deprecation")
    public static int getCMD(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return 0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return 0;

        if (!meta.hasCustomModelData())
            return 0;
        try {
            Integer cmd = meta.getCustomModelData();
            return cmd != null ? cmd : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String itemToBase64(ItemStack item) {
        if (item == null)
            return null;
        try {
            byte[] bytes = item.serializeAsBytes();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to serialize ItemStack to Base64", e);
            return null;
        }
    }

    public static ItemStack base64ToItem(String base64) {
        if (base64 == null || base64.isEmpty())
            return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return ItemStack.deserializeBytes(bytes);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to deserialize ItemStack from Base64", e);
            return null;
        }
    }
}