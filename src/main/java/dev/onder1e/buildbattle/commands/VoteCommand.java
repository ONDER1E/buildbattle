package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /vote <1-10> — Cast a numeric score for the build currently on display.
 *
 * Rules enforced by GameManager:
 *   - Only active during VOTING phase.
 *   - Players cannot vote on their own build.
 *   - Each player may only vote once per plot.
 *   - Score must be an integer between 1 and 10.
 */
public class VoteCommand implements CommandExecutor {

    private final GameManager gameManager;

    public VoteCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender == null || !(sender instanceof Player player)) {
            if (sender != null) {
                sender.sendMessage("Only players can use this command.");
            }
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(Component.text("Usage: /vote <1-10>", NamedTextColor.RED));
            return true;
        }

        // Parse the numeric score, catching bad input gracefully
        int score;
        try {
            score = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Score must be a number between 1 and 10.", NamedTextColor.RED));
            return true;
        }

        // Delegate to GameManager for state validation and vote recording
        gameManager.castVote(player, score);
        return true;
    }
}
