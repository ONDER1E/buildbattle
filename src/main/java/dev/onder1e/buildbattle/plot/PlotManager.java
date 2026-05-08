package dev.onder1e.buildbattle.plot;

import dev.onder1e.buildbattle.BuildBattle;
import org.bukkit.*;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PlotManager â€” plot layout, async generation, fast chunk cleanup.
 *
 * GENERATION OPTIMISATIONS (v1.0.2):
 * ─────────────────────────────────────
 * 1. Hollow iron walls â€” only the 1-block-thick surface shell of the buffer
 *    zone is filled with IRON_BLOCK. The interior of the wall is left as air.
 *    This reduces block-set operations on edge chunks significantly and keeps
 *    the wall visually solid (players see the surface, not the interior).
 *
 * 2. Async chunk loading â€” getChunkAtAsync() fires all chunk loads in parallel.
 *    fillChunk() runs on the main thread callback (safe) using direct Chunk
 *    object access (no world.getBlockAt overhead).
 *
 * 3. Early-exit column classification â€” per column we determine in O(1)
 *    whether it is: inner (grass only), outer air (skip entirely), or wall
 *    surface (fill selectively). This avoids any block writes for air columns.
 *
 * CLEANUP OPTIMISATION (v1.0.2):
 * ─────────────────────────────────
 * world.regenerateChunk() is REMOVED. It runs the full terrain pipeline
 * (biome sampling, noise, decoration) even for a void generator â€” wasteful.
 *
 * New approach:
 *   1. Delete the region file entry for each chunk using the Anvil region
 *      file directly (set chunk offset to 0 in the .mca header â†’ chunk
 *      treated as ungenerated on next load).
 *   2. Unload the chunk from memory with save=false.
 *
 * Result: next time the chunk loads it is generated fresh by VoidChunkGenerator
 * (all air) with zero disk I/O beyond the header write. Batched at 30
 * chunks/tick so the operation is lag-free.
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
                            plugin.getLogger().info(() -> "[PlotManager] "
                                    + orderedPlots.size() + " plots generated.");
                            onComplete.run();
                        }
                    });
                }
            }
        }
    }

    /**
     * Fills one chunk with the correct materials for its plot zone.
     *
     * Column classification (all O(1)):
     *
     *  INNER  â€” worldX/Z inside inner plot bounds â†’ grass at y=64 only.
     *
     *  WALL SURFACE â€” worldX/Z is on the outermost or innermost shell of the
     *                 buffer zone â†’ iron from minY to maxY.
     *                 "Surface" means: the column touches the total boundary
     *                 (outer face) OR touches the inner plot edge (inner face).
     *
     *  WALL INTERIOR â€” worldX/Z is inside the buffer but not on either face
     *                  â†’ leave as air (hollow interior). No block writes needed.
     *
     * This makes walls look solid from the outside and inside while writing
     * far fewer blocks (2 faces Ã- perimeter vs entire buffer volume).
     */
    private void fillChunk(Chunk chunk, Plot plot) {
        int cwx = chunk.getX() << 4;  // chunk's world X origin
        int cwz = chunk.getZ() << 4;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        // Pre-compute boundaries for fast per-column checks
        int iMinX = plot.getInnerMinX(), iMaxX = plot.getInnerMaxX();
        int iMinZ = plot.getInnerMinZ(), iMaxZ = plot.getInnerMaxZ();
        int tMinX = plot.getTotalMinX(), tMaxX = plot.getTotalMaxX();
        int tMinZ = plot.getTotalMinZ(), tMaxZ = plot.getTotalMaxZ();

        for (int lx = 0; lx < 16; lx++) {
            int wx = cwx + lx;

            // Skip columns outside the total footprint entirely
            if (wx < tMinX || wx > tMaxX) continue;

            for (int lz = 0; lz < 16; lz++) {
                int wz = cwz + lz;
                if (wz < tMinZ || wz > tMaxZ) continue;

                boolean isInner = wx >= iMinX && wx <= iMaxX
                               && wz >= iMinZ && wz <= iMaxZ;

                if (isInner) {
                    // Inner plot: grass floor only
                    chunk.getBlock(lx, 64, lz).setType(Material.GRASS_BLOCK, false);

                } else {
                    // Buffer zone: only fill if on the OUTER face or INNER face
                    // Outer face: column touches the total bounding box edge
                    boolean onOuterFace = wx == tMinX || wx == tMaxX
                                       || wz == tMinZ || wz == tMaxZ;
                    // Inner face: column is directly adjacent to the inner plot
                    boolean onInnerFace = wx == iMinX - 1 || wx == iMaxX + 1
                                       || wz == iMinZ - 1 || wz == iMaxZ + 1;

                    if (onOuterFace || onInnerFace) {
                        // Solid iron surface shell
                        for (int y = minY; y < maxY; y++) {
                            chunk.getBlock(lx, y, lz).setType(Material.IRON_BLOCK, false);
                        }
                        // else: hollow interior â†’ leave as air, no writes
                    }
                }
            }
        }
    }

    // =========================================================================
    // ── Cleanup ───────────────────────────────────────────────────────────────
    // =========================================================================

    /**
     * Destroys all plot chunks reliably on Paper 1.20.1.
     *
     * ROOT CAUSE OF PREVIOUS FAILURES:
     *
     *  - Direct region file header edit: Paper's RegionFileCache holds open file
     *    handles and overwrites our edits on its next flush.
     *
     *  - unloadChunk(cx, cz, false): Paper 1.20.1 ignores the save=false flag
     *    for dirty (modified) chunks as a safety measure and saves them anyway.
     *
     * SOLUTION â€” regenerateChunk():
     *   world.regenerateChunk(cx, cz) replaces the chunk's content in-memory
     *   and on-disk using the world's ChunkGenerator (our VoidChunkGenerator).
     *   It is deprecated in newer Paper versions but is the ONLY reliable
     *   no-NMS approach on 1.20.1 that actually clears the chunk data.
     *
     *   We then call world.unloadChunk(cx, cz, false) after regeneration.
     *   At this point the chunk is no longer dirty (regenerateChunk wrote void
     *   data to it), so unloadChunk correctly discards the in-memory copy.
     *
     * PERFORMANCE:
     *   regenerateChunk on a void world is fast â€” the generator returns an
     *   empty ChunkData with no terrain logic. We batch 10 chunks per tick
     *   (regenerateChunk is heavier than a plain unload) to stay smooth.
     *
     * After all chunks are processed we attempt to delete any .mca region
     * files that are entirely within plot space â€” this is a bonus cleanup
     * that removes the now-void region files from disk entirely.
     */
    @SuppressWarnings("deprecation")
    public void destroyAllPlots(Runnable onComplete) {
        List<Plot> toDestroy = new ArrayList<>(orderedPlots);
        plotsByOwner.clear();
        orderedPlots.clear();

        if (toDestroy.isEmpty()) {
            onComplete.run();
            return;
        }

        // Collect all unique chunk coordinates
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

        // Which region files are 100% plot-owned (safe to delete after cleanup)
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

        final int BATCH = 10; // regenerateChunk is heavier â€” keep batches small
        final int[] cursor = {0};

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int end = Math.min(cursor[0] + BATCH, coordList.size());
            for (int i = cursor[0]; i < end; i++) {
                int cx = coordList.get(i)[0];
                int cz = coordList.get(i)[1];

                // Ensure the chunk is loaded before we can regenerate it
                if (!world.isChunkLoaded(cx, cz)) {
                    world.loadChunk(cx, cz, true);
                }

                // Replace chunk content with void (uses our VoidChunkGenerator)
                world.regenerateChunk(cx, cz);

                // Now safe to unload â€” chunk is no longer dirty after regeneration
                world.unloadChunk(cx, cz, false);
            }
            cursor[0] = end;

            if (cursor[0] >= coordList.size()) {
                task.cancel();

                // Bonus: delete fully-owned region files from disk.
                // Use int[] so it can be referenced inside the logger lambda.
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
                        + chunkCount + " chunks regenerated, "
                        + delCount + " region files deleted.");
                onComplete.run();
            }
        }, 1L, 1L);
    }

    /**
     * Safe fallback plot erasure using direct block-by-block AIR fill.
     *
     * Slower than destroyAllPlots() but guaranteed to visually clear the
     * build regardless of Paper's chunk save behaviour.
     * Exposed via /safe_erase_plots for admin use when normal cleanup fails.
     *
     * Batched at 2 chunks/tick â€” each chunk has up to 65536 block sets so
     * we keep batches small to avoid tick spikes. The operation runs over
     * several seconds but produces no lag spikes.
     *
     * @param onComplete Called on the main thread when done.
     */
    public void safeErasePlots(Runnable onComplete) {
        // Take a snapshot â€” can be called while orderedPlots is still populated
        // (admin might call this mid-game as an emergency)
        List<Plot> toErase = new ArrayList<>(orderedPlots);
        plotsByOwner.clear();
        orderedPlots.clear();

        if (toErase.isEmpty()) {
            plugin.getLogger().info("[PlotManager] safeErase: no plots to erase.");
            onComplete.run();
            return;
        }

        // Collect chunks
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
        final int BATCH = 2; // 2 chunks/tick â€” each chunk up to 65536 block sets
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
                world.unloadChunk(cx, cz, true); // save=true to persist the now-empty chunk
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

    // ── Lookup helpers ────────────────────────────────────────────────────────

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

    public List<Plot> getOrderedPlots()     { return Collections.unmodifiableList(orderedPlots); }
    public Map<UUID, Plot> getPlotsByOwner(){ return Collections.unmodifiableMap(plotsByOwner); }
    public World getWorld()                 { return world; }
    public int   getInnerBlocks()           { return innerBlocks; }
    public int   getBufferBlocks()          { return bufferBlocks; }
    public int   getTotalBlocks()           { return totalBlocks; }
}

