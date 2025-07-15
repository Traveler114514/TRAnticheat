package com.tr.anticheat;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LanguageManager {
    private final JavaPlugin plugin;
    private String language;
    private FileConfiguration langConfig;
    private final Map<String, String> messages = new ConcurrentHashMap<>();

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadLanguageFile();
    }

    public void loadLanguageFile() {
        // 从配置获取语言设置
        language = plugin.getConfig().getString("language", "en");
        
        File langFile = new File(plugin.getDataFolder(), "messages_" + language + ".yml");
        
        // 如果语言文件不存在，从JAR中复制
        if (!langFile.exists()) {
            plugin.saveResource("messages_" + language + ".yml", false);
            plugin.getLogger().info("已创建语言文件: " + langFile.getName());
        }
        
        // 加载语言文件
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        
        // 加载默认语言作为后备
        try {
            FileConfiguration defaultLang = YamlConfiguration.loadConfiguration(
                new InputStreamReader(plugin.getResource("messages_en.yml"))
            );
            langConfig.setDefaults(defaultLang);
        } catch (Exception e) {
            plugin.getLogger().warning("缺少默认语言文件: messages_en.yml");
        }
        
        // 预加载所有消息到内存
        messages.clear();
        for (String key : langConfig.getKeys(true)) {
            if (langConfig.isString(key)) {
                messages.put(key, langConfig.getString(key));
            }
        }
        
        plugin.getLogger().info("已加载语言文件: " + langFile.getName() + " (" + messages.size() + " 条消息)");
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
    
    /**
     * 重新加载语言文件
     */
    public void reload() {
        loadLanguageFile();
        plugin.getLogger().info("语言文件已重新加载");
    }
}
