package com.tr.anticheat;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

public class PlayerData {
    private final UUID playerId;
    private Location lastValidLocation;
    private float lastYaw;
    private float lastPitch;
    private long lastRotationCheck;
    private int violationCount;
    private final Deque<Long> clickRecords = new ConcurrentLinkedDeque<>();
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
    
    public void resetViolationCount() {
        this.violationCount = 0;
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
    
    public void decrementClickViolations() {
        this.clickViolations = Math.max(0, this.clickViolations - 1);
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
        this.kickCount = kickCount;
    }
    
    public void incrementKickCount() {
        this.kickCount++;
    }
    
    private boolean isPlayerOnGround(Player player) {
        Location loc = player.getLocation();
        
        // 检查玩家脚下方块是否固体
        if (loc.getBlock().getRelative(BlockFace.DOWN).getType().isSolid()) {
            return true;
        }
        
        // 检查玩家位置下方0.5格是否有方块
        Location below = loc.clone().subtract(0, 0.5, 0);
        if (below.getBlock().getType().isSolid()) {
            return true;
        }
        
        // 使用Bukkit的isOnGround方法作为后备
        return player.isOnGround();
    }
}
