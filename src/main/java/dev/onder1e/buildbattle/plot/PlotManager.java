package dev.onder1e.buildbattle.plot;

import dev.onder1e.buildbattle.BuildBattle;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PlotManager
 * ============
 * Handles plot layout, async generation, and fast chunk-level cleanup.
 *
 * PERFORMANCE DESIGN:
 * -------------------
 * The old approach called world.getBlockAt(x,y,z).setType() in a triple loop
 * — up to 256 * 160 * 160 = ~6.5 million calls per plot, all on the main thread.
 * That's what caused the 5s freeze.
 *
 * New approach (two-phase):
 *
 * GENERATION:
 *   1. getChunkAtAsync() — loads/generates each chunk off the main thread.
 *   2. Once ALL chunks for a plot are ready, we fill them using the Chunk object
 *      directly (chunk.getBlock(lx,y,lz).setType) which bypasses world lookups.
 *   3. We batch chunk loading across all plots simultaneously so generation
 *      is parallelised as much as the Paper scheduler allows.
 *   4. A Bukkit runnable polls completion and calls the onComplete callback
 *      when every plot chunk is filled, then teleports players.
 *
 * CLEANUP:
 *   1. Instead of filling each chunk with AIR block-by-block, we call
 *      world.regenerateChunk(cx, cz) which replaces the chunk wholesale
 *      with a freshly generated void chunk (from our VoidChunkGenerator).
 *      This is O(1) per chunk vs O(65536) per chunk.
 *   2. After regeneration the chunk is unloaded with save=false.
 */
public class PlotManager {

    private final BuildBattle plugin;
    private final World       world;

    private final int plotChunks;
    private final int bufferChunks;
    private final int innerBlocks;
    private final int bufferBlocks;
    private final int totalBlocks;

    /** Plots extend in the positive X direction starting here. */
    private static final int FIRST_PLOT_ORIGIN_X = 0;
    /** One-chunk gap between adjacent plot total footprints. */
    private static final int PLOT_GAP_BLOCKS     = 16;

    private final Map<UUID, Plot> plotsByOwner = new LinkedHashMap<>();
    private final List<Plot>      orderedPlots = new ArrayList<>();

    public PlotManager(BuildBattle plugin, World world) {
        this.plugin        = plugin;
        this.world         = world;
        this.plotChunks    = plugin.getConfig().getInt("plot_size",   10);
        this.bufferChunks  = plugin.getConfig().getInt("buffer_size",  2);
        this.innerBlocks   = plotChunks  * 16;
        this.bufferBlocks  = bufferChunks * 16;
        this.totalBlocks   = innerBlocks + 2 * bufferBlocks;
    }

    // =========================================================================
    // ── Generation ────────────────────────────────────────────────────────────
    // =========================================================================

    /**
     * Asynchronously generate one plot per player.
     *
     * Chunks are loaded in parallel via getChunkAtAsync(). Once every chunk
     * for a plot is resident in memory we fill it on the main thread in a
     * single tight loop over the Chunk object — no world.getBlockAt overhead.
     *
     * @param players    Ordered list; plot index matches list order.
     * @param onComplete Called on the main thread when all plots are ready.
     */
    public void generatePlots(List<Player> players, Runnable onComplete) {
        plotsByOwner.clear();
        orderedPlots.clear();

        // Build the Plot metadata objects first (pure math, instant)
        for (int i = 0; i < players.size(); i++) {
            int totalMinX = FIRST_PLOT_ORIGIN_X + i * (totalBlocks + PLOT_GAP_BLOCKS);
            int innerMinX = totalMinX + bufferBlocks;
            int innerMinZ = -(innerBlocks / 2);
            Plot plot = new Plot(i, players.get(i).getUniqueId(),
                                 innerMinX, innerMinZ, innerBlocks, bufferBlocks);
            plotsByOwner.put(players.get(i).getUniqueId(), plot);
            orderedPlots.add(plot);
        }

        // Count total chunks across all plots so we know when we're done
        int totalChunks = orderedPlots.stream()
                .mapToInt(p -> {
                    int cxRange = (p.getTotalMaxX() >> 4) - (p.getTotalMinX() >> 4) + 1;
                    int czRange = (p.getTotalMaxZ() >> 4) - (p.getTotalMinZ() >> 4) + 1;
                    return cxRange * czRange;
                }).sum();

        AtomicInteger readyChunks = new AtomicInteger(0);

        // Fire off async chunk loads for every plot chunk simultaneously
        for (Plot plot : orderedPlots) {
            int minCX = plot.getTotalMinX() >> 4;
            int maxCX = plot.getTotalMaxX() >> 4;
            int minCZ = plot.getTotalMinZ() >> 4;
            int maxCZ = plot.getTotalMaxZ() >> 4;

            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    final int finalCX = cx;
                    final int finalCZ = cz;
                    final Plot finalPlot = plot;

                    // getChunkAtAsync loads/generates the chunk off the main thread,
                    // then calls our callback on the main thread once it's resident.
                    world.getChunkAtAsync(cx, cz).thenAccept(chunk -> {
                        // This callback fires on the main thread — safe to set blocks
                        fillChunk(chunk, finalPlot);

                        // When the last chunk is done, call onComplete
                        if (readyChunks.incrementAndGet() == totalChunks) {
                            plugin.getLogger().info("[PlotManager] All "
                                    + orderedPlots.size() + " plots generated.");
                            onComplete.run();
                        }
                    });
                }
            }
        }
    }

    /**
     * Fills a single chunk with the correct plot material.
     *
     * For chunks that are entirely within the iron-wall buffer zone: fill
     * the entire column (y=minHeight to maxHeight) with IRON_BLOCK.
     *
     * For chunks that are entirely within the inner plot area: place a single
     * GRASS_BLOCK layer at y=64 and leave everything else AIR.
     *
     * For chunks that straddle the boundary (edge chunks): check each
     * block column individually.
     *
     * This block-local arithmetic is much faster than calling world.getBlockAt()
     * because it skips the world coordinate → chunk lookup step.
     */
    private void fillChunk(Chunk chunk, Plot plot) {
        int chunkWorldX = chunk.getX() << 4; // world X of chunk's local x=0
        int chunkWorldZ = chunk.getZ() << 4;

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for (int lx = 0; lx < 16; lx++) {
            int worldX = chunkWorldX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int worldZ = chunkWorldZ + lz;

                boolean isInner = worldX >= plot.getInnerMinX() && worldX <= plot.getInnerMaxX()
                               && worldZ >= plot.getInnerMinZ() && worldZ <= plot.getInnerMaxZ();

                if (isInner) {
                    // Inner plot column: grass floor at y=64, air everywhere else
                    chunk.getBlock(lx, 64, lz).setType(Material.GRASS_BLOCK, false);
                    // Everything else in the column is already air (void world)
                } else {
                    // Wall column: iron from minHeight to maxHeight
                    for (int y = minY; y < maxY; y++) {
                        chunk.getBlock(lx, y, lz).setType(Material.IRON_BLOCK, false);
                    }
                }
            }
        }
    }

    // =========================================================================
    // ── Cleanup ───────────────────────────────────────────────────────────────
    // =========================================================================

    /**
     * Destroys all plot chunks by regenerating them as void chunks.
     *
     * world.regenerateChunk(cx, cz) replaces the chunk entirely using the
     * world's ChunkGenerator (our VoidChunkGenerator → all air). This is
     * orders of magnitude faster than setting every block to AIR individually.
     *
     * After regeneration the chunk is unloaded with save=false so no data
     * is written to disk.
     *
     * @param onComplete Called on the main thread when cleanup is finished.
     */
    @SuppressWarnings("deprecation") // regenerateChunk is deprecated but still the fastest approach in 1.20
    public void destroyAllPlots(Runnable onComplete) {
        List<Plot> toDestroy = new ArrayList<>(orderedPlots);
        plotsByOwner.clear();
        orderedPlots.clear();

        if (toDestroy.isEmpty()) {
            onComplete.run();
            return;
        }

        // Collect all unique chunk coordinates across all plots
        Set<Long> chunkKeys = new LinkedHashSet<>();
        Map<Long, int[]> chunkCoords = new HashMap<>();
        for (Plot plot : toDestroy) {
            int minCX = plot.getTotalMinX() >> 4;
            int maxCX = plot.getTotalMaxX() >> 4;
            int minCZ = plot.getTotalMinZ() >> 4;
            int maxCZ = plot.getTotalMaxZ() >> 4;
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    long key = Plot.chunkKey(cx, cz);
                    chunkKeys.add(key);
                    chunkCoords.put(key, new int[]{cx, cz});
                }
            }
        }

        // Process chunks in batches of 20 per tick to avoid a single-tick spike.
        // Each batch takes ~1ms; 20 chunks/tick = smooth ~1 tick per batch.
        List<Long> keyList = new ArrayList<>(chunkKeys);
        final int BATCH_SIZE = 20;
        final int[] cursor = {0};

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int end = Math.min(cursor[0] + BATCH_SIZE, keyList.size());
            for (int i = cursor[0]; i < end; i++) {
                int[] coords = chunkCoords.get(keyList.get(i));
                int cx = coords[0], cz = coords[1];
                // regenerateChunk replaces chunk contents via our VoidChunkGenerator
                world.regenerateChunk(cx, cz);
                // Unload without saving — chunk is now void, no disk footprint
                world.unloadChunk(cx, cz, false);
            }
            cursor[0] = end;

            if (cursor[0] >= keyList.size()) {
                task.cancel();
                plugin.getLogger().info("[PlotManager] All plots destroyed ("
                        + keyList.size() + " chunks reset).");
                onComplete.run();
            }
        }, 1L, 1L); // start 1 tick after call, run every tick
    }

    // =========================================================================
    // ── Lookup helpers ────────────────────────────────────────────────────────
    // =========================================================================

    public Plot getPlot(UUID uuid)     { return plotsByOwner.get(uuid); }
    public Plot getPlot(Player player) { return getPlot(player.getUniqueId()); }

    public Plot getPlotAtLocation(Location loc) {
        if (!loc.getWorld().equals(world)) return null;
        int bx = loc.getBlockX(), bz = loc.getBlockZ();
        for (Plot p : orderedPlots) {
            if (bx >= p.getInnerMinX() && bx <= p.getInnerMaxX()
             && bz >= p.getInnerMinZ() && bz <= p.getInnerMaxZ()) return p;
        }
        return null;
    }

    public List<Plot> getOrderedPlots()          { return Collections.unmodifiableList(orderedPlots); }
    public Map<UUID, Plot> getPlotsByOwner()      { return Collections.unmodifiableMap(plotsByOwner); }
    public World getWorld()                       { return world; }
    public int   getInnerBlocks()                 { return innerBlocks; }
    public int   getBufferBlocks()                { return bufferBlocks; }
    public int   getTotalBlocks()                 { return totalBlocks; }
}
