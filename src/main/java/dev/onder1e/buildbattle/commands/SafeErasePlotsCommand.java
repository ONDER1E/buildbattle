package dev.onder1e.buildbattle.commands;

import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.game.GameManager;
import dev.onder1e.buildbattle.game.GameState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

/**
 * /safe_erase_plots
 *
 * Emergency admin command that clears all plot chunks using a guaranteed
 * block-by-block AIR fill. Slower than the normal regenerateChunk cleanup
 * but visually removes all builds regardless of Paper chunk save behaviour.
 *
 * Can be used:
 *  - When /force_end → RESET didn't visually clear the plots.
 *  - As a standalone manual cleanup tool.
 *
 * Safe to call in any game state. If called during BUILDING or VOTING the
 * game is force-reset first to ensure state consistency.
 *
 * Permission: buildbattle.admin
 */
public class SafeErasePlotsCommand implements CommandExecutor {

    private final BuildBattle plugin;
    private final GameManager gameManager;

    public SafeErasePlotsCommand(BuildBattle plugin, GameManager gameManager) {
        this.plugin      = plugin;
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        player.sendMessage(Component.text(
                "Starting safe block-by-block plot erase. This may take a few seconds...",
                NamedTextColor.YELLOW));

        // If a game is active, abort timers and packet filters cleanly first
        GameState state = gameManager.getCurrentState();
        if (state == GameState.BUILDING || state == GameState.VOTING
                || state == GameState.WORD_SELECTION || state == GameState.RESULTS) {
            player.sendMessage(Component.text(
                    "Active game detected — forcing reset before erase.", NamedTextColor.GOLD));
            gameManager.forceReset();
            // forceReset calls destroyAllPlots internally, but safeErasePlots
            // is called here as an ADDITIONAL visible wipe on top of that.
            // We delay slightly so forceReset can clear its own state first.
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () ->
                    runErase(player), 40L); // 2 second delay
        } else {
            runErase(player);
        }

        return true;
    }

    private void runErase(Player player) {
        plugin.getPlotManager().safeErasePlots(() -> {
            if (player.isOnline()) {
                player.sendMessage(Component.text(
                        "Safe erase complete — all plots cleared.", NamedTextColor.GREEN));
            }
        });
    }
}
