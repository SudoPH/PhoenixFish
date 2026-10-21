package com.phoenix.fish.manager;

import com.phoenix.fish.PhoenixFish;
import com.phoenix.fish.listener.FishingListener;
import com.phoenix.fish.minigame.MinigameTask;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the per-player fishing mode (PhoenixFish custom fishing vs vanilla fishing).
 *
 * Players can switch between the plugin's custom fishing system and the vanilla
 * Minecraft fishing system with "/phoenixfish toggle". The selected mode is stored
 * per-player in the PersistentDataContainer (BYTE: 1 = VANILLA, 0 = CUSTOM), so it
 * survives relogs and server restarts.
 *
 * This class performs NO messaging. All player-visible messages are sent by
 * FishCommand through MessageManager keys (messages_tr.yml / messages_en.yml).
 */
public class FishingModeManager {

    /** Fishing modes a player can choose between. */
    public enum Mode {
        /** PhoenixFish custom fishing system (default). */
        CUSTOM,
        /** Vanilla Minecraft fishing. */
        VANILLA
    }

    /** Result of a toggle attempt. */
    public enum ToggleResult {
        /** Mode switched successfully. */
        SUCCESS,
        /** Player lacks the phoenixfish.toggle permission. */
        NO_PERMISSION,
        /** The fishing-mode feature is disabled in config.yml. */
        FEATURE_DISABLED,
        /** Player is still inside the toggle cooldown. */
        COOLDOWN
    }

    private final PhoenixFish plugin;

    /** PDC key storing the player's explicit mode choice (BYTE: 1 = VANILLA, 0 = CUSTOM). */
    private final NamespacedKey modeKey;

    /** Last toggle timestamp per player (UUID -> epoch millis), used for the cooldown. */
    private final ConcurrentHashMap<UUID, Long> lastToggle = new ConcurrentHashMap<>();

    private final boolean featureEnabled;
    private final boolean requirePermission;
    private final boolean broadcastChange;
    private final long toggleCooldownSeconds;
    private final Mode defaultMode;

    public FishingModeManager(PhoenixFish plugin) {
        this.plugin = plugin;

        this.modeKey = new NamespacedKey(plugin, "fishing_mode");

        this.featureEnabled = plugin.getConfig().getBoolean("fishing-mode.enabled", true);
        this.requirePermission = plugin.getConfig().getBoolean("fishing-mode.require-permission", false);
        this.broadcastChange = plugin.getConfig().getBoolean("fishing-mode.broadcast-change", false);
        this.toggleCooldownSeconds = plugin.getConfig().getLong("fishing-mode.toggle-cooldown", 5L);
        this.defaultMode = parseMode(plugin.getConfig().getString("fishing-mode.default-mode", "custom"), Mode.CUSTOM);
    }

    /**
     * Parses a mode name from the config ("custom" or "vanilla", case-insensitive).
     * Falls back to the given fallback value when the name is unknown.
     */
    private Mode parseMode(String raw, Mode fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Mode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    /** Returns true when the toggle feature is enabled in config.yml. */
    public boolean isFeatureEnabled() {
        return featureEnabled;
    }

    /** Returns true when phoenixfish.toggle is required to switch modes. */
    public boolean isRequirePermission() {
        return requirePermission;
    }

    /** Returns true when mode changes should be broadcast to the whole server. */
    public boolean isBroadcastChange() {
        return broadcastChange;
    }

    /** Returns the mode used by players who never toggled manually. */
    public Mode getDefaultMode() {
        return defaultMode;
    }

    /**
     * Returns the effective mode of a player. An explicit PDC choice always wins;
     * otherwise the configured default mode is used.
     */
    public Mode getMode(Player player) {
        Byte stored = player.getPersistentDataContainer().get(modeKey, PersistentDataType.BYTE);
        if (stored != null) {
            return stored == (byte) 1 ? Mode.VANILLA : Mode.CUSTOM;
        }
        return defaultMode;
    }

    /**
     * Returns true when the player has an explicit mode choice stored in their PDC.
     * Used by FishCommand to fill the %source% placeholder (choice vs default).
     */
    public boolean hasPlayerChoice(Player player) {
        return player.getPersistentDataContainer().has(modeKey, PersistentDataType.BYTE);
    }

    /**
     * Returns true when the player is currently in vanilla fishing mode.
     * FishingListener checks this first and returns without touching the event.
     */
    public boolean isVanillaMode(Player player) {
        return getMode(player) == Mode.VANILLA;
    }

    /**
     * Returns the remaining toggle cooldown for the player in whole seconds
     * (rounded up). Returns 0 when no cooldown is active or configured.
     */
    public long getRemainingCooldown(Player player) {
        if (toggleCooldownSeconds <= 0L) {
            return 0L;
        }
        Long last = lastToggle.get(player.getUniqueId());
        if (last == null) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - last;
        long cooldownMillis = toggleCooldownSeconds * 1000L;
        if (elapsed >= cooldownMillis) {
            return 0L;
        }
        return (cooldownMillis - elapsed + 999L) / 1000L;
    }

    /**
     * Toggles the fishing mode of a player.
     *
     * Players with phoenixfish.admin always bypass the permission requirement.
     * The cooldown is only consumed on a successful toggle.
     *
     * FIX: When switching to VANILLA mode, any active minigame is cancelled.
     * Otherwise the minigame scheduler keeps running in the background and
     * eventually calls handleWin(), giving the player a custom fish even
     * though they are now in vanilla mode. This was the root cause of the
     * "vanilla sistemde çalışmıyor / custom balık veriyor" bug.
     */
    public ToggleResult toggle(Player player) {
        if (!featureEnabled) {
            return ToggleResult.FEATURE_DISABLED;
        }
        if (requirePermission
                && !player.hasPermission("phoenixfish.toggle")
                && !player.hasPermission("phoenixfish.admin")) {
            return ToggleResult.NO_PERMISSION;
        }
        if (getRemainingCooldown(player) > 0L) {
            return ToggleResult.COOLDOWN;
        }

        Mode newMode = isVanillaMode(player) ? Mode.CUSTOM : Mode.VANILLA;
        setMode(player, newMode);

        // When entering vanilla mode, immediately cancel any active minigame.
        // The minigame scheduler runs independently of the PlayerFishEvent and
        // would otherwise keep ticking until progress hits 100, then call
        // handleWin() which gives the player a custom fish.
        if (newMode == Mode.VANILLA) {
            cancelActiveMinigameSilently(player);
        }

        lastToggle.put(player.getUniqueId(), System.currentTimeMillis());
        return ToggleResult.SUCCESS;
    }

    /**
     * Cancels any active minigame for the player WITHOUT awarding a fish.
     *
     * MinigameTask.cancel() only stops the scheduler task and removes the
     * player from the activeMinigames map — it does NOT call handleWin(),
     * so no custom fish is given. This is exactly what we want when the
     * player switches to vanilla mode mid-minigame.
     */
    private void cancelActiveMinigameSilently(Player player) {
        FishingListener listener = plugin.getFishingListener();
        if (listener == null) {
            return;
        }
        MinigameTask task = listener.getActiveMinigames().get(player.getUniqueId());
        if (task != null) {
            task.cancel(); // cancels scheduler + removes from activeMinigames, NO fish given
        }
    }

    /**
     * Stores the mode of a player explicitly in their PersistentDataContainer.
     * BYTE: 1 = VANILLA, 0 = CUSTOM.
     */
    public void setMode(Player player, Mode mode) {
        Mode target = (mode == null) ? defaultMode : mode;
        player.getPersistentDataContainer().set(modeKey, PersistentDataType.BYTE,
                (byte) (target == Mode.VANILLA ? 1 : 0));
    }

    /** Clears the stored mode and the cooldown of an online player. */
    public void resetPlayer(Player player) {
        player.getPersistentDataContainer().remove(modeKey);
        lastToggle.remove(player.getUniqueId());
    }

    /** Clears the stored mode and the cooldown of a player by UUID (online players only). */
    public void resetPlayer(UUID uuid) {
        lastToggle.remove(uuid);
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null) {
            player.getPersistentDataContainer().remove(modeKey);
        }
    }
}