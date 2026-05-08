package dev.onder1e.buildbattle.listener;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.game.GameManager;
import dev.onder1e.buildbattle.game.GameState;
import dev.onder1e.buildbattle.plot.Plot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * PlayerListener
 *
 * Join/Quit:
 *   All routing logic lives in GameManager.handleJoin() and handleQuit() to
 *   keep the state machine self-contained. This class just forwards the events.
 *
 * Block protection (BUILDING):
 *   HIGHEST priority ensures we fire after other plugins.
 *   Three rules: no breaking IRON_BLOCK, no breaking below y=64, no
 *   breaking/placing outside inner XZ plot boundary.
 *
 * Movement (BUILDING):
 *   Authoritative boundary check. Creative players can fly under the grass
 *   floor or through walls before BlockBreakEvent fires. We catch the
 *   position directly and teleport them back.
 *
 * Movement (VOTING):
 *   Spectators are confined to the INNER plot boundary (not total footprint).
 *   This means they can see and fly around inside the build area (160x160)
 *   but cannot exit through the iron walls into the buffer zone.
 *   lobbyWaiters (late joiners) are NOT teleported to plots and are exempt
 *   from this confinement — they stay at lobby spawn.
 */
public class PlayerListener implements Listener {

    private final BuildBattle plugin;
    private final GameManager gameManager;

    private static final int PLOT_FLOOR_Y = 64;
    private static final int PLOT_CEIL_Y  = 256;

    public PlayerListener(BuildBattle plugin, GameManager gameManager) {
        this.plugin      = plugin;
        this.gameManager = gameManager;
    }

    // ── Join / Quit ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        // All routing logic (state-aware) is in GameManager
        gameManager.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        gameManager.handleQuit(event.getPlayer());
    }

    // ── Block break ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Material type = event.getBlock().getType();

        if (plugin.isInsideLobby(event.getBlock().getLocation())) {
            if (!type.name().endsWith("_WOOL")) {
                event.setCancelled(true);
            }
            return; 
        }

        if (type == Material.BARRIER) {
            event.setCancelled(true);
            return;
        }

        if (gameManager.getCurrentState() != GameState.BUILDING) return;

        Plot plot = plugin.getPlotManager().getPlot(event.getPlayer());
        if (plot == null) { event.setCancelled(true); return; }

        int bx = event.getBlock().getX();
        int by = event.getBlock().getY();
        int bz = event.getBlock().getZ();

        // Iron walls are indestructible
        if (event.getBlock().getType() == Material.IRON_BLOCK) {
            event.setCancelled(true);
            return;
        }

        // Cannot dig below the grass floor
        if (by < PLOT_FLOOR_Y) {
            event.setCancelled(true);
            return;
        }

        // Must be within inner XZ boundary
        if (bx < plot.getInnerMinX() || bx > plot.getInnerMaxX()
         || bz < plot.getInnerMinZ() || bz > plot.getInnerMaxZ()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    Component.text("You cannot break blocks outside your plot!", NamedTextColor.RED));
        }
    }

    // ── Block place ───────────────────────────────────────────────────────────

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

    // ── Respawn ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        GameState state = gameManager.getCurrentState();

        Location respawnLoc = plugin.getLobbySpawn();

        if (state == GameState.BUILDING) {
            Plot plot = plugin.getPlotManager().getPlot(player);
            if (plot != null) {
                respawnLoc = plot.getCentreLocation(plugin.getPlotManager().getWorld());
            }
        }
        
        event.setRespawnLocation(respawnLoc);
    }

    // ── Movement ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to   = event.getTo();

        // Skip pure head-rotation (no block position change)
        if (from.getBlockX() == to.getBlockX()
         && from.getBlockY() == to.getBlockY()
         && from.getBlockZ() == to.getBlockZ()) return;

        GameState state = gameManager.getCurrentState();

        // ── BUILDING: confine creative players to their inner plot ────────────
        if (state == GameState.BUILDING) {
            Plot plot = plugin.getPlotManager().getPlot(event.getPlayer());
            if (plot == null) return;

            boolean outsideXZ = to.getBlockX() < plot.getInnerMinX()
                             || to.getBlockX() > plot.getInnerMaxX()
                             || to.getBlockZ() < plot.getInnerMinZ()
                             || to.getBlockZ() > plot.getInnerMaxZ();
            boolean belowFloor = to.getBlockY() < PLOT_FLOOR_Y;
            boolean aboveCeil  = to.getBlockY() > PLOT_CEIL_Y;

            if (outsideXZ || belowFloor || aboveCeil) {
                event.setCancelled(true);
                Location safe = plot.getCentreLocation(plugin.getPlotManager().getWorld());
                safe.setYaw(to.getYaw());
                safe.setPitch(to.getPitch());
                event.getPlayer().teleport(safe);
            }
            return;
        }

        // ── VOTING: confine to the INNER boundary of the current voting plot ──
        //
        // We use INNER (not total) so spectators can freely explore the 160x160
        // build area and view every block — they just can't exit through the
        // iron walls into the buffer zone or another plot's space.
        //
        // lobbyWaiters are exempt — they stay at lobby spawn and are not
        // teleported to voting plots, so no confinement applies to them.
        if (state == GameState.VOTING) {
            Plot votingPlot = gameManager.getCurrentVotingPlot();
            if (votingPlot == null) return;

            // Skip confinement for late-join lobby waiters
            if (gameManager.getLobbyWaiters().contains(event.getPlayer().getUniqueId())) return;

            int bx = to.getBlockX();
            int by = to.getBlockY();
            int bz = to.getBlockZ();

            // Confine to INNER plot XZ (the 160x160 grass build area)
            boolean outsideInner = bx < votingPlot.getInnerMinX()
                                || bx > votingPlot.getInnerMaxX()
                                || bz < votingPlot.getInnerMinZ()
                                || bz > votingPlot.getInnerMaxZ();

            // Allow a generous Y range so spectators can zoom out and see the build
            boolean belowFloor = by < PLOT_FLOOR_Y - 10;
            boolean aboveCeil  = by > PLOT_CEIL_Y + 20;

            if (outsideInner || belowFloor || aboveCeil) {
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
