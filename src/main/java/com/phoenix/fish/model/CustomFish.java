package com.phoenix.fish.model;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public record CustomFish(
        String id,
        Component name,
        int rarity,
        int weight,
        double baseSpeed,
        double fightStrength,
        int xpReward,
        double minWeight,
        double maxWeight,
        List<String> biomes,
        double price,
        ItemStack itemStack) {

    public CustomFish {
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("Fish ID cannot be null or blank");
        if (name == null)
            throw new IllegalArgumentException("Fish name cannot be null");
        if (itemStack == null)
            throw new IllegalArgumentException("Fish itemStack cannot be null");

        rarity = Math.max(1, rarity);
        weight = Math.max(1, weight);
        baseSpeed = Math.max(0.1, baseSpeed);
        fightStrength = Math.max(0.1, fightStrength);
        xpReward = Math.max(0, xpReward);

        if (minWeight <= 0)
            minWeight = 0.1;
        if (maxWeight <= minWeight)
            maxWeight = minWeight + 1.0;
        if (biomes == null)
            biomes = List.of();
        if (price < 0)
            price = 0.0;
    }
}