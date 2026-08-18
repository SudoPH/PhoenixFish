package com.phoenix.fish.data;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FishingData {

    private final UUID uuid;
    private volatile int currentXp;
    private volatile int currentLevel;
    private volatile int totalXp;

    private volatile int craftingXp;
    private volatile int craftingLevel;
    private volatile int totalCraftingXp;

    private final Set<String> discoveredFish;

    private volatile int skillPoints;
    private volatile int fastCatcher;
    private volatile int masterHunter;
    private volatile int doubleCatch;
    private volatile int luckyBait;

    private double balance;

    public FishingData() {
        this(null, 0, 1, 0, null, 0, 0, 0, 0, 0, 0.0, 0, 1, 0);
    }

    public FishingData(UUID uuid, int currentXp, int currentLevel, int totalXp, Set<String> discoveredFish,
            int skillPoints, int fastCatcher, int masterHunter, int doubleCatch, int luckyBait, double balance,
            int craftingXp, int craftingLevel, int totalCraftingXp) {
        this.uuid = uuid;
        this.currentXp = Math.max(0, currentXp);
        this.currentLevel = Math.max(1, currentLevel);
        this.totalXp = Math.max(0, totalXp);

        this.craftingXp = Math.max(0, craftingXp);
        this.craftingLevel = Math.max(1, craftingLevel);
        this.totalCraftingXp = Math.max(0, totalCraftingXp);

        this.discoveredFish = ConcurrentHashMap.newKeySet();
        if (discoveredFish != null) {
            this.discoveredFish.addAll(discoveredFish);
        }

        this.skillPoints = Math.max(0, skillPoints);
        this.fastCatcher = clampSkill(fastCatcher);
        this.masterHunter = clampSkill(masterHunter);
        this.doubleCatch = clampSkill(doubleCatch);
        this.luckyBait = clampSkill(luckyBait);
        this.balance = Math.max(0.0, balance);
    }

    private int clampSkill(int level) {
        return Math.min(5, Math.max(0, level));
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getCurrentXp() {
        return currentXp;
    }

    public int getCurrentLevel() {
        return currentLevel;
    }

    public int getTotalXp() {
        return totalXp;
    }

    public int getCraftingXp() {
        return craftingXp;
    }

    public int getCraftingLevel() {
        return craftingLevel;
    }

    public int getTotalCraftingXp() {
        return totalCraftingXp;
    }

    public Set<String> getDiscoveredFish() {
        return Collections.unmodifiableSet(discoveredFish);
    }

    public boolean hasDiscovered(String fishId) {
        return discoveredFish.contains(fishId);
    }

    public boolean discoverFish(String fishId) {
        return discoveredFish.add(fishId);
    }

    public int getSkillPoints() {
        return skillPoints;
    }

    public int getFastCatcher() {
        return fastCatcher;
    }

    public int getMasterHunter() {
        return masterHunter;
    }

    public int getDoubleCatch() {
        return doubleCatch;
    }

    public int getLuckyBait() {
        return luckyBait;
    }

    public void setCurrentXp(int currentXp) {
        this.currentXp = Math.max(0, currentXp);
    }

    public void setCurrentLevel(int currentLevel) {
        this.currentLevel = Math.max(1, currentLevel);
    }

    public void setTotalXp(int totalXp) {
        this.totalXp = Math.max(0, totalXp);
    }

    public void setCraftingXp(int craftingXp) {
        this.craftingXp = Math.max(0, craftingXp);
    }

    public void setCraftingLevel(int craftingLevel) {
        this.craftingLevel = Math.max(1, craftingLevel);
    }

    public void setTotalCraftingXp(int totalCraftingXp) {
        this.totalCraftingXp = Math.max(0, totalCraftingXp);
    }

    public void setSkillPoints(int skillPoints) {
        this.skillPoints = Math.max(0, skillPoints);
    }

    public void setFastCatcher(int level) {
        this.fastCatcher = clampSkill(level);
    }

    public void setMasterHunter(int level) {
        this.masterHunter = clampSkill(level);
    }

    public void setDoubleCatch(int level) {
        this.doubleCatch = clampSkill(level);
    }

    public void setLuckyBait(int level) {
        this.luckyBait = clampSkill(level);
    }

    public synchronized double getBalance() {
        return balance;
    }

    public synchronized void setBalance(double balance) {
        this.balance = Math.max(0.0, balance);
    }

    public synchronized void addBalance(double amount) {
        if (amount > 0)
            this.balance += amount;
    }

    public synchronized boolean withdrawBalance(double amount) {
        if (amount > 0 && this.balance >= amount) {
            this.balance -= amount;
            return true;
        }
        return false;
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
            this.skillPoints++;
            return true;
        }
        return false;
    }

    public synchronized void addCraftingXp(int amount) {
        if (amount <= 0)
            return;
        this.craftingXp += amount;
        this.totalCraftingXp += amount;
    }

    public synchronized boolean checkCraftingLevelUp() {
        int requiredXp = this.craftingLevel * 100;
        if (this.craftingXp >= requiredXp) {
            this.craftingXp -= requiredXp;
            this.craftingLevel++;
            return true;
        }
        return false;
    }

    public synchronized int checkAllLevelUps() {
        int leveled = 0;
        while (this.currentXp >= this.currentLevel * 100) {
            this.currentXp -= this.currentLevel * 100;
            this.currentLevel++;
            this.skillPoints++;
            leveled++;
        }
        return leveled;
    }

    public synchronized int checkAllCraftingLevelUps() {
        int leveled = 0;
        while (this.craftingXp >= this.craftingLevel * 100) {
            this.craftingXp -= this.craftingLevel * 100;
            this.craftingLevel++;
            leveled++;
        }
        return leveled;
    }
}