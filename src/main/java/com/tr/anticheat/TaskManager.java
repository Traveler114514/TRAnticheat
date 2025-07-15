package com.tr.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TaskManager {
    private final AntiCheatPlugin plugin;
    private final DetectionManager detectionManager;
    
    // 配置参数
    private boolean clicksEnabled;
    private int maxCps;
    private int clicksCheckInterval;
    private int clicksViolationsToKick;
    private boolean autoBanEnabled;
    private int kicksBeforeBan;

    public TaskManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.detectionManager = plugin.getDetectionManager();
        loadConfig();
    }
    
    private void loadConfig() {
        clicksEnabled = plugin.getConfig().getBoolean("settings.clicks.enabled", true);
        maxCps = plugin.getConfig().getInt("settings.clicks.max-cps", 15);
        clicksCheckInterval = plugin.getConfig().getInt("settings.clicks.check-interval", 5);
        clicksViolationsToKick = plugin.getConfig().getInt("settings.clicks.violations-to-kick", 3);
        autoBanEnabled = plugin.getConfig().getBoolean("settings.violations.auto-ban.enabled", false);
        kicksBeforeBan = plugin.getConfig().getInt("settings.violations.auto-ban.kicks-before-ban", 3);
    }
    
    public void startTasks() {
        startCleanupTask();
        startClickCheckTask();
    }
    
    private void startCleanupTask() {
        // 每10分钟清理一次离线玩家数据
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            detectionManager.cleanupOfflinePlayers();
        }, 20 * 60 * 10, 20 * 60 * 10);
    }

    private void startClickCheckTask() {
        if (!clicksEnabled) return;
        
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // 维护模式时跳过检测
            if (plugin.getMaintenanceManager().isMaintenanceMode()) {
                return;
            }
            
            long now = System.currentTimeMillis();
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (detectionManager.shouldBypassCheck(player)) continue;
                
                UUID uuid = player.getUniqueId();
                Deque<Long> clicks = detectionManager.getClickRecords(uuid);
                
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
                    detectionManager.handleClickViolation(player, cps);
                } else if (detectionManager.getClickViolations(uuid) > 0) {
                    // 正常点击时减少违规计数
                    detectionManager.decrementClickViolations(uuid);
                }
            }
        }, 0, clicksCheckInterval * 20L);
    }
}
