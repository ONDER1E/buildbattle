package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender; // Fixed this import
import org.jetbrains.annotations.NotNull;

public class DoPvPCommand implements CommandExecutor {
    private final GameManager gameManager;

    public DoPvPCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("buildbattle.admin")) {
            sender.sendMessage(Component.text("No permission!", NamedTextColor.RED));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /doPvP <true|false>", NamedTextColor.RED));
            return true;
        }

        boolean enable = Boolean.parseBoolean(args[0]);
        gameManager.setLobbyPvP(enable);
        
        sender.sendMessage(Component.text("Lobby PvP is now " + (enable ? "ENABLED" : "DISABLED"), 
            enable ? NamedTextColor.GREEN : NamedTextColor.RED));
            
        return true;
    }
}