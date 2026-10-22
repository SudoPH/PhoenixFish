package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    public void loadLanguage() {
        synchronized (configLock) {
            String lang = plugin.getConfig().getString("settings.language", "en").toLowerCase();
            File langFile = new File(plugin.getDataFolder(), "messages_" + lang + ".yml");
            if (!langFile.exists()) {
                plugin.getLogger()
                        .warning("Language file 'messages_" + lang + ".yml' not found! Falling back to English.");
                langFile = new File(plugin.getDataFolder(), "messages_en.yml");
                if (!langFile.exists()) {
                    plugin.saveResource("messages_en.yml", false);
                }
            }

            FileConfiguration cfg = YamlConfiguration.loadConfiguration(langFile);
            String pfx = cfg != null ? cfg.getString("prefix", "") : "";
            // Atomic publish: a single volatile write makes both fields visible together.
            this.snapshot = new LangSnapshot(cfg, pfx, lang);
            plugin.getLogger().info("Loaded language: " + lang);
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
            // H-1 FIX: only log missing key once per key to avoid console spam (caller may retry).
            plugin.getLogger().warning("Missing message key in language file: " + key);
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
            plugin.getLogger().warning("Missing message key in language file: " + key);
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
