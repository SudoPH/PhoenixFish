package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PhoenixFish language manager — chat / GUI messages layer.
 *
 * <p><b>v1.8.4 — BUGFIX:</b> the v1.8.3 refactor moved message files to
 * the nested {@code /lang/<code>/messages.yml} layout (matching the new
 * ContentLanguageManager), but this class still looked for the v1.8.2
 * flat {@code /lang/messages_<code>.yml} filename. On a fresh install
 * this caused every message lookup to fail with "Language configuration
 * not loaded". Fixed by aligning this class's resolution order with
 * ContentLanguageManager's: prefer {@code /lang/<code>/messages.yml},
 * fall back to legacy {@code /lang/messages_<code>.yml} and root-level
 * {@code messages_<code>.yml} for servers that customized files before
 * v1.8.3.</p>
 */
public class MessageManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;

    private static final class LangSnapshot {
        final FileConfiguration langConfig;
        final String prefix;
        final String lang;

        LangSnapshot(FileConfiguration langConfig, String prefix, String lang) {
            this.langConfig = langConfig;
            this.prefix = prefix;
            this.lang = lang;
        }
    }

    private volatile LangSnapshot snapshot;
    private final Object configLock = new Object();

    public MessageManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        loadLanguage();
    }

    private String readRequestedLanguage() {
        String lang = plugin.getConfig().getString("language", null);
        if (lang == null || lang.trim().isEmpty()) {
            lang = plugin.getConfig().getString("settings.language", "en");
        }
        if (lang == null) lang = "en";
        return lang.trim().toLowerCase();
    }

    /**
     * v1.8.4 — Resolve the actual file path for a language code.
     * Search order (first existing wins):
     *   1. {@code <dataFolder>/lang/<code>/messages.yml}  (v1.8.3 nested — preferred)
     *   2. {@code <dataFolder>/lang/messages_<code>.yml}  (v1.8.2 flat in /lang/)
     *   3. {@code <dataFolder>/messages_<code>.yml}        (v1.3.5 root)
     *   4. Bundled jar resource {@code lang/<code>/messages.yml}  → extract to #1
     *   5. Bundled jar resource {@code lang/messages_<code>.yml} → extract to #2
     *   6. Bundled jar resource {@code messages_<code>.yml}       → extract to #3
     */
    private File resolveLanguageFile(String code) {
        File dataFolder = plugin.getDataFolder();

        File nested = new File(dataFolder, "lang/" + code + "/messages.yml");
        if (nested.exists()) return nested;

        File flatInLang = new File(dataFolder, "lang/messages_" + code + ".yml");
        if (flatInLang.exists()) return flatInLang;

        File legacyRoot = new File(dataFolder, "messages_" + code + ".yml");
        if (legacyRoot.exists()) return legacyRoot;

        if (extractBundledResource("lang/" + code + "/messages.yml", nested)) {
            return nested;
        }
        if (extractBundledResource("lang/messages_" + code + ".yml", flatInLang)) {
            return flatInLang;
        }
        if (extractBundledResource("messages_" + code + ".yml", legacyRoot)) {
            return legacyRoot;
        }
        return null;
    }

    private boolean extractBundledResource(String resourcePath, File targetFile) {
        if (targetFile.exists()) return true;
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) return false;
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            plugin.saveResource(resourcePath, false);
            plugin.getLogger().info("Extracted bundled language resource: " + resourcePath);
            return targetFile.exists();
        } catch (Exception e) {
            plugin.getLogger().warning("Could not extract '" + resourcePath + "': " + e.getMessage());
            return false;
        }
    }

    public void loadLanguage() {
        synchronized (configLock) {
            String requestedLang = readRequestedLanguage();

            for (String code : new String[]{"en", "tr", "zh_cn"}) {
                File target = new File(plugin.getDataFolder(), "lang/" + code + "/messages.yml");
                extractBundledResource("lang/" + code + "/messages.yml", target);
            }

            String resolvedLang = requestedLang;
            File langFile = resolveLanguageFile(resolvedLang);

            if (langFile == null) {
                plugin.getLogger().warning("Language file for '" + resolvedLang
                        + "' not found in /lang/<code>/, /lang/, data folder root, or jar! "
                        + "Falling back to English.");
                resolvedLang = "en";
                langFile = resolveLanguageFile("en");
            }

            if (langFile == null || !langFile.exists()) {
                plugin.getLogger().severe("Could not load ANY language file — even English is missing. "
                        + "All plugin messages will be empty until the issue is resolved.");
                this.snapshot = new LangSnapshot(null, "", resolvedLang);
                return;
            }

            FileConfiguration cfg;
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(langFile), StandardCharsets.UTF_8)) {
                cfg = YamlConfiguration.loadConfiguration(reader);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load language file " + langFile + ": " + e.getMessage());
                this.snapshot = new LangSnapshot(null, "", resolvedLang);
                return;
            }

            InputStream enStream = plugin.getResource("lang/en/messages.yml");
            if (enStream == null) enStream = plugin.getResource("lang/messages_en.yml");
            if (enStream == null) enStream = plugin.getResource("messages_en.yml");
            if (enStream != null) {
                try (InputStreamReader reader = new InputStreamReader(enStream, StandardCharsets.UTF_8)) {
                    FileConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                    cfg.setDefaults(defaults);
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not load English fallback defaults: " + e.getMessage());
                }
            } else {
                plugin.getLogger().severe("Bundled English messages file is missing from the jar — "
                        + "no English fallback available! Missing keys will be empty.");
            }

            String pfx = cfg.getString("prefix", "");
            this.snapshot = new LangSnapshot(cfg, pfx, resolvedLang);

            String source;
            File parent = langFile.getParentFile();
            if (parent != null && parent.getName().equals(resolvedLang)
                    && parent.getParentFile() != null
                    && parent.getParentFile().getName().equals("lang")) {
                source = "/lang/" + resolvedLang + "/";
            } else if (parent != null && parent.getName().equals("lang")) {
                source = "/lang/ (v1.8.2 flat)";
            } else {
                source = "root (v1.3.5)";
            }
            plugin.getLogger().info("Loaded language: " + resolvedLang
                    + (requestedLang.equals(resolvedLang) ? "" : " (requested '" + requestedLang + "')")
                    + " (source: " + source + ")");
        }
    }

    public void reload() {
        loadLanguage();
    }

    public String getLanguage() {
        LangSnapshot snap = this.snapshot;
        return snap != null && snap.lang != null ? snap.lang : "en";
    }

    public MiniMessage getMiniMessage() {
        return miniMessage;
    }

    private String replacePlaceholders(String message, Map<String, String> placeholders) {
        if (placeholders != null && !placeholders.isEmpty()) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String safeValue = miniMessage.escapeTags(entry.getValue());
                message = message.replace(entry.getKey(), safeValue);
            }
        }
        return message;
    }

    public Component getMessage(String key, boolean addPrefix, Map<String, String> placeholders) {
        LangSnapshot snap = this.snapshot;
        if (snap == null || snap.langConfig == null) {
            plugin.getLogger().warning("Language configuration not loaded! Using fallback message for key: " + key);
            return Component.text("Missing language configuration");
        }

        String message = snap.langConfig.getString(key);
        if (message == null) {
            plugin.getLogger().warning("Missing message key in language file (and English fallback): " + key);
            return Component.empty();
        }

        message = replacePlaceholders(message, placeholders);

        if (addPrefix && snap.prefix != null && !snap.prefix.isEmpty()) {
            message = snap.prefix + message;
        }

        return miniMessage.deserialize(message);
    }

    public Component getMessage(String key, boolean addPrefix) {
        return getMessage(key, addPrefix, null);
    }

    public List<Component> getMessageList(String key, Map<String, String> placeholders) {
        LangSnapshot snap = this.snapshot;
        if (snap == null || snap.langConfig == null) {
            plugin.getLogger().warning("Language configuration not loaded for list key: " + key);
            return List.of();
        }

        List<String> lines = snap.langConfig.getStringList(key);
        if (lines.isEmpty())
            return List.of();

        List<Component> components = new ArrayList<>(lines.size());
        for (String line : lines) {
            line = replacePlaceholders(line, placeholders);
            components.add(miniMessage.deserialize(line));
        }
        return components;
    }

    public String getPlainMessage(String key, Map<String, String> placeholders) {
        LangSnapshot snap = this.snapshot;
        if (snap == null || snap.langConfig == null) {
            plugin.getLogger().warning("Language configuration not loaded for plain message key: " + key);
            return "Missing language configuration";
        }

        String message = snap.langConfig.getString(key);
        if (message == null) {
            plugin.getLogger().warning("Missing message key in language file (and English fallback): " + key);
            return key;
        }

        return replacePlaceholders(message, placeholders);
    }

    public String getPlainMessage(String key) {
        return getPlainMessage(key, null);
    }

    public String getPrefix() {
        LangSnapshot snap = this.snapshot;
        return snap != null && snap.prefix != null ? snap.prefix : "";
    }
}
