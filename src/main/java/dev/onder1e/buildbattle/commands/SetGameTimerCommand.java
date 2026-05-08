package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.game.GameManager;
import dev.onder1e.buildbattle.game.GameState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /set_game_timer <minutes> - (Admin) Update the building phase duration.
 *
 * Persists the new value to config.yml immediately so it survives restarts.
 * Takes effect on the NEXT building phase - not mid-game.
 *
 * Permission: buildbattle.admin
 */
public class SetGameTimerCommand implements CommandExecutor {

    private final BuildBattle plugin;
    private final GameManager gameManager;

    public SetGameTimerCommand(BuildBattle plugin, GameManager gameManager) {
        this.plugin      = plugin;
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender == null) return true;
        
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        // Validate argument
        if (args.length < 1) {
            player.sendMessage(Component.text("Usage: /set_game_timer <minutes>", NamedTextColor.RED));
            return true;
        }

        int minutes;
        try {
            minutes = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Please provide a valid number of minutes.", NamedTextColor.RED));
            return true;
        }

        // Enforce sensible bounds (1–60 minutes)
        if (minutes < 1 || minutes > 60) {
            player.sendMessage(Component.text("Timer must be between 1 and 60 minutes.", NamedTextColor.RED));
            return true;
        }

        // Warn if changing mid-game (the timer won't update the current round)
        if (gameManager.getCurrentState() == GameState.BUILDING) {
            player.sendMessage(Component.text(
                    "⚠ Game is in progress. Change takes effect NEXT round.", NamedTextColor.YELLOW));
        }

        // Persist to config.yml
        plugin.getConfig().set("game_timer", minutes);
        plugin.saveConfig();

        // Update runtime value
        gameManager.setBuildTimerMinutes(minutes);
        gameManager.reloadConfig();

        player.sendMessage(Component.text("Build timer updated to " + minutes + " minutes.", NamedTextColor.GREEN));
        return true;
    }
}
