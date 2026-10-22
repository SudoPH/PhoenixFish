package com.phoenix.fish.util;

import com.phoenix.fish.PhoenixFish;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public final class SoundUtils {

    /**
     * Private constructor — utility class, no instances.
     * Throws {@link UnsupportedOperationException} if ever called via
     * reflection, to make the intent explicit.
     */
    private SoundUtils() {
        throw new UnsupportedOperationException("Utility class — no instances");
    }

    /**
     * Plays a sound to a player, reading the sound name from the plugin config.
     *
     * @param plugin        the plugin instance (for config + logger); nullable
     * @param player        the player to play the sound to; nullable
     * @param path          the config path to read the sound name from
     *                      (e.g. {@code "sounds.bait_consume"})
     * @param defaultSound  the fallback sound if config is missing or invalid;
     *                      if this is also null, no sound plays
     * @param volume        volume (0.0 - 1.0+, Bukkit allows >1 for distance)
     * @param pitch         pitch (0.5 - 2.0)
     */
    @SuppressWarnings("removal") // Sound.valueOf(String) is deprecated for removal
    public static void playFromConfig(PhoenixFish plugin, Player player, String path,
            Sound defaultSound, float volume, float pitch) {
        // Defensive: prevent NPE / stale-reference exceptions.
        if (plugin == null || player == null || !player.isOnline()) {
            return;
        }

        Sound soundToPlay = defaultSound;
        String soundName = plugin.getConfig().getString(path);

        if (soundName != null && !soundName.isEmpty()) {
            try {
                soundToPlay = Sound.valueOf(soundName.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Configured sound name is not a valid Sound enum value.
                // Log a warning with enough context to fix the config, then
                // fall back to the default sound.
                plugin.getLogger().warning(
                        "Invalid sound in config: " + soundName
                                + " (path: " + path + "). "
                                + "Using the default sound: "
                                + (defaultSound != null ? defaultSound.name() : "none"));
            }
        }

        // If both config and defaultSound are null/missing, skip playing.
        // Otherwise play whatever we resolved (config value or default).
        if (soundToPlay != null) {
            player.playSound(player, soundToPlay, volume, pitch);
        }
    }
}
