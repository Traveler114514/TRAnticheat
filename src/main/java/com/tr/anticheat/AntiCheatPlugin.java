public class AntiCheatPlugin extends JavaPlugin implements Listener, CommandExecutor {

    // 插件元数据
    private static final int PLUGIN_VERSION = 105; // 1.0.5
    private static final String VERSION_CHECK_URL = "https://raw.githubusercontent.com/Traveler114514/TRAnticheat/main/version.txt";
    
    // 配置参数
    private String language;
    private boolean debugMode;
    private boolean broadcastKicks;
    private boolean maintenanceMode;
    
    // 检测开关
    private boolean movementDetectionEnabled;
    private boolean rotationDetectionEnabled;
    private boolean clicksDetectionEnabled;
    private boolean flightDetectionEnabled;
    private boolean elytraDetectionEnabled;
    
    // 检测阈值
    private double maxHorizontalSpeed;
    private double maxVerticalSpeed;
    private float maxAngleChange;
    private long rotationCheckInterval;
    private int maxCps;
    private int clicksCheckInterval;
    private int clicksViolationsToKick;
    private int maxAirTime;
    private double elytraHorizontalThreshold;
    private double elytraVerticalThreshold;
    
    // 违规处理
    private int maxViolations;
    private int violationExpire;
    private boolean autoBanEnabled;
    private int kicksBeforeBan;
    
    // 数据存储
    private final Map<UUID, Location> lastValidLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastPitch = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRotationCheck = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> violationCount = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> clickRecords = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> clickViolations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> airTimeCounters = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> wasOnGround = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> kickCount = new ConcurrentHashMap<>();
    
    // 白名单
    private final Set<UUID> whitelistedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> whitelistedWorlds = ConcurrentHashMap.newKeySet();
    
    // 配置
    private File banFile;
    private FileConfiguration banConfig;
    private FileConfiguration langConfig;
    private final Map<String, String> messages = new ConcurrentHashMap<>();
    
    // 封禁队列
    private final Queue<BanTask> banQueue = new ConcurrentLinkedQueue<>();
    
    @Override
    public void onEnable() {
        // 保存默认配置
        saveDefaultConfig();
        reloadConfig();
        
        // 加载语言文件
        language = getConfig().getString("language", "en");
        loadLanguageFile();
        
        // 加载设置
        loadSettings();
        
        // 加载白名单
        loadWhitelist();
        
        // 加载封禁配置
        loadBanConfig();
        
        // 注册事件监听器
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // 注册命令
        getCommand("traban").setExecutor(this);
        getCommand("traunban").setExecutor(this);
        
        // 启动任务
        startCleanupTask();
        startClickCheckTask();
        startBanProcessor();
        
        // 检查版本更新
        checkVersion();
        
        // 记录启用消息
        getLogger().info(getMessage("plugin.enabled", getFormattedPluginVersion()));
    }
    
    @Override
    public void onDisable() {
        // 保存配置
        saveBanConfig();
        
        // 记录禁用消息
        getLogger().info(getMessage("plugin.disabled"));
    }
    
    @Override
    public void reloadConfig() {
        super.reloadConfig();
        loadSettings();
        loadWhitelist();
        getLogger().info(getMessage("plugin.reloaded"));
    }
    
    private void loadSettings() {
        // 调试模式
        debugMode = getConfig().getBoolean("settings.debug", false);
        
        // 广播设置
        broadcastKicks = getConfig().getBoolean("settings.broadcast-kick", true);
        
        // 检测开关
        movementDetectionEnabled = getConfig().getBoolean("settings.movement.enabled", true);
        rotationDetectionEnabled = getConfig().getBoolean("settings.rotation.enabled", true);
        clicksDetectionEnabled = getConfig().getBoolean("settings.clicks.enabled", true);
        flightDetectionEnabled = getConfig().getBoolean("settings.flight.enabled", true);
        elytraDetectionEnabled = getConfig().getBoolean("settings.elytra.enabled", true);
        
        // 检测阈值
        maxHorizontalSpeed = getConfig().getDouble("settings.movement.max-horizontal-speed", 0.90);
        maxVerticalSpeed = getConfig().getDouble("settings.movement.max-vertical-speed", 9999.0);
        maxAngleChange = (float) getConfig().getDouble("settings.rotation.max-angle-change", 1350);
        rotationCheckInterval = getConfig().getLong("settings.rotation.check-interval", 15);
        maxCps = getConfig().getInt("settings.clicks.max-cps", 18);
        clicksCheckInterval = getConfig().getInt("settings.clicks.check-interval", 1);
        clicksViolationsToKick = getConfig().getInt("settings.clicks.violations-to-kick", 2);
        maxAirTime = getConfig().getInt("settings.flight.max-air-time", 80);
        elytraHorizontalThreshold = getConfig().getDouble("settings.elytra.max-horizontal-speed", 2.0);
        elytraVerticalThreshold = getConfig().getDouble("settings.elytra.max-vertical-speed", 1.5);
        
        // 违规处理
        maxViolations = getConfig().getInt("settings.violations.max-violations", 10);
        violationExpire = getConfig().getInt("settings.violations.violation-expire", 60);
        autoBanEnabled = getConfig().getBoolean("settings.violations.auto-ban.enabled", true);
        kicksBeforeBan = getConfig().getInt("settings.violations.auto-ban.kicks-before-ban", 3);
    }
    
    private void loadWhitelist() {
        // 清空现有白名单
        whitelistedPlayers.clear();
        whitelistedWorlds.clear();
        
        // 加载玩家白名单
        for (String uuidStr : getConfig().getStringList("whitelist.players")) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                whitelistedPlayers.add(uuid);
            } catch (IllegalArgumentException e) {
                getLogger().warning(getMessage("error.invalid-uuid", uuidStr));
            }
        }
        
        // 加载世界白名单
        whitelistedWorlds.addAll(getConfig().getStringList("whitelist.worlds"));
    }
    
    private void loadBanConfig() {
        banFile = new File(getDataFolder(), "bans.yml");
        if (!banFile.exists()) {
            saveResource("bans.yml", false);
        }
        
        banConfig = YamlConfiguration.loadConfiguration(banFile);
        
        // 设置默认值
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
            "&b- 网站: https://traveler114514\n" +
            "&b- QQ群: 315809417\n" +
            "&b- 邮箱: admin@traveler114514\n" +
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
    
    private void loadLanguageFile() {
        File langFile = new File(getDataFolder(), "messages_" + language + ".yml");
        
        // 如果语言文件不存在，从JAR中复制
        if (!langFile.exists()) {
            saveResource("messages_" + language + ".yml", false);
            getLogger().info(getMessage("language.loaded", language, "default"));
        }
        
        // 加载语言文件
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        
        // 预加载所有消息
        messages.clear();
        for (String key : langConfig.getKeys(true)) {
            if (langConfig.isString(key)) {
                messages.put(key, langConfig.getString(key));
            }
        }
        
        getLogger().info(getMessage("language.loaded", language, String.valueOf(messages.size())));
    }
    
    public String getMessage(String key, Object... args) {
        String message = messages.getOrDefault(key, key);
        
        // 替换占位符
        for (int i = 0; i < args.length; i++) {
            message = message.replace("{" + i + "}", String.valueOf(args[i]));
        }
        
        return ChatColor.translateAlternateColorCodes('&', message);
    }
    
    private String getFormattedDate() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
    
    private String getFormattedPluginVersion() {
        return formatVersion(PLUGIN_VERSION);
    }
    
    private String formatVersion(int version) {
        String versionStr = String.valueOf(version);
        while (versionStr.length() < 3) {
            versionStr = "0" + versionStr;
        }
        return versionStr.substring(0, versionStr.length() - 2) + "." +
               versionStr.substring(versionStr.length() - 2, versionStr.length() - 1) + "." +
               versionStr.substring(versionStr.length() - 1);
    }
    
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
        if (!clicksDetectionEnabled) return;
        
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
                if (cps > maxCps) {
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
        if (shouldBypassCheck(player)) return;
        
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // 移动速度检测 (仅当开启时)
        if (movementDetectionEnabled && checkMovementSpeed(player, from, to)) {
            handleViolation(player, "violation.movement", true);
            event.setTo(lastValidLocations.get(player.getUniqueId()));
            return;
        }
        
        // 视角检测 (仅当开启时)
        if (rotationDetectionEnabled && checkRotationSpeed(player, from, to)) {
            handleViolation(player, "violation.rotation", true);
            to.setYaw(lastYaw.get(player.getUniqueId()));
            to.setPitch(lastPitch.get(player.getUniqueId()));
            event.setTo(to);
        }
        
        // 飞行检测 (仅当开启时)
        if (flightDetectionEnabled && checkFlight(player, from, to)) {
            handleViolation(player, "violation.flight", true);
            event.setTo(lastValidLocations.get(player.getUniqueId()));
        }
        
        // 更新最后有效位置
        updatePlayerData(player);
    }
    
    @EventHandler
    public void onEntityGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (debugMode) {
                if (event.isGliding()) {
                    player.sendMessage("§a鞘翅飞行已启动");
                } else {
                    player.sendMessage("§c鞘翅飞行已停止");
                }
            }
            
            // 当玩家停止使用鞘翅时，重置飞行计数器
            if (!event.isGliding()) {
                airTimeCounters.remove(player.getUniqueId());
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
        
        if (!clicksDetectionEnabled) return;
        
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

    private boolean checkMovementSpeed(Player player, Location from, Location to) {
        Vector vector = to.toVector().subtract(from.toVector());
        
        double horizontal = Math.hypot(vector.getX(), vector.getZ());
        double vertical = Math.abs(vector.getY());
        
        // 鞘翅飞行特殊处理 (仅当鞘翅检测开启时)
        if (elytraDetectionEnabled && player.isGliding()) {
            // 使用专用阈值检查鞘翅飞行
            return horizontal > elytraHorizontalThreshold || vertical > elytraVerticalThreshold;
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
        
        // 新增：检查玩家是否在梯子或藤蔓上
        Material playerMaterial = player.getLocation().getBlock().getType();
        if (playerMaterial == Material.LADDER || 
            playerMaterial == Material.VINE || 
            playerMaterial == Material.SCAFFOLDING) {
            // 重置飞行计数器
            airTimeCounters.remove(player.getUniqueId());
            return false;
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
        UUID uuid = player.getUniqueId();
        
        // 增加违规计数
        int count = violationCount.merge(uuid, 1, Integer::sum);
        
        // 记录日志
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
        
        // 调试消息
        if (debugMode) {
            player.sendMessage(getMessage("violation.detected", getMessage(reasonKey)));
        }
        
        // 超过阈值踢出
        if (count >= maxViolations) {
            Bukkit.getScheduler().runTask(this, () -> {
                String reasonMsg = getMessage(reasonKey);
                broadcastKickMessage(player, reasonMsg);
                player.kickPlayer(getMessage("kick.message", count, maxViolations, reasonMsg));
                
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
                // 获取具体原因消息
                String reasonMsg = getMessage("clicks.kick", cps);
                // 广播踢出消息
                broadcastKickMessage(player, reasonMsg);
                // 执行踢出
                player.kickPlayer(reasonMsg);
                
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

    /* ------------------------- 广播踢出消息 ------------------------- */
    private void broadcastKickMessage(Player player, String reason) {
        if (broadcastKicks) {
            String message = getMessage("kick.broadcast", player.getName(), reason);
            Bukkit.broadcastMessage(message);
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
            "&b- 网站: https://traveler114514\n" +
            "&b- QQ群: 315809417\n" +
            "&b- 邮箱: admin@traveler114514\n" +
            "&r\n" +
            "&7请提供您的游戏ID和封禁时间以便我们处理");
        
        return ChatColor.translateAlternateColorCodes('&', template
            .replace("{player}", playerName)
            .replace("{reason}", reason)
            .replace("{date}", date)
            .replace("{banned-by}", bannedBy));
    }
    
    /**
     * 获取格式化日期
     */
    private String getFormattedDate() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
    
    /**
     * 自定义封禁玩家
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
    }
    
    /**
     * 解封玩家
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
    
    /**
     * 保存封禁配置
     */
    private void saveBanConfig() {
        try {
            banConfig.save(banFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, getMessage("error.ban-save"), e);
        }
    }
    
    /**
     * 检查玩家是否被封禁
     */
    private boolean isBanned(String playerName) {
        return banConfig.contains("bans." + playerName.toLowerCase()) || 
               Bukkit.getBanList(Type.NAME).isBanned(playerName);
    }
    
    /* ------------------------- 其他辅助方法 ------------------------- */
    
    /**
     * 格式化版本号
     */
    private String formatVersion(int version) {
        String versionStr = String.valueOf(version);
        while (versionStr.length() < 3) {
            versionStr = "0" + versionStr;
        }
        return versionStr.substring(0, versionStr.length() - 2) + "." +
               versionStr.substring(versionStr.length() - 2, versionStr.length() - 1) + "." +
               versionStr.substring(versionStr.length() - 1);
    }
    
    /**
     * 检查版本更新
     */
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
     * 读取远程文件内容
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
    
    /**
     * 加载语言文件
     */
    private void loadLanguageFile() {
        File langFile = new File(getDataFolder(), "messages_" + language + ".yml");
        
        // 如果语言文件不存在，从JAR中复制
        if (!langFile.exists()) {
            saveResource("messages_" + language + ".yml", false);
            getLogger().info("已创建语言文件: " + langFile.getName());
        }
        
        // 加载语言文件
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        
        // 预加载所有消息到内存
        messages.clear();
        for (String key : langConfig.getKeys(true)) {
            if (langConfig.isString(key)) {
                messages.put(key, langConfig.getString(key));
            }
        }
        
        getLogger().info(getMessage("language.loaded", language, String.valueOf(messages.size())));
    }
    
    /**
     * 获取本地化消息
     */
    public String getMessage(String key, Object... args) {
        String message = messages.getOrDefault(key, key);
        
        // 替换占位符
        for (int i = 0; i < args.length; i++) {
            message = message.replace("{" + i + "}", String.valueOf(args[i]));
        }
        
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
