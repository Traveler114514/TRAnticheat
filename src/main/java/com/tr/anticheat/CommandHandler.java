package com.tr.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

public class CommandHandler implements CommandExecutor {
    private final AntiCheatPlugin plugin;
    private final BanManager banManager;
    private final LanguageManager languageManager;
    
    public CommandHandler(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.banManager = plugin.getBanManager();
        this.languageManager = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("traban")) {
            return handleBanCommand(sender, args);
        } else if (cmd.getName().equalsIgnoreCase("traunban")) {
            return handleUnbanCommand(sender, args);
        }
        return false;
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
        banManager.customBanPlayer(playerName, reason, bannedBy);
        
        // 踢出在线玩家
        Player targetPlayer = Bukkit.getPlayer(playerName);
        if (targetPlayer != null && targetPlayer.isOnline()) {
            String banDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String banMessage = banManager.generateBanMessage(playerName, reason, banDate, bannedBy);
            targetPlayer.kickPlayer(banMessage);
        }
        
        sender.sendMessage(ChatColor.GREEN + "已封禁玩家 " + playerName + " | 理由: " + reason);
        plugin.getLogger().info("玩家 " + playerName + " 已被 " + bannedBy + " 封禁 | 理由: " + reason);
        
        return true;
    }
    
    private boolean handleUnbanCommand(CommandSender sender, String[] args) {
        // 权限检查
        if (!sender.hasPermission("anticheat.traunban")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
            return true;
        }
        
        // 参数验证
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "用法: /traunban <玩家> [理由]");
            return true;
        }
        
        String playerName = args[0];
        
        // 检查玩家是否被封禁
        if (!banManager.isBanned(playerName)) {
            sender.sendMessage(ChatColor.RED + languageManager.getMessage("command.unban.not-banned", playerName));
            return true;
        }
        
        // 获取解封理由
        String reason = "管理员解封";
        if (args.length > 1) {
            StringBuilder reasonBuilder = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                reasonBuilder.append(args[i]).append(" ");
            }
            reason = reasonBuilder.toString().trim();
        }
        
        // 获取执行者名称
        String unbannedBy = sender instanceof Player ? sender.getName() : "控制台";
        
        // 执行解封
        banManager.unbanPlayer(playerName, reason, unbannedBy);
        
        // 通知执行者
        sender.sendMessage(ChatColor.GREEN + languageManager.getMessage("command.unban.success", playerName, reason));
        plugin.getLogger().info(languageManager.getMessage("command.unban.log", playerName, reason, unbannedBy));
        
        return true;
    }
}
