package dev.onder1e.buildbattle.listener;

import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.game.GameManager;
import dev.onder1e.buildbattle.game.GameState;
import dev.onder1e.buildbattle.plot.Plot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;

/**
 * PlayerListener
 * ==============
 * Handles all player-related events:
 *
 *  - Join:  Adds the player to the lobby.
 *  - Quit:  Removes the player from the active round.
 *  - Block interactions: Prevents building outside a player's plot using
 *    vanilla block events (WorldEdit masking is handled in WorldEditListener).
 */
public class PlayerListener implements Listener {

    private final BuildBattle plugin;
    private final GameManager gameManager;

    public PlayerListener(BuildBattle plugin, GameManager gameManager) {
        this.plugin      = plugin;
        this.gameManager = gameManager;
    }

    // ── Player join / quit ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        // Only add to game if we're in LOBBY state
        if (gameManager.getCurrentState() == GameState.LOBBY) {
            gameManager.addPlayer(event.getPlayer());
        } else {
            // Late join: put them in spectator mode at the lobby
            event.getPlayer().setGameMode(org.bukkit.GameMode.SPECTATOR);
            event.getPlayer().teleport(plugin.getLobbySpawn());
            event.getPlayer().sendMessage(Component.text(
                    "A game is in progress. You are spectating!", NamedTextColor.YELLOW));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        gameManager.removePlayer(event.getPlayer());
    }

    // ── Block place / break restriction ──────────────────────────────────────

    /**
     * Prevent any player from placing blocks outside their inner plot area.
     *
     * This acts as a VANILLA safety net. The primary restriction during
     * WorldEdit operations is enforced by the WorldEditListener mask.
     * This listener catches manual block placement with the hand.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (gameManager.getCurrentState() != GameState.BUILDING) return;

        Plot plot = plugin.getPlotManager().getPlot(event.getPlayer());
        if (plot == null) {
            // Player has no plot — they shouldn't be building at all
            event.setCancelled(true);
            return;
        }

        // Check if the placed block is inside the player's inner plot area
        int bx = event.getBlock().getX();
        int bz = event.getBlock().getZ();
        if (bx < plot.getInnerMinX() || bx > plot.getInnerMaxX()
         || bz < plot.getInnerMinZ() || bz > plot.getInnerMaxZ()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "You cannot build outside your plot!", NamedTextColor.RED));
        }
    }

    /**
     * Prevent block breaking outside the inner plot area.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (gameManager.getCurrentState() != GameState.BUILDING) return;

        Plot plot = plugin.getPlotManager().getPlot(event.getPlayer());
        if (plot == null) {
            event.setCancelled(true);
            return;
        }

        int bx = event.getBlock().getX();
        int bz = event.getBlock().getZ();
        if (bx < plot.getInnerMinX() || bx > plot.getInnerMaxX()
         || bz < plot.getInnerMinZ() || bz > plot.getInnerMaxZ()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "You cannot break blocks outside your plot!", NamedTextColor.RED));
        }
    }

    /**
     * Prevent LOBBY barrier walls from being broken by anyone.
     * (They're barrier blocks, so players can't break them in survival/adventure anyway,
     * but this adds an extra safeguard in creative mode.)
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBarrierBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == org.bukkit.Material.BARRIER) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevent movement outside the lobby during LOBBY state.
     * Players in creative/spectator during building are trusted by plot restriction.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // During LOBBY, prevent players from leaving the game world
        if (gameManager.getCurrentState() == GameState.LOBBY) {
            if (event.getTo().getY() < 60) {
                // Player fell below the lobby floor — teleport them back
                event.setCancelled(true);
                event.getPlayer().teleport(plugin.getLobbySpawn());
            }
        }
    }
}
