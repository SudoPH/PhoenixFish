package com.phoenix.fish.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Represents an event fired when a player gains Phoenix XP.
 */
public class PhoenixXPGainEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    private final UUID uuid;
    private final int amount;
    private final String source;
    private boolean cancelled;

    public PhoenixXPGainEvent(UUID uuid, int amount, String source) {
        this.uuid = uuid;
        this.amount = amount;
        this.source = source;
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getAmount() {
        return amount;
    }

    public String getSource() {
        return source;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}