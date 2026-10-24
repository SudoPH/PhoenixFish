package com.phoenix.fish.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when PhoenixFish completes a config reload (via {@code /pf reload}).
 *
 * <p>External plugins (e.g. PhoenixCraft) should listen to this event to
 * invalidate any cached data and close any open GUIs that may be showing
 * stale recipe definitions.</p>
 *
 * <p>This event fires on the main server thread, after all reload steps
 * have completed successfully. It is <b>not</b> fired if the reload fails.</p>
 *
 * @since 1.3.2
 */
public class PhoenixFishReloadEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long durationMs;

    public PhoenixFishReloadEvent(long durationMs) {
        this.durationMs = durationMs;
    }

    /**
     * @return how long the reload took, in milliseconds.
     */
    public long getDurationMs() {
        return durationMs;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
