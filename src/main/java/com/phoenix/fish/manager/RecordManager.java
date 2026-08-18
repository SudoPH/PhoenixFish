package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.model.CustomFish;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RecordManager {

    private final PhoenixFish plugin;
    private final PlainTextComponentSerializer plainSerializer = PlainTextComponentSerializer.plainText();
    private File recordFile;
    private FileConfiguration recordConfig;
    private final Object fileLock = new Object();

    public RecordManager(PhoenixFish plugin) {
        this.plugin = plugin;
        loadFile();
    }

    public void loadFile() {
        synchronized (fileLock) {
            if (recordFile == null) {
                recordFile = new File(plugin.getDataFolder(), "records.yml");
            }
            if (!recordFile.exists()) {
                try {
                    recordFile.createNewFile();
                } catch (IOException e) {
                    plugin.getLogger().severe("Could not create records.yml: " + e.getMessage());
                }
            }
            recordConfig = YamlConfiguration.loadConfiguration(recordFile);
        }
    }

    public void saveFile() {
        CompletableFuture.runAsync(() -> {
            synchronized (fileLock) {
                if (recordConfig == null || recordFile == null)
                    return;
                try {
                    recordConfig.save(recordFile);
                } catch (IOException e) {
                    plugin.getLogger().severe("Could not save records.yml: " + e.getMessage());
                }
            }
        });
    }

    public void checkRecords(Player player, CustomFish fish, double weight, String formattedWeight) {
        if (player == null || fish == null)
            return;

        UUID uuid = player.getUniqueId();
        String fishId = fish.id();

        boolean pbBroken = false;
        boolean wrBroken = false;

        synchronized (fileLock) {
            double currentPB = recordConfig.getDouble("players." + uuid + "." + fishId, 0.0);
            if (weight > currentPB) {
                recordConfig.set("players." + uuid + "." + fishId, weight);
                pbBroken = true;
            }

            double currentWR = recordConfig.getDouble("server-records." + fishId, 0.0);
            if (weight > currentWR) {
                recordConfig.set("server-records." + fishId, weight);
                wrBroken = true;
            }
        }

        if (wrBroken) {
            @SuppressWarnings("null")
            Map<String, String> ph = Map.of(
                    "%player%", player.getName(),
                    "%fish_name%", plainSerializer.serialize(fish.name()),
                    "%weight%", formattedWeight);
            Bukkit.broadcast(plugin.getMessageManager().getMessage("record_server_broken", true, ph));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        } else if (pbBroken) {
            @SuppressWarnings("null")
            Map<String, String> ph = Map.of(
                    "%fish_name%", plainSerializer.serialize(fish.name()),
                    "%weight%", formattedWeight);
            player.sendActionBar(plugin.getMessageManager().getMessage("record_personal_broken", false, ph));
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
        }

        if (pbBroken || wrBroken) {
            saveFile();
        }
    }

    public double getServerRecord(String fishId) {
        synchronized (fileLock) {
            if (recordConfig == null)
                return 0.0;
            return recordConfig.getDouble("server-records." + fishId, 0.0);
        }
    }

    public double getPersonalRecord(UUID uuid, String fishId) {
        synchronized (fileLock) {
            if (recordConfig == null || uuid == null)
                return 0.0;
            return recordConfig.getDouble("players." + uuid + "." + fishId, 0.0);
        }
    }
}