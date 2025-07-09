package com.tr.anticheat;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public class AntiCheatPlugin extends JavaPlugin implements Listener {

    /* ------------------------- 配置参数 ------------------------- */
    private String language;
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
    
    // 通用违规
    private int maxViolations;
    
    // 自动封禁
    private boolean autoBanEnabled;
    private int kicksBeforeBan;
    
    // 语言配置
    private FileConfiguration langConfig;
    private final Map<String, String> messages = new ConcurrentHashMap<>();
    
    // 自定义封禁存储
    private File banFile;
    private FileConfiguration banConfig;

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
    
    // 踢出次数记录
    private final ConcurrentHashMap<UUID, Integer> kickCount = new ConcurrentHashMap<>();
    
    // 待封禁玩家队列
    private final ConcurrentLinkedQueue<BanTask> banQueue = new ConcurrentLinkedQueue<>();

    /* ------------------------- 插件生命周期 ------------------------- */
    @Override
    public void onEnable() {
        // 1. 初始化配置
        saveDefaultConfig();
        reloadConfig();
        
        // 2. 初始化封禁系统
        initBanSystem();
        
        // 3. 注册事件
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // 4. 启动定时任务
        startCleanupTask();
        startClickCheckTask();
        startBanProcessor();
        
        getLogger().info(getMessage("plugin.enabled", getDescription().getVersion()));
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        FileConfiguration config = getConfig();
        
        // 加载语言设置
        language = config.getString("language", "en");
        loadLanguageFile();
        
        // 加载通用设置
        debugMode = config.getBoolean("settings.debug", false);
        maxViolations = config.getInt("settings.violations.max-violations", 10);
        
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
        
        // 自动封禁
        autoBanEnabled = config.getBoolean("settings.violations.auto-ban.enabled", false);
        kicksBeforeBan = config.getInt("settings.violations.auto-ban.kicks-before-ban", 3);
        
        // 白名单
        whitelistedWorlds = ConcurrentHashMap.newKeySet();
        whitelistedWorlds.addAll(config.getStringList("whitelist.worlds"));
        
        whitelistedPlayers = ConcurrentHashMap.newKeySet();
        config.getStringList("whitelist.players").forEach(uuidStr -> {
            try {
                whitelistedPlayers.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                getLogger().warning(getMessage("error.invalid-uuid", uuidStr));
            }
        });
    }
    
    /* ------------------------- 多语言支持 ------------------------- */
    private void loadLanguageFile() {
        File langFile = new File(getDataFolder(), "lang/messages_" + language + ".yml");
        
        // 如果语言文件不存在，从JAR中复制
        if (!langFile.exists()) {
            saveResource("lang/messages_" + language + ".yml", false);
        }
        
        // 加载语言文件
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        
        // 加载默认语言作为后备
        try {
            FileConfiguration defaultLang = YamlConfiguration.loadConfiguration(
                new InputStreamReader(getResource("lang/messages_en.yml"))
            );
            langConfig.setDefaults(defaultLang);
        } catch (Exception e) {
            getLogger().warning(getMessage("error.language-missing", "en"));
        }
        
        // 预加载所有消息到内存
        messages.clear();
        for (String key : langConfig.getKeys(true)) {
            if (langConfig.isString(key)) {
                messages.put(key, langConfig.getString(key));
            }
        }
        
        if (debugMode) {
            getLogger().info(getMessage("language.loaded", language, messages.size()));
        }
    }
    
    /**
     * 获取本地化消息
     * @param key 消息键
     * @param args 替换参数
     * @return 本地化后的消息
     */
    public String getMessage(String key, Object... args) {
        String message = messages.getOrDefault(key, key);
        
        // 替换占位符
        for (int i = 0; i < args.length; i++) {
            message = message.replace("{" + i + "}", String.valueOf(args[i]));
        }
        
        return ChatColor.translateAlternateColorCodes('&', message);
    }
    
    /* ------------------------- 自定义封禁系统 ------------------------- */
    private void initBanSystem() {
        banFile = new File(getDataFolder(), "bans.yml");
        if (!banFile.exists()) {
            saveResource("bans.yml", false);
        }
        
        banConfig = YamlConfiguration.loadConfiguration(banFile);
        
        // 创建默认封禁配置
        banConfig.addDefault("ban-message", getMessage("ban.info", "Unknown reason", "System", "Unknown date"));
        banConfig.addDefault("default-reason", getMessage("ban.reason", "multiple"));
        banConfig.options().copyDefaults(true);
        saveBanConfig();
    }
    
    private void saveBanConfig() {
        try {
            banConfig.save(banFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, getMessage("error.ban-save"), e);
        }
    }
    
    /**
     * 获取格式化日期
     */
    private String getFormattedDate() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
    
    /**
     * 自定义封禁玩家
     * @param playerName 玩家名
     * @param reason 封禁原因
     */
    private void customBanPlayer(String playerName, String reason) {
        // 添加到封禁列表
        String path = "bans." + playerName.toLowerCase();
        String banDate = getFormattedDate();
        banConfig.set(path + ".reason", reason);
        banConfig.set(path + ".date", banDate);
        banConfig.set(path + ".banned-by", "AntiCheat");
        saveBanConfig();
        
        // 如果玩家在线，立即踢出
        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null && player.isOnline()) {
            String banMessage = generateBanMessage(playerName, reason, banDate);
            
            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(banMessage);
            });
        }
    }
    
    /**
     * 生成封禁消息
     */
    private String generateBanMessage(String playerName, String reason, String date) {
        String template = banConfig.getString("ban-message", 
            getMessage("ban.info", "Unknown reason", "System", "Unknown date"));
        
        return ChatColor.translateAlternateColorCodes('&', template
            .replace("{player}", playerName)
            .replace("{reason}", reason)
            .replace("{date}", date)
            .replace("{banned-by}", "AntiCheat"));
    }
    
    /**
     * 检查玩家是否被封禁
     * @param playerName 玩家名
     * @return 是否被封禁
     */
    public boolean isBanned(String playerName) {
        return banConfig.contains("bans." + playerName.toLowerCase());
    }
    
    /**
     * 获取封禁信息
     * @param playerName 玩家名
     * @return 封禁信息
     */
    public String getBanInfo(String playerName) {
        String path = "bans." + playerName.toLowerCase();
        if (!banConfig.contains(path)) {
            return getMessage("command.not-banned", playerName);
        }
        
        String reason = banConfig.getString(path + ".reason", banConfig.getString("default-reason"));
        String date = banConfig.getString(path + ".date", "Unknown date");
        
        return generateBanMessage(playerName, reason, date);
    }

    /* ------------------------- 定时任务 ------------------------- */
    private void startCleanupTask() {
        // 每10分钟清理一次离线玩家数据
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            int before = violationCount.size();
            violationCount.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
            
            // 清理踢出记录
            int beforeKicks = kickCount.size();
            kickCount.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
            
            if (debugMode && (before != violationCount.size() || beforeKicks != kickCount.size())) {
                getLogger().info(getMessage("cleanup.removed", 
                    (before - violationCount.size()), 
                    (beforeKicks - kickCount.size())));
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
                
                // 确保队列不为空再执行清理
                if (!clicks.isEmpty()) {
                    // 使用迭代器安全地移除过期记录
                    Iterator<Long> iterator = clicks.iterator();
                    while (iterator.hasNext()) {
                        if (now - iterator.next() > 1000) {
                            iterator.remove();
                        } else {
                            // 由于队列是按时间排序的，遇到未过期的即可停止
                            break;
                        }
                    }
                }
                
                // 计算CPS
                double cps = clicks.size();
                if (cps > maxCps) { // 使用严格大于
                    handleClickViolation(player, cps);
                } else if (clickViolations.getOrDefault(uuid, 0) > 0) {
                    // 正常点击时减少违规计数
                    clickViolations.put(uuid, Math.max(0, clickViolations.get(uuid) - 1));
                }
                
                // 调试信息
                if (debugMode && cps > maxCps / 2) {
                    player.sendMessage(getMessage("debug.cps", cps));
                }
            }
        }, 0, clicksCheckInterval * 20L);
    }
    
    // 封禁处理器
    private void startBanProcessor() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            while (!banQueue.isEmpty()) {
                BanTask task = banQueue.poll();
                if (task != null) {
                    // 确保玩家已离线
                    if (Bukkit.getPlayer(task.playerName) != null) {
                        // 玩家还在线，重新加入队列稍后处理
                        banQueue.add(task);
                        continue;
                    }
                    
                    // 执行封禁命令
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), task.banCommand);
                    
                    if (debugMode) {
                        getLogger().info(getMessage("ban.executed", task.playerName, task.banCommand));
                    }
                }
            }
        }, 20, 20); // 每秒检查一次
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
            handleViolation(player, "violation.movement", true);
            event.setTo(lastValidLocations.get(player.getUniqueId()));
            return;
        }
        
        // 视角检测
        if (checkRotationSpeed(player, from, to)) {
            handleViolation(player, "violation.rotation", true);
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
        
        // 初始化踢出计数
        kickCount.putIfAbsent(uuid, 0);
        
        if (debugMode) {
            int kicks = kickCount.get(uuid);
            if (kicks > 0) {
                getLogger().info(getMessage("player.join", player.getName(), kicks));
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        
        // 清理玩家数据 (但保留踢出计数)
        lastValidLocations.remove(uuid);
        lastYaw.remove(uuid);
        lastPitch.remove(uuid);
        lastRotationCheck.remove(uuid);
        violationCount.remove(uuid);
        clickRecords.remove(uuid);
        clickViolations.remove(uuid);
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        // 检查玩家是否被封禁
        if (isBanned(event.getPlayer().getName())) {
            String playerName = event.getPlayer().getName();
            String path = "bans." + playerName.toLowerCase();
            
            String reason = banConfig.getString(path + ".reason", 
                banConfig.getString("default-reason", getMessage("ban.reason", "multiple")));
            
            String date = banConfig.getString(path + ".date", getFormattedDate());
            
            String banMessage = generateBanMessage(playerName, reason, date);
            
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, banMessage);
        }
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
    private void handleViolation(Player player, String reasonKey, boolean rollback) {
        UUID uuid = player.getUniqueId();
        
        // 增加违规计数
        int count = violationCount.merge(uuid, 1, Integer::sum);
        
        // 记录日志
        if (getConfig().getBoolean("settings.log-violations", true)) {
            getLogger().warning(getMessage("violation.log", 
                player.getWorld().getName(),
                player.getName(),
                getMessage(reasonKey),
                count,
                maxViolations
            ));
        }
        
        // 调试消息
        if (debugMode) {
            player.sendMessage(getMessage("violation.detected", getMessage(reasonKey)));
        }
        
        // 超过阈值踢出
        if (count >= maxViolations) {
            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(getMessage("kick.message", count, maxViolations));
                violationCount.remove(uuid);
                
                // 记录踢出次数并检查封禁
                recordKickAndCheckBan(player);
            });
        }
        
        // 回滚位置
        if (rollback && lastValidLocations.containsKey(uuid)) {
            player.teleport(lastValidLocations.get(uuid));
            if (debugMode) {
                player.sendMessage(getMessage("debug.teleport"));
            }
        }
    }

    private void handleClickViolation(Player player, double cps) {
        UUID uuid = player.getUniqueId();
        int violations = clickViolations.merge(uuid, 1, Integer::sum);
        
        // 日志记录
        if (getConfig().getBoolean("settings.log-violations", true)) {
            getLogger().warning(getMessage("clicks.violation", 
                player.getName(),
                cps,
                violations,
                clicksViolationsToKick
            ));
        }
        
        // 超过阈值踢出
        if (violations >= clicksViolationsToKick) {
            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(getMessage("clicks.kick", cps));
                clickViolations.remove(uuid);
                
                // 记录踢出次数并检查封禁
                recordKickAndCheckBan(player);
            });
        }
    }
    
    // 记录踢出次数并执行封禁
    private void recordKickAndCheckBan(Player player) {
        if (!autoBanEnabled) return;
        
        UUID uuid = player.getUniqueId();
        int kicks = kickCount.merge(uuid, 1, Integer::sum);
        
        // 记录日志
        getLogger().info(getMessage("player.kicked", player.getName(), kicks, kicksBeforeBan));
        
        if (kicks >= kicksBeforeBan) {
            // 使用自定义封禁
            String reason = getMessage("ban.reason", String.valueOf(kicks));
            customBanPlayer(player.getName(), reason);
            
            // 移除记录
            kickCount.remove(uuid);
            
            // 记录到日志
            getLogger().info(getMessage("ban.executed", player.getName(), reason));
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
    
    // 内部类: 封禁任务
    private static class BanTask {
        final String playerName;
        final String banCommand;
        
        BanTask(String playerName, String banCommand) {
            this.playerName = playerName;
            this.banCommand = banCommand;
        }
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
    
    /**
     * 重新加载语言文件
     */
    public void reloadLanguage() {
        loadLanguageFile();
        getLogger().info(getMessage("language.reloaded", language));
    }
}
