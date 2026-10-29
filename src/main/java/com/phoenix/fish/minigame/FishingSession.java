package com.phoenix.fish.minigame;

public class FishingSession {

    private volatile int playerBarCenter;
    private volatile int fishPosition;
    private volatile double progress;
    private volatile double tension;
    private volatile long lastPullTick;

    public FishingSession(int playerBarCenter, int fishPosition, double progress, double tension) {
        this.playerBarCenter = playerBarCenter;
        this.fishPosition = fishPosition;
        setProgress(progress);
        setTension(tension);
        this.lastPullTick = 0;
    }

    public int getPlayerBarCenter() {
        return playerBarCenter;
    }

    public void setPlayerBarCenter(int playerBarCenter) {
        this.playerBarCenter = playerBarCenter;
    }

    public int getFishPosition() {
        return fishPosition;
    }

    public void setFishPosition(int fishPosition) {
        this.fishPosition = fishPosition;
    }

    public double getProgress() {
        return progress;
    }

    public void setProgress(double progress) {
        this.progress = Math.max(0.0, Math.min(100.0, progress));
    }

    public double getTension() {
        return tension;
    }

    public void setTension(double tension) {
        this.tension = Math.max(0.0, Math.min(100.0, tension));
    }

    public long getLastPullTick() {
        return lastPullTick;
    }

    public void setLastPullTick(long lastPullTick) {
        this.lastPullTick = lastPullTick;
    }
}