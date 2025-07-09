package com.tr.anticheat;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
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

    /* [之前的所有代码保持不变] */

    // 新增: 自定义封禁存储
    private File banFile;
    private FileConfiguration banConfig;
    
    /* ------------------------- 插件生命周期 ------------------------- */
    @Override
    public void onEnable() {
        // [之前初始化代码保持不变]
        
        // 初始化自定义封禁系统
        initBanSystem();
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
        
        String reason = banConfig.getString(path + ".reason", getMessage("ban.reason", "unknown"));
        String date = banConfig.getString(path + ".date", "Unknown date");
        String bannedBy = banConfig.getString(path + ".banned-by", "System");
        
        return generateBanMessage(playerName, reason, date);
    }
    
    /* ------------------------- 事件处理器 ------------------------- */
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

    /* ------------------------- 违规处理 ------------------------- */
    // 修改封禁处理方法
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
    
    /* [其余代码保持不变] */
}
