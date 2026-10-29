package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.model.CustomFish;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * v1.8.8 — Daily Contract System (full v1.7.0+ refactor).
 *
 * <p>Template-based daily contracts with:</p>
 * <ul>
 *   <li><b>Template parsing</b> — templates loaded from {@code contract_templates.yml}</li>
 *   <li><b>Randomized rolls</b> — amount / money / xp rolled within min-max ranges per player per day</li>
 *   <li><b>Placeholder substitution</b> — {@code %amount%}, {@code %money%}, {@code %xp%}, {@code %value%}
 *       resolved instantly on first load AND on every reload (no stale values)</li>
 *   <li><b>Click-to-claim rewards</b> — {@link #handleSlotClick(Player, int)} dispatches money + XP +
 *       item rewards when the player clicks a completed-contract slot. Robust against double-claims,
 *       null inventories, and partial fulfillment (overflow drops to the ground).</li>
 *   <li><b>Per-player persistence</b> — rolled contracts + progress + claim state saved in
 *       {@code contracts.yml} (separate from the template file)</li>
 *   <li><b>Localized display-name + description</b> — fetched via {@link ContentLanguageManager}
 *       with English fallback; inline template values serve as the English baseline</li>
 * </ul>
 *
 * <p>Config: {@code features.contracts.enabled}</p>
 */
public class ContractManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;
    private volatile boolean enabled;

    // ── Template pool ──
    private final Map<String, ContractTemplate> templates = new LinkedHashMap<>();
    private ContractGuiConfig guiConfig;
    // v1.8.9 — Roll settings loaded from contract_templates.yml (not config.yml).
    // Stored here so rollDailyContracts() reads from the correct source.
    private volatile int poolRollSize = 5;
    private volatile int dailyContractCount = 3;

    // ── Per-player state ──
    private final Map<UUID, List<Contract>> activeContracts = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastResetDate = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    public ContractManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.enabled = plugin.getConfig().getBoolean("features.contracts.enabled", false);
        if (enabled) {
            loadTemplates();
            loadState();
            plugin.getLogger().info("ContractManager enabled — " + templates.size()
                    + " templates loaded.");
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * v1.8.6+ — Re-reads the enabled flag from the freshly-loaded config
     * and re-loads templates/state if (newly) enabled.
     */
    public synchronized void reload() {
        boolean newEnabled = plugin.getConfig().getBoolean("features.contracts.enabled", false);
        if (newEnabled == this.enabled) {
            if (newEnabled) {
                // Even if state is unchanged, re-read templates in case the admin
                // edited contract_templates.yml. This also re-substitutes placeholders
                // with the current language pack — fixing the v1.8.5 bug where
                // placeholders stayed stale after a language switch.
                loadTemplates();
            }
            return;
        }
        if (newEnabled) {
            this.enabled = true;
            loadTemplates();
            loadState();
            plugin.getLogger().info("ContractManager enabled via reload — "
                    + templates.size() + " templates loaded.");
        } else {
            save();
            this.enabled = false;
            templates.clear();
            activeContracts.clear();
            lastResetDate.clear();
            plugin.getLogger().info("ContractManager disabled via reload — state saved.");
        }
    }

    // =====================================================================
    //  Template Loading
    // =====================================================================

    private File getTemplateFile() {
        return new File(plugin.getDataFolder(), "contract_templates.yml");
    }

    private void ensureDefaultFile() {
        File f = getTemplateFile();
        if (!f.exists()) {
            try {
                plugin.saveResource("contract_templates.yml", false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe("ContractManager: failed to extract "
                        + "default contract_templates.yml: " + e.getMessage());
            }
            return;
        }
        // v1.7.1 — If the file exists but the templates section is empty, restore defaults.
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        if (cfg.getConfigurationSection("templates") == null
                || cfg.getConfigurationSection("templates").getKeys(false).isEmpty()) {
            File backup = new File(plugin.getDataFolder(),
                    "contract_templates.backup-" + System.currentTimeMillis() + ".yml");
            try {
                java.nio.file.Files.move(f.toPath(), backup.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { }
            plugin.saveResource("contract_templates.yml", true);
            plugin.getLogger().warning("ContractManager: contract_templates.yml "
                    + "had empty templates section — restored defaults. "
                    + "Previous file backed up as " + backup.getName() + ".");
        }
    }

    /**
     * v1.8.8 — Load (or reload) all contract templates from contract_templates.yml.
     * Re-substitutes placeholders with the current language pack so a language
     * switch via /pf settings language <code> immediately re-renders contract
     * display-names + descriptions in the new language.
     */
    private synchronized void loadTemplates() {
        ensureDefaultFile();
        File f = getTemplateFile();
        if (!f.exists()) {
            plugin.getLogger().severe("ContractManager: contract_templates.yml missing — "
                    + "no templates loaded.");
            return;
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        templates.clear();

        // v1.8.9 — Read roll settings from the template file's `settings:` block
        // (NOT from config.yml — the old v1.6.0 path `features.contracts.settings.*`
        // doesn't exist in the standard config). Falls back to sensible defaults.
        ConfigurationSection settingsSec = cfg.getConfigurationSection("settings");
        if (settingsSec != null) {
            poolRollSize = Math.max(1, settingsSec.getInt("pool-roll-size", 5));
            dailyContractCount = Math.max(1, settingsSec.getInt("daily-contract-count", 3));
        }

        // Parse the gui config block
        guiConfig = parseGuiConfig(cfg.getConfigurationSection("settings.gui"));

        ConfigurationSection templatesSec = cfg.getConfigurationSection("templates");
        if (templatesSec == null) {
            plugin.getLogger().warning("ContractManager: no 'templates' section in "
                    + "contract_templates.yml — no contracts will be available.");
            return;
        }

        for (String id : templatesSec.getKeys(false)) {
            ConfigurationSection s = templatesSec.getConfigurationSection(id);
            if (s == null) continue;
            try {
                ContractTemplate tpl = parseTemplate(id, s);
                if (tpl != null) {
                    templates.put(id, tpl);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("ContractManager: failed to parse template '"
                        + id + "': " + e.getMessage());
            }
        }

        plugin.getLogger().info("ContractManager: loaded " + templates.size()
                + " contract templates from contract_templates.yml.");
    }

    private ContractGuiConfig parseGuiConfig(ConfigurationSection s) {
        if (s == null) {
            // Sensible defaults
            return new ContractGuiConfig(
                    "<dark_gray>» <gold><bold>Daily Contracts</bold></gold> <dark_gray>«",
                    27, Material.GRAY_STAINED_GLASS_PANE, " ",
                    new int[]{10, 12, 14, 16, 22},
                    Material.PAPER, Material.LIME_CONCRETE, Material.GREEN_STAINED_GLASS);
        }
        String title = s.getString("title",
                "<dark_gray>» <gold><bold>Daily Contracts</bold></gold> <dark_gray>«");
        int size = s.getInt("size", 27);
        Material fillerMat = parseMaterial(
                s.getString("filler.material", "GRAY_STAINED_GLASS_PANE"),
                Material.GRAY_STAINED_GLASS_PANE);
        String fillerName = s.getString("filler.name", " ");
        int[] slots = s.getIntegerList("slots").stream().mapToInt(Integer::intValue).toArray();
        if (slots.length == 0) slots = new int[]{10, 12, 14, 16, 22};
        Material iconActive = parseMaterial(
                s.getString("icons.active", "PAPER"), Material.PAPER);
        Material iconClaimable = parseMaterial(
                s.getString("icons.completed-claimable", "LIME_CONCRETE"),
                Material.LIME_CONCRETE);
        Material iconClaimed = parseMaterial(
                s.getString("icons.completed-claimed", "GREEN_STAINED_GLASS"),
                Material.GREEN_STAINED_GLASS);
        return new ContractGuiConfig(title, size, fillerMat, fillerName, slots,
                iconActive, iconClaimable, iconClaimed);
    }

    private ContractTemplate parseTemplate(String id, ConfigurationSection s) {
        // v1.8.8 — Localized display-name + description via ContentLanguageManager.
        // Resolution order:
        //   1. lang/<active>/contracts.yml: contracts.<id>.display-name
        //   2. lang/en/contracts.yml: contracts.<id>.display-name (auto fallback)
        //   3. Inline display-name from contract_templates.yml (English baseline)
        //   4. "<yellow>" + id (last resort)
        String inlineName = s.getString("display-name", "<yellow>" + id);
        String displayName = plugin.getContentLanguageManager().getContractName(id, inlineName);

        List<String> inlineDesc = s.getStringList("description");
        if (inlineDesc.isEmpty()) inlineDesc = Collections.singletonList("<gray>(no description)");
        List<String> description = plugin.getContentLanguageManager()
                .getContractDescription(id, inlineDesc);

        ConfigurationSection targetSec = s.getConfigurationSection("target");
        if (targetSec == null) {
            plugin.getLogger().warning("Template '" + id + "' missing 'target:' block — skipped.");
            return null;
        }
        TargetType targetType;
        try {
            targetType = TargetType.valueOf(
                    targetSec.getString("type", "ANY").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Template '" + id + "' has invalid target type '"
                    + targetSec.getString("type") + "' — skipped.");
            return null;
        }
        String targetValue = targetSec.getString("value", "").trim();

        Range amount = parseRange(s, "amount", 1, 1);
        ConfigurationSection rewardsSec = s.getConfigurationSection("rewards");
        Range money = parseRange(rewardsSec, "money", 0, 0);
        Range xp = parseRange(rewardsSec, "xp", 0, 0);
        List<ItemReward> items = new ArrayList<>();
        if (rewardsSec != null && rewardsSec.isList("items")) {
            for (Map<?, ?> m : rewardsSec.getMapList("items")) {
                Material mat = parseMaterial(String.valueOf(m.get("material")), Material.AIR);
                if (mat == Material.AIR) continue;
                Object amtObj = m.get("amount");
                int amt = (amtObj instanceof Number) ? ((Number) amtObj).intValue() : 1;
                items.add(new ItemReward(mat, Math.max(1, amt)));
            }
        }

        ConfigurationSection iconSec = s.getConfigurationSection("icon");
        Material iconMat = Material.PAPER;
        int iconCmd = 0;
        if (iconSec != null) {
            iconMat = parseMaterial(iconSec.getString("material", "PAPER"), Material.PAPER);
            iconCmd = iconSec.getInt("custom-model-data", 0);
        }

        return new ContractTemplate(id, displayName, description, targetType, targetValue,
                amount, money, xp, items, iconMat, iconCmd);
    }

    private Range parseRange(ConfigurationSection parent, String key, int defaultMin, int defaultMax) {
        if (parent == null || !parent.contains(key)) {
            return new Range(defaultMin, defaultMax);
        }
        Object raw = parent.get(key);
        if (raw instanceof Number n) {
            int v = n.intValue();
            return new Range(v, v);
        }
        if (raw instanceof Map<?, ?> m) {
            Object minObj = m.get("min");
            Object maxObj = m.get("max");
            int min = (minObj instanceof Number) ? ((Number) minObj).intValue() : defaultMin;
            int max = (maxObj instanceof Number) ? ((Number) maxObj).intValue() : defaultMax;
            return new Range(Math.min(min, max), Math.max(min, max));
        }
        // Could be a ConfigurationSection (block form)
        if (raw instanceof org.bukkit.configuration.MemorySection ms) {
            int min = ms.getInt("min", defaultMin);
            int max = ms.getInt("max", defaultMax);
            return new Range(Math.min(min, max), Math.max(min, max));
        }
        return new Range(defaultMin, defaultMax);
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null || name.trim().isEmpty()) return fallback;
        try {
            return Material.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // =====================================================================
    //  Per-player state persistence (contracts.yml)
    // =====================================================================

    private File getStateFile() {
        return new File(plugin.getDataFolder(), "contracts.yml");
    }

    private synchronized void loadState() {
        File f = getStateFile();
        if (!f.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        for (String uuidStr : cfg.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidStr); }
            catch (IllegalArgumentException e) { continue; }

            ConfigurationSection playerSec = cfg.getConfigurationSection(uuidStr);
            if (playerSec == null) continue;

            String date = playerSec.getString("date", "");
            lastResetDate.put(uuid, date);

            List<Contract> list = new ArrayList<>();
            ConfigurationSection contractsSec = playerSec.getConfigurationSection("contracts");
            if (contractsSec != null) {
                for (String key : contractsSec.getKeys(false)) {
                    ConfigurationSection cSec = contractsSec.getConfigurationSection(key);
                    if (cSec == null) continue;
                    try {
                        list.add(Contract.fromConfig(cSec));
                    } catch (Exception e) {
                        plugin.getLogger().warning("ContractManager: failed to load contract "
                                + key + " for " + uuidStr + ": " + e.getMessage());
                    }
                }
            }
            activeContracts.put(uuid, list);
        }
    }

    public synchronized void save() {
        if (!enabled || !dirty) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, List<Contract>> entry : activeContracts.entrySet()) {
            String uuidStr = entry.getKey().toString();
            cfg.set(uuidStr + ".date", lastResetDate.getOrDefault(entry.getKey(), ""));
            int i = 0;
            for (Contract c : entry.getValue()) {
                c.toConfig(cfg, uuidStr + ".contracts." + i);
                i++;
            }
        }
        try {
            cfg.save(getStateFile());
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().severe("ContractManager: failed to save contracts.yml: "
                    + e.getMessage());
        }
    }

    // =====================================================================
    //  Daily reset + rolling
    // =====================================================================

    public void checkDailyReset(Player player) {
        if (!enabled || player == null) return;
        UUID uuid = player.getUniqueId();
        String today = todayString();
        String last = lastResetDate.get(uuid);
        if (last == null || !last.equals(today)) {
            rollDailyContracts(uuid);
            lastResetDate.put(uuid, today);
            dirty = true;
            if (plugin.getConfig().getBoolean("features.contracts.announce-on-reset", true)
                    || plugin.getConfig().getBoolean(
                            "features.contracts.settings.announce-on-reset", true)) {
                player.sendMessage(miniMessage.deserialize(
                        "<gold><bold>★ New Daily Contracts!</bold></gold> "
                                + "<gray>Open with <yellow>/pf contracts</yellow>."));
            }
        }
    }

    /**
     * v1.8.8 — Roll a fresh set of daily contracts for the player.
     * Shuffles all templates, takes the top N (pool-roll-size), then assigns
     * the first M (daily-contract-count) to the player. For each assigned
     * template, amount / money / xp are rolled within their min-max ranges.
     */
    /**
     * v1.8.9 — Roll a fresh set of daily contracts for the player.
     *
     * <p><b>Anti-duplication fix:</b> Uses a secure shuffle + subList approach
     * with a <b>Set-based duplicate guard</b>. Each template can only appear
     * once in the rolled set — if the shuffle somehow produces duplicates
     * (it can't with {@code Collections.shuffle}, but we defend against it
     * anyway), the duplicate is skipped and the next unique template is taken.</p>
     *
     * <p><b>Null-safe template fetch:</b> Before rolling each contract,
     * {@link #validateTemplate(ContractTemplate)} is called. If the template
     * has null fields (missing display-name, description, target type, etc.),
     * it is skipped and the next valid template is used. If NO valid templates
     * exist, a single fallback contract is generated so the GUI never shows
     * empty slots.</p>
     */
    private synchronized void rollDailyContracts(UUID uuid) {
        // v1.8.9 — Read from the stored fields (loaded from contract_templates.yml)
        int roll = this.poolRollSize;
        int count = this.dailyContractCount;
        if (guiConfig != null && guiConfig.slots() != null) {
            count = Math.min(count, guiConfig.slots().length);
        }
        roll = Math.max(roll, count);

        List<ContractTemplate> pool = new ArrayList<>(templates.values());
        if (pool.isEmpty()) {
            // v1.8.9 — No templates loaded → generate a single fallback contract
            // so the GUI shows something instead of empty slots.
            List<Contract> fallback = new ArrayList<>();
            fallback.add(createFallbackContract());
            activeContracts.put(uuid, fallback);
            plugin.getLogger().warning("ContractManager: no templates loaded — "
                    + "generated fallback contract for player " + uuid);
            return;
        }

        // v1.8.9 — Secure shuffle with ThreadLocalRandom.
        Collections.shuffle(pool, ThreadLocalRandom.current());

        // v1.8.9 — Iterate through the shuffled pool, taking up to `count`
        // UNIQUE, VALID templates. The Set guards against duplicates;
        // validateTemplate() guards against null/malformed templates.
        Set<String> usedTemplateIds = new HashSet<>();
        List<Contract> contracts = new ArrayList<>();
        int scanned = 0;
        while (contracts.size() < count && scanned < pool.size()) {
            ContractTemplate tpl = pool.get(scanned);
            scanned++;

            // Skip null templates
            if (tpl == null) continue;

            // Skip duplicates (defensive — shuffle shouldn't produce them,
            // but a corrupt templates map with duplicate keys could)
            if (usedTemplateIds.contains(tpl.id())) {
                plugin.getLogger().warning("ContractManager: duplicate template ID '"
                        + tpl.id() + "' skipped during roll.");
                continue;
            }

            // Skip templates with null/missing required fields
            if (!validateTemplate(tpl)) {
                plugin.getLogger().warning("ContractManager: template '" + tpl.id()
                        + "' failed validation — skipped.");
                continue;
            }

            usedTemplateIds.add(tpl.id());
            contracts.add(rollContract(tpl));
        }

        // v1.8.9 — If no valid templates were found, generate a fallback
        if (contracts.isEmpty()) {
            contracts.add(createFallbackContract());
            plugin.getLogger().warning("ContractManager: no valid templates after "
                    + "validation — generated fallback contract for player " + uuid);
        }

        activeContracts.put(uuid, contracts);
    }

    /**
     * v1.8.9 — Validate that a template has all required fields non-null.
     * Returns false if any field is null or empty (which would cause the
     * GUI to show "no description" / missing title / missing rewards).
     */
    private boolean validateTemplate(ContractTemplate tpl) {
        if (tpl == null) return false;
        if (tpl.id() == null || tpl.id().trim().isEmpty()) return false;
        if (tpl.displayName() == null || tpl.displayName().trim().isEmpty()) return false;
        if (tpl.description() == null || tpl.description().isEmpty()) return false;
        if (tpl.targetType() == null) return false;
        if (tpl.targetValue() == null) return false;
        if (tpl.amount() == null) return false;
        if (tpl.money() == null) return false;
        if (tpl.xp() == null) return false;
        if (tpl.rewardItems() == null) return false;
        // iconMaterial can be null → defaults to PAPER in parseTemplate
        return true;
    }

    /**
     * v1.8.9 — Create a fallback contract when no valid templates are available.
     * Guarantees the GUI always shows at least one entry with a proper title,
     * description, and reward — never empty slots or "no description" text.
     */
    private Contract createFallbackContract() {
        return new Contract(
                "fallback",
                "<yellow>Daily Contract",
                java.util.List.of("<gray>Catch <white>5</white> fish of any kind."),
                TargetType.ANY,
                "",
                5, 0, 100.0, 25,
                java.util.List.of(),
                0, false
        );
    }

    /**
     * v1.8.9 — Roll a single contract from a template.
     *
     * <p><b>Null-safe:</b> All template fields are null-checked before access.
     * If a field is null, a sensible default is used instead of throwing NPE:</p>
     * <ul>
     *   <li>{@code displayName} null → {@code "<yellow>Daily Contract"}</li>
     *   <li>{@code description} null/empty → {@code ["<gray>Catch target fish"]}</li>
     *   <li>{@code amount} null → Range(1, 1) → amount = 1</li>
     *   <li>{@code money} null → Range(0, 0) → money = 0</li>
     *   <li>{@code xp} null → Range(0, 0) → xp = 0</li>
     *   <li>{@code targetValue} null → empty string</li>
     *   <li>{@code rewardItems} null → empty list</li>
     * </ul>
     *
     * <p>Placeholders ({@code %amount%}, {@code %money%}, {@code %xp%},
     * {@code %value%}) are substituted into the display-name and description
     * lines immediately on roll — so the stored Contract already has
     * fully-resolved text. This fixes the bug where placeholders stayed raw
     * until GUI open.</p>
     */
    private Contract rollContract(ContractTemplate tpl) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // v1.8.9 — Null-safe field access with defaults
        Range amountRange = tpl.amount() != null ? tpl.amount() : new Range(1, 1);
        Range moneyRange = tpl.money() != null ? tpl.money() : new Range(0, 0);
        Range xpRange = tpl.xp() != null ? tpl.xp() : new Range(0, 0);

        int amount = amountRange.roll(rng);
        double money = moneyRange.rollDouble(rng);
        int xp = xpRange.roll(rng);

        String targetValue = tpl.targetValue() != null ? tpl.targetValue() : "";

        // v1.8.9 — Null-safe display-name + description with fallbacks
        String displayName = tpl.displayName() != null ? tpl.displayName() : "<yellow>Daily Contract";
        List<String> templateDesc = tpl.description();
        if (templateDesc == null || templateDesc.isEmpty()) {
            templateDesc = java.util.List.of("<gray>Catch target fish.");
        }
        List<ItemReward> rewardItems = tpl.rewardItems() != null ? tpl.rewardItems() : java.util.List.of();

        // Substitute placeholders into display-name + description
        Map<String, String> ph = new HashMap<>(4);
        ph.put("%amount%", String.valueOf(amount));
        ph.put("%money%", String.format("%.2f", money));
        ph.put("%xp%", String.valueOf(xp));
        ph.put("%value%", targetValue);

        String resolvedName = substitutePlaceholders(displayName, ph);
        List<String> resolvedDesc = new ArrayList<>(templateDesc.size());
        for (String line : templateDesc) {
            resolvedDesc.add(substitutePlaceholders(line, ph));
        }

        return new Contract(tpl.id(), resolvedName, resolvedDesc, tpl.targetType(),
                targetValue, amount, 0, money, xp,
                new ArrayList<>(rewardItems), tpl.iconCmd(), false);
    }

    private String substitutePlaceholders(String text, Map<String, String> ph) {
        if (text == null) return "";
        for (Map.Entry<String, String> e : ph.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text;
    }

    private String todayString() {
        return java.time.LocalDate.now().toString();
    }

    // =====================================================================
    //  Catch processing
    // =====================================================================

    /**
     * v1.8.8 — Called after every fish catch. Updates contract progress.
     * The 3-arg version (no weight) delegates with weight=0 (no weight check).
     */
    public void checkCatch(Player player, String fishId, int amount) {
        checkCatch(player, fishId, amount, 0.0);
    }

    /**
     * v1.8.8 — 4-arg version accepting the fish weight (kg). Used by the
     * v1.7.0+ FishRewardHelper signature. WEIGHT_MILESTONE contracts only
     * count fish whose weight ≥ the template's value threshold.
     */
    public void checkCatch(Player player, String fishId, int amount, double weight) {
        if (!enabled || player == null || fishId == null) return;
        UUID uuid = player.getUniqueId();
        List<Contract> contracts = activeContracts.get(uuid);
        if (contracts == null || contracts.isEmpty()) return;

        // Look up the caught fish's rarity once
        int fishRarity = -1;
        if (plugin.getFishManager() != null) {
            CustomFish fish = plugin.getFishManager().getFishById(fishId);
            if (fish != null) {
                fishRarity = fish.rarity();
            }
        }

        boolean updated = false;
        for (int i = 0; i < contracts.size(); i++) {
            Contract c = contracts.get(i);
            if (c.claimed() || c.isCompleted()) continue;

            boolean matches = matchesContract(c, fishId, fishRarity, weight);
            if (matches) {
                int newCaught = Math.min(c.amountCaught() + amount, c.amountNeeded());
                Contract updated2 = c.withCatch(amount);
                contracts.set(i, updated2);
                updated = true;

                if (updated2.isCompleted()) {
                    player.sendMessage(miniMessage.deserialize(
                            "<green><bold>✔ Contract Complete!</bold></green> <white>"
                                    + updated2.displayName() + " <dark_gray>— <gray>Click to claim in /pf contracts"));
                }
            }
        }
        if (updated) {
            dirty = true;
        }
    }

    private boolean matchesContract(Contract c, String fishId, int fishRarity, double weight) {
        if (c.claimed() || c.isCompleted()) return false;
        switch (c.targetType()) {
            case ANY:
                return true;
            case ANY_RARITY:
                int targetRarity = parseRarityValue(c.targetValue());
                return targetRarity > 0 && fishRarity == targetRarity;
            case FISH_ID:
                return c.targetValue().equalsIgnoreCase(fishId);
            case WEIGHT_MILESTONE:
                double threshold = parseDouble(c.targetValue(), 0.0);
                return weight >= threshold;
            default:
                return false;
        }
    }

    private int parseRarityValue(String value) {
        if (value == null) return -1;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "common", "1" -> 1;
            case "uncommon", "2" -> 2;
            case "rare", "3" -> 3;
            case "epic", "4" -> 4;
            case "legendary", "5" -> 5;
            default -> -1;
        };
    }

    private double parseDouble(String s, double fallback) {
        try { return Double.parseDouble(s.trim()); }
        catch (Exception e) { return fallback; }
    }

    // =====================================================================
    //  Click-to-claim reward dispatch
    // =====================================================================

    /**
     * v1.8.8 — Called by AchievementContractListener when a player clicks a
     * contract slot in the GUI. If the contract is completed but not yet
     * claimed, dispatches rewards (money + XP + items) and marks it claimed.
     *
     * <p>Robust against:</p>
     * <ul>
     *   <li><b>Double-claims</b> — the {@code claimed} flag is checked + set
     *       atomically before any reward is dispatched.</li>
     *   <li><b>Null inventories</b> — guarded with {@code if (player == null)}.</li>
     *   <li><b>Item overflow</b> — items that don't fit in the player's inventory
     *       are dropped at the player's location (no silent loss).</li>
     *   <li><b>Invalid slot indices</b> — returns false if the slot doesn't
     *       map to a contract.</li>
     * </ul>
     *
     * @return true if a claim happened (the GUI should be refreshed).
     */
    public synchronized boolean handleSlotClick(Player player, int slot) {
        if (!enabled || player == null) return false;
        UUID uuid = player.getUniqueId();
        List<Contract> contracts = activeContracts.get(uuid);
        if (contracts == null) return false;

        int contractIndex = slotToContractIndex(slot);
        if (contractIndex < 0 || contractIndex >= contracts.size()) return false;

        Contract c = contracts.get(contractIndex);
        if (c.claimed()) return false;
        if (!c.isCompleted()) return false;

        // ── Dispatch money reward ──
        if (c.rewardMoney() > 0) {
            var cache = plugin.getCacheManager();
            if (cache != null) {
                var data = cache.getData(uuid);
                if (data != null) {
                    data.addBalance(c.rewardMoney());
                }
            }
        }

        // ── Dispatch XP reward ──
        if (c.rewardXp() > 0) {
            var cache = plugin.getCacheManager();
            if (cache != null) {
                cache.addXpAndCheckLevel(player, c.rewardXp());
            }
        }

        // ── Dispatch item rewards (overflow drops to the ground) ──
        for (ItemReward ir : c.rewardItems()) {
            ItemStack stack = new ItemStack(ir.material(), Math.max(1, ir.amount()));
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }

        // ── Mark as claimed (atomic: set BEFORE sending message) ──
        contracts.set(contractIndex, c.markClaimed());
        dirty = true;

        // ── Notify the player ──
        java.util.Map<String, String> ph = new java.util.HashMap<>(3);
        ph.put("%name%", c.displayName());
        ph.put("%money%", String.format("%.2f", c.rewardMoney()));
        ph.put("%xp%", String.valueOf(c.rewardXp()));
        String claimMsg = plugin.getMessageManager().getPlainMessage("contract_claimed", ph);
        if (claimMsg == null || "contract_claimed".equals(claimMsg) || claimMsg.isEmpty()) {
            claimMsg = "<green><bold>✔ Reward Claimed!</bold></green> <white>%name%</white> "
                    + "<dark_gray>— <gold>$%money% <aqua>+ %xp% XP";
            claimMsg = substitutePlaceholders(claimMsg, ph);
        }
        player.sendMessage(miniMessage.deserialize(claimMsg));
        player.playSound(player, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);

        return true;
    }

    /** Map a GUI slot index to a contract list index (or -1 if not a contract slot). */
    private int slotToContractIndex(int slot) {
        for (int i = 0; i < guiConfig.slots.length; i++) {
            if (guiConfig.slots[i] == slot) return i;
        }
        return -1;
    }

    // =====================================================================
    //  GUI
    // =====================================================================

    public static class ContractGUIHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() { return null; }
    }

    public void openGUI(Player player) {
        if (!enabled) {
            String disabledMsg = plugin.getMessageManager().getPlainMessage("contracts_disabled");
            if (disabledMsg == null || "contracts_disabled".equals(disabledMsg) || disabledMsg.isEmpty()) {
                disabledMsg = "<red>Contract system is disabled.";
            }
            player.sendMessage(miniMessage.deserialize(disabledMsg));
            return;
        }
        // v1.8.9 — Ensure guiConfig is initialized (defensive: if loadTemplates
        // failed before parsing the gui block, guiConfig could be null).
        if (guiConfig == null) {
            guiConfig = parseGuiConfig(null);
        }

        checkDailyReset(player);
        List<Contract> contracts = activeContracts.getOrDefault(
                player.getUniqueId(), Collections.emptyList());

        // v1.8.9 — If contracts list is null or empty (e.g. first open before
        // daily reset fired), trigger a roll immediately so the GUI never
        // shows empty slots.
        if (contracts == null || contracts.isEmpty()) {
            rollDailyContracts(player.getUniqueId());
            contracts = activeContracts.getOrDefault(
                    player.getUniqueId(), Collections.emptyList());
        }

        Inventory inv = Bukkit.createInventory(new ContractGUIHolder(), guiConfig.size,
                miniMessage.deserialize(guiConfig.title));

        // Fill all slots with the filler item first
        ItemStack filler = buildFiller();
        for (int i = 0; i < guiConfig.size; i++) {
            inv.setItem(i, filler);
        }

        // Place each contract in its configured slot
        for (int i = 0; i < contracts.size() && i < guiConfig.slots.length; i++) {
            inv.setItem(guiConfig.slots[i], buildContractItem(contracts.get(i)));
        }

        player.openInventory(inv);
    }

    private ItemStack buildFiller() {
        ItemStack item = new ItemStack(guiConfig.fillerMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(guiConfig.fillerName));
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildContractItem(Contract c) {
        boolean done = c.isCompleted();
        boolean claimed = c.claimed();
        Material mat = claimed ? guiConfig.iconClaimed
                : (done ? guiConfig.iconClaimable : guiConfig.iconActive);

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // v1.8.9 — Null-safe display name with fallback.
        // If the contract was loaded from a legacy contracts.yml with a missing
        // displayName field, fall back to "Daily Contract" instead of rendering
        // an empty title.
        String displayName = c.displayName();
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "<yellow>Daily Contract";
        }

        // Display name — color reflects state
        String nameColor = claimed ? "<green>" : (done ? "<green><bold>" : "<yellow>");
        String closeBold = done && !claimed ? "</bold>" : "";
        meta.displayName(miniMessage.deserialize(nameColor + displayName + closeBold));

        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();

        // v1.8.9 — Null-safe description with fallback.
        // If description is null/empty, show "Catch target fish." instead of
        // rendering nothing (which caused the "no description" bug).
        List<String> description = c.description();
        if (description == null || description.isEmpty()) {
            description = java.util.List.of("<gray>Catch target fish.");
        }
        for (String line : description) {
            if (line != null && !line.isEmpty()) {
                lore.add(miniMessage.deserialize(line));
            }
        }
        lore.add(miniMessage.deserialize("<dark_gray>───────────────"));

        // Target + progress — null-safe
        String targetLine = "<gray>Target: <white>" + humanTarget(c) + " <dark_gray>("
                + humanQualifier(c) + ")";
        lore.add(miniMessage.deserialize(targetLine));

        int amountNeeded = c.amountNeeded();
        int amountCaught = c.amountCaught();
        String progressColor = done ? "<green>" : "<yellow>";
        String progressLine = "<gray>Progress: " + progressColor + amountCaught
                + "<gray>/<white>" + amountNeeded;
        if (done && !claimed) progressLine += " <green>✔";
        if (claimed) progressLine += " <dark_gray>(claimed)";
        lore.add(miniMessage.deserialize(progressLine));

        // Rewards block — null-safe
        lore.add(miniMessage.deserialize("<dark_gray>───────────────"));
        lore.add(miniMessage.deserialize("<gray>Rewards:"));
        double rewardMoney = c.rewardMoney();
        if (rewardMoney > 0) {
            lore.add(miniMessage.deserialize(" <dark_gray>• <gold>$"
                    + String.format("%.2f", rewardMoney)));
        }
        int rewardXp = c.rewardXp();
        if (rewardXp > 0) {
            lore.add(miniMessage.deserialize(" <dark_gray>• <aqua>" + rewardXp + " XP"));
        }
        List<ItemReward> rewardItems = c.rewardItems();
        if (rewardItems != null) {
            for (ItemReward ir : rewardItems) {
                if (ir != null && ir.material() != null) {
                    lore.add(miniMessage.deserialize(" <dark_gray>• <white>"
                            + ir.amount() + "x " + prettifyMaterial(ir.material())));
                }
            }
        }

        // State prompt
        if (done && !claimed) {
            lore.add(miniMessage.deserialize("<dark_gray>───────────────"));
            lore.add(miniMessage.deserialize("<green><bold>► Click to Claim!</bold></green>"));
        } else if (claimed) {
            lore.add(miniMessage.deserialize("<dark_gray>───────────────"));
            lore.add(miniMessage.deserialize("<dark_gray>✔ Reward already claimed"));
        }

        if (c.iconCmd() != 0) {
            meta.setCustomModelData(c.iconCmd());
        }

        meta.lore(lore);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    private String humanTarget(Contract c) {
        TargetType tt = c.targetType();
        if (tt == null) return "Any Fish";
        String tv = c.targetValue() != null ? c.targetValue() : "";
        return switch (tt) {
            case ANY -> "Any Fish";
            case ANY_RARITY -> "Any " + capitalize(tv) + " Fish";
            case FISH_ID -> tv.isEmpty() ? "Any Fish" : tv;
            case WEIGHT_MILESTONE -> "Fish ≥ " + tv + " kg";
        };
    }

    private String humanQualifier(Contract c) {
        TargetType tt = c.targetType();
        if (tt == null) return "any fish";
        String tv = c.targetValue() != null ? c.targetValue().toLowerCase() : "";
        return switch (tt) {
            case ANY -> "any fish";
            case ANY_RARITY -> "rarity: " + tv;
            case FISH_ID -> "specific fish";
            case WEIGHT_MILESTONE -> "weight ≥ " + c.targetValue() + " kg";
        };
    }

    private String prettifyMaterial(Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    // =====================================================================
    //  Lifecycle helpers
    // =====================================================================

    public void shutdown() { save(); }

    public void clear(UUID uuid) {
        activeContracts.remove(uuid);
        lastResetDate.remove(uuid);
        dirty = true;
    }

    // =====================================================================
    //  Records
    // =====================================================================

    public enum TargetType {
        ANY, ANY_RARITY, FISH_ID, WEIGHT_MILESTONE
    }

    public record Range(int min, int max) {
        public int roll(java.util.concurrent.ThreadLocalRandom rng) {
            if (min == max) return min;
            return rng.nextInt(min, max + 1);
        }
        public double rollDouble(java.util.concurrent.ThreadLocalRandom rng) {
            if (min == max) return min;
            return rng.nextDouble(min, max + 1);
        }
    }

    public record ItemReward(Material material, int amount) {}

    public record ContractTemplate(
            String id,
            String displayName,
            List<String> description,
            TargetType targetType,
            String targetValue,
            Range amount,
            Range money,
            Range xp,
            List<ItemReward> rewardItems,
            Material iconMaterial,
            int iconCmd
    ) {}

    /**
     * v1.8.8 — Per-player contract instance. All text fields (displayName,
     * description) are already placeholder-substituted at roll time so the
     * stored data is self-contained.
     */
    public record Contract(
            String templateId,
            String displayName,
            List<String> description,
            TargetType targetType,
            String targetValue,
            int amountNeeded,
            int amountCaught,
            double rewardMoney,
            int rewardXp,
            List<ItemReward> rewardItems,
            int iconCmd,
            boolean claimed
    ) {
        public boolean isCompleted() { return amountCaught >= amountNeeded; }

        public Contract withCatch(int amount) {
            return new Contract(templateId, displayName, description, targetType, targetValue,
                    amountNeeded, Math.min(amountCaught + amount, amountNeeded),
                    rewardMoney, rewardXp, rewardItems, iconCmd, claimed);
        }

        public Contract markClaimed() {
            return new Contract(templateId, displayName, description, targetType, targetValue,
                    amountNeeded, amountCaught, rewardMoney, rewardXp, rewardItems, iconCmd, true);
        }

        public void toConfig(YamlConfiguration cfg, String base) {
            cfg.set(base + ".template", templateId);
            cfg.set(base + ".displayName", displayName);
            cfg.set(base + ".description", description);
            cfg.set(base + ".targetType", targetType.name());
            cfg.set(base + ".targetValue", targetValue);
            cfg.set(base + ".amountNeeded", amountNeeded);
            cfg.set(base + ".amountCaught", amountCaught);
            cfg.set(base + ".rewardMoney", rewardMoney);
            cfg.set(base + ".rewardXp", rewardXp);
            cfg.set(base + ".iconCmd", iconCmd);
            cfg.set(base + ".claimed", claimed);
            // Items
            List<Map<String, Object>> itemsList = new ArrayList<>();
            for (ItemReward ir : rewardItems) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("material", ir.material().name());
                m.put("amount", ir.amount());
                itemsList.add(m);
            }
            cfg.set(base + ".rewardItems", itemsList);
        }

        public static Contract fromConfig(ConfigurationSection c) {
            // v1.8.9 — Null-safe loading with fallbacks for every field.
            // Handles legacy contracts.yml entries (pre-v1.8.8) that may be
            // missing the new displayName/description/targetType fields.
            String templateId = c.getString("template", "");
            if (templateId == null || templateId.trim().isEmpty()) templateId = "unknown";

            String displayName = c.getString("displayName", null);
            if (displayName == null || displayName.trim().isEmpty()) {
                // v1.8.9 — Fallback to a proper title instead of the raw templateId
                displayName = "<yellow>Daily Contract";
            }

            List<String> description = c.getStringList("description");
            if (description == null || description.isEmpty()) {
                description = java.util.List.of("<gray>Catch target fish.");
            }

            TargetType targetType;
            try {
                String ttStr = c.getString("targetType", "ANY");
                targetType = TargetType.valueOf(ttStr != null ? ttStr.toUpperCase() : "ANY");
            } catch (IllegalArgumentException e) {
                targetType = TargetType.ANY;
            }

            String targetValue = c.getString("targetValue", "");
            if (targetValue == null) targetValue = "";

            int amountNeeded = Math.max(1, c.getInt("amountNeeded", 1));
            int amountCaught = Math.max(0, c.getInt("amountCaught", 0));
            double rewardMoney = Math.max(0, c.getDouble("rewardMoney", 0));
            int rewardXp = Math.max(0, c.getInt("rewardXp", 0));
            int iconCmd = c.getInt("iconCmd", 0);
            boolean claimed = c.getBoolean("claimed", false);

            List<ItemReward> items = new ArrayList<>();
            List<Map<?, ?>> rawItems = c.getMapList("rewardItems");
            if (rawItems != null) {
                for (Map<?, ?> m : rawItems) {
                    if (m == null) continue;
                    Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
                    if (mat == null) continue;
                    Object amtObj = m.get("amount");
                    int amt = (amtObj instanceof Number) ? ((Number) amtObj).intValue() : 1;
                    items.add(new ItemReward(mat, Math.max(1, amt)));
                }
            }

            return new Contract(templateId, displayName, description, targetType, targetValue,
                    amountNeeded, amountCaught, rewardMoney, rewardXp, items, iconCmd, claimed);
        }
    }

    private record ContractGuiConfig(
            String title,
            int size,
            Material fillerMaterial,
            String fillerName,
            int[] slots,
            Material iconActive,
            Material iconClaimable,
            Material iconClaimed
    ) {}
}
