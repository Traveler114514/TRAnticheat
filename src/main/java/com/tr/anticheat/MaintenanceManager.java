package com.tr.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MaintenanceManager {
    private final AntiCheatPlugin plugin;
    private volatile boolean maintenanceMode = false;
    private ScheduledExecutorService maintenanceScheduler;
    
    private static final String MAINTENANCE_URL = "https://raw.githubusercontent.com/Traveler114514/FileCloud/refs/heads/main/TRAnticheat/maintenance.txt";

    public MaintenanceManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }
    
    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }
    
    public void setMaintenanceMode(boolean maintenance) {
        this.maintenanceMode = maintenance;
    }
    
    public void startMaintenanceCheck() {
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
                    plugin.getLogger().info("维护模式状态变化: " + (maintenanceMode ? "启用" : "禁用"));
                    
                    // 通知所有玩家
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.sendMessage(plugin.getLanguageManager().getMessage(statusKey));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("检查维护状态失败: " + e.getMessage());
            }
        }, 0, 5, TimeUnit.MINUTES); // 每5分钟检查一次
    }
    
    public void stopMaintenanceCheck() {
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
        java.net.URL url = new java.net.URL(urlString);
        StringBuilder content = new StringBuilder();
        
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(url.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        
        return content.toString();
    }
}
