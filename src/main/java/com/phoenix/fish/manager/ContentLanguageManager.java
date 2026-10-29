package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.8.3 — Full content localization loader.
 *
 * <p>Extends message-only localization to cover ALL plugin content: fish
 * species, baits, custom rods, contract templates, achievement definitions,
 * and skill tree entries.</p>
 *
 * <p><b>File layout</b> — a "language pack" is a directory under
 * {@code <dataFolder>/lang/<code>/} containing 7 YAML files:</p>
 * <pre>
 *   lang/
 *     en/
 *       messages.yml      (chat / GUI messages — formerly messages_en.yml)
 *       fish.yml          (per-fish display-name + lore)
 *       baits.yml         (per-bait display-name + lore)
 *       rods.yml          (per-rod display-name + lore)
 *       contracts.yml     (per-contract-template display-name + description)
 *       achievements.yml  (per-achievement-id title + lore)
 *       skills.yml        (per-skill-id name + description)
 *     tr/  (same 7 files, Turkish)
 *     zh_cn/ (same 7 files, Simplified Chinese)
 * </pre>
 *
 * <p><b>Fallback policy</b> — for any key lookup, the active language's
 * file is consulted first. If the key is missing, the bundled English
 * file is consulted. If English is also missing, the caller's hardcoded
 * default is returned. Implemented via {@link FileConfiguration#setDefaults}
 * so missing keys auto-fill silently — no NPEs, no raw key leakage.</p>
 *
 * <p><b>Reload semantics</b> — calling {@link #reload()} re-reads the
 * active language pack from disk in-place. {@code /pf reload} triggers
 * this, so changing the {@code language:} option in config.yml takes
 * effect immediately without a server restart.</p>
 */
public class ContentLanguageManager {

    private final PhoenixFish plugin;

    public enum ContentType {
        MESSAGES("messages.yml", "messages"),
        FISH("fish.yml", "fishes"),
        BAITS("baits.yml", "baits"),
        RODS("rods.yml", "rods"),
        CONTRACTS("contracts.yml", "contracts"),
        ACHIEVEMENTS("achievements.yml", "achievements"),
        SKILLS("skills.yml", "skills");

        public final String fileName;
        public final String rootKey;

        ContentType(String fileName, String rootKey) {
            this.fileName = fileName;
            this.rootKey = rootKey;
        }
    }

    private static final class PackSnapshot {
        final String lang;
        final Map<ContentType, FileConfiguration> configs;

        PackSnapshot(String lang, Map<ContentType, FileConfiguration> configs) {
            this.lang = lang;
            this.configs = configs;
        }

        FileConfiguration get(ContentType type) {
            return configs.get(type);
        }
    }

    private volatile PackSnapshot snapshot;
    private final Object loadLock = new Object();

    private final Map<ContentType, FileConfiguration> bundledEnglishDefaults = new ConcurrentHashMap<>();

    public ContentLanguageManager(PhoenixFish plugin) {
        this.plugin = plugin;
        loadBundledEnglishDefaults();
        reload();
    }

    private void loadBundledEnglishDefaults() {
        for (ContentType type : ContentType.values()) {
            String resourcePath = "lang/en/" + type.fileName;
            try (InputStream in = plugin.getResource(resourcePath)) {
                if (in == null) {
                    if (type == ContentType.MESSAGES) {
                        try (InputStream legacy = plugin.getResource("messages_en.yml")) {
                            if (legacy != null) {
                                FileConfiguration cfg = YamlConfiguration.loadConfiguration(
                                        new InputStreamReader(legacy, StandardCharsets.UTF_8));
                                bundledEnglishDefaults.put(type, cfg);
                            }
                        }
                    }
                    continue;
                }
                FileConfiguration cfg = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                bundledEnglishDefaults.put(type, cfg);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not load bundled English default '"
                        + resourcePath + "': " + e.getMessage());
            }
        }
    }

    public void reload() {
        synchronized (loadLock) {
            String lang = readRequestedLanguage();
            Map<ContentType, FileConfiguration> configs = new ConcurrentHashMap<>();

            for (ContentType type : ContentType.values()) {
                FileConfiguration cfg = loadOneFile(lang, type);
                if (cfg != null) {
                    configs.put(type, cfg);
                }
            }

            this.snapshot = new PackSnapshot(lang, configs);
            plugin.getLogger().info("ContentLanguageManager loaded pack: " + lang
                    + " (" + configs.size() + "/7 files OK)");
        }
    }

    private String readRequestedLanguage() {
        String lang = plugin.getConfig().getString("language", null);
        if (lang == null || lang.trim().isEmpty()) {
            lang = plugin.getConfig().getString("settings.language", "en");
        }
        if (lang == null) lang = "en";
        return lang.trim().toLowerCase();
    }

    private FileConfiguration loadOneFile(String lang, ContentType type) {
        File nested = new File(plugin.getDataFolder(), "lang/" + lang + "/" + type.fileName);
        File source = nested;

        if (!nested.exists() && type == ContentType.MESSAGES) {
            File legacy = new File(plugin.getDataFolder(), "messages_" + lang + ".yml");
            if (legacy.exists()) source = legacy;
        }

        if (!source.exists()) {
            String resourcePath = "lang/" + lang + "/" + type.fileName;
            try (InputStream in = plugin.getResource(resourcePath)) {
                if (in != null) {
                    File parent = nested.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    plugin.saveResource(resourcePath, false);
                    source = nested;
                    plugin.getLogger().info("Extracted bundled language resource: " + resourcePath);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not extract '" + resourcePath + "': " + e.getMessage());
            }
        }

        if (!source.exists()) {
            String enResource = "lang/en/" + type.fileName;
            try (InputStream in = plugin.getResource(enResource)) {
                if (in != null) {
                    File enTarget = new File(plugin.getDataFolder(), "lang/en/" + type.fileName);
                    File parent = enTarget.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    plugin.saveResource(enResource, false);
                    source = enTarget;
                }
            } catch (Exception ignored) { }
        }

        if (!source.exists()) {
            plugin.getLogger().warning("Language file missing and no bundled fallback: "
                    + "lang/" + lang + "/" + type.fileName);
            return null;
        }

        FileConfiguration cfg;
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8)) {
            cfg = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load " + source + ": " + e.getMessage());
            return null;
        }

        FileConfiguration defaults = bundledEnglishDefaults.get(type);
        if (defaults != null) {
            cfg.setDefaults(defaults);
        }
        return cfg;
    }

    public String getLanguage() {
        PackSnapshot snap = this.snapshot;
        return snap != null ? snap.lang : "en";
    }

    public String get(ContentType type, String path, String fallback) {
        PackSnapshot snap = this.snapshot;
        if (snap == null) return fallback;
        FileConfiguration cfg = snap.get(type);
        if (cfg == null) return fallback;
        String v = cfg.getString(path);
        return v != null ? v : fallback;
    }

    public List<String> getStringList(ContentType type, String path, List<String> fallback) {
        PackSnapshot snap = this.snapshot;
        if (snap == null) return fallback != null ? fallback : Collections.emptyList();
        FileConfiguration cfg = snap.get(type);
        if (cfg == null) return fallback != null ? fallback : Collections.emptyList();
        List<String> v = cfg.getStringList(path);
        return v.isEmpty() && fallback != null ? fallback : v;
    }

    public String getFishName(String fishId, String fallback) {
        return get(ContentType.FISH, "fishes." + fishId + ".display-name", fallback);
    }

    public List<String> getFishLore(String fishId, List<String> fallback) {
        return getStringList(ContentType.FISH, "fishes." + fishId + ".lore", fallback);
    }

    public String getBaitName(String baitId, String fallback) {
        return get(ContentType.BAITS, "baits." + baitId + ".display-name", fallback);
    }

    public List<String> getBaitLore(String baitId, List<String> fallback) {
        return getStringList(ContentType.BAITS, "baits." + baitId + ".lore", fallback);
    }

    public String getRodName(String rodId, String fallback) {
        return get(ContentType.RODS, "rods." + rodId + ".display-name", fallback);
    }

    public List<String> getRodLore(String rodId, List<String> fallback) {
        return getStringList(ContentType.RODS, "rods." + rodId + ".lore", fallback);
    }

    public String getContractName(String templateId, String fallback) {
        return get(ContentType.CONTRACTS, "contracts." + templateId + ".display-name", fallback);
    }

    public List<String> getContractDescription(String templateId, List<String> fallback) {
        return getStringList(ContentType.CONTRACTS, "contracts." + templateId + ".description", fallback);
    }

    public String getAchievementTitle(String achievementId, String fallback) {
        return get(ContentType.ACHIEVEMENTS, "achievements." + achievementId + ".title", fallback);
    }

    public List<String> getAchievementLore(String achievementId, List<String> fallback) {
        return getStringList(ContentType.ACHIEVEMENTS, "achievements." + achievementId + ".lore", fallback);
    }

    public String getSkillName(String skillId, String fallback) {
        return get(ContentType.SKILLS, "skills." + skillId + ".name", fallback);
    }

    public String getSkillDesc(String skillId, String fallback) {
        return get(ContentType.SKILLS, "skills." + skillId + ".desc", fallback);
    }

    /**
     * v1.8.5 — Returns the set of available language codes by scanning
     * the {@code /lang/} directory. Used by the {@code /pf settings language}
     * tab-completer to suggest valid language codes.
     */
    public List<String> listAvailableLanguages() {
        List<String> codes = new ArrayList<>();
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (langDir.exists() && langDir.isDirectory()) {
            File[] children = langDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory() && new File(child, "messages.yml").exists()) {
                        codes.add(child.getName());
                    }
                }
            }
        }
        if (codes.isEmpty()) {
            // Bundled defaults — fallback when the data folder hasn't been
            // populated yet (e.g. tab-complete fired before first reload).
            codes.add("en");
            codes.add("tr");
            codes.add("zh_cn");
        }
        Collections.sort(codes);
        return codes;
    }

    public List<String> listKeys(ContentType type) {
        PackSnapshot snap = this.snapshot;
        if (snap == null) return Collections.emptyList();
        FileConfiguration cfg = snap.get(type);
        if (cfg == null) return Collections.emptyList();
        org.bukkit.configuration.ConfigurationSection root = cfg.getConfigurationSection(type.rootKey);
        if (root == null) return Collections.emptyList();
        return new ArrayList<>(root.getKeys(false));
    }
}
