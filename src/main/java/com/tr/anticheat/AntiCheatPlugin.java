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
    private static final int PLUGIN_VERSION = 105;
    
    /* ------------------------- 远程服务配置 ------------------------- */
    private static final String VERSION_CHECK_URL = "https://raw.githubusercontent.com/Traveler114514/FileCloud/refs/heads/main/TRAnticheat/version.txt";
    private static final String MAINTENANCE_URL = "https://raw.githubusercontent.com/Traveler114514/FileCloud/refs/heads/main/TRAnticheat/maintenance.txt";
    
    /* ------------------------- 配置参数 ------------------------- */
    private String language;
    private boolean debugMode;
    private Set<String> whitelistedWorlds;
    private Set<UUID> whitelistedPlayers;
    
    // 移动检测
    private double maxHorizontalSpeed;
    private double maxVerticalSpeed;
    
    // 鞘翅专用阈值
    private double elytraHorizontalThreshold;
    private double elytraVerticalThreshold;
    
    // 视角检测
    private float maxAngleChange;
    private long rotationCheckInterval;
    
    // 点击检测
    private boolean clicksEnabled;
    private int maxCps;
    private int clicksCheckInterval;
    private int clicksViolationsToKick;
    
    // 飞行检测
    private boolean flightDetectionEnabled = true;
    private int maxAirTime = 80;
    
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
        
        language = config.getString("language", "en");
        loadLanguageFile();
        debugMode = config.getBoolean("settings.debug", false);
        maxViolations = config.getInt("settings.violations.max-violations", 10);
        maxHorizontalSpeed = config.getDouble("settings.movement.max-horizontal-speed", 0.35);
        maxVerticalSpeed = config.getDouble("settings.movement.max-vertical-speed", 0.45);
        elytraHorizontalThreshold = config.getDouble("settings.elytra.max-horizontal-speed", 2.0);
        elytraVerticalThreshold = config.getDouble("settings.elytra.max-vertical-speed", 1.5);
        maxAngleChange = (float) config.getDouble("settings.rotation.max-angle-change", 30.0);
        rotationCheckInterval = config.getLong("settings.rotation.check-interval", 50);
        clicksEnabled = config.getBoolean("settings.clicks.enabled", true);
        maxCps = config.getInt("settings.clicks.max-cps", 15);
        clicksCheckInterval = config.getInt("settings.clicks.check-interval", 5);
        clicksViolationsToKick = config.getInt("settings.clicks.violations-to-kick", 3);
        flightDetectionEnabled = config.getBoolean("settings.flight.enabled", true);
        maxAirTime = config.getInt("settings.flight.max-air-time", 80);
        autoBanEnabled = config.getBoolean("settings.violations.auto-ban.enabled", false);
        kicksBeforeBan = config.getInt("settings.violations.auto-ban.kicks-before-ban", 3);
        
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
                String content = readRemoteFile(VERSION_CHECK_URL);
                getLogger().info("远程版本文件内容: " + content);
                int remoteVersion = Integer.parseInt(content.trim());
                getLogger().info("解析后的远程版本号: " + remoteVersion);
                String formattedCurrent = formatVersion(PLUGIN_VERSION);
                String formattedRemote = formatVersion(remoteVersion);
                
                if (remoteVersion > PLUGIN_VERSION) {
                    String availableMsg = getMessage("update.available", formattedCurrent, formattedRemote);
                    String downloadMsg = getMessage("update.download");
                    getLogger().warning(availableMsg);
                    getLogger().warning(downloadMsg);
                    
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
    
    private String formatVersion(int version) {
        String versionStr = String.valueOf(version);
        while (versionStr.length() < 3) {
            versionStr = "0" + versionStr;
        }
        if (versionStr.length() >= 3) {
            return versionStr.substring(0, versionStr.length() - 2) + "." +
                   versionStr.substring(versionStr.length() - 2, versionStr.length() - 1) + "." +
                   versionStr.substring(versionStr.length() - 1);
        }
        return String.valueOf(version);
    }
    
    /* ------------------------- 维护模式功能 ------------------------- */
    private void startMaintenanceCheck() {
        maintenanceScheduler = Executors.newSingleThreadScheduledExecutor();
        maintenanceScheduler.scheduleAtFixedRate(() -> {
            try {
                String content = readRemoteFile(MAINTENANCE_URL);
                boolean newMode = "true".equalsIgnoreCase(content.trim());
                if (newMode != maintenanceMode) {
                    maintenanceMode = newMode;
                    String statusKey = maintenanceMode ? "maintenance.enabled" : "maintenance.disabled";
                    getLogger().info("维护模式状态变化: " + (maintenanceMode ? "启用" : "禁用"));
                    getLogger().info(getMessage("maintenance.status-changed", getMessage(statusKey)));
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.sendMessage(getMessage(statusKey));
                    }
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, getMessage("maintenance.check-failed"), e);
            }
        }, 0, 5, TimeUnit.MINUTES);
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
    
    private String readRemoteFile(String urlString) throws Exception {
        URL url = new URL(urlString);
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        return content.toString();
    }
    
    private boolean shouldCheckPlayer(Player player) {
        if (maintenanceMode) {
            if (debugMode) player.sendMessage(getMessage("maintenance.bypass"));
            return false;
        }
        return !shouldBypassCheck(player);
    }
    
    /* ------------------------- 多语言支持 ------------------------- */
    private void loadLanguageFile() {
        File langFile = new File(getDataFolder(), "messages_" + language + ".yml");
        if (!langFile.exists()) saveResource("messages_" + language + ".yml", false);
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        try {
            FileConfiguration defaultLang = YamlConfiguration.loadConfiguration(
                new InputStreamReader(getResource("messages_en.yml"))
            );
            langConfig.setDefaults(defaultLang);
        } catch (Exception e) {
            getLogger().warning(getMessage("error.language-missing", "en"));
        }
        messages.clear();
        for (String key : langConfig.getKeys(true)) {
            if (langConfig.isString(key)) messages.put(key, langConfig.getString(key));
        }
        if (debugMode) {
            getLogger().info(getMessage("language.loaded", language, messages.size()));
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
            messages.put(key, key);
        }
    }
    
    public String getMessage(String key, Object... args) {
        String message = messages.getOrDefault(key, key);
        for (int i = 0; i < args.length; i++) {
            message = message.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }
    
    /* ------------------------- 自定义封禁系统 ------------------------- */
    private void initBanSystem() {
        banFile = new File(getDataFolder(), "bans.yml");
        if (!banFile.exists()) saveResource("bans.yml", false);
        banConfig = YamlConfiguration.loadConfiguration(banFile);
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
    
    private String getFormattedDate() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
    
    private void customBanPlayer(String playerName, String reason, String bannedBy) {
        String path = "bans." + playerName.toLowerCase();
        String banDate = getFormattedDate();
        banConfig.set(path + ".reason", reason);
        banConfig.set(path + ".date", banDate);
        banConfig.set(path + ".banned-by", bannedBy);
        saveBanConfig();
        Bukkit.getBanList(Type.NAME).addBan(playerName, reason, null, bannedBy);
        Player player = Bukkit.getPlayerExact(playerName);
        if (player != null && player.isOnline()) {
            String banMessage = generateBanMessage(playerName, reason, banDate, bannedBy);
            Bukkit.getScheduler().runTask(this, () -> player.kickPlayer(banMessage));
        }
    }
    
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
    
    public boolean isBanned(String playerName) {
        return banConfig.contains("bans." + playerName.toLowerCase()) || 
               Bukkit.getBanList(Type.NAME).isBanned(playerName);
    }
    
    public String getBanInfo(String playerName) {
        String path = "bans." + playerName.toLowerCase();
        if (!banConfig.contains(path)) return getMessage("command.not-banned", playerName);
        String reason = banConfig.getString(path + ".reason", banConfig.getString("default-reason"));
        String date = banConfig.getString(path + ".date", "Unknown date");
        String bannedBy = banConfig.getString(path + ".banned-by", "系统");
        return generateBanMessage(playerName, reason, date, bannedBy);
    }
    
    /* ------------------------- 解封功能 ------------------------- */
    private boolean unbanPlayer(String playerName) {
        String path = "bans." + playerName.toLowerCase();
        boolean found = false;
        if (banConfig.contains(path)) {
            banConfig.set(path, null);
            found = true;
        }
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
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            int before = violationCount.size();
            violationCount.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
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
            if (maintenanceMode) {
                if (debugMode) getLogger().info("维护模式启用，跳过点击检测");
                return;
            }
            long now = System.currentTimeMillis();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (shouldBypassCheck(player)) continue;
                UUID uuid = player.getUniqueId();
                Deque<Long> clicks = clickRecords.getOrDefault(uuid, new ConcurrentLinkedDeque<>());
                if (!clicks.isEmpty()) {
                    Iterator<Long> iterator = clicks.iterator();
                    while (iterator.hasNext()) {
                        if (now - iterator.next() > 1000) iterator.remove();
                        else break;
                    }
                }
                double cps = clicks.size();
                if (cps > maxCps) {
                    handleClickViolation(player, cps);
                } else if (clickViolations.getOrDefault(uuid, 0) > 0) {
                    clickViolations.put(uuid, Math.max(0, clickViolations.get(uuid) - 1));
                }
                if (debugMode && cps > maxCps / 2) {
                    player.sendMessage(getMessage("debug.cps", cps));
                }
            }
        }, 0, clicksCheckInterval * 20L);
    }
    
    private void startBanProcessor() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            while (!banQueue.isEmpty()) {
                BanTask task = banQueue.poll();
                if (task != null) {
                    if (Bukkit.getPlayer(task.playerName) != null) {
                        banQueue.add(task);
                        continue;
                    }
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), task.banCommand);
                    if (debugMode) getLogger().info(getMessage("ban.executed", task.playerName, task.banCommand));
                }
            }
        }, 20, 20);
    }

    /* ------------------------- 事件处理器 ------------------------- */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (maintenanceMode) {
            if (debugMode) event.getPlayer().sendMessage(getMessage("maintenance.bypass"));
            return;
        }
        Player player = event.getPlayer();
        if (!shouldCheckPlayer(player)) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (checkMovementSpeed(player, from, to)) {
            handleViolation(player, "violation.movement", true);
            event.setTo(lastValidLocations.get(player.getUniqueId()));
            return;
        }
        if (checkRotationSpeed(player, from, to)) {
            handleViolation(player, "violation.rotation", true);
            to.setYaw(lastYaw.get(player.getUniqueId()));
            to.setPitch(lastPitch.get(player.getUniqueId()));
            event.setTo(to);
        }
        if (flightDetectionEnabled && checkFlight(player, from, to)) {
            handleViolation(player, "violation.flight", true);
            event.setTo(lastValidLocations.get(player.getUniqueId()));
        }
        updatePlayerData(player);
    }

    @EventHandler
    public void onEntityGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (debugMode) {
                player.sendMessage(event.isGliding() ? "§a鞘翅飞行已启动" : "§c鞘翅飞行已停止");
            }
            if (!event.isGliding()) airTimeCounters.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (maintenanceMode) {
            if (debugMode) event.getPlayer().sendMessage(getMessage("maintenance.bypass"));
            return;
        }
        if (!clicksEnabled) return;
        Player player = event.getPlayer();
        if (shouldBypassCheck(player)) return;
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            clickRecords.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentLinkedDeque<>())
                       .add(System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        lastValidLocations.put(uuid, player.getLocation().clone());
        lastYaw.put(uuid, player.getLocation().getYaw());
        lastPitch.put(uuid, player.getLocation().getPitch());
        lastRotationCheck.put(uuid, System.currentTimeMillis());
        clickRecords.put(uuid, new ConcurrentLinkedDeque<>());
        clickViolations.put(uuid, 0);
        airTimeCounters.put(uuid, 0);
        wasOnGround.put(uuid, isPlayerOnGround(player));
        kickCount.putIfAbsent(uuid, 0);
        if (debugMode) {
            int kicks = kickCount.get(uuid);
            if (kicks > 0) getLogger().info(getMessage("player.join", player.getName(), kicks));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastValidLocations.remove(uuid);
        lastYaw.remove(uuid);
        lastPitch.remove(uuid);
        lastRotationCheck.remove(uuid);
        violationCount.remove(uuid);
        clickRecords.remove(uuid);
        clickViolations.remove(uuid);
        airTimeCounters.remove(uuid);
        wasOnGround.remove(uuid);
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (isBanned(event.getPlayer().getName())) {
            String playerName = event.getPlayer().getName();
            String path = "bans." + playerName.toLowerCase();
            String reason = banConfig.getString(path + ".reason", banConfig.getString("default-reason"));
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
        if (player.isGliding()) {
            return horizontal > elytraHorizontalThreshold || vertical > elytraVerticalThreshold;
        }
        return horizontal > maxHorizontalSpeed || vertical > maxVerticalSpeed;
    }

    private boolean checkRotationSpeed(Player player, Location from, Location to) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastCheck = lastRotationCheck.get(uuid);
        if (lastCheck == null || now - lastCheck < rotationCheckInterval) return false;
        float deltaYaw = Math.abs(to.getYaw() - from.getYaw());
        float deltaPitch = Math.abs(to.getPitch() - from.getPitch());
        if (deltaYaw > 180) deltaYaw = 360 - deltaYaw;
        if (deltaPitch > 180) deltaPitch = 360 - deltaPitch;
        float timeDelta = (now - lastCheck) / 1000f;
        float yawSpeed = deltaYaw / timeDelta;
        float pitchSpeed = deltaPitch / timeDelta;
        lastRotationCheck.put(uuid, now);
        return yawSpeed > maxAngleChange || pitchSpeed > maxAngleChange;
    }
    
    private boolean checkFlight(Player player, Location from, Location to) {
        if (player.isGliding()) return false;
        if (player.getGameMode() == GameMode.CREATIVE || 
            player.getGameMode() == GameMode.SPECTATOR ||
            player.getAllowFlight()) return false;
        UUID uuid = player.getUniqueId();
        boolean isOnGround = isPlayerOnGround(player);
        boolean wasOnGround = this.wasOnGround.getOrDefault(uuid, false);
        this.wasOnGround.put(uuid, isOnGround);
        if (isOnGround) {
            airTimeCounters.remove(uuid);
            return false;
        }
        if (wasOnGround && !isOnGround) {
            airTimeCounters.put(uuid, 1);
            return false;
        }
        int airTime = airTimeCounters.getOrDefault(uuid, 0) + 1;
        airTimeCounters.put(uuid, airTime);
        if (airTime > maxAirTime) {
            if (debugMode) player.sendMessage(getMessage("flight.detected", airTime, maxAirTime));
            return true;
        }
        return false;
    }
    
    private boolean isPlayerOnGround(Player player) {
        Location loc = player.getLocation();
        Block blockUnder = loc.getBlock().getRelative(BlockFace.DOWN);
        if (blockUnder.getType().isSolid()) return true;
        Location below = loc.clone().subtract(0, 0.5, 0);
        if (below.getBlock().getType().isSolid()) return true;
        return player.isOnGround();
    }

    /* ------------------------- 违规处理 ------------------------- */
    private void handleViolation(Player player, String reasonKey, boolean rollback) {
        UUID uuid = player.getUniqueId();
        int count = violationCount.merge(uuid, 1, Integer::sum);
        if (getConfig().getBoolean("settings.log-violations", true)) {
            String reasonMsg = getMessage(reasonKey);
            getLogger().warning(getMessage("violation.log", 
                player.getWorld().getName(),
                player.getName(),
                reasonMsg,
                count,
                maxViolations
            ));
        }
        if (debugMode) player.sendMessage(getMessage("violation.detected", getMessage(reasonKey)));
        if (count >= maxViolations) {
            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(getMessage("kick.message", count, maxViolations));
                violationCount.remove(uuid);
                recordKickAndCheckBan(player);
            });
        }
        if (rollback && lastValidLocations.containsKey(uuid)) {
            player.teleport(lastValidLocations.get(uuid));
            if (debugMode) player.sendMessage(getMessage("debug.teleport"));
        }
    }

    private void handleClickViolation(Player player, double cps) {
        UUID uuid = player.getUniqueId();
        int violations = clickViolations.merge(uuid, 1, Integer::sum);
        if (getConfig().getBoolean("settings.log-violations", true)) {
            getLogger().warning(getMessage("clicks.violation", 
                player.getName(),
                cps,
                violations,
                clicksViolationsToKick
            ));
        }
        if (violations >= clicksViolationsToKick) {
            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(getMessage("clicks.kick", cps));
                clickViolations.remove(uuid);
                recordKickAndCheckBan(player);
            });
        }
    }
    
    private void recordKickAndCheckBan(Player player) {
        if (!autoBanEnabled) return;
        UUID uuid = player.getUniqueId();
        int kicks = kickCount.merge(uuid, 1, Integer::sum);
        getLogger().info(getMessage("player.kicked", player.getName(), kicks, kicksBeforeBan));
        if (kicks >= kicksBeforeBan) {
            String reason = getMessage("ban.reason", String.valueOf(kicks));
            customBanPlayer(player.getName(), reason, "AntiCheat系统");
            kickCount.remove(uuid);
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
        if (whitelistedWorlds.contains(player.getWorld().getName())) return true;
        if (whitelistedPlayers.contains(player.getUniqueId())) return true;
        for (String perm : getConfig().getStringList("whitelist.bypass-permissions")) {
            if (player.hasPermission(perm)) return true;
        }
        return player.getGameMode() == GameMode.CREATIVE 
            || player.getGameMode() == GameMode.SPECTATOR
            || player.getAllowFlight();
    }
    
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
    
    public void reloadLanguage() {
        loadLanguageFile();
        getLogger().info(getMessage("language.reloaded", language));
    }
    
    public void setMaintenanceMode(boolean maintenance) {
        this.maintenanceMode = maintenance;
    }
    
    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }
    
    public void forceVersionCheck() {
        checkVersion();
    }
    
    public int getPluginVersion() {
        return PLUGIN_VERSION;
    }
    
    public String getFormattedPluginVersion() {
        return formatVersion(PLUGIN_VERSION);
    }
    
    /* ------------------------- 命令处理器 ------------------------- */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("traban")) return handleBanCommand(sender, args);
        else if (cmd.getName().equalsIgnoreCase("traunban")) return handleUnbanCommand(sender, args);
        return false;
    }
    
    private boolean handleUnbanCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("anticheat.traunban")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "用法: /traunban <玩家>");
            return true;
        }
        String playerName = args[0];
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
        if (!sender.hasPermission("anticheat.traban")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /traban <玩家> <理由>");
            return true;
        }
        String playerName = args[0];
        StringBuilder reasonBuilder = new StringBuilder();
        for (int i = 1; i < args.length; i++) reasonBuilder.append(args[i]).append(" ");
        String reason = reasonBuilder.toString().trim();
        String bannedBy = sender instanceof Player ? sender.getName() : "控制台";
        customBanPlayer(playerName, reason, bannedBy);
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
