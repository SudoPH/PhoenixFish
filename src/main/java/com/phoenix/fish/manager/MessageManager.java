package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PhoenixFish dil yöneticisi.
 *
 * <p><b>v1.3.5 — Dinamik dil desteği + İngilizce fallback:</b></p>
 * <ul>
 *   <li>Herhangi bir dil kodu kabul edilir (tr, en, zh_cn, de, fr, es, ...).</li>
 *   <li><code>messages_&lt;dil&gt;.yml</code> veri klasöründe yoksa, jar'dan çıkarılır.</li>
 *   <li>Yoksa İngilizce'ye fallback yapılır.</li>
 *   <li>Eksik anahtarlar her zaman İngilizce dosyadan otomatik tamamlanır
 *       (Bukkit {@link FileConfiguration#setDefaults} mekanizması ile).</li>
 * </ul>
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

    public void loadLanguage() {
        synchronized (configLock) {
            String requestedLang = plugin.getConfig().getString("settings.language", "en");
            if (requestedLang == null) requestedLang = "en";
            requestedLang = requestedLang.trim().toLowerCase();

            // Always re-extract bundled English + Turkish files (so admins can copy them
            // as templates for new languages). saveResource(replace=false) → no-op if file exists.
            plugin.saveResource("messages_en.yml", false);
            plugin.saveResource("messages_tr.yml", false);

            String resolvedLang = requestedLang;
            File langFile = new File(plugin.getDataFolder(), "messages_" + resolvedLang + ".yml");

            // If the data-folder file doesn't exist, try extracting it from the jar
            // (so server admins can ship custom languages inside the jar, or future
            // PhoenixFish builds can bundle extra languages).
            if (!langFile.exists()) {
                String resourcePath = "messages_" + resolvedLang + ".yml";
                try (InputStream in = plugin.getResource(resourcePath)) {
                    if (in != null) {
                        plugin.saveResource(resourcePath, false);
                        plugin.getLogger().info("Extracted bundled language file: " + resourcePath);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not check/extract '" + resourcePath + "': " + e.getMessage());
                }
            }

            // If still missing → fall back to English
            if (!langFile.exists()) {
                plugin.getLogger().warning("Language file 'messages_" + resolvedLang
                        + ".yml' not found in data folder or jar! Falling back to English.");
                resolvedLang = "en";
                langFile = new File(plugin.getDataFolder(), "messages_en.yml");
                if (!langFile.exists()) {
                    plugin.saveResource("messages_en.yml", false);
                }
            }

            // Load the resolved language file.
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(langFile);

            // Attach the bundled English file as the default fallback for missing keys.
            // This is the bug fix: previously, a missing key in messages_<lang>.yml would
            // return Component.empty() (silent failure). Now it falls back to the English
            // value automatically.
            //
            // v1.4.0 — Önce InputStream'i null check et, sonra InputStreamReader oluştur.
            // Eğer jar'da messages_en.yml yoksa (özel build), InputStreamReader(null)
            // constructor'u NPE fırlatırdı. Şimdi: null ise sessizce atla + severe log.
            java.io.InputStream enStream = plugin.getResource("messages_en.yml");
            if (enStream != null) {
                try (InputStreamReader reader = new InputStreamReader(enStream, StandardCharsets.UTF_8)) {
                    FileConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                    cfg.setDefaults(defaults);
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not load English fallback defaults: " + e.getMessage());
                }
            } else {
                plugin.getLogger().severe("Bundled messages_en.yml is missing from the jar — "
                        + "no English fallback available! All missing keys will be empty.");
            }

            String pfx = cfg != null ? cfg.getString("prefix", "") : "";
            // Atomic publish: a single volatile write makes both fields visible together.
            this.snapshot = new LangSnapshot(cfg, pfx, resolvedLang);
            plugin.getLogger().info("Loaded language: " + resolvedLang
                    + (requestedLang.equals(resolvedLang) ? "" : " (requested '" + requestedLang + "')"));
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

        // cfg.getString(key) now consults the defaults layer (English) if the key is
        // missing from the user's chosen language file. So this branch is only hit
        // if the key is missing from BOTH the user's file AND the English fallback
        // — i.e. a truly unknown key.
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
