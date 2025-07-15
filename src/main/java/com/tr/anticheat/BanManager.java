package com.tr.anticheat;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class BanManager {
    private final AntiCheatPlugin plugin;
    private File banFile;
    private FileConfiguration banConfig;

    public BanManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        initBanSystem();
    }

    private void initBanSystem() {
        banFile = new File(plugin.getDataFolder(), "bans.yml");
        if (!banFile.exists()) {
            plugin.saveResource("bans.yml", false);
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
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "保存封禁配置失败", e);
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
    public void customBanPlayer(String playerName, String reason, String bannedBy) {
        // 添加到封禁列表
        String path = "bans." + playerName.toLowerCase();
        String banDate = getFormattedDate();
        banConfig.set(path + ".reason", reason);
        banConfig.set(path + ".date", banDate);
        banConfig.set(path + ".banned-by", bannedBy);
        saveBanConfig();
        
        // 如果玩家在线，立即踢出
        Player player = plugin.getServer().getPlayerExact(playerName);
        if (player != null && player.isOnline()) {
            String banMessage = generateBanMessage(playerName, reason, banDate, bannedBy);
            player.kickPlayer(banMessage);
        }
    }
    
    /**
     * 生成封禁消息
     */
    public String generateBanMessage(String playerName, String reason, String date, String bannedBy) {
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
            return plugin.getLanguageManager().getMessage("command.not-banned", playerName);
        }
        
        String reason = banConfig.getString(path + ".reason", banConfig.getString("default-reason"));
        String date = banConfig.getString(path + ".date", "Unknown date");
        String bannedBy = banConfig.getString(path + ".banned-by", "系统");
        
        return generateBanMessage(playerName, reason, date, bannedBy);
    }
    
    /**
     * 解封玩家
     * @param playerName 玩家名
     * @param reason 解封理由
     * @param unbannedBy 执行者
     */
    public void unbanPlayer(String playerName, String reason, String unbannedBy) {
        String path = "bans." + playerName.toLowerCase();
        
        // 保存解封记录
        String unbanDate = getFormattedDate();
        banConfig.set(path + ".unbanned", true);
        banConfig.set(path + ".unban-reason", reason);
        banConfig.set(path + ".unbanned-by", unbannedBy);
        banConfig.set(path + ".unban-date", unbanDate);
        
        // 保存配置
        saveBanConfig();
    }
}
