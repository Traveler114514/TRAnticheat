package com.tr.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AntiCheatPlugin extends JavaPlugin implements Listener {

    // 数据存储
    private final Map<UUID, Integer> violationCount = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastValidLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastPitch = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRotationCheck = new ConcurrentHashMap<>();
    private final Set<UUID> whitelistedPlayers = new HashSet<>();
    
    // 配置值
    private double maxHorizontalSpeed;
    private double maxVerticalSpeed;
    private float maxAngleChange;
    private long rotationCheckInterval;
    private int maxViolations;
    private String kickMessage;
    private boolean debugMode;
    private Set<String> whitelistedWorlds;
    
    @Override
    public void onEnable() {
        // 保存默认配置
        saveDefaultConfig();
        reloadConfig();
        
        // 注册事件
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // 加载白名单
        loadWhitelists();
        
        // 启动违规计数清理任务
        startCleanupTask();
        
        // 注册命令
        setupCommands();
        
        getLogger().info("反作弊插件已启用! 版本: " + getDescription().getVersion());
    }
    
    private void setupCommands() {
        // 重载配置命令
        getCommand("anticheat-reload").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("anticheat.admin")) {
                sender.sendMessage("§c你没有权限执行此命令!");
                return true;
            }
            reloadConfig();
            sender.sendMessage("§a反作弊配置已重载!");
            return true;
        });
        
        // 重置违规计数命令
        getCommand("anticheat-reset").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("anticheat.admin")) {
                sender.sendMessage("§c你没有权限执行此命令!");
                return true;
            }
            
            if (args.length == 0) {
                sender.sendMessage("§c用法: /anticheat-reset <玩家>");
                return true;
            }
            
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§c玩家未在线或不存在!");
                return true;
            }
            
            violationCount.remove(target.getUniqueId());
            sender.sendMessage("§a已重置玩家 " + target.getName() + " 的违规计数!");
            return true;
        });
    }
    
    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        
        // 加载设置
        maxHorizontalSpeed = config.getDouble("settings.movement.max-horizontal-speed", 0.35);
        maxVerticalSpeed = config.getDouble("settings.movement.max-vertical-speed", 0.45);
        maxAngleChange = (float) config.getDouble("settings.rotation.max-angle-change", 30.0);
        rotationCheckInterval = config.getLong("settings.rotation.check-interval", 50);
        maxViolations = config.getInt("settings.violations.max-violations", 10);
        kickMessage = config.getString("settings.violations.kick-message", "检测到多次作弊行为");
        debugMode = config.getBoolean("settings.debug", false);
        
        // 加载白名单世界
        whitelistedWorlds = new HashSet<>(config.getStringList("whitelist.worlds"));
    }
    
    private void loadWhitelists() {
        FileConfiguration config = getConfig();
        
        // 加载白名单玩家
        for (String uuidStr : config.getStringList("whitelist.players")) {
            try {
                whitelistedPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                getLogger().warning("无效的UUID格式: " + uuidStr);
            }
        }
        
        getLogger().info("已加载 " + whitelistedPlayers.size() + " 名白名单玩家");
    }
    
    private void startCleanupTask() {
        // 每5分钟清理一次过期违规记录
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            int before = violationCount.size();
            violationCount.entrySet().removeIf(entry -> 
                !Bukkit.getOfflinePlayer(entry.getKey()).isOnline());
            
            if (debugMode) {
                getLogger().info("清理违规记录: " + (before - violationCount.size()) + " 条");
            }
        }, 20 * 60 * 5, 20 * 60 * 5); // 每5分钟
    }
    
    @Override
    public void reloadConfig() {
        super.reloadConfig();
        loadConfigValues();
        loadWhitelists();
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // 初始化玩家数据
        lastValidLocations.put(playerId, player.getLocation().clone());
        lastYaw.put(playerId, player.getLocation().getYaw());
        lastPitch.put(playerId, player.getLocation().getPitch());
        lastRotationCheck.put(playerId, System.currentTimeMillis());
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        
        // 清理玩家数据
        violationCount.remove(playerId);
        lastValidLocations.remove(playerId);
        lastYaw.remove(playerId);
        lastPitch.remove(playerId);
        lastRotationCheck.remove(playerId);
    }
    
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Location to = event.getTo();
        
        if (to == null) return;
        
        // 检查白名单
        if (shouldBypassCheck(player)) {
            updatePlayerData(player);
            return;
        }
        
        Location from = event.getFrom();
        
        // 移动速度检测
        if (checkMovementSpeed(player, from, to)) {
            handleViolation(player, "移动速度异常", true);
            event.setTo(lastValidLocations.get(playerId).clone());
            return;
        }
        
        // 异常转头检测
        if (checkRotationSpeed(player, from, to)) {
            handleViolation(player, "异常转头行为", true);
            // 恢复之前的角度
            to.setYaw(lastYaw.get(playerId));
            to.setPitch(lastPitch.get(playerId));
            event.setTo(to.clone());
        }
        
        // 更新玩家数据
        updatePlayerData(player);
    }
    
    private boolean shouldBypassCheck(Player player) {
        // 检查世界白名单
        if (whitelistedWorlds.contains(player.getWorld().getName())) {
            return true;
        }
        
        // 检查玩家白名单
        if (whitelistedPlayers.contains(player.getUniqueId())) {
            return true;
        }
        
        // 检查权限
        for (String permission : getConfig().getStringList("whitelist.bypass-permissions")) {
            if (player.hasPermission(permission)) {
                return true;
            }
        }
        
        // 忽略飞行/创造模式玩家
        return player.getAllowFlight() || player.isOp() || player.getGameMode() == org.bukkit.GameMode.CREATIVE;
    }
    
    private void updatePlayerData(Player player) {
        UUID playerId = player.getUniqueId();
        Location loc = player.getLocation();
        
        lastValidLocations.put(playerId, loc.clone());
        lastYaw.put(playerId, loc.getYaw());
        lastPitch.put(playerId, loc.getPitch());
    }
    
    private boolean checkMovementSpeed(Player player, Location from, Location to) {
        // 计算移动向量
        Vector vector = to.toVector().subtract(from.toVector());
        
        // 计算水平移动距离
        double horizontalDistance = Math.sqrt(vector.getX() * vector.getX() + vector.getZ() * vector.getZ());
        
        // 计算垂直移动距离
        double verticalDistance = Math.abs(vector.getY());
        
        // 检查是否超过阈值
        boolean speedHack = horizontalDistance > maxHorizontalSpeed;
        boolean flyHack = verticalDistance > maxVerticalSpeed;
        
        return speedHack || flyHack;
    }
    
    private boolean checkRotationSpeed(Player player, Location from, Location to) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        
        // 获取上次检测时间
        Long lastCheck = lastRotationCheck.get(playerId);
        if (lastCheck == null) {
            lastRotationCheck.put(playerId, currentTime);
            return false;
        }
        
        // 检查时间间隔
        if (currentTime - lastCheck < rotationCheckInterval) {
            return false;
        }
        
        // 计算角度变化
        float deltaYaw = Math.abs(to.getYaw() - from.getYaw());
        float deltaPitch = Math.abs(to.getPitch() - from.getPitch());
        
        // 标准化角度差
        if (deltaYaw > 180) deltaYaw = 360 - deltaYaw;
        if (deltaPitch > 180) deltaPitch = 360 - deltaPitch;
        
        // 计算时间差（秒）
        float timeDelta = (currentTime - lastCheck) / 1000f;
        float yawSpeed = deltaYaw / timeDelta;
        float pitchSpeed = deltaPitch / timeDelta;
        
        // 更新最后检测时间
        lastRotationCheck.put(playerId, currentTime);
        
        // 检查是否超过阈值
        return yawSpeed > maxAngleChange || pitchSpeed > maxAngleChange;
    }
    
    private void handleViolation(Player player, String reason, boolean rollback) {
        UUID playerId = player.getUniqueId();
        
        // 增加违规计数
        int count = violationCount.getOrDefault(playerId, 0) + 1;
        violationCount.put(playerId, count);
        
        // 日志记录
        if (getConfig().getBoolean("settings.log-violations", true)) {
            getLogger().warning(String.format(
                "玩家 %s 违规: %s (次数: %d/%d) 世界: %s",
                player.getName(),
                reason,
                count,
                maxViolations,
                player.getWorld().getName()
            ));
        }
        
        // 调试消息
        if (debugMode) {
            player.sendMessage("§c[反作弊] §7检测到可疑行为: §f" + reason);
        }
        
        // 超过阈值踢出玩家
        if (count >= maxViolations) {
            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(kickMessage + " (" + count + "/" + maxViolations + ")");
                violationCount.remove(playerId);
            });
        }
        
        // 回弹处理
        if (rollback && lastValidLocations.containsKey(playerId)) {
            player.teleport(lastValidLocations.get(playerId));
        }
    }
    
    // 提供API给其他插件使用
    public int getViolations(UUID playerId) {
        return violationCount.getOrDefault(playerId, 0);
    }
    
    public void resetViolations(UUID playerId) {
        violationCount.remove(playerId);
    }
    
    public void addWhitelistPlayer(UUID playerId) {
        whitelistedPlayers.add(playerId);
    }
    
    public void removeWhitelistPlayer(UUID playerId) {
        whitelistedPlayers.remove(playerId);
    }
}
