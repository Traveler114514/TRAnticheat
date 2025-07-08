package com.tr.anticheat;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.*;

public class AntiCheatPlugin extends JavaPlugin implements Listener {

    /* ------------------------- 配置参数 ------------------------- */
    private boolean debugMode;
    private Set<String> whitelistedWorlds;
    private Set<UUID> whitelistedPlayers;
    
    // 移动检测
    private double maxHorizontalSpeed;
    private double maxVerticalSpeed;
    
    // 视角检测
    private float maxAngleChange;
    private long rotationCheckInterval;
    
    // 点击检测
    private boolean clicksEnabled;
    private int maxCps;
    private int clicksCheckInterval;
    private int clicksViolationsToKick;
    private String clicksKickMessage;
    
    // 通用违规
    private int maxViolations;
    private String kickMessage;

    /* ------------------------- 数据存储 ------------------------- */
    // 移动/视角数据
    private final ConcurrentHashMap<UUID, Location> lastValidLocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> lastPitch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastRotationCheck = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> violationCount = new ConcurrentHashMap<>();
    
    // 点击数据
    private final ConcurrentHashMap<UUID, Deque<Long>> clickRecords = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> clickViolations = new ConcurrentHashMap<>();

    /* ------------------------- 插件生命周期 ------------------------- */
    @Override
    public void onEnable() {
        // 1. 初始化配置
        saveDefaultConfig();
        reloadConfig();
        
        // 2. 注册事件
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // 3. 启动定时任务
        startCleanupTask();
        startClickCheckTask();
        
        getLogger().info(ChatColor.GREEN + "反作弊插件已启用! 版本: " + getDescription().getVersion());
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        FileConfiguration config = getConfig();
        
        // 加载通用设置
        debugMode = config.getBoolean("settings.debug", false);
        maxViolations = config.getInt("settings.violations.max-violations", 10);
        kickMessage = ChatColor.translateAlternateColorCodes('&', 
            config.getString("settings.violations.kick-message", "&c检测到多次作弊行为"));
        
        // 移动检测
        maxHorizontalSpeed = config.getDouble("settings.movement.max-horizontal-speed", 0.35);
        maxVerticalSpeed = config.getDouble("settings.movement.max-vertical-speed", 0.45);
        
        // 视角检测
        maxAngleChange = (float) config.getDouble("settings.rotation.max-angle-change", 30.0);
        rotationCheckInterval = config.getLong("settings.rotation.check-interval", 50);
        
        // 点击检测
        clicksEnabled = config.getBoolean("settings.clicks.enabled", true);
        maxCps = config.getInt("settings.clicks.max-cps", 15);
        clicksCheckInterval = config.getInt("settings.clicks.check-interval", 5);
        clicksViolationsToKick = config.getInt("settings.clicks.violations-to-kick", 3);
        clicksKickMessage = ChatColor.translateAlternateColorCodes('&',
            config.getString("settings.clicks.kick-message", "&c检测到异常点击行为 (CPS: {cps})"));
        
        // 白名单
        whitelistedWorlds = ConcurrentHashMap.newKeySet();
        whitelistedWorlds.addAll(config.getStringList("whitelist.worlds"));
        
        whitelistedPlayers = ConcurrentHashMap.newKeySet();
        config.getStringList("whitelist.players").forEach(uuidStr -> {
            try {
                whitelistedPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                getLogger().warning("无效的UUID格式: " + uuidStr);
            }
        });
    }

    /* ------------------------- 定时任务 ------------------------- */
    private void startCleanupTask() {
        // 每10分钟清理一次离线玩家数据
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            int before = violationCount.size();
            violationCount.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
            
            if (debugMode && before != violationCount.size()) {
                getLogger().info("清理数据: 移除了 " + (before - violationCount.size()) + " 条离线玩家记录");
            }
        }, 20 * 60 * 10, 20 * 60 * 10);
    }

    private void startClickCheckTask() {
        if (!clicksEnabled) return;
        
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (shouldBypassCheck(player)) continue;
                
                UUID uuid = player.getUniqueId();
                Deque<Long> clicks = clickRecords.getOrDefault(uuid, new ConcurrentLinkedDeque<>());
                
                // 移除1秒前的记录
                while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000) {
                    clicks.removeFirst();
                }
                
                // 计算CPS
                double cps = clicks.size();
                if (cps >= maxCps) {
                    handleClickViolation(player, cps);
                } else if (clickViolations.getOrDefault(uuid, 0) > 0) {
                    // 正常点击时减少违规计数
                    clickViolations.put(uuid, clickViolations.get(uuid) - 1);
                }
            }
        }, 0, clicksCheckInterval * 20L);
    }

    /* ------------------------- 事件处理器 ------------------------- */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldBypassCheck(player)) return;
        
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // 移动速度检测
        if (checkMovementSpeed(from, to)) {
            handleViolation(player, "移动速度异常", true);
            event.setTo(lastValidLocations.get(player.getUniqueId()));
            return;
        }
        
        // 视角检测
        if (checkRotationSpeed(player, from, to)) {
            handleViolation(player, "异常转头行为", true);
            to.setYaw(lastYaw.get(player.getUniqueId()));
            to.setPitch(lastPitch.get(player.getUniqueId()));
            event.setTo(to);
        }
        
        // 更新最后有效位置
        updatePlayerData(player);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!clicksEnabled) return;
        
        Player player = event.getPlayer();
        if (shouldBypassCheck(player)) return;
        
        // 只检测左键点击
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            clickRecords.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentLinkedDeque<>())
                       .add(System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // 初始化玩家数据
        lastValidLocations.put(uuid, player.getLocation().clone());
        lastYaw.put(uuid, player.getLocation().getYaw());
        lastPitch.put(uuid, player.getLocation().getPitch());
        lastRotationCheck.put(uuid, System.currentTimeMillis());
        
        // 初始化点击数据
        clickRecords.put(uuid, new ConcurrentLinkedDeque<>());
        clickViolations.put(uuid, 0);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        
        // 清理所有玩家数据
        lastValidLocations.remove(uuid);
        lastYaw.remove(uuid);
        lastPitch.remove(uuid);
        lastRotationCheck.remove(uuid);
        violationCount.remove(uuid);
        clickRecords.remove(uuid);
        clickViolations.remove(uuid);
    }

    /* ------------------------- 检测逻辑 ------------------------- */
    private boolean checkMovementSpeed(Location from, Location to) {
        Vector vector = to.toVector().subtract(from.toVector());
        
        double horizontal = Math.hypot(vector.getX(), vector.getZ());
        double vertical = Math.abs(vector.getY());
        
        return horizontal > maxHorizontalSpeed || vertical > maxVerticalSpeed;
    }

    private boolean checkRotationSpeed(Player player, Location from, Location to) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        
        Long lastCheck = lastRotationCheck.get(uuid);
        if (lastCheck == null || now - lastCheck < rotationCheckInterval) {
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
        
        lastRotationCheck.put(uuid, now);
        return yawSpeed > maxAngleChange || pitchSpeed > maxAngleChange;
    }

    /* ------------------------- 违规处理 ------------------------- */
    private void handleViolation(Player player, String reason, boolean rollback) {
        UUID uuid = player.getUniqueId();
        
        // 增加违规计数
        int count = violationCount.merge(uuid, 1, Integer::sum);
        
        // 记录日志
        if (getConfig().getBoolean("settings.log-violations", true)) {
            getLogger().warning(String.format(
                "[%s] %s 违规: %s (%d/%d)",
                player.getWorld().getName(),
                player.getName(),
                reason,
                count,
                maxViolations
            ));
        }
        
        // 调试消息
        if (debugMode) {
            player.sendMessage(ChatColor.RED + "[反作弊] " + ChatColor.GRAY + "检测到: " + ChatColor.WHITE + reason);
        }
        
        // 超过阈值踢出
        if (count >= maxViolations) {
            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(kickMessage + " (" + count + "/" + maxViolations + ")");
                violationCount.remove(uuid);
            });
        }
        
        // 回滚位置
        if (rollback && lastValidLocations.containsKey(uuid)) {
            player.teleport(lastValidLocations.get(uuid));
        }
    }

    private void handleClickViolation(Player player, double cps) {
        UUID uuid = player.getUniqueId();
        int violations = clickViolations.merge(uuid, 1, Integer::sum);
        
        // 日志记录
        if (getConfig().getBoolean("settings.log-violations", true)) {
            getLogger().warning(String.format(
                "%s 点击异常: %.1f CPS (%d/%d)",
                player.getName(),
                cps,
                violations,
                clicksViolationsToKick
            ));
        }
        
        // 超过阈值踢出
        if (violations >= clicksViolationsToKick) {
            String msg = clicksKickMessage.replace("{cps}", String.format("%.1f", cps));
            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(msg);
                clickViolations.remove(uuid);
            });
        }
    }

    /* ------------------------- 工具方法 ------------------------- */
    private void updatePlayerData(Player player) {
        UUID uuid = player.getUniqueId();
        Location loc = player.getLocation();
        
        lastValidLocations.put(uuid, loc.clone());
        lastYaw.put(uuid, loc.getYaw());
        lastPitch.put(uuid, loc.getPitch());
    }

    private boolean shouldBypassCheck(Player player) {
        // 世界白名单
        if (whitelistedWorlds.contains(player.getWorld().getName())) {
            return true;
        }
        
        // 玩家白名单
        if (whitelistedPlayers.contains(player.getUniqueId())) {
            return true;
        }
        
        // 权限检查
        for (String perm : getConfig().getStringList("whitelist.bypass-permissions")) {
            if (player.hasPermission(perm)) {
                return true;
            }
        }
        
        // 创造模式/飞行玩家
        return player.getGameMode() == GameMode.CREATIVE 
            || player.getGameMode() == GameMode.SPECTATOR
            || player.getAllowFlight();
    }

    /* ------------------------- API方法 ------------------------- */
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
