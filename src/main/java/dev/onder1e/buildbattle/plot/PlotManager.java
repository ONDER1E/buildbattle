package dev.onder1e.buildbattle.plot;

import dev.onder1e.buildbattle.BuildBattle;
import org.bukkit.*;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PlotManager — plot layout, async generation, fast chunk cleanup.
 *
 * GENERATION OPTIMISATIONS (v1.0.2):
 * ─────────────────────────────────────
 * 1. Hollow iron walls — only the 1-block-thick surface shell of the buffer
 *    zone is filled with IRON_BLOCK. The interior of the wall is left as air.
 *    This reduces block-set operations on edge chunks significantly and keeps
 *    the wall visually solid (players see the surface, not the interior).
 *
 * 2. Async chunk loading — getChunkAtAsync() fires all chunk loads in parallel.
 *    fillChunk() runs on the main thread callback (safe) using direct Chunk
 *    object access (no world.getBlockAt overhead).
 *
 * 3. Early-exit column classification — per column we determine in O(1)
 *    whether it is: inner (grass only), outer air (skip entirely), or wall
 *    surface (fill selectively). This avoids any block writes for air columns.
 *
 * CLEANUP OPTIMISATION (v1.0.2):
 * ─────────────────────────────────
 * world.regenerateChunk() is REMOVED. It runs the full terrain pipeline
 * (biome sampling, noise, decoration) even for a void generator — wasteful.
 *
 * New approach:
 *   1. Delete the region file entry for each chunk using the Anvil region
 *      file directly (set chunk offset to 0 in the .mca header → chunk
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
                            plugin.getLogger().info("[PlotManager] "
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
     *  INNER  — worldX/Z inside inner plot bounds → grass at y=64 only.
     *
     *  WALL SURFACE — worldX/Z is on the outermost or innermost shell of the
     *                 buffer zone → iron from minY to maxY.
     *                 "Surface" means: the column touches the total boundary
     *                 (outer face) OR touches the inner plot edge (inner face).
     *
     *  WALL INTERIOR — worldX/Z is inside the buffer but not on either face
     *                  → leave as air (hollow interior). No block writes needed.
     *
     * This makes walls look solid from the outside and inside while writing
     * far fewer blocks (2 faces × perimeter vs entire buffer volume).
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
                        // else: hollow interior → leave as air, no writes
                    }
                }
            }
        }
    }

    // =========================================================================
    // ── Cleanup ───────────────────────────────────────────────────────────────
    // =========================================================================

    /**
     * Destroys all plot chunks by:
     *  1. Erasing each chunk's entry in the Anvil region file (set offset=0)
     *     so the chunk is treated as ungenerated. Next load → VoidChunkGenerator
     *     produces all-air with zero extra work.
     *  2. Unloading the chunk from memory with save=false (discard in-memory data).
     *
     * This is faster than regenerateChunk() which triggers the full generation
     * pipeline, and faster than block-by-block air fill.
     * Batched at 30 chunks/tick for smooth performance.
     */
    public void destroyAllPlots(Runnable onComplete) {
        List<Plot> toDestroy = new ArrayList<>(orderedPlots);
        plotsByOwner.clear();
        orderedPlots.clear();

        if (toDestroy.isEmpty()) {
            onComplete.run();
            return;
        }

        // Collect unique chunk coords
        Map<Long, int[]> chunkCoords = new LinkedHashMap<>();
        for (Plot plot : toDestroy) {
            for (int cx = plot.getTotalMinX() >> 4; cx <= plot.getTotalMaxX() >> 4; cx++) {
                for (int cz = plot.getTotalMinZ() >> 4; cz <= plot.getTotalMaxZ() >> 4; cz++) {
                    chunkCoords.putIfAbsent(Plot.chunkKey(cx, cz), new int[]{cx, cz});
                }
            }
        }

        List<int[]> coords = new ArrayList<>(chunkCoords.values());
        File worldFolder = world.getWorldFolder();
        final int BATCH = 30;
        final int[] cursor = {0};

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int end = Math.min(cursor[0] + BATCH, coords.size());
            for (int i = cursor[0]; i < end; i++) {
                int cx = coords.get(i)[0];
                int cz = coords.get(i)[1];

                // Erase chunk from region file so it's treated as ungenerated
                eraseChunkFromRegion(worldFolder, cx, cz);

                // Unload from memory without saving (discard modified data)
                if (world.isChunkLoaded(cx, cz)) {
                    world.unloadChunk(cx, cz, false);
                }
            }
            cursor[0] = end;

            if (cursor[0] >= coords.size()) {
                task.cancel();
                plugin.getLogger().info("[PlotManager] " + coords.size() + " chunks erased.");
                onComplete.run();
            }
        }, 1L, 1L);
    }

    /**
     * Sets the chunk's entry in its Anvil .mca region file to 0 (ungenerated).
     *
     * Anvil region format: the first 4096 bytes are the chunk offset table.
     * Each chunk has a 4-byte entry at offset ((cx & 31) + (cz & 31) * 32) * 4.
     * Writing 4 zero bytes marks the chunk as absent — the next load regenerates it.
     */
    private void eraseChunkFromRegion(File worldFolder, int cx, int cz) {
        int regionX = cx >> 5;
        int regionZ = cz >> 5;
        File regionFile = new File(worldFolder, "region/r." + regionX + "." + regionZ + ".mca");
        if (!regionFile.exists()) return;

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(regionFile, "rw")) {
            // Each header entry is 4 bytes at index ((cx&31) + (cz&31)*32)*4
            int headerOffset = ((cx & 31) + (cz & 31) * 32) * 4;
            raf.seek(headerOffset);
            raf.writeInt(0); // 0 = chunk not present
        } catch (java.io.IOException e) {
            // Fall back: just unload without erasing
            plugin.getLogger().warning("[PlotManager] Could not erase chunk ("
                    + cx + "," + cz + ") from region: " + e.getMessage());
        }
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

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
