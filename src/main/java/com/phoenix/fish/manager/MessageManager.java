package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MessageManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;

    private volatile FileConfiguration langConfig;
    private volatile String prefix;
    private volatile String currentLang;

    private final Object configLock = new Object();

    public MessageManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        loadLanguage();
    }

    public void loadLanguage() {
        synchronized (configLock) {
            String lang = plugin.getConfig().getString("settings.language", "en").toLowerCase();
            this.currentLang = lang;

            File langFile = new File(plugin.getDataFolder(), "messages_" + lang + ".yml");
            if (!langFile.exists()) {
                plugin.getLogger()
                        .warning("Language file 'messages_" + lang + ".yml' not found! Falling back to English.");
                langFile = new File(plugin.getDataFolder(), "messages_en.yml");
                if (!langFile.exists()) {
                    plugin.saveResource("messages_en.yml", false);
                }
            }

            this.langConfig = YamlConfiguration.loadConfiguration(langFile);
            this.prefix = langConfig != null ? langConfig.getString("prefix", "") : "";
            plugin.getLogger().info("Loaded language: " + lang);
        }
    }

    public void reload() {
        loadLanguage();
    }

    public String getLanguage() {
        return currentLang != null ? currentLang : "en";
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
        if (langConfig == null) {
            plugin.getLogger().warning("Language configuration not loaded! Using fallback message for key: " + key);
            return Component.text("Missing language configuration");
        }

        String message = langConfig.getString(key);
        if (message == null) {
            plugin.getLogger().warning("Missing message key in language file: " + key);
            return Component.empty();
        }

        message = replacePlaceholders(message, placeholders);

        if (addPrefix && prefix != null && !prefix.isEmpty()) {
            message = prefix + message;
        }

        return miniMessage.deserialize(message);
    }

    public Component getMessage(String key, boolean addPrefix) {
        return getMessage(key, addPrefix, null);
    }

    public List<Component> getMessageList(String key, Map<String, String> placeholders) {
        if (langConfig == null) {
            plugin.getLogger().warning("Language configuration not loaded for list key: " + key);
            return List.of();
        }

        List<String> lines = langConfig.getStringList(key);
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
        if (langConfig == null) {
            plugin.getLogger().warning("Language configuration not loaded for plain message key: " + key);
            return "Missing language configuration";
        }

        String message = langConfig.getString(key);
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
        return (prefix != null) ? prefix : "";
    }
}