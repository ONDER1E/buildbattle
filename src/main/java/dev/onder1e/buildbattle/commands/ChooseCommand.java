package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /choose <theme|index> — Player votes for a theme during WORD_SELECTION.
 *
 * Accepts:
 *   /choose 1         — vote for option 1 (numeric index)
 *   /choose spiderman — vote by name (partial match supported)
 */
public class ChooseCommand implements CommandExecutor {

    private final GameManager gameManager;

    public ChooseCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        // Require exactly one argument (the theme choice)
        if (args.length < 1) {
            player.sendMessage(Component.text("Usage: /choose <theme>", NamedTextColor.RED));
            return true;
        }

        // Join args in case theme has spaces (e.g. "haunted mansion")
        String input = String.join(" ", args);

        // Delegate to GameManager which validates state and records the vote
        gameManager.castThemeVote(player, input);
        return true;
    }
}
