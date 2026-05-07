package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /setplotblock <material>
 *
 * Replaces the entire y=64 floor of the player's plot with the given block.
 * Only usable during the BUILDING phase.
 *
 * Examples:
 *   /setplotblock stone
 *   /setplotblock oak_planks
 *   /setplotblock sand
 */
public class SetPlotBlockCommand implements CommandExecutor {

    private final GameManager gameManager;

    public SetPlotBlockCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(Component.text(
                    "Usage: /setplotblock <material>  e.g. /setplotblock stone",
                    NamedTextColor.RED));
            return true;
        }
        // Delegate all validation and block-setting to GameManager
        gameManager.setPlotBlock(player, args[0]);
        return true;
    }
}
