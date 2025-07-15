package com.tr.anticheat;

import org.bukkit.plugin.java.JavaPlugin;

public class AntiCheatPlugin extends JavaPlugin {
    private DetectionManager detectionManager;
    private BanManager banManager;
    private LanguageManager languageManager;
    private MaintenanceManager maintenanceManager;
    private CommandHandler commandHandler;
    private TaskManager taskManager;

    @Override
    public void onEnable() {
        // 初始化管理器
        languageManager = new LanguageManager(this);
        banManager = new BanManager(this);
        detectionManager = new DetectionManager(this);
        maintenanceManager = new MaintenanceManager(this);
        taskManager = new TaskManager(this);
        commandHandler = new CommandHandler(this);

        // 加载配置
        saveDefaultConfig();
        reloadConfig();

        // 注册事件
        getServer().getPluginManager().registerEvents(detectionManager, this);

        // 设置命令执行器
        getCommand("traban").setExecutor(commandHandler);
        getCommand("traunban").setExecutor(commandHandler);

        // 启动任务
        taskManager.startTasks();
        maintenanceManager.startMaintenanceCheck();

        getLogger().info("反作弊插件已启用!");
    }

    @Override
    public void onDisable() {
        maintenanceManager.stopMaintenanceCheck();
        getLogger().info("反作弊插件已禁用!");
    }

    // Getter 方法
    public DetectionManager getDetectionManager() {
        return detectionManager;
    }

    public BanManager getBanManager() {
        return banManager;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public MaintenanceManager getMaintenanceManager() {
        return maintenanceManager;
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }
}
