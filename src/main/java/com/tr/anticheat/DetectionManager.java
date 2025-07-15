package com.tr.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.*;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.*;

public class DetectionManager implements Listener {
    private final AntiCheatPlugin plugin;
    private final Map<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();
    
    // 配置参数
    private double maxHorizontalSpeed;
    private double maxVerticalSpeed;
    private double elytraHorizontalThreshold;
    private double elytraVerticalThreshold;
    private float maxAngleChange;
    private long rotationCheckInterval;
    private boolean clicksEnabled;
    private int maxCps;
    private int clicksCheckInterval;
    private int clicksViolationsToKick;
    private boolean flightDetectionEnabled;
    private int maxAirTime;
    private int maxViolations;
    private boolean autoBanEnabled;
    private int kicksBeforeBan;
    private Set<String> whitelistedWorlds;
    private Set<UUID> whitelistedPlayers;

    public DetectionManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        // 从主插件加载配置
        maxHorizontalSpeed = plugin.getConfig().getDouble("settings.movement.max-horizontal-speed", 0.35);
        maxVerticalSpeed = plugin.getConfig().getDouble("settings.movement.max-vertical-speed", 0.45);
        elytraHorizontalThreshold = plugin.getConfig().getDouble("settings.elytra.max-horizontal-speed", 2.0);
        elytraVerticalThreshold = plugin.getConfig().getDouble("settings.elytra.max-vertical-speed", 1.5);
        maxAngleChange = (float) plugin.getConfig().getDouble("settings.rotation.max-angle-change", 30.0);
        rotationCheckInterval = plugin.getConfig().getLong("settings.rotation.check-interval", 50);
        clicksEnabled = plugin.getConfig().getBoolean("settings.clicks.enabled", true);
        maxCps = plugin.getConfig().getInt("settings.clicks.max-cps", 15);
        clicksCheckInterval = plugin.getConfig().getInt("settings.clicks.check-interval", 5);
        clicksViolationsToKick = plugin.getConfig().getInt("settings.clicks.violations-to-kick", 3);
        flightDetectionEnabled = plugin.getConfig().getBoolean("settings.flight.enabled", true);
        maxAirTime = plugin.getConfig().getInt("settings.flight.max-air-time", 80);
        maxViolations = plugin.getConfig().getInt("settings.violations.max-violations", 10);
        autoBanEnabled = plugin.getConfig().getBoolean("settings.violations.auto-ban.enabled", false);
        kicksBeforeBan = plugin.getConfig().getInt("settings.violations.auto-ban.kicks-before-ban", 3);
        
        whitelistedWorlds = ConcurrentHashMap.newKeySet();
        whitelistedWorlds.addAll(plugin.getConfig().getStringList("whitelist.worlds"));
        
        whitelistedPlayers = ConcurrentHashMap.newKeySet();
        plugin.getConfig().getStringList("whitelist.players").forEach(uuidStr -> {
            try {
                whitelistedPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning(plugin.getLanguageManager().getMessage("error.invalid-uuid", uuidStr));
            }
        });
    }

    public PlayerData getPlayerData(Player player) {
        return playerDataMap.computeIfAbsent(player.getUniqueId(), k -> new PlayerData(player));
    }
    
    public void cleanupOfflinePlayers() {
        playerDataMap.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }
    
    public Deque<Long> getClickRecords(UUID uuid) {
        PlayerData data = playerDataMap.get(uuid);
        return data != null ? data.getClickRecords() : new ConcurrentLinkedDeque<>();
    }
    
    public int getClickViolations(UUID uuid) {
        PlayerData data = playerDataMap.get(uuid);
        return data != null ? data.getClickViolations() : 0;
    }
    
    public void decrementClickViolations(UUID uuid) {
        PlayerData data = playerDataMap.get(uuid);
        if (data != null) {
            data.decrementClickViolations();
        }
    }
    
    public void handleClickViolation(Player player, double cps) {
        PlayerData data = getPlayerData(player);
        data.incrementClickViolations();
        
        // 日志记录
        if (plugin.getConfig().getBoolean("settings.log-violations", true)) {
            plugin.getLogger().warning(plugin.getLanguageManager().getMessage("clicks.violation", 
                player.getName(),
                cps,
                data.getClickViolations(),
                clicksViolationsToKick
            ));
        }
        
        // 超过阈值踢出
        if (data.getClickViolations() >= clicksViolationsToKick) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.kickPlayer(plugin.getLanguageManager().getMessage("clicks.kick", cps));
                data.setClickViolations(0);
                
                // 记录踢出次数并检查封禁
                recordKickAndCheckBan(player);
            });
        }
    }
    
    public boolean shouldBypassCheck(Player player) {
        // 世界白名单
        if (whitelistedWorlds.contains(player.getWorld().getName())) {
            return true;
        }
        
        // 玩家白名单
        if (whitelistedPlayers.contains(player.getUniqueId())) {
            return true;
        }
        
        // 权限检查
        for (String perm : plugin.getConfig().getStringList("whitelist.bypass-permissions")) {
            if (player.hasPermission(perm)) {
                return true;
            }
        }
        
        // 创造模式/飞行玩家
        return player.getGameMode() == GameMode.CREATIVE 
