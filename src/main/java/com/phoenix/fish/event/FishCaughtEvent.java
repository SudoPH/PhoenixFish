package com.phoenix.fish.event;

import com.phoenix.fish.model.CustomFish;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class FishCaughtEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final CustomFish fish;
    private final double weight;
    private boolean cancelled;

    public FishCaughtEvent(Player player, CustomFish fish, double weight) {
        this.player = player;
        this.fish = fish;
        this.weight = weight;
    }

    public Player getPlayer() {
        return player;
    }

    public CustomFish getFish() {
        return fish;
    }

    public double getWeight() {
        return weight;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}