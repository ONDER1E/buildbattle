package dev.onder1e.buildbattle.listener;

import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.game.GameManager;
import dev.onder1e.buildbattle.game.GameState;
import dev.onder1e.buildbattle.plot.Plot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;

/**
 * PlayerListener
 * ==============
 *
 * PLOT BOUNDARY ENFORCEMENT (building state):
 * -------------------------------------------
 * We enforce THREE hard rules during BUILDING:
 *
 *  1. BlockBreakEvent / BlockPlaceEvent — cancel if target block is outside
 *     the player's inner plot XZ range, OR below y=64 (floor protection), OR
 *     the block being broken is IRON_BLOCK (wall protection).
 *
 *  2. PlayerMoveEvent — if a player physically moves outside their inner plot
 *     XZ boundary or below y=64 we teleport them back to the plot centre.
 *     This catches the "phase under the floor via creative flight" exploit.
 *
 *  Note: BlockBreakEvent does NOT fire reliably in creative mode for instant
 *  breaks, so the movement check is the authoritative escape prevention.
 *
 * SPECTATOR CONFINEMENT (voting state):
 * --------------------------------------
 * Spectators can fly through solid blocks. We cancel PlayerMoveEvent whenever
 * a spectator's position leaves the TOTAL footprint (inner + walls) of the
 * currently displayed plot, and also clamp Y so they can't fly under the map.
 */
public class PlayerListener implements Listener {

    private final BuildBattle plugin;
    private final GameManager gameManager;

    /** Y floor during building — players cannot go below this. */
    private static final int PLOT_FLOOR_Y = 64;
    /** Y ceiling during building — creative players can't rocket out of range. */
    private static final int PLOT_CEIL_Y  = 256;

    public PlayerListener(BuildBattle plugin, GameManager gameManager) {
        this.plugin      = plugin;
        this.gameManager = gameManager;
    }

    // ── Join / Quit ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        if (gameManager.getCurrentState() == GameState.LOBBY) {
            gameManager.addPlayer(event.getPlayer());
        } else {
            event.getPlayer().setGameMode(GameMode.SPECTATOR);
            event.getPlayer().teleport(plugin.getLobbySpawn());
            event.getPlayer().sendMessage(Component.text(
                    "A game is in progress. You are spectating!", NamedTextColor.YELLOW));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        gameManager.removePlayer(event.getPlayer());
    }

    // ── Block break ───────────────────────────────────────────────────────────

    /**
     * Cancels block breaks that would escape the plot:
     *  - Breaking IRON_BLOCK anywhere (walls must never be removable).
     *  - Breaking any block below y=64 (floor layer is protected).
     *  - Breaking any block outside the inner XZ plot boundary.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // Always protect barrier blocks (lobby walls)
        if (event.getBlock().getType() == Material.BARRIER) {
            event.setCancelled(true);
            return;
        }

        if (gameManager.getCurrentState() != GameState.BUILDING) return;

        Plot plot = plugin.getPlotManager().getPlot(event.getPlayer());
        if (plot == null) { event.setCancelled(true); return; }

        int bx = event.getBlock().getX();
        int by = event.getBlock().getY();
        int bz = event.getBlock().getZ();

        // Hard rule: iron wall blocks can NEVER be broken
        if (event.getBlock().getType() == Material.IRON_BLOCK) {
            event.setCancelled(true);
            return;
        }

        // Hard rule: nothing below the floor level
        if (by < PLOT_FLOOR_Y) {
            event.setCancelled(true);
            return;
        }

        // Hard rule: must be inside inner XZ boundary
        if (bx < plot.getInnerMinX() || bx > plot.getInnerMaxX()
         || bz < plot.getInnerMinZ() || bz > plot.getInnerMaxZ()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    Component.text("You cannot break blocks outside your plot!", NamedTextColor.RED));
        }
    }

    // ── Block place ───────────────────────────────────────────────────────────

    /**
     * Cancels block placements outside the inner XZ boundary or below the floor.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (gameManager.getCurrentState() != GameState.BUILDING) return;

        Plot plot = plugin.getPlotManager().getPlot(event.getPlayer());
        if (plot == null) { event.setCancelled(true); return; }

        int bx = event.getBlock().getX();
        int by = event.getBlock().getY();
        int bz = event.getBlock().getZ();

        if (by < PLOT_FLOOR_Y
         || bx < plot.getInnerMinX() || bx > plot.getInnerMaxX()
         || bz < plot.getInnerMinZ() || bz > plot.getInnerMaxZ()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    Component.text("You cannot build outside your plot!", NamedTextColor.RED));
        }
    }

    // ── Movement ──────────────────────────────────────────────────────────────

    /**
     * Central movement guard — handles three states:
     *
     * BUILDING: Creative players cannot fly below the floor or outside
     *           their inner plot XZ bounds. Catches floor-phase exploits.
     *
     * VOTING:   Spectators are confined to the TOTAL footprint (inner + iron
     *           wall region) of the plot being voted on, and cannot fly below
     *           the floor or above the ceiling.
     *
     * LOBBY:    Prevent falling out of the lobby box.
     *
     * Performance: We early-exit if the player hasn't crossed a block boundary.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to   = event.getTo();

        // Skip head-rotation-only moves (no position change)
        if (from.getBlockX() == to.getBlockX()
         && from.getBlockY() == to.getBlockY()
         && from.getBlockZ() == to.getBlockZ()) return;

        GameState state = gameManager.getCurrentState();

        // ── BUILDING: confine creative players to their plot ──────────────────
        if (state == GameState.BUILDING) {
            Plot plot = plugin.getPlotManager().getPlot(event.getPlayer());
            if (plot == null) return;

            int bx = to.getBlockX();
            int by = to.getBlockY();
            int bz = to.getBlockZ();

            boolean outsideXZ = bx < plot.getInnerMinX() || bx > plot.getInnerMaxX()
                              || bz < plot.getInnerMinZ() || bz > plot.getInnerMaxZ();
            boolean belowFloor = by < PLOT_FLOOR_Y;
            boolean aboveCeil  = by > PLOT_CEIL_Y;

            if (outsideXZ || belowFloor || aboveCeil) {
                event.setCancelled(true);
                // Snap back: keep their yaw/pitch but return to a safe position
                Location safe = plot.getCentreLocation(plugin.getPlotManager().getWorld());
                safe.setYaw(to.getYaw());
                safe.setPitch(to.getPitch());
                event.getPlayer().teleport(safe);
            }
            return;
        }

        // ── VOTING: confine spectators to the current voting plot ─────────────
        if (state == GameState.VOTING) {
            Plot votingPlot = gameManager.getCurrentVotingPlot();
            if (votingPlot == null) return;

            int bx = to.getBlockX();
            int by = to.getBlockY();
            int bz = to.getBlockZ();

            // Use TOTAL footprint so spectators can see the walls too
            boolean outsideXZ = bx < votingPlot.getTotalMinX() || bx > votingPlot.getTotalMaxX()
                              || bz < votingPlot.getTotalMinZ() || bz > votingPlot.getTotalMaxZ();
            boolean belowFloor = by < PLOT_FLOOR_Y - 5;  // small buffer below floor for viewing
            boolean aboveCeil  = by > PLOT_CEIL_Y + 10;

            if (outsideXZ || belowFloor || aboveCeil) {
                event.setCancelled(true);
                Location safe = votingPlot.getCentreLocation(plugin.getPlotManager().getWorld());
                safe.add(0, 5, 0);
                safe.setYaw(to.getYaw());
                safe.setPitch(to.getPitch());
                event.getPlayer().teleport(safe);
            }
            return;
        }

        // ── LOBBY: prevent falling out of the barrier box ─────────────────────
        if (state == GameState.LOBBY) {
            if (to.getY() < 60) {
                event.setCancelled(true);
                event.getPlayer().teleport(plugin.getLobbySpawn());
            }
        }
    }
}
