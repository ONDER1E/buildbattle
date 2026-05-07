package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * /ready — Toggles the calling player's ready status in the LOBBY.
 * When all participants are ready, the game automatically advances to WORD_SELECTION.
 */
public class ReadyCommand implements CommandExecutor {

    private final GameManager gameManager;

    public ReadyCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Command requires a player — not callable from console
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        // Toggle ready state; GameManager handles state validation and messaging
        boolean isNowReady = gameManager.toggleReady(player);

        // Feedback to the individual player
        if (isNowReady) {
            player.sendMessage(Component.text("You are now READY!", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("You are no longer ready.", NamedTextColor.GRAY));
        }

        return true;
    }
}
