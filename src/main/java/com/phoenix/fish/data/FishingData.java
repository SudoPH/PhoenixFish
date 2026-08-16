package com.phoenix.fish.data;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class FishingData {

    private volatile UUID uuid;
    private volatile int currentXp;
    private volatile int currentLevel;
    private volatile int totalXp;
    private volatile Set<String> discoveredFish;

    public FishingData() {
        this(null, 0, 1, 0, new HashSet<>());
    }

    public FishingData(int currentXp, int currentLevel) {
        this(null, currentXp, currentLevel, 0, new HashSet<>());
    }

    public FishingData(UUID uuid, int currentXp, int currentLevel, int totalXp) {
        this(uuid, currentXp, currentLevel, totalXp, new HashSet<>());
    }

    // YENİ CONSTRUCTOR (Veritabanı yüklerken kullanılacak)
    public FishingData(UUID uuid, int currentXp, int currentLevel, int totalXp, Set<String> discoveredFish) {
        this.uuid = uuid;
        this.currentXp = Math.max(0, currentXp);
        this.currentLevel = Math.max(1, currentLevel);
        this.totalXp = Math.max(0, totalXp);
        this.discoveredFish = discoveredFish != null ? new HashSet<>(discoveredFish) : new HashSet<>();
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public int getCurrentXp() {
        return currentXp;
    }

    public void setCurrentXp(int currentXp) {
        this.currentXp = Math.max(0, currentXp);
    }

    public int getCurrentLevel() {
        return currentLevel;
    }

    public void setCurrentLevel(int currentLevel) {
        this.currentLevel = Math.max(1, currentLevel);
    }

    public int getTotalXp() {
        return totalXp;
    }

    public void setTotalXp(int totalXp) {
        this.totalXp = Math.max(0, totalXp);
    }

    // YENİ METOTLAR
    public Set<String> getDiscoveredFish() {
        return discoveredFish;
    }

    public void setDiscoveredFish(Set<String> discoveredFish) {
        this.discoveredFish = discoveredFish;
    }

    public boolean hasDiscovered(String fishId) {
        return discoveredFish.contains(fishId);
    }

    public boolean discoverFish(String fishId) {
        return discoveredFish.add(fishId); // Eğer yeni eklendiyse true döner
    }

    public synchronized void addXp(int amount) {
        if (amount <= 0)
            return;
        this.currentXp += amount;
        this.totalXp += amount;
    }

    public synchronized boolean checkLevelUp() {
        int requiredXp = this.currentLevel * 100;
        if (this.currentXp >= requiredXp) {
            this.currentXp -= requiredXp;
            this.currentLevel++;
            return true;
        }
        return false;
    }
}