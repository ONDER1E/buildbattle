package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /force_choose <theme> — (Admin) Override and forcibly set the theme,
 * skipping the vote entirely and advancing immediately to BUILDING.
 *
 * Permission: buildbattle.admin
 */
public class ForceChooseCommand implements CommandExecutor {

    private final GameManager gameManager;

    public ForceChooseCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        // Require the theme argument
        if (args.length < 1) {
            player.sendMessage(Component.text("Usage: /force_choose <theme>", NamedTextColor.RED));
            return true;
        }

        // Join all args to support multi-word themes
        String theme = String.join(" ", args);

        // GameManager validates phase and broadcasts the override
        gameManager.forceTheme(player, theme);
        return true;
    }
}
