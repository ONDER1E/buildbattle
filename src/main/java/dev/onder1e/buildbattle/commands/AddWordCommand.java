package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.game.GameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /addword <theme> - (Admin) Add a new theme to the pool.
 *
 * Immediately persists the updated list to config.yml.
 * Supports multi-word themes (e.g. /addword haunted mansion).
 *
 * Permission: buildbattle.admin
 */
public class AddWordCommand implements CommandExecutor {

    private final BuildBattle plugin;
    private final GameManager gameManager;

    public AddWordCommand(BuildBattle plugin, GameManager gameManager) {
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
            player.sendMessage(Component.text("Usage: /addword <theme>", NamedTextColor.RED));
            return true;
        }

        // Join all arguments to support multi-word themes
        String newWord = String.join(" ", args).trim().toLowerCase();

        // Load the current pool, check for duplicates, append, and save
        List<String> pool = gameManager.getThemePool();

        if (pool.contains(newWord)) {
            player.sendMessage(Component.text("'" + newWord + "' is already in the theme pool.", NamedTextColor.YELLOW));
            return true;
        }

        pool.add(newWord);

        // Persist: write the entire list back as a comma-separated string
        savePool(pool);

        player.sendMessage(Component.text("Added '" + newWord + "' to the theme pool. ("
                + pool.size() + " themes total)", NamedTextColor.GREEN));
        return true;
    }

    /** Write the updated theme list back to config.yml and reload. */
    private void savePool(List<String> pool) {
        String joined = String.join(", ", pool);
        plugin.getConfig().set("themes", joined);
        plugin.saveConfig();
    }
}
