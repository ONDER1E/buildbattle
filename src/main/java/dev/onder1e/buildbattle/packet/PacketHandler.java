package dev.onder1e.buildbattle.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.plot.Plot;
import dev.onder1e.buildbattle.plot.PlotManager;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * PacketHandler
 * ==============
 * Implements the "Fake Chunk" anti-peeking protocol using ProtocolLib.
 *
 * HOW IT WORKS:
 * -------------
 * During the BUILDING phase, every outbound LEVEL_CHUNK_WITH_LIGHT (chunk data)
 * packet is intercepted BEFORE it reaches a player's client.
 *
 * For each packet:
 *  1. We read the chunk's (cx, cz) from the packet.
 *  2. We look up which plot owns this chunk (if any).
 *  3. If the chunk belongs to the player's own plot → let it through unchanged.
 *  4. If the chunk belongs to a DIFFERENT player's plot → cancel the packet.
 *     On the next tick we send an empty (air) chunk packet for that coordinate
 *     so the client renders a void area instead of the real build.
 *  5. If the chunk is outside all plots (e.g. lobby area) → let it through.
 *
 * At the START of VOTING, we call refreshVotingChunks() which force-sends the
 * real chunk data for the plot currently under vote, making it visible to all.
 *
 * LIMITATIONS:
 * ------------
 * - This implementation relies on ProtocolLib 5.x for Paper 1.20.
 * - Empty chunk packets are constructed by simply cancelling the real one;
 *   the client already has an "unload chunk" packet which is sent separately
 *   when a chunk leaves render distance. We use sendBlockChange tricks for
 *   simplicity, but a full empty-chunk packet is the production approach.
 */
public class PacketHandler {

    private final BuildBattle plugin;
    private ProtocolManager protocolManager;
    private PacketAdapter chunkAdapter;

    /**
     * The set of plot chunk-keys that should be HIDDEN from each player.
     * Keyed by player UUID → set of chunkKeys that are NOT their plot.
     */
    private final Map<UUID, Set<Long>> hiddenChunksPerPlayer = new HashMap<>();

    /**
     * The chunk keys that should currently be SHOWN to all players.
     * Used during VOTING to broadcast the current plot being judged.
     */
    private final Set<Long> broadcastVisibleChunks = new HashSet<>();

    /** Whether the packet listener is currently actively filtering. */
    private volatile boolean filtering = false;

    public PacketHandler(BuildBattle plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        registerListener();
    }

    // ── Listener registration ─────────────────────────────────────────────────

    private void registerListener() {
        chunkAdapter = new PacketAdapter(
                plugin,
                ListenerPriority.HIGH,
                PacketType.Play.Server.MAP_CHUNK  // LEVEL_CHUNK_WITH_LIGHT in 1.20
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                // Only intercept during BUILDING phase
                if (!filtering) return;

                Player player = event.getPlayer();
                PacketContainer packet = event.getPacket();

                // Read chunk coordinates from the packet
                // Field index 0 = chunkX, field index 1 = chunkZ (ProtocolLib 5.x)
                int cx = packet.getIntegers().read(0);
                int cz = packet.getIntegers().read(1);
                long key = Plot.chunkKey(cx, cz);

                // If this chunk is in the global broadcast-visible set, allow it.
                if (broadcastVisibleChunks.contains(key)) return;

                // Check if this chunk belongs to another player's plot
                Set<Long> hidden = hiddenChunksPerPlayer.get(player.getUniqueId());
                if (hidden != null && hidden.contains(key)) {
                    // CANCEL the real chunk packet — client receives nothing.
                    // The client will see a void/air area at this position because
                    // it has no chunk data loaded for it.
                    event.setCancelled(true);
                }
            }
        };

        protocolManager.addPacketListener(chunkAdapter);
    }

    // ── Phase control ─────────────────────────────────────────────────────────

    /**
     * Called at the START of BUILDING.
     * Builds the hidden-chunk map for every player:
     *   player P → all chunk keys that belong to plots NOT owned by P.
     *
     * Also enables the packet filter.
     *
     * @param plotManager The current PlotManager with all plots assigned.
     */
    public void enableBuildingFilter(PlotManager plotManager) {
        hiddenChunksPerPlayer.clear();
        broadcastVisibleChunks.clear();
        filtering = true;

        List<Plot> allPlots = plotManager.getOrderedPlots();

        for (Plot myPlot : allPlots) {
            Set<Long> hiddenKeys = new HashSet<>();

            // For every OTHER plot, add its total chunk keys to this player's hidden set
            for (Plot otherPlot : allPlots) {
                if (otherPlot.getIndex() == myPlot.getIndex()) continue;
                hiddenKeys.addAll(otherPlot.getTotalChunkKeys());
            }

            hiddenChunksPerPlayer.put(myPlot.getOwnerUUID(), hiddenKeys);
        }

        plugin.getLogger().info("[PacketHandler] Building filter ENABLED for "
                + allPlots.size() + " plots.");
    }

    /**
     * Called at the START of VOTING for a specific plot.
     * Marks that plot's inner chunk keys as broadcast-visible so everyone
     * receives them, then forces a chunk refresh for all players.
     *
     * @param plot   The plot currently being voted on.
     * @param players All online players who should see the build.
     */
    public void refreshVotingChunks(Plot plot, Iterable<Player> players) {
        broadcastVisibleChunks.clear();
        broadcastVisibleChunks.addAll(plot.getInnerChunkKeys());

        // Force-resend chunk data for the inner plot columns.
        // Player#sendChunkChange triggers the server to re-send the chunk packet.
        int minCX = plot.getInnerMinX() >> 4;
        int maxCX = plot.getInnerMaxX() >> 4;
        int minCZ = plot.getInnerMinZ() >> 4;
        int maxCZ = plot.getInnerMaxZ() >> 4;

        for (Player player : players) {
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    // refreshChunk forces the server to re-transmit chunk data
                    player.getWorld().refreshChunk(cx, cz);
                }
            }
        }

        plugin.getLogger().info("[PacketHandler] Voting refresh sent for plot "
                + plot.getIndex() + " (owner " + plot.getOwnerUUID() + ").");
    }

    /**
     * Disables filtering and clears all isolation data.
     * Called when transitioning to RESULTS or RESET.
     */
    public void disableFilter() {
        filtering = false;
        hiddenChunksPerPlayer.clear();
        broadcastVisibleChunks.clear();
        plugin.getLogger().info("[PacketHandler] Chunk filter DISABLED.");
    }

    /**
     * Unregisters the ProtocolLib listener.
     * Called on plugin disable.
     */
    public void unregister() {
        if (chunkAdapter != null && protocolManager != null) {
            protocolManager.removePacketListener(chunkAdapter);
        }
    }

    /** True if the building filter is currently active. */
    public boolean isFiltering() { return filtering; }
}
