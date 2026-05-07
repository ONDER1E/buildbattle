package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /force_start — (Admin) Skips the ready-check and immediately advances
 * the game from LOBBY to WORD_SELECTION.
 *
 * Permission: buildbattle.admin
 */
public class ForceStartCommand implements CommandExecutor {

    private final GameManager gameManager;

    public ForceStartCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Console or admin player can use this
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        // GameManager validates state and broadcasts the force-start message
        gameManager.forceStart(player);
        return true;
    }
}
