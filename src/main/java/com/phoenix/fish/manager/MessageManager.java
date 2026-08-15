package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;

public class MessageManager {

    private final PhoenixFish plugin;
    private final MiniMessage miniMessage;

    private volatile FileConfiguration langConfig;
    private volatile String prefix;

    public MessageManager(PhoenixFish plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        loadLanguage();
    }

    public void loadLanguage() {
        String lang = plugin.getConfig().getString("settings.language", "en").toLowerCase();
        File langFile = new File(plugin.getDataFolder(), "messages_" + lang + ".yml");

        if (!langFile.exists()) {
            plugin.getLogger().warning("Language file 'messages_" + lang + ".yml' not found! Falling back to English.");
            langFile = new File(plugin.getDataFolder(), "messages_en.yml");

            if (!langFile.exists()) {
                plugin.saveResource("messages_en.yml", false);
            }
        }

        this.langConfig = YamlConfiguration.loadConfiguration(langFile);
        this.prefix = langConfig.getString("prefix", "");
        plugin.getLogger().info("Loaded language: " + lang);
    }

    public Component getMessage(String key, boolean addPrefix, Map<String, String> placeholders) {
        String message = langConfig.getString(key);

        if (message == null) {
            plugin.getLogger().warning("Missing message key in language file: " + key);
            return Component.empty();
        }

        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String safeValue = miniMessage.escapeTags(entry.getValue());
                message = message.replace(entry.getKey(), safeValue);
            }
        }

        if (addPrefix && !prefix.isEmpty()) {
            message = prefix + message;
        }

        return miniMessage.deserialize(message);
    }

    public Component getMessage(String key, boolean addPrefix) {
        return getMessage(key, addPrefix, null);
    }

    public String getPlainMessage(String key) {
        return langConfig.getString(key, key);
    }
}