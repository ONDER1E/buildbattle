package dev.onder1e.buildbattle.plot;

import dev.onder1e.buildbattle.BuildBattle;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * PlotManager
 * ============
 * Responsible for all spatial and block-level plot management:
 *
 *  1. Calculating X-axis offsets for each plot so they never overlap.
 *  2. Programmatically generating each plot's terrain (grass floor + iron walls).
 *  3. Cleaning up (deleting) plots when the game resets.
 *
 * COORDINATE SYSTEM:
 *  - Plots are generated in the POSITIVE X direction.
 *  - Each plot's TOTAL footprint = innerSize + 2*bufferSize blocks wide on X and Z.
 *  - A small gap (one full plot width) is left between plots for air isolation.
 *  - Plot 0 starts after the lobby exclusion zone (x ≥ 0).
 *
 * PLOT LAYOUT (top-down per plot):
 *
 *   [IRON WALL - bufferSize chunks wide]
 *   [IRON WALL][    GRASS FLOOR (10×10 chunks)    ][IRON WALL]
 *   [IRON WALL - bufferSize chunks wide]
 */
public class PlotManager {

    private final BuildBattle plugin;
    private final World world;

    /** Config-driven sizes. */
    private final int plotChunks;   // e.g. 10
    private final int bufferChunks; // e.g. 2

    /** Derived block sizes. */
    private final int innerBlocks;  // plotChunks  * 16 = 160
    private final int bufferBlocks; // bufferChunks * 16 = 32
    private final int totalBlocks;  // innerBlocks + 2 * bufferBlocks = 224

    /**
     * The first X coordinate (block level) where Plot 0's TOTAL footprint begins.
     * We start well clear of the lobby (which sits at negative X).
     */
    private static final int FIRST_PLOT_ORIGIN_X = 0;

    /** Active plots, keyed by owner UUID. */
    private final Map<UUID, Plot> plotsByOwner = new LinkedHashMap<>();

    /** Ordered list of plots (index 0 = first generated). */
    private final List<Plot> orderedPlots = new ArrayList<>();

    public PlotManager(BuildBattle plugin, World world) {
        this.plugin       = plugin;
        this.world        = world;

        this.plotChunks   = plugin.getConfig().getInt("plot_size",   10);
        this.bufferChunks = plugin.getConfig().getInt("buffer_size",  2);

        this.innerBlocks  = plotChunks  * 16;
        this.bufferBlocks = bufferChunks * 16;
        this.totalBlocks  = innerBlocks + 2 * bufferBlocks;
    }

    // ── Plot generation ───────────────────────────────────────────────────────

    /**
     * Generate one plot per player in the provided (ordered) list.
     *
     * Each plot is placed at:
     *   totalMinX = FIRST_PLOT_ORIGIN_X + index * (totalBlocks + GAP)
     *
     * Generation is done synchronously on the main thread using Paper's
     * chunk-loading API to ensure chunks exist before we place blocks.
     *
     * @param players Ordered list of players who need plots.
     */
    public void generatePlots(List<Player> players) {
        plotsByOwner.clear();
        orderedPlots.clear();

        // Gap between plots = one chunk on each side so the iron walls of
        // adjacent plots do not touch.
        int gap = 16; // 1 chunk gap

        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);

            // Calculate where this plot's inner area starts (in blocks)
            int totalMinX = FIRST_PLOT_ORIGIN_X + i * (totalBlocks + gap);
            int innerMinX = totalMinX + bufferBlocks;

            // Z is centred around 0 for all plots
            int innerMinZ = -(innerBlocks / 2);

            Plot plot = new Plot(i, player.getUniqueId(),
                                 innerMinX, innerMinZ,
                                 innerBlocks, bufferBlocks);

            plotsByOwner.put(player.getUniqueId(), plot);
            orderedPlots.add(plot);

            // Physically build the plot terrain
            buildPlot(plot);
        }

        plugin.getLogger().info("[PlotManager] Generated " + players.size() + " plots.");
    }

    /**
     * Fills a single plot with its grass floor and iron-block walls.
     *
     * Floor:  y=64, inner area only, Material.GRASS_BLOCK
     * Walls:  y=0–255, bufferBlocks wide on all four sides, Material.IRON_BLOCK
     *         Also covers the corners (diagonal wall sections).
     */
    private void buildPlot(Plot plot) {
        int totalMinX = plot.getTotalMinX();
        int totalMaxX = plot.getTotalMaxX();
        int totalMinZ = plot.getTotalMinZ();
        int totalMaxZ = plot.getTotalMaxZ();
        int innerMinX = plot.getInnerMinX();
        int innerMaxX = plot.getInnerMaxX();
        int innerMinZ = plot.getInnerMinZ();
        int innerMaxZ = plot.getInnerMaxZ();

        // Ensure all required chunks are loaded first
        preloadChunks(totalMinX >> 4, totalMaxX >> 4,
                      totalMinZ >> 4, totalMaxZ >> 4);

        // ── Place grass floor at y=64 (inner area only) ────────────────────
        for (int x = innerMinX; x <= innerMaxX; x++) {
            for (int z = innerMinZ; z <= innerMaxZ; z++) {
                world.getBlockAt(x, 64, z).setType(Material.GRASS_BLOCK, false);
            }
        }

        // ── Place iron walls (y=0 to y=255) ───────────────────────────────
        // We iterate over the TOTAL footprint and place iron wherever we are
        // NOT in the inner area.
        for (int x = totalMinX; x <= totalMaxX; x++) {
            for (int z = totalMinZ; z <= totalMaxZ; z++) {
                boolean isInner = (x >= innerMinX && x <= innerMaxX
                                && z >= innerMinZ && z <= innerMaxZ);
                if (!isInner) {
                    // This column is part of the iron wall
                    for (int y = 0; y <= 255; y++) {
                        world.getBlockAt(x, y, z).setType(Material.IRON_BLOCK, false);
                    }
                }
            }
        }
    }

    /**
     * Synchronously load (or generate) all chunks in the given chunk coordinate
     * range so that setType calls don't drop into unloaded chunk space.
     */
    private void preloadChunks(int minCX, int maxCX, int minCZ, int maxCZ) {
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    world.loadChunk(cx, cz, true);
                }
            }
        }
    }

    // ── Plot cleanup ──────────────────────────────────────────────────────────

    /**
     * Deletes all plot chunks from disk and memory.
     *
     * Called during the RESET state to prevent world bloat.
     * Iterates over every chunk in each plot's total footprint and
     * forces it to be unloaded and deleted.
     */
    public void destroyAllPlots() {
        for (Plot plot : orderedPlots) {
            destroyPlot(plot);
        }
        plotsByOwner.clear();
        orderedPlots.clear();
        plugin.getLogger().info("[PlotManager] All plots destroyed.");
    }

    /**
     * Regenerates a single plot's chunk range as void (air), then unloads it.
     * This effectively "deletes" the plot without touching the region file
     * infrastructure — the chunk will simply be re-generated as void next time.
     */
    private void destroyPlot(Plot plot) {
        int minCX = plot.getTotalMinX() >> 4;
        int maxCX = plot.getTotalMaxX() >> 4;
        int minCZ = plot.getTotalMinZ() >> 4;
        int maxCZ = plot.getTotalMaxZ() >> 4;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (world.isChunkLoaded(cx, cz)) {
                    Chunk chunk = world.getChunkAt(cx, cz);

                    // Fill every block in the chunk with air to wipe the build
                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                                Block block = chunk.getBlock(lx, y, lz);
                                if (block.getType() != Material.AIR) {
                                    block.setType(Material.AIR, false);
                                }
                            }
                        }
                    }

                    // Unload and delete the chunk from disk
                    world.unloadChunk(cx, cz, false); // false = do NOT save to disk
                }
            }
        }
    }

    // ── Lookup helpers ────────────────────────────────────────────────────────

    /** Get a player's plot, or null if they have none. */
    public Plot getPlot(UUID playerUUID) {
        return plotsByOwner.get(playerUUID);
    }

    /** Get a player's plot, or null. */
    public Plot getPlot(Player player) {
        return getPlot(player.getUniqueId());
    }

    /**
     * Determine which plot (if any) a Location falls inside the INNER area of.
     * Used for WorldEdit mask enforcement.
     */
    public Plot getPlotAtLocation(Location loc) {
        if (!loc.getWorld().equals(world)) return null;
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        for (Plot plot : orderedPlots) {
            if (bx >= plot.getInnerMinX() && bx <= plot.getInnerMaxX()
             && bz >= plot.getInnerMinZ() && bz <= plot.getInnerMaxZ()) {
                return plot;
            }
        }
        return null;
    }

    /** Ordered list of all active plots. */
    public List<Plot> getOrderedPlots() { return Collections.unmodifiableList(orderedPlots); }

    /** All active plots keyed by owner UUID. */
    public Map<UUID, Plot> getPlotsByOwner() { return Collections.unmodifiableMap(plotsByOwner); }

    public World getWorld() { return world; }
    public int getInnerBlocks()  { return innerBlocks; }
    public int getBufferBlocks() { return bufferBlocks; }
    public int getTotalBlocks()  { return totalBlocks; }
}
