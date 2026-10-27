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

    private static final int CONFIG_MAX_CAP = 10;

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final NamespacedKey actionKey;
    private final NamespacedKey valueKey;
    private final int maxLevel;

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
        int configVal = plugin.getConfig().getInt("xp-system.skill-max-level", 5);
        this.maxLevel = Math.max(1, Math.min(CONFIG_MAX_CAP, configVal));
    }

    public int getMaxLevel() {
        return maxLevel;
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

        // v1.4.0 — FishingData'ya config-driven max skill level'ı set et.
        // clampSkill artık hardcoded 5 değil, config'teki skill-max-level değerini kullanır.
        data.setMaxSkillLevel(maxLevel);

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
                .replace("%max%", String.valueOf(maxLevel)));
        fastLore.add(plugin.getMessageManager().getPlainMessage("skills_fast_desc"));
        inv.setItem(10, createItem(Material.GOLDEN_BOOTS,
                plugin.getMessageManager().getPlainMessage("skills_name_fast"), fastLore, "upgrade", "fast"));

        List<String> hunterLore = new ArrayList<>();
        hunterLore.add(plugin.getMessageManager().getPlainMessage("skills_level")
                .replace("%level%", String.valueOf(data.getMasterHunter()))
                .replace("%max%", String.valueOf(maxLevel)));
        hunterLore.add(plugin.getMessageManager().getPlainMessage("skills_hunter_desc"));
        inv.setItem(12, createItem(Material.GOLDEN_SWORD,
                plugin.getMessageManager().getPlainMessage("skills_name_hunter"), hunterLore, "upgrade", "hunter"));

        List<String> doubleLore = new ArrayList<>();
        doubleLore.add(plugin.getMessageManager().getPlainMessage("skills_level")
                .replace("%level%", String.valueOf(data.getDoubleCatch()))
                .replace("%max%", String.valueOf(maxLevel)));
        doubleLore.add(plugin.getMessageManager().getPlainMessage("skills_double_desc"));
        inv.setItem(14, createItem(Material.TRIPWIRE_HOOK,
                plugin.getMessageManager().getPlainMessage("skills_name_double"), doubleLore, "upgrade", "double"));

        List<String> baitLore = new ArrayList<>();
        baitLore.add(plugin.getMessageManager().getPlainMessage("skills_level")
                .replace("%level%", String.valueOf(data.getLuckyBait()))
                .replace("%max%", String.valueOf(maxLevel)));
        baitLore.add(plugin.getMessageManager().getPlainMessage("skills_bait_desc"));
        inv.setItem(16, createItem(Material.GOLDEN_CARROT,
                plugin.getMessageManager().getPlainMessage("skills_name_bait"), baitLore, "upgrade", "bait"));

        // v1.6.0 — Yeni skill butonları (sadece ilgili feature açıkken göster)
        if (plugin.getConfig().getBoolean("features.skill-big-catch.enabled", false)) {
            List<String> bigLore = new ArrayList<>();
            bigLore.add(plugin.getMessageManager().getPlainMessage("skills_level")
                    .replace("%level%", String.valueOf(data.getBigCatch()))
                    .replace("%max%", String.valueOf(maxLevel)));
            bigLore.add("<gray>+5% balık ağırlığı per level (daha ağır = daha pahalı)");
            inv.setItem(19, createItem(Material.ANVIL,
                    "<#DAA520>Big Catch", bigLore, "upgrade", "big"));
        }

        if (plugin.getConfig().getBoolean("features.skill-lucky-cast.enabled", false)) {
            List<String> luckyLore = new ArrayList<>();
            luckyLore.add(plugin.getMessageManager().getPlainMessage("skills_level")
                    .replace("%level%", String.valueOf(data.getLuckyCast()))
                    .replace("%max%", String.valueOf(maxLevel)));
            luckyLore.add("<gray>+2% bir üst rarelıkta balık şansı per level");
            inv.setItem(21, createItem(Material.GHAST_TEAR,
                    "<#55FFFF>Lucky Cast", luckyLore, "upgrade", "lucky"));
        }

        if (plugin.getConfig().getBoolean("features.skill-stamina.enabled", false)) {
            List<String> staminaLore = new ArrayList<>();
            staminaLore.add(plugin.getMessageManager().getPlainMessage("skills_level")
                    .replace("%level%", String.valueOf(data.getStamina()))
                    .replace("%max%", String.valueOf(maxLevel)));
            staminaLore.add("<gray>+10% tension resistance per level (hat daha geç kopar)");
            inv.setItem(23, createItem(Material.GOLDEN_APPLE,
                    "<#FF6347>Stamina", staminaLore, "upgrade", "stamina"));
        }

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

        boolean validSkill = "fast".equals(skill) || "hunter".equals(skill)
                || "double".equals(skill) || "bait".equals(skill)
                // v1.6.0 — Yeni skill'ler
                || "big".equals(skill) || "lucky".equals(skill) || "stamina".equals(skill);
        if (!validSkill) {
            plugin.getLogger().warning("Unknown skill upgrade requested: " + skill + " by " + player.getName());
            player.sendMessage(plugin.getMessageManager().getMessage("skills_invalid", true));
            return;
        }

        boolean upgraded = data.tryUpgradeSkill(skill, maxLevel);

        if (upgraded) {
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
            openMenu(player);
        } else if (data.getSkillPoints() <= 0) {
            player.sendMessage(plugin.getMessageManager().getMessage("skills_no_points", true));
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
