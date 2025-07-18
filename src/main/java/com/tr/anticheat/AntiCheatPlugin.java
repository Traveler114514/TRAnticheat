package com.tr.anticheat;

import org.bukkit.*;
import org.bukkit.BanList.Type;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.bukkit.Tag;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.io.BufferedReader;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public class AntiCheatPlugin extends JavaPlugin implements Listener, CommandExecutor {

    /* ------------------------- 插件版本配置 ------------------------- */
    private static final int PLUGIN_VERSION = 107;
    
    /* ------------------------- 远程服务配置 ------------------------- */
    private static final String VERSION_CHECK_URL = "https://raw.githubusercontent.com/Traveler114514/FileCloud/refs/heads/main/TRAnticheat/version.txt";
    private static final String MAINTENANCE_URL = "https://raw.githubusercontent.com/Traveler114514/FileCloud/refs/heads/main/TRAnticheat/maintenance.txt";
    
    /* ------------------------- 配置参数 ------------------------- */
    private String language;
    private boolean debugMode;
    private Set<String> whitelistedWorlds;
    private Set<UUID> whitelistedPlayers;
    
    // 检测开关
    private boolean movementDetectionEnabled;
    private boolean rotationDetectionEnabled;
    private boolean clicksEnabled;
    private boolean flightDetectionEnabled;
    private boolean elytraDetectionEnabled;
    
    // 移动检测
    private double maxHorizontalSpeed;
    private double maxVerticalSpeed;
    
    // 鞘翅专用阈值
    private double elytraHorizontalThreshold;
    private double elytraVerticalThreshold;
    private double elytraLandingBuffer; // 落地缓冲时间（秒）
    private double elytraBufferMultiplier; // 缓冲期速度乘数
    
    // 视角检测
    private float maxAngleChange;
    private long rotationCheckInterval;
    
    // 点击检测
    private int maxCps;
    private int clicksCheckInterval;
    private int clicksViolationsToKick;
    
    // 飞行检测
    private int maxAirTime;
    private boolean ignoreWater;
    private boolean ignoreClimbables;
    
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
    
    // 维护模式状态
    private volatile boolean maintenanceMode = false;
    private ScheduledExecutorService maintenanceScheduler;

    /* ------------------------- 数据存储 ------------------------- */
    private final ConcurrentHashMap<UUID, Location> lastValidLocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> lastPitch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastRotationCheck = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> violationCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Deque<Long>> clickRecords = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> clickViolations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> airTimeCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> wasOnGround = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> kickCount = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<BanTask> banQueue = new ConcurrentLinkedQueue<>();
    
    // 鞘翅优化数据
    private final ConcurrentHashMap<UUID, Long> elytraEndTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> wasGliding = new ConcurrentHashMap<>();

    /* ------------------------- 插件生命周期 ------------------------- */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        initBanSystem();
        Bukkit.getPluginManager().registerEvents(this, this);
        startCleanupTask();
        startClickCheckTask();
        startBanProcessor();
        startMaintenanceCheck();
        checkVersion();
        getCommand("traban").setExecutor(this);
        getCommand("traunban").setExecutor(this);
        getLogger().info(getMessage("plugin.enabled", getDescription().getVersion()));
    }
    
    @Override
    public void onDisable() {
        stopMaintenanceCheck();
        getLogger().info(getMessage("plugin.disabled"));
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
        
        // 检测开关
        movementDetectionEnabled = config.getBoolean("settings.movement.enabled", true);
        rotationDetectionEnabled = config.getBoolean("settings.rotation.enabled", true);
        clicksEnabled = config.getBoolean("settings.clicks.enabled", true);
        flightDetectionEnabled = config.getBoolean("settings.flight.enabled", true);
        elytraDetectionEnabled = config.getBoolean("settings.elytra.enabled", false);
        
        // 移动检测
        maxHorizontalSpeed = config.getDouble("settings.movement.max-horizontal-speed", 0.35);
        maxVerticalSpeed = config.getDouble("settings.movement.max-vertical-speed", 0.45);
        
        // 鞘翅专用阈值
        elytraHorizontalThreshold = config.getDouble("settings.elytra.max-horizontal-speed", 2.0);
        elytraVerticalThreshold = config.getDouble("settings.elytra.max-vertical-speed", 1.5);
        elytraLandingBuffer = config.getDouble("settings.elytra.landing-buffer", 0.5);
        elytraBufferMultiplier = config.getDouble("settings.elytra.buffer-multiplier", 1.5);
        
        // 视角检测
        maxAngleChange = (float) config.getDouble("settings.rotation.max-angle-change", 30.0);
        rotationCheckInterval = config.getLong("settings.rotation.check-interval", 50);
        
        // 点击检测
        maxCps = config.getInt("settings.clicks.max-cps", 15);
        clicksCheckInterval = config.getInt("settings.clicks.check-interval", 5);
        clicksViolationsToKick = config.getInt("settings.clicks.violations-to-kick", 3);
        
        // 飞行检测
        maxAirTime = config.getInt("settings.flight.max-air-time", 80);
        ignoreWater = config.getBoolean("settings.flight.ignore-water", true);
        ignoreClimbables = config.getBoolean("settings.flight.ignore-climbables", true);
        
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
    
    /* ------------------------- 版本检测功能 ------------------------- */
    private void checkVersion() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                getLogger().info("开始检查插件更新...");
                
                // 从远程文件读取版本号
                String content = readRemoteFile(VERSION_CHECK_URL);
                getLogger().info("远程版本文件内容: " + content);
                
                int remoteVersion = Integer.parseInt(content.trim());
                getLogger().info("解析后的远程版本号: " + remoteVersion);
                
                // 格式化版本号用于显示
                String formattedCurrent = formatVersion(PLUGIN_VERSION);
                String formattedRemote = formatVersion(remoteVersion);
                
                if (remoteVersion > PLUGIN_VERSION) {
                    String availableMsg = getMessage("update.available", formattedCurrent, formattedRemote);
                    String downloadMsg = getMessage("update.download");
                    
                    getLogger().warning(availableMsg);
                    getLogger().warning(downloadMsg);
                    
                    // 通知在线管理员
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.hasPermission("anticheat.admin")) {
                            player.sendMessage(ChatColor.RED + "[反作弊] 发现新版本可用!");
                            player.sendMessage(ChatColor.GOLD + "当前版本: " + formattedCurrent);
                            player.sendMessage(ChatColor.GREEN + "最新版本: " + formattedRemote);
                            player.sendMessage(ChatColor.YELLOW + "请前往下载更新");
                        }
                    }
                } else if (remoteVersion < PLUGIN_VERSION) {
                    String devVersionMsg = getMessage("update.dev-version", formattedCurrent);
                    getLogger().info(devVersionMsg);
                } else {
                    String latestMsg = getMessage("update.latest", formattedCurrent);
                    getLogger().info(latestMsg);
                }
            } catch (NumberFormatException e) {
                getLogger().warning("版本号格式错误: " + e.getMessage());
                getLogger().warning("请确保远程文件只包含数字版本号（如103）");
            } catch (Exception e) {
                String failedMsg = getMessage("update.failed");
                getLogger().log(Level.WARNING, failedMsg, e);
            }
        });
    }
    
    /**
     * 将版本号格式化为 x.x.x 形式
     * @param version 整数版本号 (如 103)
     * @return 格式化后的版本字符串 (如 "1.0.3")
     */
    private String formatVersion(int version) {
        String versionStr = String.valueOf(version);
        
        // 处理版本号长度不足的情况
        while (versionStr.length() < 3) {
            versionStr = "0" + versionStr;
        }
        
        // 确保版本号至少有3位数字
        if (versionStr.length() >= 3) {
            // 插入点号: 1.0.3
            return versionStr.substring(0, versionStr.length() - 2) + "." +
                   versionStr.substring(versionStr.length() - 2, versionStr.length() - 1) + "." +
                   versionStr.substring(versionStr.length() - 1);
        }
        
        // 如果版本号格式异常，返回原始字符串
        return String.valueOf(version);
    }
    
    /* ------------------------- 维护模式功能 ------------------------- */
    private void startMaintenanceCheck() {
        maintenanceScheduler = Executors.newSingleThreadScheduledExecutor();
        maintenanceScheduler.scheduleAtFixedRate(() -> {
            try {
                // 从远程文件读取维护状态
                String content = readRemoteFile(MAINTENANCE_URL);
                boolean newMode = "true".equalsIgnoreCase(content.trim());
                
                // 如果状态变化则更新
                if (newMode != maintenanceMode) {
                    maintenanceMode = newMode;
                    String statusKey = maintenanceMode ? "maintenance.enabled" : "maintenance.disabled";
                    getLogger().info("维护模式状态变化: " + (maintenanceMode ? "启用" : "禁用"));
                    getLogger().info(getMessage("maintenance.status-changed", getMessage(statusKey)));
                    
                    // 通知所有玩家
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.sendMessage(getMessage(statusKey));
                    }
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, getMessage("maintenance.check-failed"), e);
            }
        }, 0, 5, TimeUnit.MINUTES); // 每5分钟检查一次
    }
    
    private void stopMaintenanceCheck() {
        if (maintenanceScheduler != null) {
            maintenanceScheduler.shutdown();
            try {
                if (!maintenanceScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    maintenanceScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                maintenanceScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 读取远程文件内容
     * @param urlString 文件URL
     * @return 文件内容
     * @throws Exception 读取异常
     */
    private String readRemoteFile(String urlString) throws Exception {
        URL url = new URL(urlString);
        StringBuilder content = new StringBuilder();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(url.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        
        return content.toString();
    }
    
    private boolean shouldCheckPlayer(Player player) {
        // 如果处于维护模式，跳过所有检测
        if (maintenanceMode) {
            if (debugMode) {
                player.sendMessage(getMessage("maintenance.bypass"));
            }
            return false;
        }
        
        // 原有的白名单检查
        return !shouldBypassCheck(player);
    }
    
    /* ------------------------- 多语言支持 ------------------------- */
    private void loadLanguageFile() {
        File langFile = new File(getDataFolder(), "messages_" + language + ".yml");
        
        // 如果语言文件不存在，从JAR中复制
        if (!langFile.exists()) {
            saveResource("messages_" + language + ".yml", false);
            getLogger().info("已创建语言文件: " + langFile.getName());
        }
        
        // 加载语言文件
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        
        // 加载默认语言作为后备
        try {
            FileConfiguration defaultLang = YamlConfiguration.loadConfiguration(
                new InputStreamReader(getResource("messages_en.yml"))
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
            
            // 检查关键语言键是否存在
            checkKeyExists("violation.log");
            checkKeyExists("violation.movement");
            checkKeyExists("violation.rotation");
            checkKeyExists("violation.flight");
            checkKeyExists("flight.detected");
        }
    }
    
    private void checkKeyExists(String key) {
        if (messages.containsKey(key)) {
            getLogger().info("找到语言键: " + key + " = " + messages.get(key));
        } else {
            getLogger().warning("缺少语言键: " + key);
            // 添加默认值
            messages.put(key, key);
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
        banConfig.addDefault("ban-message", 
            "&4&l您已被服务器封禁\n" +
            "&r\n" +
            "&f玩家: &7{player}\n" +
            "&f原因: &7{reason}\n" +
            "&f封禁时间: &7{date}\n" +
            "&f执行者: &7{banned-by}\n" +
            "&r\n" +
            "&e此封禁为永久封禁\n" +
            "&r\n" +
            "&6如果您认为这是误封，请通过以下方式申诉:\n" +
            "&b- 网站: https://traveler.dpdns.org\n" +
            "&b- QQ群: 315809417\n" +
            "&b- 邮箱: admin@traveler.dpdns.org\n" +
            "&r\n" +
            "&7请提供您的游戏ID和封禁时间以便我们处理");
        
        banConfig.addDefault("default-reason", "多次检测到作弊行为");
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
     * @param bannedBy 执行者
     */
    private void customBanPlayer(String playerName, String reason, String bannedBy) {
        // 添加到封禁列表
        String path = "bans." + playerName.toLowerCase();
        String banDate = getFormattedDate();
        banConfig.set(path + ".reason", reason);
        banConfig.set(path + ".date", banDate);
        banConfig.set(path + ".banned-by", bannedBy);
        saveBanConfig();
        
        // 添加到Bukkit封禁列表
        Bukkit.getBanList(Type.NAME).addBan(playerName, reason, null, bannedBy);
        
        // 如果玩家在线，立即踢出
        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null && player.isOnline()) {
            String banMessage = generateBanMessage(playerName, reason, banDate, bannedBy);
            
            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(banMessage);
            });
        }
    }
    
    /**
     * 生成封禁消息
     */
    private String generateBanMessage(String playerName, String reason, String date, String bannedBy) {
        String template = banConfig.getString("ban-message", 
            "&4&l您已被服务器封禁\n" +
            "&r\n" +
            "&f玩家: &7{player}\n" +
            "&f原因: &7{reason}\n" +
            "&f封禁时间: &7{date}\n" +
            "&f执行者: &7{banned-by}\n" +
            "&r\n" +
            "&e此封禁为永久封禁\n" +
            "&r\n" +
            "&6如果您认为这是误封，请通过以下方式申诉:\n" +
            "&b- 网站: https://traveler.dpdns.org\n" +
            "&b- QQ群: 315809417\n" +
            "&b- 邮箱: admin@traveler.dpdns.org\n" +
            "&r\n" +
            "&7请提供您的游戏ID和封禁时间以便我们处理");
        
        return ChatColor.translateAlternateColorCodes('&', template
            .replace("{player}", playerName)
            .replace("{reason}", reason)
            .replace("{date}", date)
            .replace("{banned-by}", bannedBy));
    }
    
    /**
     * 检查玩家是否被封禁
     * @param playerName 玩家名
     * @return 是否被封禁
     */
    public boolean isBanned(String playerName) {
        return banConfig.contains("bans." + playerName.toLowerCase()) || 
               Bukkit.getBanList(Type.NAME).isBanned(playerName);
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
        String bannedBy = banConfig.getString(path + ".banned-by", "系统");
        
        return generateBanMessage(playerName, reason, date, bannedBy);
    }
    
    /* ------------------------- 解封功能 ------------------------- */
    /**
     * 解封玩家
     * @param playerName 玩家名
     * @return 是否成功解封
     */
    private boolean unbanPlayer(String playerName) {
        String path = "bans." + playerName.toLowerCase();
        boolean found = false;
        
        // 从自定义封禁系统中移除
        if (banConfig.contains(path)) {
            banConfig.set(path, null);
            found = true;
        }
        
        // 从Bukkit封禁列表中移除
        if (Bukkit.getBanList(Type.NAME).isBanned(playerName)) {
            Bukkit.getBanList(Type.NAME).pardon(playerName);
            found = true;
        }
        
        if (found) {
            saveBanConfig();
            return true;
        }
        
        return false;
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
            
            // 清理鞘翅缓存
            elytraEndTimestamps.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
            wasGliding.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
            
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
            // 维护模式时跳过检测
            if (maintenanceMode) {
                if (debugMode) {
                    getLogger().info("维护模式启用，跳过点击检测");
                }
                return;
            }
            
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
        // 维护模式时跳过检测
        if (maintenanceMode) {
            if (debugMode) {
                event.getPlayer().sendMessage(getMessage("maintenance.bypass"));
            }
            return;
        }
        
        Player player = event.getPlayer();
        if (!shouldCheckPlayer(player)) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        
        // 优化：鞘翅飞行后缓冲期特殊处理
        UUID uuid = player.getUniqueId();
        if (elytraDetectionEnabled && elytraEndTimestamps.containsKey(uuid)) {
            long timeSinceElytra = System.currentTimeMillis() - elytraEndTimestamps.get(uuid);
            
            // 鞘翅结束后缓冲期内，降低检测灵敏度
            if (timeSinceElytra < elytraLandingBuffer * 1000) {
                // 获取最后有效位置
                Location lastValid = lastValidLocations.getOrDefault(uuid, from);
                
                // 计算与最后有效位置的距离
                double distance = to.distance(lastValid);
                
                // 如果距离过大，更新位置但不标记违规
                if (distance > maxHorizontalSpeed * 2) {
                    lastValidLocations.put(uuid, to.clone());
                    if (debugMode) player.sendMessage(getMessage("debug.elytra-buffer"));
                }
                
                // 更新玩家数据后返回，不进行其他检测
                updatePlayerData(player);
                return;
            } else {
                // 超过缓冲期，移除时间戳
                elytraEndTimestamps.remove(uuid);
                if (debugMode) player.sendMessage(getMessage("elytra.buffer"));
            }
        }

        // 正常检测逻辑
        // 移动检测（带开关检查）
        if (movementDetectionEnabled && checkMovementSpeed(player, from, to)) {
            handleViolation(player, "violation.movement", true);
            event.setTo(lastValidLocations.get(player.getUniqueId()));
            return;
        }
        
        // 视角检测（带开关检查）
        if (rotationDetectionEnabled && checkRotationSpeed(player, from, to)) {
            handleViolation(player, "violation.rotation", true);
            to.setYaw(lastYaw.get(player.getUniqueId()));
            to.setPitch(lastPitch.get(player.getUniqueId()));
            event.setTo(to);
        }
        
        // 飞行检测（带开关检查）
        if (flightDetectionEnabled && checkFlight(player, from, to)) {
            handleViolation(player, "violation.flight", true);
            event.setTo(lastValidLocations.get(player.getUniqueId()));
        }
        
        // 更新玩家数据
        updatePlayerData(player);
    }

@EventHandler
    public void onEntityGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            UUID uuid = player.getUniqueId();
            
            if (debugMode) {
                player.sendMessage(event.isGliding() ? getMessage("debug.elytra-on") : getMessage("debug.elytra-off"));
            }
            
            if (event.isGliding()) {
                // 开始滑翔时重置计数器
                airTimeCounters.remove(uuid);
                
                // 更新状态缓存
                wasGliding.put(uuid, true);
            } else {
                // 记录鞘翅结束时间
                elytraEndTimestamps.put(uuid, System.currentTimeMillis());
                
                // 优化：落地瞬间特殊处理
                if (isPlayerOnGround(player)) {
                    // 落地瞬间增加缓冲时间
                    elytraEndTimestamps.put(uuid, System.currentTimeMillis());
                    
                    // 更新最后有效位置，避免误判
                    lastValidLocations.put(uuid, player.getLocation().clone());
                }
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 维护模式时跳过检测
        if (maintenanceMode) {
            if (debugMode) {
                event.getPlayer().sendMessage(getMessage("maintenance.bypass"));
            }
            return;
        }
        
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
        
        // 初始化飞行检测数据
        airTimeCounters.put(uuid, 0);
        wasOnGround.put(uuid, isPlayerOnGround(player));
        
        // 初始化踢出计数
        kickCount.putIfAbsent(uuid, 0);
        
        // 初始化鞘翅缓存
        wasGliding.put(uuid, false);
        
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
        airTimeCounters.remove(uuid);
        wasOnGround.remove(uuid);
        
        // 清理鞘翅缓存
        elytraEndTimestamps.remove(uuid);
        wasGliding.remove(uuid);
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        // 检查玩家是否被封禁
        if (isBanned(event.getPlayer().getName())) {
            String playerName = event.getPlayer().getName();
            String path = "bans." + playerName.toLowerCase();
            
            String reason = banConfig.getString(path + ".reason", 
                banConfig.getString("default-reason", "多次检测到作弊行为"));
            
            String date = banConfig.getString(path + ".date", getFormattedDate());
            
            String bannedBy = banConfig.getString(path + ".banned-by", "系统");
            
            String banMessage = generateBanMessage(playerName, reason, date, bannedBy);
            
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, banMessage);
        }
    }

    /* ------------------------- 检测逻辑 ------------------------- */
    private boolean checkMovementSpeed(Player player, Location from, Location to) {
        Vector vector = to.toVector().subtract(from.toVector());
        
        double horizontal = Math.hypot(vector.getX(), vector.getZ());
        double vertical = Math.abs(vector.getY());
        
        // 鞘翅飞行特殊处理
        if (player.isGliding()) {
            // 使用专用阈值检查鞘翅飞行
            return horizontal > elytraHorizontalThreshold || vertical > elytraVerticalThreshold;
        }
        
        // 鞘翅飞行后缓冲期特殊处理
        UUID uuid = player.getUniqueId();
        if (elytraDetectionEnabled && elytraEndTimestamps.containsKey(uuid)) {
            long timeSinceElytra = System.currentTimeMillis() - elytraEndTimestamps.get(uuid);
            
            // 缓冲期内使用放宽的阈值
            if (timeSinceElytra < elytraLandingBuffer * 1000) {
                return horizontal > maxHorizontalSpeed * elytraBufferMultiplier ||
                       vertical > maxVerticalSpeed * elytraBufferMultiplier;
            }
        }
        
        // 普通移动检测
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
    
    /* ------------------------- 飞行检测逻辑 ------------------------- */
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
        
        // 修复：跳过在水中或爬梯子/藤蔓的玩家
        if (ignoreWater || ignoreClimbables) {
            Location loc = player.getLocation();
            Block feetBlock = loc.getBlock();
            Block belowFeet = feetBlock.getRelative(BlockFace.DOWN);
            
            // 检查玩家是否在水中（包括流动的水）
            if (ignoreWater && (feetBlock.isLiquid() || belowFeet.isLiquid())) {
                return false;
            }
            
            // 检查玩家是否在梯子、藤蔓或其他可攀爬的方块上
            if (ignoreClimbables && (isClimbableBlock(feetBlock) || isClimbableBlock(belowFeet))) {
                return false;
            }
        }
        
        // 获取玩家UUID
        UUID uuid = player.getUniqueId();
        
        // 检查玩家是否在地面上
        boolean isOnGround = isPlayerOnGround(player);
        
        // 获取之前的地面状态
        boolean wasOnGround = this.wasOnGround.getOrDefault(uuid, false);
        this.wasOnGround.put(uuid, isOnGround);
        
        // 如果玩家在地面上，重置计数器
        if (isOnGround) {
            airTimeCounters.remove(uuid);
            return false;
        }
        
        // 如果玩家刚从地面跳起，初始化计数器
        if (wasOnGround && !isOnGround) {
            airTimeCounters.put(uuid, 1);
            return false;
        }
        
        // 增加空中时间计数
        int airTime = airTimeCounters.getOrDefault(uuid, 0) + 1;
        airTimeCounters.put(uuid, airTime);
        
        // 检查是否超过最大空中时间
        if (airTime > maxAirTime) {
            if (debugMode) {
                player.sendMessage(getMessage("flight.detected", airTime, maxAirTime));
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查方块是否可攀爬（梯子、藤蔓等）
     */
    private boolean isClimbableBlock(Block block) {
        Material type = block.getType();
        
        // 使用Bukkit的Tag系统检查可攀爬方块（1.13+）
        if (Tag.CLIMBABLE.isTagged(type)) {
            return true;
        }
        
        // 手动检查常见可攀爬方块（兼容旧版本）
        return type == Material.LADDER || 
               type == Material.VINE || 
               type == Material.SCAFFOLDING || 
               type == Material.WEEPING_VINES ||
               type == Material.WEEPING_VINES_PLANT ||
               type == Material.TWISTING_VINES ||
               type == Material.TWISTING_VINES_PLANT;
    }

    /**
     * 更精确的玩家地面检测
     */
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

    /* ------------------------- 违规处理 ------------------------- */
    private void handleViolation(Player player, String reasonKey, boolean rollback) {
        UUID uuid = player.getUniqueId();
        
        // 增加违规计数
        int count = violationCount.merge(uuid, 1, Integer::sum);
        
        // 记录日志 - 使用正确的语言键
        if (getConfig().getBoolean("settings.log-violations", true)) {
            // 获取违规原因的消息
            String reasonMsg = getMessage(reasonKey);
            
            // 使用正确的语言键和参数
            getLogger().warning(getMessage("violation.log", 
                player.getWorld().getName(),
                player.getName(),
                reasonMsg,
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
                player.kickPlayer(getMessage("kick.message", count, maxViolations, getMessage(reasonKey)));
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
            customBanPlayer(player.getName(), reason, "AntiCheat系统");
            
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
    
    /**
     * 设置维护模式状态
     */
    public void setMaintenanceMode(boolean maintenance) {
        this.maintenanceMode = maintenance;
    }
    
    /**
     * 获取维护模式状态
     */
    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }
    
    /**
     * 强制检查版本更新
     */
    public void forceVersionCheck() {
        checkVersion();
    }
    
    /**
     * 获取当前插件版本
     */
    public int getPluginVersion() {
        return PLUGIN_VERSION;
    }
    
    /**
     * 获取格式化版本号
     */
    public String getFormattedPluginVersion() {
        return formatVersion(PLUGIN_VERSION);
    }
    
    /* ------------------------- 命令处理器 ------------------------- */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("traban")) {
            // 封禁命令处理
            return handleBanCommand(sender, args);
        } 
        else if (cmd.getName().equalsIgnoreCase("traunban")) {
            // 解封命令处理
            return handleUnbanCommand(sender, args);
        }
        return false;
    }
    
    private boolean handleUnbanCommand(CommandSender sender, String[] args) {
        // 权限检查
        if (!sender.hasPermission("anticheat.traunban")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
            return true;
        }
        
        // 参数验证
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "用法: /traunban <玩家>");
            return true;
        }
        
        String playerName = args[0];
        
        // 尝试解封
        if (unbanPlayer(playerName)) {
            sender.sendMessage(ChatColor.GREEN + "已解封玩家 " + playerName);
            getLogger().info("玩家 " + playerName + " 已被解封，操作者: " + 
                (sender instanceof Player ? sender.getName() : "控制台"));
        } else {
            sender.sendMessage(ChatColor.RED + "玩家 " + playerName + " 未被封禁或不存在");
        }
        
        return true;
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
        
        // 踢出在线玩家
        Player targetPlayer = Bukkit.getPlayer(playerName);
        if (targetPlayer != null && targetPlayer.isOnline()) {
            String banMessage = generateBanMessage(playerName, reason, getFormattedDate(), bannedBy);
            targetPlayer.kickPlayer(banMessage);
        }
        
        sender.sendMessage(ChatColor.GREEN + "已封禁玩家 " + playerName + " | 理由: " + reason);
        getLogger().info("玩家 " + playerName + " 已被 " + bannedBy + " 封禁 | 理由: " + reason);
        
        return true;
    }
}
