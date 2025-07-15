package com.tr.anticheat;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public class AntiCheatPlugin extends JavaPlugin implements Listener {
    // 配置管理器
    private YamlConfiguration config;
    private YamlConfiguration languageConfig;
    private YamlConfiguration bansConfig;
    
    // 数据存储
    private final Map<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();
    private final Map<String, BanInfo> bannedPlayers = new ConcurrentHashMap<>();
    
    // 任务调度器
    private int cleanupTaskId = -1;
    private int clickCheckTaskId = -1;
    private int maintenanceTaskId = -1;
    
    // 维护模式状态
    private boolean maintenanceMode = false;
    
    @Override
    public void onEnable() {
        // 加载配置
        loadConfigs();
        
        // 注册事件
        getServer().getPluginManager().registerEvents(this, this);
        
        // 设置命令执行器
        getCommand("traban").setExecutor(this);
        getCommand("traunban").setExecutor(this);
        getCommand("trac").setExecutor(this);
        
        // 启动任务
        startTasks();
        
        getLogger().info(getMessage("plugin.enabled", getDescription().getVersion()));
    }

    @Override
    public void onDisable() {
        // 停止所有任务
        stopTasks();
        
        // 保存数据
        saveBansConfig();
        
        getLogger().info(getMessage("plugin.disabled"));
    }
    
    /**
     * 加载所有配置文件
     */
    private void loadConfigs() {
        // 主配置
        saveDefaultConfig();
        reloadConfig();
        config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
        
        // 语言文件
        String language = config.getString("language", "en");
        File langFile = new File(getDataFolder(), "messages_" + language + ".yml");
        if (!langFile.exists()) {
            saveResource("messages_" + language + ".yml", false);
        }
        languageConfig = YamlConfiguration.loadConfiguration(langFile);
        
        // 封禁列表
        File bansFile = new File(getDataFolder(), "bans.yml");
        if (!bansFile.exists()) {
            try {
                bansFile.createNewFile();
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "无法创建封禁配置文件", e);
            }
        }
        bansConfig = YamlConfiguration.loadConfiguration(bansFile);
        loadBannedPlayers();
    }
    
    /**
     * 加载封禁玩家列表
     */
    private void loadBannedPlayers() {
        bannedPlayers.clear();
        if (bansConfig != null) {
            for (String playerName : bansConfig.getKeys(false)) {
                String reason = bansConfig.getString(playerName + ".reason", "作弊");
                String bannedBy = bansConfig.getString(playerName + ".bannedBy", "系统");
                String date = bansConfig.getString(playerName + ".date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                bannedPlayers.put(playerName.toLowerCase(), new BanInfo(reason, bannedBy, date));
            }
        }
    }
    
    /**
     * 保存封禁列表
     */
    private void saveBansConfig() {
        if (bansConfig == null) return;
        
        // 清空现有数据
        for (String key : bansConfig.getKeys(false)) {
            bansConfig.set(key, null);
        }
        
        // 添加当前封禁数据
        for (Map.Entry<String, BanInfo> entry : bannedPlayers.entrySet()) {
            String player = entry.getKey();
            BanInfo info = entry.getValue();
            bansConfig.set(player + ".reason", info.getReason());
            bansConfig.set(player + ".bannedBy", info.getBannedBy());
            bansConfig.set(player + ".date", info.getDate());
        }
        
        try {
            bansConfig.save(new File(getDataFolder(), "bans.yml"));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "无法保存封禁配置文件", e);
        }
    }
    
    /**
     * 获取本地化消息
     */
    private String getMessage(String key, Object... args) {
        String message = languageConfig.getString(key, key);
        for (int i = 0; i < args.length; i++) {
            message = message.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }
    
    /**
     * 启动所有任务
     */
    private void startTasks() {
        // 清理离线玩家数据任务
        cleanupTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            playerDataMap.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        }, 20 * 60 * 10, 20 * 60 * 10); // 每10分钟清理一次
        
        // 点击检测任务
        if (config.getBoolean("settings.clicks.enabled", true)) {
            int interval = config.getInt("settings.clicks.check-interval", 5);
            clickCheckTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::checkClicks, 0, interval * 20L);
        }
        
        // 维护模式检查任务
        maintenanceTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::checkMaintenanceMode, 0, 20 * 30); // 每30秒检查一次
    }
    
    /**
     * 停止所有任务
     */
    private void stopTasks() {
        if (cleanupTaskId != -1) Bukkit.getScheduler().cancelTask(cleanupTaskId);
        if (clickCheckTaskId != -1) Bukkit.getScheduler().cancelTask(clickCheckTaskId);
        if (maintenanceTaskId != -1) Bukkit.getScheduler().cancelTask(maintenanceTaskId);
    }
    
    /**
     * 检查维护模式
     */
    private void checkMaintenanceMode() {
        // 这里可以添加从外部源检查维护模式的逻辑
        // 例如从数据库或配置文件检查
        boolean newMaintenanceMode = config.getBoolean("maintenance-mode", false);
        
        if (newMaintenanceMode != maintenanceMode) {
            maintenanceMode = newMaintenanceMode;
            getLogger().info(getMessage("maintenance.status-changed", maintenanceMode ? "启用" : "禁用"));
        }
    }
    
    /**
     * 检查点击速度
     */
    private void checkClicks() {
        // 维护模式时跳过检测
        if (maintenanceMode) return;
        
        long now = System.currentTimeMillis();
        int maxCps = config.getInt("settings.clicks.max-cps", 15);
        int violationsToKick = config.getInt("settings.clicks.violations-to-kick", 3);
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (shouldBypassCheck(player)) continue;
            
            PlayerData data = getPlayerData(player);
            Deque<Long> clicks = data.getClickRecords();
            
            // 清理过期点击记录
            if (!clicks.isEmpty()) {
                Iterator<Long> iterator = clicks.iterator();
                while (iterator.hasNext()) {
                    if (now - iterator.next() > 1000) {
                        iterator.remove();
                    } else {
                        break;
                    }
                }
            }
            
            // 计算CPS
            double cps = clicks.size();
            if (cps > maxCps) {
                handleClickViolation(player, cps, violationsToKick);
            } else if (data.getClickViolations() > 0) {
                data.decrementClickViolations();
            }
        }
    }
    
    /**
     * 处理点击违规
     */
    private void handleClickViolation(Player player, double cps, int violationsToKick) {
        PlayerData data = getPlayerData(player);
        data.incrementClickViolations();
        
        // 日志记录
        if (config.getBoolean("settings.log-violations", true)) {
            getLogger().warning(getMessage("clicks.violation", 
                player.getName(),
                cps,
                data.getClickViolations(),
                violationsToKick
            ));
        }
        
        // 超过阈值踢出
        if (data.getClickViolations() >= violationsToKick) {
            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(getMessage("clicks.kick", cps));
                data.setClickViolations(0);
                
                // 记录踢出次数并检查封禁
                recordKickAndCheckBan(player);
            });
        }
    }
    
    /**
     * 检查玩家是否应该绕过检测
     */
    private boolean shouldBypassCheck(Player player) {
        // 世界白名单
        List<String> whitelistedWorlds = config.getStringList("whitelist.worlds");
        if (whitelistedWorlds.contains(player.getWorld().getName())) {
            return true;
        }
        
        // 玩家白名单
        List<String> whitelistedPlayers = config.getStringList("whitelist.players");
        if (whitelistedPlayers.contains(player.getUniqueId().toString()) || 
            whitelistedPlayers.contains(player.getName())) {
            return true;
        }
        
        // 权限检查
        for (String perm : config.getStringList("whitelist.bypass-permissions")) {
            if (player.hasPermission(perm)) {
                return true;
            }
        }
        
        // 创造模式/飞行玩家
        return player.getGameMode() == GameMode.CREATIVE || 
            player.getGameMode() == GameMode.SPECTATOR ||
            player.getAllowFlight();
    }
    
    /**
     * 获取玩家数据
     */
    private PlayerData getPlayerData(Player player) {
        return playerDataMap.computeIfAbsent(player.getUniqueId(), k -> new PlayerData(player));
    }
    
    /**
     * 事件处理
     */
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        // 维护模式时跳过检测
        if (maintenanceMode) {
            if (config.getBoolean("settings.debug", false)) {
                event.getPlayer().sendMessage(getMessage("maintenance.bypass"));
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
        
        // 飞行检测
        if (config.getBoolean("settings.flight.enabled", true) && checkFlight(player, from, to)) {
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
            
            if (config.getBoolean("settings.debug", false)) {
                if (event.isGliding()) {
                    player.sendMessage(getMessage("debug.elytra-on"));
                } else {
                    player.sendMessage(getMessage("debug.elytra-off"));
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
        if (maintenanceMode) {
            if (config.getBoolean("settings.debug", false)) {
                event.getPlayer().sendMessage(getMessage("maintenance.bypass"));
            }
            return;
        }
        
        if (!config.getBoolean("settings.clicks.enabled", true)) return;
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
        
        if (config.getBoolean("settings.debug", false)) {
            getLogger().info(getMessage("player.join", 
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
        if (isBanned(event.getPlayer().getName())) {
            String banMessage = getBanInfo(event.getPlayer().getName());
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, banMessage);
        }
    }
    
    /**
     * 检测逻辑
     */
    
    private boolean checkMovementSpeed(Player player, Location from, Location to) {
        Vector vector = to.toVector().subtract(from.toVector());
        
        double horizontal = Math.hypot(vector.getX(), vector.getZ());
        double vertical = Math.abs(vector.getY());
        
        // 获取配置值
        double maxHorizontal = config.getDouble("settings.movement.max-horizontal-speed", 0.35);
        double maxVertical = config.getDouble("settings.movement.max-vertical-speed", 0.45);
        double elytraHorizontal = config.getDouble("settings.elytra.max-horizontal-speed", 2.0);
        double elytraVertical = config.getDouble("settings.elytra.max-vertical-speed", 1.5);
        
        // 考虑服务器延迟和TPS影响
        double tpsFactor = Math.max(0.5, Math.min(1.5, 20.0 / Bukkit.getServer().getTPS()[0]));
        double adjustedHorizontalThreshold = maxHorizontal * tpsFactor;
        double adjustedVerticalThreshold = maxVertical * tpsFactor;
        
        // 鞘翅飞行特殊处理
        if (player.isGliding()) {
            // 使用专用阈值检查鞘翅飞行
            return horizontal > (elytraHorizontal * tpsFactor) || 
                   vertical > (elytraVertical * tpsFactor);
        }
        
        // 考虑玩家状态
        if (player.isSprinting()) {
            adjustedHorizontalThreshold *= 1.3; // 冲刺时增加阈值
        }
        
        if (player.isSneaking()) {
            adjustedHorizontalThreshold *= 0.7; // 潜行时降低阈值
        }
        
        // 普通移动检测
        return horizontal > adjustedHorizontalThreshold || 
               vertical > adjustedVerticalThreshold;
    }

    private boolean checkRotationSpeed(Player player, Location from, Location to) {
        PlayerData data = getPlayerData(player);
        long now = System.currentTimeMillis();
        
        long lastCheck = data.getLastRotationCheck();
        long rotationInterval = config.getLong("settings.rotation.check-interval", 50);
        if (now - lastCheck < rotationInterval) {
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
        float maxAngle = (float) config.getDouble("settings.rotation.max-angle-change", 30.0);
        return yawSpeed > maxAngle || pitchSpeed > maxAngle;
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
        int maxAirTime = config.getInt("settings.flight.max-air-time", 80);
        if (data.getAirTimeCounter() > maxAirTime) {
            if (config.getBoolean("settings.debug", false)) {
                player.sendMessage(getMessage("flight.detected", 
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
        if (config.getBoolean("settings.log-violations", true)) {
            getLogger().warning(getMessage("violation.log", 
                player.getWorld().getName(),
                player.getName(),
                getMessage(reasonKey),
                data.getViolationCount(),
                config.getInt("settings.violations.max-violations", 10)
            ));
        }
        
        // 调试消息
        if (config.getBoolean("settings.debug", false)) {
            player.sendMessage(getMessage("violation.detected", 
                getMessage(reasonKey)));
        }
        
        // 超过阈值踢出
        int maxViolations = config.getInt("settings.violations.max-violations", 10);
        if (data.getViolationCount() >= maxViolations) {
            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(getMessage("kick.message", 
                    data.getViolationCount(), maxViolations));
                data.setViolationCount(0);
                
                // 记录踢出次数并检查封禁
                recordKickAndCheckBan(player);
            });
        }
        
        // 回滚位置
        if (rollback) {
            player.teleport(data.getLastValidLocation());
            if (config.getBoolean("settings.debug", false)) {
                player.sendMessage(getMessage("debug.teleport"));
            }
        }
    }
    
    private void recordKickAndCheckBan(Player player) {
        if (!config.getBoolean("settings.violations.auto-ban.enabled", false)) return;
        
        PlayerData data = getPlayerData(player);
        data.incrementKickCount();
        
        // 记录日志
        getLogger().info(getMessage("player.kicked", 
            player.getName(), data.getKickCount(), 
            config.getInt("settings.violations.auto-ban.kicks-before-ban", 3)));
        
        int kicksBeforeBan = config.getInt("settings.violations.auto-ban.kicks-before-ban", 3);
        if (data.getKickCount() >= kicksBeforeBan) {
            // 封禁玩家
            customBanPlayer(player.getName(), 
                getMessage("ban.reason", String.valueOf(data.getKickCount())), 
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
    
    /**
     * 封禁管理
     */
    
    public boolean isBanned(String playerName) {
        return bannedPlayers.containsKey(playerName.toLowerCase());
    }
    
    public String getBanInfo(String playerName) {
        BanInfo info = bannedPlayers.get(playerName.toLowerCase());
        if (info == null) return "未找到封禁信息";
        
        return getMessage("ban-message",
            "player", playerName,
            "reason", info.getReason(),
            "date", info.getDate(),
            "banned-by", info.getBannedBy()
        );
    }
    
    public void customBanPlayer(String playerName, String reason, String bannedBy) {
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        bannedPlayers.put(playerName.toLowerCase(), new BanInfo(reason, bannedBy, date));
        saveBansConfig();
        
        // 踢出在线玩家
        Player player = Bukkit.getPlayer(playerName);
        if (player != null && player.isOnline()) {
            player.kickPlayer(getBanInfo(playerName));
        }
        
        getLogger().info(getMessage("ban.executed", playerName, reason));
    }
    
    public void unbanPlayer(String playerName, String reason, String unbannedBy) {
        if (!isBanned(playerName)) {
            getLogger().info(getMessage("command.unban.not-banned", playerName));
            return;
        }
        
        bannedPlayers.remove(playerName.toLowerCase());
        saveBansConfig();
        getLogger().info(getMessage("command.unban.executed", playerName, reason));
    }
    
    /**
     * 命令处理
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("traban")) {
            return handleBanCommand(sender, args);
        } else if (cmd.getName().equalsIgnoreCase("traunban")) {
            return handleUnbanCommand(sender, args);
        } else if (cmd.getName().equalsIgnoreCase("trac")) {
            return handleDebugCommand(sender, args);
        }
        return false;
    }
    
    private boolean handleBanCommand(CommandSender sender, String[] args) {
        // 权限检查
        if (!sender.hasPermission("anticheat.traban")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
            return true;
        }
        
        // 参数验证
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /traban <玩家> <理由>");
            return true;
        }
        
        String playerName = args[0];
        
        // 构建理由
        StringBuilder reasonBuilder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            reasonBuilder.append(args[i]).append(" ");
        }
        String reason = reasonBuilder.toString().trim();
        
        // 获取执行者名称
        String bannedBy = sender instanceof Player ? sender.getName() : "控制台";
        
        // 执行封禁
        customBanPlayer(playerName, reason, bannedBy);
        
        sender.sendMessage(ChatColor.GREEN + "已封禁玩家 " + playerName + " | 理由: " + reason);
        getLogger().info("玩家 " + playerName + " 已被 " + bannedBy + " 封禁 | 理由: " + reason);
        
        return true;
    }
    
private boolean handleUnbanCommand(CommandSender sender, String[] args) {
        // 权限检查
        if (!sender.hasPermission("anticheat.traunban")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
            return true;
        }
        
        // 参数验证
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "用法: /traunban <玩家> [理由]");
            return true;
        }
        
        String playerName = args[0];
        
        // 构建解封理由
        String reason = "管理员解封";
        if (args.length > 1) {
            StringBuilder reasonBuilder = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                reasonBuilder.append(args[i]).append(" ");
            }
            reason = reasonBuilder.toString().trim();
        }
        
        // 获取执行者名称
        String unbannedBy = sender instanceof Player ? sender.getName() : "控制台";
        
        // 执行解封
        unbanPlayer(playerName, reason, unbannedBy);
        
        sender.sendMessage(ChatColor.GREEN + "已解封玩家 " + playerName + " | 理由: " + reason);
        getLogger().info("玩家 " + playerName + " 已被 " + unbannedBy + " 解封 | 理由: " + reason);
        
        return true;
    }
    
    private boolean handleDebugCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("anticheat.debug")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令!");
            return true;
        }
        
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "用法: /trac debug <on|off|reload>");
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "on":
                config.set("settings.debug", true);
                saveConfig();
                reloadPluginConfig();
                sender.sendMessage(ChatColor.GREEN + "调试模式已启用!");
                break;
            case "off":
                config.set("settings.debug", false);
                saveConfig();
                reloadPluginConfig();
                sender.sendMessage(ChatColor.GREEN + "调试模式已禁用!");
                break;
            case "reload":
                reloadPluginConfig();
                sender.sendMessage(ChatColor.GREEN + "配置已重载!");
                break;
            default:
                sender.sendMessage(ChatColor.RED + "用法: /trac debug <on|off|reload>");
                break;
        }
        
        return true;
    }
    
    /**
     * 玩家数据类
     */
    private static class PlayerData {
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
    }
    
    /**
     * 封禁信息类
     */
    private static class BanInfo {
        private final String reason;
        private final String bannedBy;
        private final String date;
        
        public BanInfo(String reason, String bannedBy, String date) {
            this.reason = reason;
            this.bannedBy = bannedBy;
            this.date = date;
        }
        
        public String getReason() {
            return reason;
        }
        
        public String getBannedBy() {
            return bannedBy;
        }
        
        public String getDate() {
            return date;
        }
    }
}
