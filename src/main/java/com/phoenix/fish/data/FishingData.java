package com.phoenix.fish.data;

import java.util.UUID;

public class FishingData {

    private volatile UUID uuid;
    private volatile int currentXp;
    private volatile int currentLevel;
    private volatile int totalXp;

    public FishingData() {
        this(null, 0, 1, 0);
    }

    public FishingData(int currentXp, int currentLevel) {
        this(null, currentXp, currentLevel, 0);
    }

    public FishingData(UUID uuid, int currentXp, int currentLevel, int totalXp) {
        this.uuid = uuid;
        this.currentXp = Math.max(0, currentXp);
        this.currentLevel = Math.max(1, currentLevel);
        this.totalXp = Math.max(0, totalXp);
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