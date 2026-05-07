package dev.onder1e.buildbattle.plot;

import org.bukkit.Location;

import java.util.*;

/**
 * Immutable value object representing one Build Battle plot.
 *
 * A plot consists of:
 *   - An inner 160×160 (10-chunk) grass floor at y=64.
 *   - A 2-chunk-wide iron-block surrounding wall from y=0 to y=255.
 *
 * Coordinates are stored at the BLOCK level (not chunk level).
 */
public class Plot {

    /** Unique index (0-based) used to calculate the X offset. */
    private final int index;

    /** UUID of the player who owns this plot. */
    private final UUID ownerUUID;

    /**
     * World X coordinate of the BOTTOM-LEFT corner of the INNER plot area
     * (i.e., the first grass block column, NOT including the iron wall).
     */
    private final int innerMinX;

    /**
     * World Z coordinate of the BOTTOM-LEFT corner of the inner plot area.
     * Always 0 in our layout — plots only extend on the X axis.
     */
    private final int innerMinZ;

    /** Block size of the inner plot (plot_size * 16). e.g. 160 for 10 chunks. */
    private final int innerSize;

    /** Block width of the iron wall on each side (buffer_size * 16). */
    private final int bufferSize;

    // ── Voting state ─────────────────────────────────────────────────────────

    /** Map of voter UUID → score (1-10). */
    private final Map<UUID, Integer> votes = new HashMap<>();

    /** Cumulative score sum used for average calculation. */
    private double totalScore = 0;

    // ── Build state ───────────────────────────────────────────────────────────

    /** True once the owner types /done. */
    private boolean done = false;

    // ─────────────────────────────────────────────────────────────────────────

    public Plot(int index, UUID ownerUUID, int innerMinX, int innerMinZ,
                int innerSize, int bufferSize) {
        this.index      = index;
        this.ownerUUID  = ownerUUID;
        this.innerMinX  = innerMinX;
        this.innerMinZ  = innerMinZ;
        this.innerSize  = innerSize;
        this.bufferSize = bufferSize;
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    /** Inclusive X start of the inner (grass) area. */
    public int getInnerMinX() { return innerMinX; }

    /** Inclusive X end of the inner (grass) area. */
    public int getInnerMaxX() { return innerMinX + innerSize - 1; }

    /** Inclusive Z start of the inner (grass) area. */
    public int getInnerMinZ() { return innerMinZ; }

    /** Inclusive Z end of the inner (grass) area. */
    public int getInnerMaxZ() { return innerMinZ + innerSize - 1; }

    /** Inclusive X start of the TOTAL plot footprint (including iron walls). */
    public int getTotalMinX() { return innerMinX - bufferSize; }

    /** Inclusive X end of the TOTAL plot footprint (including iron walls). */
    public int getTotalMaxX() { return innerMinX + innerSize - 1 + bufferSize; }

    /** Inclusive Z start of the TOTAL plot footprint (including iron walls). */
    public int getTotalMinZ() { return innerMinZ - bufferSize; }

    /** Inclusive Z end of the TOTAL plot footprint (including iron walls). */
    public int getTotalMaxZ() { return innerMinZ + innerSize - 1 + bufferSize; }

    /**
     * Returns the centre of the inner plot at standing height (y=65) as a
     * Location — used for teleporting players to their plot.
     */
    public Location getCentreLocation(org.bukkit.World world) {
        double cx = innerMinX + innerSize / 2.0;
        double cz = innerMinZ + innerSize / 2.0;
        return new Location(world, cx, 65, cz);
    }

    /**
     * Returns chunk X values that belong to the inner plot (used for packet filtering).
     * Range: chunkX from (innerMinX >> 4) to ((innerMinX + innerSize - 1) >> 4)
     */
    public Set<Long> getInnerChunkKeys() {
        Set<Long> keys = new HashSet<>();
        int minCX = innerMinX >> 4;
        int maxCX = (innerMinX + innerSize - 1) >> 4;
        int minCZ = innerMinZ >> 4;
        int maxCZ = (innerMinZ + innerSize - 1) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                keys.add(chunkKey(cx, cz));
            }
        }
        return keys;
    }

    /**
     * Returns all chunk keys that belong to the TOTAL footprint (inner + walls).
     * Used to identify which chunks to send as "fake air" to other players.
     */
    public Set<Long> getTotalChunkKeys() {
        Set<Long> keys = new HashSet<>();
        int minCX = getTotalMinX() >> 4;
        int maxCX = getTotalMaxX() >> 4;
        int minCZ = getTotalMinZ() >> 4;
        int maxCZ = getTotalMaxZ() >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                keys.add(chunkKey(cx, cz));
            }
        }
        return keys;
    }

    /** Pack chunkX and chunkZ into a single long key (same as Bukkit chunk key). */
    public static long chunkKey(int cx, int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    // ── Voting ────────────────────────────────────────────────────────────────

    /** Record a vote from a player. Returns false if already voted. */
    public boolean addVote(UUID voterUUID, int score) {
        if (votes.containsKey(voterUUID)) return false;
        votes.put(voterUUID, score);
        totalScore += score;
        return true;
    }

    /** Returns the average score or 0 if no votes were cast. */
    public double getAverageScore() {
        return votes.isEmpty() ? 0 : totalScore / votes.size();
    }

    public int getVoteCount() { return votes.size(); }

    // ── Getters ───────────────────────────────────────────────────────────────

    public int   getIndex()     { return index; }
    public UUID  getOwnerUUID() { return ownerUUID; }
    public int   getInnerSize() { return innerSize; }
    public int   getBufferSize(){ return bufferSize; }
    public boolean isDone()     { return done; }
    public void  setDone(boolean done) { this.done = done; }
}
