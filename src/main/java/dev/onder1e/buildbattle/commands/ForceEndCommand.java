package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /force_end — (Admin) Immediately ends the CURRENT active phase.
 *
 * Behaviour per phase:
 *   WORD_SELECTION → Picks the current winner and starts BUILDING.
 *   BUILDING       → Cancels the build timer and starts VOTING.
 *   VOTING         → Skips the remaining plots and goes to RESULTS.
 *
 * Permission: buildbattle.admin
 */
public class ForceEndCommand implements CommandExecutor {

    private final GameManager gameManager;

    public ForceEndCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        // GameManager handles per-state logic and broadcasts the action
        gameManager.forceEnd(player);
        return true;
    }
}
