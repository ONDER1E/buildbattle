package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /config <key> <value>
 *
 * Live config editor. Changes are written to config.yml immediately
 * and take effect at the next relevant phase transition.
 *
 * Valid keys:
 *   game_timer            - build phase duration in minutes (1–120)
 *   word_selection_timer  - theme vote duration in seconds (10–300)
 *   voting_timer          - per-build vote duration in seconds (5–300)
 *   min_players           - minimum players to start (1–20, immediate)
 *   lobby_return_timer    - results→lobby countdown seconds (5–120, immediate)
 *   plot_size             - plot size in chunks (1–30, next game)
 *   buffer_size           - iron wall width in chunks (1–10, next game)
 *
 * Usage examples:
 *   /config game_timer 10
 *   /config min_players 3
 *   /config voting_timer 20
 *
 * Permission: buildbattle.admin
 */
public class ConfigCommand implements CommandExecutor {

    private final GameManager gameManager;

    public ConfigCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /config <key> <value>", NamedTextColor.RED));
            player.sendMessage(Component.text(
                    "Keys: game_timer, word_selection_timer, voting_timer, " +
                    "min_players, lobby_return_timer, plot_size, buffer_size",
                    NamedTextColor.GRAY));
            return true;
        }

        String key   = args[0];
        String value = args[1];

        // Delegate to GameManager which validates, persists, and stages the change
        String result = gameManager.applyConfig(key, value);

        NamedTextColor colour = result.contains("must be") || result.contains("Unknown")
                ? NamedTextColor.RED : NamedTextColor.GREEN;
        player.sendMessage(Component.text("[Config] " + result, colour));
        return true;
    }
}
