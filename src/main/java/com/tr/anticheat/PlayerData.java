package com.tr.anticheat;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

public class PlayerData {
    private UUID playerId;
    private Location lastValidLocation;
    private float lastYaw;
    private float lastPitch;
    private long lastRotationCheck;
    private int violationCount;
    private Deque<Long> clickRecords = new ConcurrentLinkedDeque<>();
    private int clickViolations;
    private int airTimeCounter;
    private boolean wasOnGround;
    private int kickCount;

    public PlayerData(Player player) {
        this.playerId = player.getUniqueId();
        this.lastValidLocation = player.getLocation().clone();
        this.lastYaw = player.getLocation().getYaw();
        this.lastPitch = player.getLocation().getPitch();
        this.lastRotationCheck = System.currentTimeMillis();
        this.wasOnGround = isPlayerOnGround(player);
    }

    // Getter 和 Setter 方法
    public UUID getPlayerId() {
        return playerId;
    }

    public Location getLastValidLocation() {
        return lastValidLocation;
    }

    public void setLastValidLocation(Location lastValidLocation) {
        this.lastValidLocation = lastValidLocation;
    }

    public float getLastYaw() {
        return lastYaw;
    }

    public void setLastYaw(float lastYaw) {
        this.lastYaw = lastYaw;
    }

    public float getLastPitch() {
        return lastPitch;
    }

    public void setLastPitch(float lastPitch) {
        this.lastPitch = lastPitch;
    }

    public long getLastRotationCheck() {
        return lastRotationCheck;
    }

    public void setLastRotationCheck(long lastRotationCheck) {
        this.lastRotationCheck = lastRotationCheck;
    }

    public int getViolationCount() {
        return violationCount;
    }

    public void setViolationCount(int violationCount) {
        this.violationCount = violationCount;
    }

    public void incrementViolationCount() {
        this.violationCount++;
    }

    public Deque<Long> getClickRecords() {
        return clickRecords;
    }

    public int getClickViolations() {
        return clickViolations;
    }

    public void setClickViolations(int clickViolations) {
        this.clickViolations = clickViolations;
    }

    public void incrementClickViolations() {
        this.clickViolations++;
    }

    public int getAirTimeCounter() {
        return airTimeCounter;
    }

    public void setAirTimeCounter(int airTimeCounter) {
        this.airTimeCounter = airTimeCounter;
    }

    public void incrementAirTimeCounter() {
        this.airTimeCounter++;
    }

    public boolean isWasOnGround() {
        return wasOnGround;
    }

    public void setWasOnGround(boolean wasOnGround) {
        this.wasOnGround = wasOnGround;
    }

    public int getKickCount() {
        return kickCount;
    }

    public void setKickCount(int kickCount) {
        this.kickCount = kick极速飞艇官网Count;
    }

    public void incrementKickCount() {
        this.kickCount++;
    }

    private boolean isPlayerOnGround(Player player) {
        // 实现地面检测逻辑
        return player.isOnGround();
    }
}
