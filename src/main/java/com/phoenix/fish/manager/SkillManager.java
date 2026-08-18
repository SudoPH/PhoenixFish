package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.data.FishingData;
import net.kyori.adventure.text.Component;
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

import java.util.ArrayList;
import java.util.List;

public class SkillManager {

    private static final int MAX_LEVEL = 5;

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final NamespacedKey actionKey;
    private final NamespacedKey valueKey;

    public static class SkillGUIHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    public SkillManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.actionKey = new NamespacedKey(plugin, "skill_action");
        this.valueKey = new NamespacedKey(plugin, "skill_value");
    }

    public void openMenu(Player player) {
        if (!plugin.isXpSystemEnabled()) {
            player.sendMessage(plugin.getMessageManager().getMessage("skills_disabled", true));
            return;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("skills_data_error", true));
            return;
        }

        Inventory inv = Bukkit.createInventory(new SkillGUIHolder(), 27,
                miniMessage.deserialize(plugin.getMessageManager().getPlainMessage("skills_menu_title")));

        ItemStack pointsItem = createItem(Material.NETHER_STAR,
                plugin.getMessageManager().getPlainMessage("skills_points_name")
                        .replace("%points%", String.valueOf(data.getSkillPoints())),
                List.of(), "none", "none");
        inv.setItem(4, pointsItem);

        List<String> fastLore = new ArrayList<>();
        fastLore.add(plugin.getMessageManager().getPlainMessage("skills_level")
                .replace("%level%", String.valueOf(data.getFastCatcher()))
                .replace("%max%", String.valueOf(MAX_LEVEL)));
        fastLore.add(plugin.getMessageManager().getPlainMessage("skills_fast_desc"));
        inv.setItem(10, createItem(Material.GOLDEN_BOOTS,
                plugin.getMessageManager().getPlainMessage("skills_name_fast"), fastLore, "upgrade", "fast"));

        List<String> hunterLore = new ArrayList<>();
        hunterLore.add(plugin.getMessageManager().getPlainMessage("skills_level")
                .replace("%level%", String.valueOf(data.getMasterHunter()))
                .replace("%max%", String.valueOf(MAX_LEVEL)));
        hunterLore.add(plugin.getMessageManager().getPlainMessage("skills_hunter_desc"));
        inv.setItem(12, createItem(Material.GOLDEN_SWORD,
                plugin.getMessageManager().getPlainMessage("skills_name_hunter"), hunterLore, "upgrade", "hunter"));

        List<String> doubleLore = new ArrayList<>();
        doubleLore.add(plugin.getMessageManager().getPlainMessage("skills_level")
                .replace("%level%", String.valueOf(data.getDoubleCatch()))
                .replace("%max%", String.valueOf(MAX_LEVEL)));
        doubleLore.add(plugin.getMessageManager().getPlainMessage("skills_double_desc"));
        inv.setItem(14, createItem(Material.TRIPWIRE_HOOK,
                plugin.getMessageManager().getPlainMessage("skills_name_double"), doubleLore, "upgrade", "double"));

        List<String> baitLore = new ArrayList<>();
        baitLore.add(plugin.getMessageManager().getPlainMessage("skills_level")
                .replace("%level%", String.valueOf(data.getLuckyBait()))
                .replace("%max%", String.valueOf(MAX_LEVEL)));
        baitLore.add(plugin.getMessageManager().getPlainMessage("skills_bait_desc"));
        inv.setItem(16, createItem(Material.GOLDEN_CARROT,
                plugin.getMessageManager().getPlainMessage("skills_name_bait"), baitLore, "upgrade", "bait"));

        player.openInventory(inv);
    }

    public void handleUpgrade(Player player, String skill) {
        if (!plugin.isXpSystemEnabled()) {
            player.sendMessage(plugin.getMessageManager().getMessage("skills_disabled", true));
            return;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("skills_data_error", true));
            return;
        }

        if (data.getSkillPoints() <= 0) {
            player.sendMessage(plugin.getMessageManager().getMessage("skills_no_points", true));
            return;
        }

        boolean upgraded = false;
        switch (skill) {
            case "fast":
                if (data.getFastCatcher() < MAX_LEVEL) {
                    data.setFastCatcher(data.getFastCatcher() + 1);
                    upgraded = true;
                }
                break;
            case "hunter":
                if (data.getMasterHunter() < MAX_LEVEL) {
                    data.setMasterHunter(data.getMasterHunter() + 1);
                    upgraded = true;
                }
                break;
            case "double":
                if (data.getDoubleCatch() < MAX_LEVEL) {
                    data.setDoubleCatch(data.getDoubleCatch() + 1);
                    upgraded = true;
                }
                break;
            case "bait":
                if (data.getLuckyBait() < MAX_LEVEL) {
                    data.setLuckyBait(data.getLuckyBait() + 1);
                    upgraded = true;
                }
                break;
            default:
                plugin.getLogger().warning("Unknown skill upgrade requested: " + skill + " by " + player.getName());
                player.sendMessage(plugin.getMessageManager().getMessage("skills_invalid", true));
                return;
        }

        if (upgraded) {
            data.setSkillPoints(data.getSkillPoints() - 1);
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
            openMenu(player);
        } else {
            player.sendMessage(plugin.getMessageManager().getMessage("skills_maxed", true));
        }
    }

    private ItemStack createItem(Material mat, String name, List<String> lore, String action, String value) {
        if (mat == null)
            mat = Material.STONE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(name));

            List<Component> loreComponents = new ArrayList<>();
            for (String line : lore) {
                loreComponents.add(miniMessage.deserialize(line));
            }
            meta.lore(loreComponents);

            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            meta.getPersistentDataContainer().set(valueKey, PersistentDataType.STRING, value);
            item.setItemMeta(meta);
        }
        return item;
    }

    public NamespacedKey getActionKey() {
        return actionKey;
    }

    public NamespacedKey getValueKey() {
        return valueKey;
    }
}