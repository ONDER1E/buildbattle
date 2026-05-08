package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.game.GameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /removeword <theme> - (Admin) Remove a theme from the pool.
 *
 * Immediately persists the updated list to config.yml.
 * Requires at least 3 themes to remain in the pool (to support WORD_SELECTION).
 *
 * Permission: buildbattle.admin
 */
public class RemoveWordCommand implements CommandExecutor {

    private final BuildBattle plugin;
    private final GameManager gameManager;

    public RemoveWordCommand(BuildBattle plugin, GameManager gameManager) {
        this.plugin      = plugin;
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(Component.text("Usage: /removeword <theme>", NamedTextColor.RED));
            return true;
        }

        String target = String.join(" ", args).trim().toLowerCase();
        List<String> pool = gameManager.getThemePool();

        // Guard: must retain at least 3 themes for voting to function
        if (pool.size() <= 3) {
            player.sendMessage(Component.text(
                    "Cannot remove theme - pool must contain at least 3 themes.", NamedTextColor.RED));
            return true;
        }

        // Case-insensitive removal
        boolean removed = pool.removeIf(t -> t.equalsIgnoreCase(target));

        if (!removed) {
            player.sendMessage(Component.text("Theme '" + target + "' was not found in the pool.", NamedTextColor.YELLOW));
            return true;
        }

        // Persist updated list
        String joined = String.join(", ", pool);
        plugin.getConfig().set("themes", joined);
        plugin.saveConfig();

        player.sendMessage(Component.text("Removed '" + target + "' from the theme pool. ("
                + pool.size() + " themes remaining)", NamedTextColor.GREEN));
        return true;
    }
}
