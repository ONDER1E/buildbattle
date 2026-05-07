package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.game.GameManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /pause  — freeze the active countdown.
 * /resume — continue the countdown from where it was paused.
 *
 * Permission: buildbattle.admin
 */
public class PauseResumeCommand implements CommandExecutor {

    private final GameManager gameManager;

    public PauseResumeCommand(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (label.equalsIgnoreCase("pause")) {
            boolean ok = gameManager.pauseGame(player);
            if (ok) player.sendMessage(Component.text("Countdown paused.", NamedTextColor.YELLOW));
        } else {
            boolean ok = gameManager.resumeGame(player);
            if (ok) player.sendMessage(Component.text("Countdown resumed.", NamedTextColor.GREEN));
        }
        return true;
    }
}
