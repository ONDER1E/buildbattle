package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /done - Marks the player's build as complete and switches them to spectator.
 *
 * If all players finish before the timer, voting starts immediately.
 * The build is then locked - the iron walls prevent re-entry and the
 * GameMode change prevents block interaction.
 */
public class DoneCommand implements CommandExecutor {

    private final GameManager gameManager;

    public DoneCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        // GameManager validates state, sets plot.done = true, and enters spectator
        gameManager.markDone(player);
        return true;
    }
}
