package dev.onder1e.buildbattle.plot;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import dev.onder1e.buildbattle.BuildBattle;
import org.bukkit.*;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.logging.Level;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PlotManager
 *
 * WorldGuard integration strategy:
 * ─────────────────────────────────
 * Each inner plot gets a ProtectedCuboidRegion named "buildbattle_plot_<index>".
 * The region covers the full Y range of the inner plot XZ footprint.
 *
 * Flags set on each plot region:
 *   BUILD       ALLOW  - members (the plot owner) can build
 *   BUILD       DENY   - non-members cannot build (region default)
 *
 * The plot owner is added as a MEMBER so WorldGuard allows them to build.
 * All other players are implicitly denied by the region's default BUILD deny.
 *
 * LOBBY protection:
 *   A single "buildbattle_lobby" region is created once in onEnable() covering
 *   the lobby XZ/Y bounds. Flags: BUILD DENY, INTERACT DENY for non-members.
 *   This handles lobby protection without duplicating regions per game.
 *   The lobby region is never removed - it persists for the life of the plugin.
 *
 * Plot regions are created in generatePlots() and removed in destroyAllPlots().
 * WorldGuard persists regions to disk automatically via its own scheduler.
 *
 * IMPORTANT: WorldGuard's RegionManager.save() is called after bulk changes
 * to ensure regions survive a server restart mid-game.
 */
public class PlotManager {

    private final BuildBattle plugin;
    private final World       world;

    private final int plotChunks;
    private final int bufferChunks;
    private final int innerBlocks;
    private final int bufferBlocks;
    private final int totalBlocks;

    private static final int FIRST_PLOT_ORIGIN_X = 0;
    private static final int PLOT_GAP_BLOCKS     = 16;

    /** WorldGuard region name for the lobby - created once, never removed. */
    public static final String LOBBY_REGION_ID = "buildbattle_lobby";
    /** Prefix for per-plot WorldGuard regions. */
    private static final String PLOT_REGION_PREFIX = "buildbattle_plot_";

    private final Map<UUID, Plot> plotsByOwner = new LinkedHashMap<>();
    private final List<Plot>      orderedPlots = new ArrayList<>();

    public PlotManager(BuildBattle plugin, World world) {
        this.plugin       = plugin;
        this.world        = world;
        this.plotChunks   = plugin.getConfig().getInt("plot_size",   10);
        this.bufferChunks = plugin.getConfig().getInt("buffer_size",  2);
        this.innerBlocks  = plotChunks  * 16;
        this.bufferBlocks = bufferChunks * 16;
        this.totalBlocks  = innerBlocks + 2 * bufferBlocks;
    }

    // =========================================================================
    // ── WorldGuard helpers ────────────────────────────────────────────────────
    // =========================================================================

    /**
     * Returns the RegionManager for the game world, or null if WorldGuard is
     * not loaded (should never happen given plugin.yml depend).
     */
    private RegionManager getRegionManager() {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        return container.get(BukkitAdapter.adapt(world));
    }

    /**
     * Creates the permanent lobby WorldGuard region.
     *
     * Called once from BuildBattle.onEnable() AFTER buildLobby() so the bounds
     * are known. If the region already exists (server restart) it is left alone
     * - the existing region already has the correct flags.
     *
     * The lobby region:
     *   - Denies BUILD and INTERACT for everyone (no members)
     *   - Allows FAWE/WorldEdit operations to be blocked by WorldGuard
     *     (WorldGuard's __global__ deny-message handles the WE side)
     *
     * Wool is exempt from the BUILD deny via PlayerListener (wool-only exception
     * for PvP) - WorldGuard's region flag cannot distinguish material types, so
     * the PlayerListener's onBlockBreak wool exception remains in place.
     */
    public void createLobbyRegion(int minX, int minZ, int maxX, int maxZ) {
        RegionManager rm = getRegionManager();
        if (rm == null) return;

        // If region already exists from a previous server session, leave it
        if (rm.hasRegion(LOBBY_REGION_ID)) return;

        BlockVector3 min = BlockVector3.at(minX, world.getMinHeight(), minZ);
        BlockVector3 max = BlockVector3.at(maxX, world.getMaxHeight(), maxZ);

        ProtectedCuboidRegion region = new ProtectedCuboidRegion(LOBBY_REGION_ID, min, max);

        // Deny all building and interaction inside the lobby by default.
        // PvP (entity damage) is allowed - handled by world.setPVP(true).
        region.setFlag(Flags.BUILD,    StateFlag.State.DENY);
        region.setFlag(Flags.INTERACT, StateFlag.State.DENY);

        rm.addRegion(region);
        saveRegions(rm);

        plugin.getLogger().info("[PlotManager] Lobby region created: " + LOBBY_REGION_ID);
    }

    /**
     * Creates a WorldGuard region for a single plot's INNER area.
     *
     * Region name: "buildbattle_plot_<index>"
     * Owner (MEMBER): the plot owner's UUID - WorldGuard allows members to build.
     * Non-members receive BUILD DENY from the region default.
     *
     * The region covers the full Y column of the inner XZ footprint so players
     * cannot WorldEdit above or below their plot either.
     */
    private void createPlotRegion(Plot plot) {
        RegionManager rm = getRegionManager();
        if (rm == null) return;

        String id = PLOT_REGION_PREFIX + plot.getIndex();

        BlockVector3 min = BlockVector3.at(
                plot.getInnerMinX(), world.getMinHeight(), plot.getInnerMinZ());
        BlockVector3 max = BlockVector3.at(
                plot.getInnerMaxX(), world.getMaxHeight(), plot.getInnerMaxZ());

        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id, min, max);

        // Higher priority than __global__ so ALLOW overrides the global DENY
        region.setPriority(10);

        // ALLOW build for members only - non-members still hit __global__ DENY
        region.setFlag(Flags.BUILD, StateFlag.State.ALLOW);

        // Add owner as MEMBER - WorldGuard applies BUILD ALLOW to members
        Player owner = Bukkit.getPlayer(plot.getOwnerUUID());
        if (owner != null) {
            region.getMembers().addPlayer(
                com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst()
                    .wrapPlayer(owner)
            );
        } else {
            // Fallback if somehow offline - UUID only
            region.getMembers().addPlayer(plot.getOwnerUUID());
        }

        rm.addRegion(region);
    }

    public void ensureGlobalFlags() {
        RegionManager rm = getRegionManager();
        if (rm == null) return;

        // __global__ always exists in every WG world - just grab and flag it
        com.sk89q.worldguard.protection.regions.ProtectedRegion global =
                rm.getRegion("__global__");
        if (global == null) return;

        // Only set if not already set - avoids overwriting admin customisations
        if (global.getFlag(Flags.BUILD) == null) {
            global.setFlag(Flags.BUILD, StateFlag.State.DENY);
        }
        if (global.getFlag(Flags.INTERACT) == null) {
            global.setFlag(Flags.INTERACT, StateFlag.State.DENY);
        }

        saveRegions(rm);
        plugin.getLogger().info("[PlotManager] __global__ flags ensured for buildbattle world.");
    }

    /**
     * Removes a single plot's WorldGuard region.
     * Called per-plot during destroyAllPlots().
     */
    private void removePlotRegion(Plot plot) {
        RegionManager rm = getRegionManager();
        if (rm == null) return;
        rm.removeRegion(PLOT_REGION_PREFIX + plot.getIndex());
    }

    /** Saves the region manager to disk asynchronously. */
    private void saveRegions(RegionManager rm) {
        try {
            rm.save();
        } catch (StorageException e) {
            plugin.getLogger().log(Level.WARNING, "[PlotManager] WorldGuard storage error: {0}", e.getMessage());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[PlotManager] Unexpected error during region save:", e);
        }
    }

    // =========================================================================
    // ── Generation ────────────────────────────────────────────────────────────
    // =========================================================================

    public void generatePlots(List<Player> players, Runnable onComplete) {
        plotsByOwner.clear();
        orderedPlots.clear();

        for (int i = 0; i < players.size(); i++) {
            int totalMinX = FIRST_PLOT_ORIGIN_X + i * (totalBlocks + PLOT_GAP_BLOCKS);
            int innerMinX = totalMinX + bufferBlocks;
            int innerMinZ = -(innerBlocks / 2);
            Plot plot = new Plot(i, players.get(i).getUniqueId(),
                                 innerMinX, innerMinZ, innerBlocks, bufferBlocks);
            plotsByOwner.put(players.get(i).getUniqueId(), plot);
            orderedPlots.add(plot);
        }

        int totalChunks = orderedPlots.stream().mapToInt(p -> {
            int cxR = (p.getTotalMaxX() >> 4) - (p.getTotalMinX() >> 4) + 1;
            int czR = (p.getTotalMaxZ() >> 4) - (p.getTotalMinZ() >> 4) + 1;
            return cxR * czR;
        }).sum();

        AtomicInteger readyChunks = new AtomicInteger(0);

        for (Plot plot : orderedPlots) {
            int minCX = plot.getTotalMinX() >> 4;
            int maxCX = plot.getTotalMaxX() >> 4;
            int minCZ = plot.getTotalMinZ() >> 4;
            int maxCZ = plot.getTotalMaxZ() >> 4;

            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    final Plot fp = plot;
                    world.getChunkAtAsync(cx, cz).thenAccept(chunk -> {
                        fillChunk(chunk, fp);
                        if (readyChunks.incrementAndGet() == totalChunks) {
                            // All chunks filled - create WorldGuard regions
                            for (Plot p : orderedPlots) {
                                createPlotRegion(p);
                            }
                            // Bulk save once after all regions are registered
                            RegionManager rm = getRegionManager();
                            if (rm != null) saveRegions(rm);

                            plugin.getLogger().info(() -> "[PlotManager] "
                                    + orderedPlots.size() + " plots generated and protected.");
                            onComplete.run();
                        }
                    });
                }
            }
        }
    }

    private void fillChunk(Chunk chunk, Plot plot) {
        int cwx = chunk.getX() << 4;
        int cwz = chunk.getZ() << 4;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        int iMinX = plot.getInnerMinX(), iMaxX = plot.getInnerMaxX();
        int iMinZ = plot.getInnerMinZ(), iMaxZ = plot.getInnerMaxZ();
        int tMinX = plot.getTotalMinX(), tMaxX = plot.getTotalMaxX();
        int tMinZ = plot.getTotalMinZ(), tMaxZ = plot.getTotalMaxZ();

        for (int lx = 0; lx < 16; lx++) {
            int wx = cwx + lx;
            if (wx < tMinX || wx > tMaxX) continue;

            for (int lz = 0; lz < 16; lz++) {
                int wz = cwz + lz;
                if (wz < tMinZ || wz > tMaxZ) continue;

                boolean isInner = wx >= iMinX && wx <= iMaxX
                               && wz >= iMinZ && wz <= iMaxZ;

                if (isInner) {
                    chunk.getBlock(lx, 64, lz).setType(Material.GRASS_BLOCK, false);
                } else {
                    boolean onOuterFace = wx == tMinX || wx == tMaxX
                                       || wz == tMinZ || wz == tMaxZ;
                    boolean onInnerFace = wx == iMinX - 1 || wx == iMaxX + 1
                                       || wz == iMinZ - 1 || wz == iMaxZ + 1;
                    if (onOuterFace || onInnerFace) {
                        for (int y = minY; y < maxY; y++) {
                            chunk.getBlock(lx, y, lz).setType(Material.IRON_BLOCK, false);
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // ── Cleanup ───────────────────────────────────────────────────────────────
    // =========================================================================

    @SuppressWarnings("deprecation")
    public void destroyAllPlots(Runnable onComplete) {
        List<Plot> toDestroy = new ArrayList<>(orderedPlots);
        plotsByOwner.clear();
        orderedPlots.clear();

        if (toDestroy.isEmpty()) {
            onComplete.run();
            return;
        }

        // Remove WorldGuard regions immediately (no need to wait for chunks)
        for (Plot plot : toDestroy) {
            removePlotRegion(plot);
        }
        RegionManager rm = getRegionManager();
        if (rm != null) saveRegions(rm);

        Map<Long, int[]> chunkCoords = new LinkedHashMap<>();
        for (Plot plot : toDestroy) {
            for (int cx = plot.getTotalMinX() >> 4; cx <= plot.getTotalMaxX() >> 4; cx++) {
                for (int cz = plot.getTotalMinZ() >> 4; cz <= plot.getTotalMaxZ() >> 4; cz++) {
                    chunkCoords.putIfAbsent(Plot.chunkKey(cx, cz), new int[]{cx, cz});
                }
            }
        }

        List<int[]> coordList = new ArrayList<>(chunkCoords.values());
        File worldFolder = world.getWorldFolder();

        Set<Long> plotChunkKeys = new HashSet<>(chunkCoords.keySet());
        Set<Long> deletableRegions = new HashSet<>();
        Map<Long, List<int[]>> regionToChunks = new HashMap<>();
        for (int[] coord : coordList) {
            int rx = coord[0] >> 5, rz = coord[1] >> 5;
            long rk = Plot.chunkKey(rx, rz);
            regionToChunks.computeIfAbsent(rk, k -> new ArrayList<>()).add(coord);
        }
        for (Map.Entry<Long, List<int[]>> e : regionToChunks.entrySet()) {
            long rk = e.getKey();
            int rx = (int)(rk & 0xFFFFFFFFL);
            int rz = (int)(rk >> 32);
            boolean full = true;
            for (int dx = 0; dx < 32 && full; dx++)
                for (int dz = 0; dz < 32 && full; dz++)
                    if (!plotChunkKeys.contains(Plot.chunkKey(rx * 32 + dx, rz * 32 + dz)))
                        full = false;
            if (full) deletableRegions.add(rk);
        }

        final int BATCH = 10;
        final int[] cursor = {0};

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int end = Math.min(cursor[0] + BATCH, coordList.size());
            for (int i = cursor[0]; i < end; i++) {
                int cx = coordList.get(i)[0];
                int cz = coordList.get(i)[1];
                if (!world.isChunkLoaded(cx, cz)) world.loadChunk(cx, cz, true);
                world.regenerateChunk(cx, cz);
                world.unloadChunk(cx, cz, false);
            }
            cursor[0] = end;

            if (cursor[0] >= coordList.size()) {
                task.cancel();

                int[] deleted = {0};
                for (long rk : deletableRegions) {
                    int rx = (int)(rk & 0xFFFFFFFFL);
                    int rz = (int)(rk >> 32);
                    File mca = new File(worldFolder, "region/r." + rx + "." + rz + ".mca");
                    if (mca.exists() && mca.delete()) deleted[0]++;
                }

                final int chunkCount = coordList.size();
                final int delCount   = deleted[0];
                plugin.getLogger().info(() -> "[PlotManager] Cleanup done: "
                        + chunkCount + " chunks cleared, "
                        + delCount + " region files deleted.");
                onComplete.run();
            }
        }, 1L, 1L);
    }

    @SuppressWarnings("deprecation")
    public void destroySinglePlot(Plot plot) {
        if (plot == null) return;

        // 1. Remove WorldGuard regions immediately
        removePlotRegion(plot);
        RegionManager rm = getRegionManager();
        if (rm != null) saveRegions(rm);

        // 2. Identify and regenerate the chunks for this specific plot
        int minX = plot.getTotalMinX() >> 4;
        int maxX = plot.getTotalMaxX() >> 4;
        int minZ = plot.getTotalMinZ() >> 4;
        int maxZ = plot.getTotalMaxZ() >> 4;

        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                // Load if necessary, regenerate, then unload to save memory
                if (!world.isChunkLoaded(cx, cz)) {
                    world.loadChunk(cx, cz, true);
                }
                world.regenerateChunk(cx, cz);
                world.unloadChunk(cx, cz, false);
            }
        }

        // 3. Remove from tracking maps so destroyAllPlots() skips it later
        plotsByOwner.remove(plot.getOwnerUUID());
        orderedPlots.remove(plot);

        plugin.getLogger().info(() -> "[PlotManager] Single plot destroyed for owner: " + plot.getOwnerUUID());
    }

    public void safeErasePlots(Runnable onComplete) {
        List<Plot> toErase = new ArrayList<>(orderedPlots);
        plotsByOwner.clear();
        orderedPlots.clear();

        if (toErase.isEmpty()) {
            plugin.getLogger().info("[PlotManager] safeErase: no plots to erase.");
            onComplete.run();
            return;
        }

        for (Plot plot : toErase) removePlotRegion(plot);
        RegionManager rm = getRegionManager();
        if (rm != null) saveRegions(rm);

        Map<Long, int[]> chunkCoords = new LinkedHashMap<>();
        for (Plot plot : toErase) {
            for (int cx = plot.getTotalMinX() >> 4; cx <= plot.getTotalMaxX() >> 4; cx++) {
                for (int cz = plot.getTotalMinZ() >> 4; cz <= plot.getTotalMaxZ() >> 4; cz++) {
                    chunkCoords.putIfAbsent(Plot.chunkKey(cx, cz), new int[]{cx, cz});
                }
            }
        }

        List<int[]> coordList = new ArrayList<>(chunkCoords.values());
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        final int BATCH = 2;
        final int[] cursor = {0};

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int end = Math.min(cursor[0] + BATCH, coordList.size());
            for (int i = cursor[0]; i < end; i++) {
                int cx = coordList.get(i)[0];
                int cz = coordList.get(i)[1];
                if (!world.isChunkLoaded(cx, cz)) world.loadChunk(cx, cz, true);
                Chunk chunk = world.getChunkAt(cx, cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int y = minY; y < maxY; y++) {
                            if (chunk.getBlock(lx, y, lz).getType() != Material.AIR) {
                                chunk.getBlock(lx, y, lz).setType(Material.AIR, false);
                            }
                        }
                    }
                }
                world.unloadChunk(cx, cz, true);
            }
            cursor[0] = end;

            if (cursor[0] >= coordList.size()) {
                task.cancel();
                final int count = coordList.size();
                plugin.getLogger().info(() -> "[PlotManager] safeErase complete: "
                        + count + " chunks cleared.");
                onComplete.run();
            }
        }, 1L, 1L);
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    public Plot getPlot(UUID uuid)      { return plotsByOwner.get(uuid); }
    public Plot getPlot(Player player)  { return getPlot(player.getUniqueId()); }

    public Plot getPlotAtLocation(Location loc) {
        if (!loc.getWorld().equals(world)) return null;
        int bx = loc.getBlockX(), bz = loc.getBlockZ();
        for (Plot p : orderedPlots) {
            if (bx >= p.getInnerMinX() && bx <= p.getInnerMaxX()
             && bz >= p.getInnerMinZ() && bz <= p.getInnerMaxZ()) return p;
        }
        return null;
    }

    public List<Plot> getOrderedPlots()      { return Collections.unmodifiableList(orderedPlots); }
    public Map<UUID, Plot> getPlotsByOwner() { return Collections.unmodifiableMap(plotsByOwner); }
    public World getWorld()                  { return world; }
    public int   getInnerBlocks()            { return innerBlocks; }
    public int   getBufferBlocks()           { return bufferBlocks; }
    public int   getTotalBlocks()            { return totalBlocks; }
}
