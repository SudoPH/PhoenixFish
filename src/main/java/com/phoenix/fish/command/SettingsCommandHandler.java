package com.phoenix.fish.command;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v1.8.5 — In-game Admin Settings command ({@code /pf settings}).
 *
 * <p>Two subcommands:</p>
 * <ol>
 *   <li><b>{@code /pf settings language &lt;lang_code&gt;}</b> — dynamically
 *       update the {@code language:} setting in {@code config.yml}, reload
 *       all language/translation assets (messages, fish, baits, rods,
 *       contracts, achievements, skills), and instantly notify the admin.</li>
 *   <li><b>{@code /pf settings features &lt;feature_name&gt; &lt;true|false&gt;}</b>
 *       — dynamically write the new boolean value to {@code config.yml}
 *       under the {@code features.} path, re-initialize or unload the
 *       corresponding Manager instance immediately, and apply changes
 *       in real-time without requiring a full server restart.</li>
 * </ol>
 *
 * <p>Both subcommands require the {@code phoenixfish.admin} permission node.
 * The handler delegates the actual config mutation + reload to
 * {@link PhoenixFish#setConfigValueAndReload(String, Object)}, which
 * atomically writes the change to {@code config.yml} and triggers the
 * full {@code reloadAll()} chain — same code path as {@code /pf reload}.</p>
 *
 * <p>For features, the handler recognizes all flags under {@code features.*}
 * in the config, including the v1.6.0 modular feature toggles (minigame-effects,
 * seasonal-fish, day-night-fish, depth-fish, fish-mutations, fishing-stats,
 * dynamic-pricing, contracts, seasons, discovery-rewards, achievements,
 * discovery-progress, skill-big-catch, skill-lucky-cast, skill-stamina,
 * placeholder-api). The feature name is matched case-insensitively, and
 * the handler accepts either the full dotted path ({@code skill-stamina.enabled})
 * or the short feature name ({@code skill-stamina}) which expands to
 * {@code features.skill-stamina.enabled}.</p>
 */
public class SettingsCommandHandler {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PhoenixFish plugin;

    public SettingsCommandHandler(PhoenixFish plugin) {
        this.plugin = plugin;
    }

    /**
     * Entry point — called by FishCommand when args[0] == "settings".
     *
     * @param sender the command sender (player or console)
     * @param args   the full args array; args[0] is "settings"
     */
    public void handle(CommandSender sender, String[] args) {
        // v1.8.5 — Require phoenixfish.settings (which is inherited by phoenixfish.admin).
        if (!sender.hasPermission("phoenixfish.settings") && !sender.hasPermission("phoenixfish.admin")) {
            sender.sendMessage(plugin.getMessageManager().getMessage("no_permission", true));
            return;
        }

        if (args.length < 2) {
            sendUsage(sender);
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "language" -> handleLanguage(sender, args);
            case "features", "feature" -> handleFeatures(sender, args);
            case "list" -> handleList(sender);
            default -> sendUsage(sender);
        }
    }

    // =====================================================================
    //  /pf settings language <lang_code>
    // =====================================================================

    private void handleLanguage(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(MM.deserialize("<red>Usage: <yellow>/pf settings language <lang_code>"));
            sender.sendMessage(MM.deserialize("<gray>Available: <white>"
                    + String.join(", ", plugin.getContentLanguageManager().listAvailableLanguages())));
            return;
        }

        String requested = args[2].trim().toLowerCase(Locale.ROOT);
        List<String> available = plugin.getContentLanguageManager().listAvailableLanguages();
        if (!available.contains(requested)) {
            sender.sendMessage(MM.deserialize("<red>Unknown language code: <yellow>" + requested));
            sender.sendMessage(MM.deserialize("<gray>Available: <white>" + String.join(", ", available)));
            return;
        }

        String current = plugin.getContentLanguageManager().getLanguage();
        if (current.equals(requested)) {
            sender.sendMessage(MM.deserialize("<yellow>Language is already set to <white>" + current
                    + "<yellow>. No change needed."));
            return;
        }

        sender.sendMessage(MM.deserialize("<gray>Switching language: <yellow>" + current
                + " <gray>→ <green>" + requested + "<gray>. Reloading..."));

        // Update the top-level `language:` option in config.yml and trigger a
        // full reloadAll() — this reloads MessageManager + ContentLanguageManager
        // + all downstream managers (FishManager, BaitManager, ItemFixer, etc.).
        plugin.setConfigValueAndReload("language", requested);

        sender.sendMessage(MM.deserialize("<green>✔ Language switched to <white>"
                + requested + "</white>. All messages, fish, baits, rods, contracts, "
                + "achievements, and skills are now using the new language."));
    }

    // =====================================================================
    //  /pf settings features <feature_name> <true|false>
    // =====================================================================

    private void handleFeatures(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(MM.deserialize("<red>Usage: <yellow>/pf settings features <feature_name> <true|false>"));
            sender.sendMessage(MM.deserialize("<gray>Available features (use /pf settings list for full details):"));
            List<String> features = listFeatureNames();
            for (String f : features) {
                boolean enabled = plugin.getConfig().getBoolean("features." + f + ".enabled", false);
                sender.sendMessage(MM.deserialize(" <dark_gray>• <white>" + f
                        + " <dark_gray>= <yellow>" + enabled));
            }
            return;
        }

        String featureName = args[2].trim().toLowerCase(Locale.ROOT);
        String boolStr = args[3].trim().toLowerCase(Locale.ROOT);

        Boolean newValue;
        if ("true".equals(boolStr) || "on".equals(boolStr) || "1".equals(boolStr) || "yes".equals(boolStr)) {
            newValue = Boolean.TRUE;
        } else if ("false".equals(boolStr) || "off".equals(boolStr) || "0".equals(boolStr) || "no".equals(boolStr)) {
            newValue = Boolean.FALSE;
        } else {
            sender.sendMessage(MM.deserialize("<red>Invalid boolean value: <yellow>" + args[3]
                    + "<red>. Use <yellow>true<red> or <yellow>false<red>."));
            return;
        }

        // Resolve the config path.
        // Accept either "skill-stamina" (short → features.skill-stamina.enabled)
        // or "skill-stamina.enabled" (full path under features.*).
        String configPath = resolveFeaturePath(featureName);
        if (configPath == null) {
            sender.sendMessage(MM.deserialize("<red>Unknown feature: <yellow>" + featureName));
            sender.sendMessage(MM.deserialize("<gray>Run <yellow>/pf settings list<gray> to see available features."));
            return;
        }

        boolean currentValue = plugin.getConfig().getBoolean(configPath, false);
        if (currentValue == newValue) {
            sender.sendMessage(MM.deserialize("<yellow>Feature <white>" + featureName
                    + " <yellow>is already <white>" + newValue + "<yellow>. No change needed."));
            return;
        }

        sender.sendMessage(MM.deserialize("<gray>Toggling feature <yellow>" + featureName
                + " <gray>: <yellow>" + currentValue + " <gray>→ <green>" + newValue
                + "<gray>. Reloading manager..."));

        // Update config.yml and trigger the full reloadAll() chain. Each manager's
        // reload() method handles the enable/disable transition: load state if newly
        // enabled, save + clear state if newly disabled.
        plugin.setConfigValueAndReload(configPath, newValue);

        sender.sendMessage(MM.deserialize("<green>✔ Feature <white>" + featureName
                + "</white> is now <white>" + newValue + "</white>. Manager reinitialized."));
    }

    // =====================================================================
    //  /pf settings list — show all features + current values
    // =====================================================================

    private void handleList(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gold><bold>=== PhoenixFish Settings ===</bold></gold>"));

        // Language
        String lang = plugin.getContentLanguageManager().getLanguage();
        sender.sendMessage(MM.deserialize("<gray>Language: <yellow>" + lang));
        sender.sendMessage(MM.deserialize("<gray>Available: <white>"
                + String.join(", ", plugin.getContentLanguageManager().listAvailableLanguages())));

        // Features
        sender.sendMessage(MM.deserialize("<gold><bold>Features:</bold></gold>"));
        List<Map.Entry<String, Boolean>> features = listFeaturesWithValues();
        for (Map.Entry<String, Boolean> entry : features) {
            String name = entry.getKey();
            boolean enabled = entry.getValue();
            String color = enabled ? "<green>" : "<red>";
            String status = enabled ? "ON" : "OFF";
            sender.sendMessage(MM.deserialize(" <dark_gray>• <white>" + name
                    + " <dark_gray>= " + color + status));
        }

        // v1.8.6 — Skill sub-toggles
        sender.sendMessage(MM.deserialize("<gold><bold>Skill Sub-Toggles:</bold></gold>"));
        boolean masterEnabled = plugin.getConfig().getBoolean("features.skills.enabled", true);
        String masterColor = masterEnabled ? "<green>" : "<red>";
        sender.sendMessage(MM.deserialize(" <dark_gray>• <white>skills (master) <dark_gray>= "
                + masterColor + (masterEnabled ? "ON" : "OFF")));
        if (masterEnabled) {
            for (String skillConfigId : com.phoenix.fish.manager.SkillManager.getAllSkillConfigIds()) {
                boolean enabled = plugin.getConfig().getBoolean(
                        "features.skills.types." + skillConfigId, false);
                String color = enabled ? "<green>" : "<red>";
                sender.sendMessage(MM.deserialize("   <dark_gray>• <gray>skills.types.<white>"
                        + skillConfigId + " <dark_gray>= " + color + (enabled ? "ON" : "OFF")));
            }
        }
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gold><bold>PhoenixFish Settings</bold></gold>"));
        sender.sendMessage(MM.deserialize("<gray>Usage:"));
        sender.sendMessage(MM.deserialize("<yellow>  /pf settings language <lang_code>"));
        sender.sendMessage(MM.deserialize("<gray>    Switch the active language. Reloads all assets instantly."));
        sender.sendMessage(MM.deserialize("<yellow>  /pf settings features <feature_name> <true|false>"));
        sender.sendMessage(MM.deserialize("<gray>    Toggle a feature flag under features.* in config.yml."));
        sender.sendMessage(MM.deserialize("<yellow>  /pf settings features skills <true|false>"));
        sender.sendMessage(MM.deserialize("<gray>    Master toggle for the entire skill system."));
        sender.sendMessage(MM.deserialize("<yellow>  /pf settings features skills.types.<skill_id> <true|false>"));
        sender.sendMessage(MM.deserialize("<gray>    Sub-toggle for a specific skill (fast, hunter, double, bait, big-catch, lucky-cast, stamina)."));
        sender.sendMessage(MM.deserialize("<yellow>  /pf settings list"));
        sender.sendMessage(MM.deserialize("<gray>    Show current language + all feature flags + skill sub-toggles."));
    }

    /**
     * Return all top-level feature names under {@code features.*} in config.yml.
     * Each feature is a ConfigurationSection with an {@code enabled:} boolean.
     */
    private List<String> listFeatureNames() {
        List<String> result = new ArrayList<>();
        ConfigurationSection featuresSec = plugin.getConfig().getConfigurationSection("features");
        if (featuresSec == null) return result;
        for (String key : featuresSec.getKeys(false)) {
            ConfigurationSection child = featuresSec.getConfigurationSection(key);
            if (child != null && child.contains("enabled")) {
                result.add(key);
            }
        }
        return result;
    }

    /**
     * Return all top-level feature names + their current enabled values.
     */
    private List<Map.Entry<String, Boolean>> listFeaturesWithValues() {
        List<Map.Entry<String, Boolean>> result = new ArrayList<>();
        for (String name : listFeatureNames()) {
            boolean enabled = plugin.getConfig().getBoolean("features." + name + ".enabled", false);
            result.add(new LinkedHashMap.SimpleEntry<>(name, enabled));
        }
        return result;
    }

    /**
     * Resolve a user-supplied feature name to a config path.
     * <p>Accepts:</p>
     * <ul>
     *   <li>"skill-stamina" → "features.skill-stamina.enabled"</li>
     *   <li>"skill-stamina.enabled" → "features.skill-stamina.enabled"</li>
     *   <li>"daily-contracts" → "features.contracts.enabled" (alias)</li>
     * </ul>
     * <p>Returns null if the feature name doesn't match anything under
     * {@code features.*}.</p>
     */
    private String resolveFeaturePath(String userInput) {
        String input = userInput.toLowerCase(Locale.ROOT);

        // ── v1.8.6 — Skill system master + sub-toggle paths ──
        // /pf settings features skills                  → features.skills.enabled
        // /pf settings features skills.enabled          → features.skills.enabled
        // /pf settings features skills.types.stamina    → features.skills.types.stamina
        // /pf settings features skills.types.big-catch  → features.skills.types.big-catch
        if ("skills".equals(input) || "skills.enabled".equals(input)) {
            return "features.skills.enabled";
        }
        if (input.startsWith("skills.types.")) {
            String skillConfigId = input.substring("skills.types.".length());
            // Validate against the known skill config IDs
            if (com.phoenix.fish.manager.SkillManager.getAllSkillConfigIds().contains(skillConfigId)) {
                return "features.skills.types." + skillConfigId;
            }
            // Even if not in the known list, accept it if the config path exists
            // (admin may have added a custom skill)
            String testPath = "features.skills.types." + skillConfigId;
            if (plugin.getConfig().contains(testPath)) {
                return testPath;
            }
        }

        // Built-in aliases — accept common alternative names.
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("daily-contracts", "features.contracts.enabled");
        aliases.put("contracts", "features.contracts.enabled");
        aliases.put("achievements", "features.achievements.enabled");
        aliases.put("seasons", "features.seasons.enabled");
        aliases.put("seasonal-fish", "features.seasonal-fish.enabled");
        aliases.put("day-night-fish", "features.day-night-fish.enabled");
        aliases.put("depth-fish", "features.depth-fish.enabled");
        aliases.put("fish-mutations", "features.fish-mutations.enabled");
        aliases.put("mutations", "features.fish-mutations.enabled");
        aliases.put("minigame-effects", "features.minigame-effects.enabled");
        aliases.put("minigame-presets", "features.minigame-presets.enabled");
        aliases.put("fishing-stats", "features.fishing-stats.enabled");
        aliases.put("dynamic-pricing", "features.dynamic-pricing.enabled");
        aliases.put("discovery-rewards", "features.discovery-rewards.enabled");
        aliases.put("discovery-progress", "features.discovery-progress.enabled");
        // v1.8.6 — Legacy skill aliases now point to the NEW skills.types.* path
        aliases.put("skill-big-catch", "features.skills.types.big-catch");
        aliases.put("skill-lucky-cast", "features.skills.types.lucky-cast");
        aliases.put("skill-stamina", "features.skills.types.stamina");
        aliases.put("placeholder-api", "features.placeholder-api.enabled");

        // Try aliases first.
        String aliased = aliases.get(input);
        if (aliased != null) return aliased;

        // Try the short form: features.<userInput>.enabled
        String shortPath = "features." + userInput + ".enabled";
        if (plugin.getConfig().contains(shortPath)) {
            return shortPath;
        }

        // Try the full form: features.<userInput> (already includes .enabled or similar)
        String fullPath = "features." + userInput;
        if (plugin.getConfig().contains(fullPath)) {
            return fullPath;
        }

        // Try the bare path (without "features." prefix) — admin may have typed
        // the full dotted path themselves.
        if (plugin.getConfig().contains(userInput)) {
            return userInput;
        }

        return null;
    }
}
