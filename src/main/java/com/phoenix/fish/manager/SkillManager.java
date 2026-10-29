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
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Skill tree GUI manager.
 *
 * <p><b>v1.8.6 — Master toggle + granular sub-toggles:</b></p>
 * <ul>
 *   <li><b>Master toggle</b> ({@code features.skills.enabled}): when false,
 *       the entire skill system is disabled. {@link #openMenu(Player)} and
 *       {@link #handleUpgrade(Player, String)} return immediately with a
 *       "disabled" message. No DB lookups, no GUI construction, 0 resources.</li>
 *   <li><b>Sub-toggles</b> ({@code features.skills.types.<id>}): when a
 *       sub-toggle is false, the skill icon is HIDDEN from the GUI, the
 *       skill's stat bonus does NOT apply during gameplay, and the skill's
 *       level is still persisted (re-enabling keeps progress).</li>
 * </ul>
 *
 * <p>Skill ID → config sub-toggle ID mapping:</p>
 * <pre>
 *   fast     → features.skills.types.fast
 *   hunter   → features.skills.types.hunter
 *   double   → features.skills.types.double
 *   bait     → features.skills.types.bait
 *   big      → features.skills.types.big-catch
 *   lucky    → features.skills.types.lucky-cast
 *   stamina  → features.skills.types.stamina
 * </pre>
 *
 * <p><b>Backward compat:</b> for the 3 v1.6.0 skills (big/lucky/stamina),
 * if the new {@code features.skills.types.<id>} key is absent, the manager
 * falls back to the old {@code features.skill-<id>.enabled} path. Legacy
 * skills (fast/hunter/double/bait) default to true when absent.</p>
 */
public class SkillManager {

    private static final int CONFIG_MAX_CAP = 10;

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private final NamespacedKey actionKey;
    private final NamespacedKey valueKey;
    private volatile int maxLevel;

    /**
     * v1.8.6 — Canonical skill ID → config sub-toggle ID mapping.
     * The config uses human-readable names (big-catch, lucky-cast) while
     * the code uses short IDs (big, lucky) for switch statements and DB columns.
     */
    private static final Map<String, String> SKILL_TO_CONFIG_ID = Map.of(
            "fast", "fast",
            "hunter", "hunter",
            "double", "double",
            "bait", "bait",
            "big", "big-catch",
            "lucky", "lucky-cast",
            "stamina", "stamina"
    );

    /**
     * v1.8.6 — Legacy skill IDs (always enabled by default in pre-v1.8.6 configs).
     * The 3 v1.6.0 skills default to false.
     */
    private static final Map<String, Boolean> SKILL_DEFAULTS = Map.of(
            "fast", Boolean.TRUE,
            "hunter", Boolean.TRUE,
            "double", Boolean.TRUE,
            "bait", Boolean.TRUE,
            "big", Boolean.FALSE,
            "lucky", Boolean.FALSE,
            "stamina", Boolean.FALSE
    );

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
        this.maxLevel = readMaxLevelFromConfig();
    }

    public void reload() {
        this.maxLevel = readMaxLevelFromConfig();
    }

    private int readMaxLevelFromConfig() {
        int configVal = plugin.getConfig().getInt("xp-system.skill-max-level", 5);
        return Math.max(1, Math.min(CONFIG_MAX_CAP, configVal));
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    // =====================================================================
    //  v1.8.6 — Master toggle + sub-toggle API
    // =====================================================================

    /**
     * v1.8.6 — Master toggle for the entire skill system.
     *
     * <p>Returns true only if ALL of the following are true:</p>
     * <ul>
     *   <li>{@code features.skills.enabled} is true (master toggle)</li>
     *   <li>The XP system is enabled ({@link PhoenixFish#isXpSystemEnabled()})</li>
     * </ul>
     *
     * <p>When this returns false, {@link #openMenu(Player)} and
     * {@link #handleUpgrade(Player, String)} return immediately with a
     * "disabled" message. No DB lookups, no GUI construction.</p>
     */
    public boolean isSystemEnabled() {
        if (!plugin.isXpSystemEnabled()) return false;
        return plugin.getConfig().getBoolean("features.skills.enabled", true);
    }

    /**
     * v1.8.6 — Sub-toggle for an individual skill.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>{@code features.skills.types.<configId>} (new v1.8.6 path)</li>
     *   <li>For the 3 v1.6.0 skills: {@code features.skill-<oldName>.enabled}
     *       (backward compat with v1.6.0–v1.8.5 configs)</li>
     *   <li>Skill-specific default (true for legacy, false for v1.6.0 skills)</li>
     * </ol>
     *
     * <p>When this returns false:</p>
     * <ul>
     *   <li>The skill icon is HIDDEN from the Skills GUI</li>
     *   <li>The skill's stat bonus does NOT apply during gameplay</li>
     *   <li>The skill's level is still persisted in the DB</li>
     * </ul>
     *
     * @param skillId the canonical skill ID (fast, hunter, double, bait, big, lucky, stamina)
     * @return true if the skill is enabled and should be visible/active
     */
    public boolean isSkillEnabled(String skillId) {
        if (!isSystemEnabled()) return false;
        if (skillId == null) return false;

        String configId = SKILL_TO_CONFIG_ID.get(skillId);
        if (configId == null) return false;

        // v1.8.6 — Check the new path first.
        String newPath = "features.skills.types." + configId;
        if (plugin.getConfig().contains(newPath)) {
            return plugin.getConfig().getBoolean(newPath, SKILL_DEFAULTS.getOrDefault(skillId, false));
        }

        // v1.8.6 — Backward compat: for the 3 v1.6.0 skills, check the old path.
        // The old config sections are: features.skill-big-catch.enabled,
        // features.skill-lucky-cast.enabled, features.skill-stamina.enabled.
        if ("big".equals(skillId) || "lucky".equals(skillId) || "stamina".equals(skillId)) {
            String oldPath = "features.skill-" + configId + ".enabled";
            if (plugin.getConfig().contains(oldPath)) {
                return plugin.getConfig().getBoolean(oldPath, false);
            }
        }

        // Fall back to the skill-specific default.
        return SKILL_DEFAULTS.getOrDefault(skillId, false);
    }

    /**
     * v1.8.6 — Returns all 7 canonical skill IDs in GUI slot order.
     * Used by the tab-completer and the GUI builder.
     */
    public static List<String> getAllSkillIds() {
        return List.of("fast", "hunter", "double", "bait", "big", "lucky", "stamina");
    }

    /**
     * v1.8.6 — Returns all config sub-toggle IDs (human-readable names).
     * Used by the tab-completer for /pf settings features skills.types.&lt;TAB&gt;.
     *
     * <p>v1.8.7 — Derived dynamically from {@link #SKILL_TO_CONFIG_ID} so
     * future skill additions automatically populate in the tab-completion
     * list without needing to update this method.</p>
     */
    public static List<String> getAllSkillConfigIds() {
        List<String> ids = new ArrayList<>(SKILL_TO_CONFIG_ID.values());
        Collections.sort(ids);
        return Collections.unmodifiableList(ids);
    }

    // =====================================================================
    //  GUI
    // =====================================================================

    public void openMenu(Player player) {
        // v1.8.6 — Master toggle check. When the skill system is disabled,
        // return immediately with a "disabled" message. No DB lookups, no
        // GUI construction, 0 resources consumed.
        if (!isSystemEnabled()) {
            player.sendMessage(plugin.getMessageManager().getMessage("skills_disabled", true));
            return;
        }

        FishingData data = plugin.getCacheManager().getData(player.getUniqueId());
        if (data == null) {
            player.sendMessage(plugin.getMessageManager().getMessage("skills_data_error", true));
            return;
        }

        data.setMaxSkillLevel(maxLevel);

        Inventory inv = Bukkit.createInventory(new SkillGUIHolder(), 27,
                miniMessage.deserialize(plugin.getMessageManager().getPlainMessage("skills_menu_title")));

        ItemStack pointsItem = createItem(Material.NETHER_STAR,
                plugin.getMessageManager().getPlainMessage("skills_points_name")
                        .replace("%points%", String.valueOf(data.getSkillPoints())),
                List.of(), "none", "none");
        inv.setItem(4, pointsItem);

        // ── Legacy skills (slots 10/12/14/16) ──
        // v1.8.6 — Only render if the sub-toggle is enabled. Disabled skills
        // are completely HIDDEN from the GUI (no icon, no hint).
        if (isSkillEnabled("fast")) {
            inv.setItem(10, buildSkillItem(Material.GOLDEN_BOOTS, "skills_name_fast", "skills_fast_desc",
                    data.getFastCatcher(), "fast", 0.0));
        }
        if (isSkillEnabled("hunter")) {
            inv.setItem(12, buildSkillItem(Material.GOLDEN_SWORD, "skills_name_hunter", "skills_hunter_desc",
                    data.getMasterHunter(), "hunter", 0.0));
        }
        if (isSkillEnabled("double")) {
            inv.setItem(14, buildSkillItem(Material.TRIPWIRE_HOOK, "skills_name_double", "skills_double_desc",
                    data.getDoubleCatch(), "double", 0.0));
        }
        if (isSkillEnabled("bait")) {
            inv.setItem(16, buildSkillItem(Material.GOLDEN_CARROT, "skills_name_bait", "skills_bait_desc",
                    data.getLuckyBait(), "bait", 0.0));
        }

        // ── v1.6.0 — New skills (slots 19/21/23) ──
        // v1.8.6 — Only render if the sub-toggle is enabled. Disabled skills
        // are completely HIDDEN from the GUI.
        if (isSkillEnabled("big")) {
            double perLevel = plugin.getConfig().getDouble(
                    "features.skill-big-catch.weight-bonus-per-level", 0.05);
            inv.setItem(19, buildSkillItem(Material.ANVIL, "skills_name_big", "skills_big_desc",
                    data.getBigCatch(), "big", perLevel));
        }
        if (isSkillEnabled("lucky")) {
            double perLevel = plugin.getConfig().getDouble(
                    "features.skill-lucky-cast.rarity-upgrade-chance-per-level", 0.10);
            inv.setItem(21, buildSkillItem(Material.GHAST_TEAR, "skills_name_lucky", "skills_lucky_desc",
                    data.getLuckyCast(), "lucky", perLevel));
        }
        if (isSkillEnabled("stamina")) {
            double perLevel = plugin.getConfig().getDouble(
                    "features.skill-stamina.tension-resist-per-level", 0.10);
            inv.setItem(23, buildSkillItem(Material.GOLDEN_APPLE, "skills_name_stamina", "skills_stamina_desc",
                    data.getStamina(), "stamina", perLevel));
        }

        player.openInventory(inv);
    }

    /**
     * v1.8.6 — Single builder for every skill slot. Centralizes the lore
     * construction (level line + per-level bonus).
     *
     * <p>Note: the "effect disabled" hint from v1.8.5 is REMOVED — disabled
     * skills are now completely hidden from the GUI rather than shown with
     * a hint. This is the v1.8.6 design: the admin controls visibility via
     * the sub-toggle, not via a lore hint.</p>
     */
    private ItemStack buildSkillItem(Material mat, String nameKey, String descKey,
                                      int currentLevel, String skillId,
                                      double perLevelBonus) {
        String nameFallback = plugin.getMessageManager().getPlainMessage(nameKey);
        String descFallback = plugin.getMessageManager().getPlainMessage(descKey);
        String name = plugin.getContentLanguageManager().getSkillName(skillId, nameFallback);
        String descLine = plugin.getContentLanguageManager().getSkillDesc(skillId, descFallback);

        List<String> lore = new ArrayList<>();
        lore.add(plugin.getMessageManager().getPlainMessage("skills_level")
                .replace("%level%", String.valueOf(currentLevel))
                .replace("%max%", String.valueOf(maxLevel)));

        if (perLevelBonus > 0.0) {
            int pct = (int) Math.round(perLevelBonus * 100);
            descLine = descLine.replace("%pct%", String.valueOf(pct));
        }
        lore.add(descLine);

        return createItem(mat, name, lore, "upgrade", skillId);
    }

    public void handleUpgrade(Player player, String skill) {
        // v1.8.6 — Master toggle check. When the skill system is disabled,
        // return immediately with a "disabled" message.
        if (!isSystemEnabled()) {
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
                || "big".equals(skill) || "lucky".equals(skill) || "stamina".equals(skill);
        if (!validSkill) {
            plugin.getLogger().warning("Unknown skill upgrade requested: " + skill + " by " + player.getName());
            player.sendMessage(plugin.getMessageManager().getMessage("skills_invalid", true));
            return;
        }

        // v1.8.6 — Sub-toggle check. The player somehow clicked a skill icon
        // that is disabled (shouldn't happen since the icon is hidden, but
        // guard against race conditions / packet hacks).
        if (!isSkillEnabled(skill)) {
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
        if (mat == null) mat = Material.STONE;

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

    public NamespacedKey getActionKey() { return actionKey; }
    public NamespacedKey getValueKey() { return valueKey; }
}
