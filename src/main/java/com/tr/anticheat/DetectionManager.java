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

    public DetectionManager(Anti极速飞艇官网CheatPlugin plugin) {
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
        return player.getGameMode() == GameMode.CREATIVE || 
            player.getGameMode() == GameMode.SPECTATOR ||
            player.getAllowFlight();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        // 维护模式时跳过检测
        if (plugin.getMaintenanceManager().isMaintenanceMode()) {
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                event.getPlayer().sendMessage(plugin.getLanguageManager().getMessage("maintenance.bypass"));
            }
            return;
        }
        
        Player player = event.getPlayer();
        if (shouldBypassCheck(player)) return;
        
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // 移动速度检测
        if (checkMovementSpeed(player, from, to)) {
            handleViolation(player, "violation.movement", true);
            event.setTo(getPlayerData(player).getLastValidLocation());
            return;
        }
        
        // 视角检测
        if (checkRotationSpeed(player, from, to)) {
            handleViolation(player, "violation.rotation", true);
            to.setYaw(getPlayerData(player).getLastYaw());
            to.setPitch(getPlayerData(player).getLastPitch());
            event.setTo(to);
        }
        
        // 飞行检测 - 放在其他检测之后
        if (flightDetectionEnabled && checkFlight(player, from, to)) {
            handleViolation(player, "violation.flight", true);
            event.setTo(getPlayerData(player).getLastValidLocation());
        }
        
        // 更新最后有效位置
        updatePlayerData(player);
    }
    
    @EventHandler
    public void onEntityGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            PlayerData data = getPlayerData(player);
            
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                if (event.isGliding()) {
                    player.sendMessage(plugin.getLanguageManager().getMessage("debug.elytra-on"));
                } else {
                    player.sendMessage(plugin.getLanguageManager().getMessage("debug.elytra-off"));
                }
            }
            
            // 当玩家停止使用鞘翅时，重置飞行计数器
            if (!event.isGliding()) {
                data.setAirTimeCounter(0);
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 维护模式时跳过检测
        if (plugin.getMaintenanceManager().isMaintenanceMode()) {
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                event.getPlayer().sendMessage(plugin.getLanguageManager().getMessage("maintenance.bypass"));
            }
            return;
        }
        
        if (!clicksEnabled) return;
        if (shouldBypassCheck(event.getPlayer())) return;
        
        // 只检测左键点击
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            PlayerData data = getPlayerData(event.getPlayer());
            data.getClickRecords().add(System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = new PlayerData(player);
        playerDataMap.put(player.getUniqueId(), data);
        
        // 初始化踢出计数
        data.setKickCount(0);
        
        if (plugin.getConfig().getBoolean("settings.debug", false)) {
            plugin.getLogger().info(plugin.getLanguageManager().getMessage("player.join", 
                player.getName(), data.getKickCount()));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerDataMap.remove(event.getPlayer().getUniqueId());
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        // 检查玩家是否被封禁
        if (plugin.getBanManager().isBanned(event.getPlayer().getName())) {
            String banMessage = plugin.getBanManager().getBanInfo(event.getPlayer().getName());
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, banMessage);
        }
    }

    private boolean checkMovementSpeed(Player player, Location from, Location to) {
        Vector vector = to.toVector().subtract(from.toVector());
        
        double horizontal = Math.hypot(vector.getX(), vector.getZ());
        double vertical = Math.abs(vector.getY());
        
        // 鞘翅飞行特殊处理
        if (player.isGliding()) {
            // 使用专用阈值检查鞘翅飞行
            return horizontal > elytraHorizontalThreshold || vertical > elytraVerticalThreshold;
        }
        
        // 普通移动检测
        return horizontal > maxHorizontalSpeed || vertical > maxVerticalSpeed;
    }

    private boolean checkRotationSpeed(Player player, Location from, Location to) {
        PlayerData data = getPlayerData(player);
        long now = System.currentTimeMillis();
        
        long lastCheck = data.getLastRotationCheck();
        if (now - lastCheck < rotationCheckInterval) {
            return false;
        }
        
        // 计算角度变化
        float deltaYaw = Math.abs(to.getYaw() - from.getYaw());
        float deltaPitch = Math.abs(to.getPitch() - from.getPitch());
        
        // 标准化角度
        if (deltaYaw > 180) deltaYaw = 360 - deltaYaw;
        if (deltaPitch > 180) deltaPitch = 360 - deltaPitch;
        
        // 计算速度(度/秒)
        float timeDelta = (now - lastCheck) / 1000f;
        float yawSpeed = deltaYaw / timeDelta;
        float pitchSpeed = deltaPitch / timeDelta;
        
        data.setLastRotationCheck(now);
        return yawSpeed > maxAngleChange || pitchSpeed > maxAngleChange;
    }
    
    private boolean checkFlight(Player player, Location from, Location to) {
        // 跳过鞘翅玩家
        if (player.isGliding()) {
            return false;
        }
        
        // 跳过创造模式、旁观模式或有飞行权限的玩家
        if (player.getGameMode() == GameMode.CREATIVE || 
            player.getGameMode() == GameMode.SPECTATOR ||
            player.getAllowFlight()) {
            return false;
        }
        
        PlayerData data = getPlayerData(player);
        
        // 检查玩家是否在地面上
        boolean isOnGround = isPlayerOnGround(player);
        
        // 获取之前的地面状态
        boolean wasOnGround = data.isWasOnGround();
        data.setWasOnGround(isOnGround);
        
        // 如果玩家在地面上，重置计数器
        if (isOnGround) {
            data.setAirTimeCounter(0);
            return false;
        }
        
        // 如果玩家刚从地面跳起，初始化计数器
        if (wasOnGround && !isOnGround) {
            data.setAirTimeCounter(1);
            return false;
        }
        
        // 增加空中时间计数
        data.incrementAirTimeCounter();
        
        // 检查是否超过最大空中时间
        if (data.getAirTimeCounter() > maxAirTime) {
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                player.sendMessage(plugin.getLanguageManager().getMessage("flight.detected", 
                    data.getAirTimeCounter(), maxAirTime));
            }
            return true;
        }
        
        return false;
    }
    
    private boolean isPlayerOnGround(Player player) {
        Location loc = player.getLocation();
        
        // 检查玩家脚下方块是否固体
        Block blockUnder = loc.getBlock().getRelative(BlockFace.DOWN);
        if (blockUnder.getType().isSolid()) {
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

    private void handleViolation(Player player, String reasonKey, boolean rollback) {
        PlayerData data = getPlayerData(player);
        data.incrementViolationCount();
        
        // 记录日志
        if (plugin.getConfig().getBoolean("settings.log-violations", true)) {
            plugin.getLogger().warning(plugin.getLanguageManager().getMessage("violation.log", 
                player.getWorld().getName(),
                player.getName(),
                plugin.getLanguageManager().getMessage(reasonKey),
                data.getViolationCount(),
                maxViolations
            ));
        }
        
        // 调试消息
        if (plugin.getConfig().getBoolean("settings.debug", false)) {
            player.sendMessage(plugin.getLanguageManager().getMessage("violation.detected", 
                plugin.getLanguageManager().getMessage(reasonKey)));
        }
        
        // 超过阈值踢出
        if (data.getViolationCount() >= maxViolations) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.kickPlayer(plugin.getLanguageManager().getMessage("kick.message", 
                    data.getViolationCount(), maxViolations));
                data.setViolationCount(0);
                
                // 记录踢出次数并检查封禁
                recordKickAndCheckBan(player);
            });
        }
        
        // 回滚位置
        if (rollback) {
            player.teleport(data.getLastValidLocation());
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                player.sendMessage(plugin.getLanguageManager().getMessage("debug.teleport"));
            }
        }
    }
    
    private void recordKickAndCheckBan(Player player) {
        if (!autoBanEnabled) return;
        
        PlayerData data = getPlayerData(player);
        data.incrementKickCount();
        
        // 记录日志
        plugin.getLogger().info(plugin.getLanguageManager().getMessage("player.kicked", 
            player.getName(), data.getKickCount(), kicksBeforeBan));
        
        if (data.getKickCount() >= kicksBeforeBan) {
            // 使用自定义封禁
            plugin.getBanManager().customBanPlayer(player.getName(), 
                plugin.getLanguageManager().getMessage("ban.reason", String.valueOf(data.getKickCount())), 
                "AntiCheat系统");
            
            // 重置踢出计数
            data.setKickCount(0);
        }
    }

    private void updatePlayerData(Player player) {
        PlayerData data = getPlayerData(player);
        Location loc = player.getLocation();
        
        data.setLastValidLocation(loc.clone());
        data.setLastYaw(loc.getYaw());
        data.setLastPitch(loc.getPitch());
    }
}
