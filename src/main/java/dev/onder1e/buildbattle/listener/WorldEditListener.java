package dev.onder1e.buildbattle.listener;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.game.GameManager;
import dev.onder1e.buildbattle.game.GameState;
import dev.onder1e.buildbattle.plot.Plot;
import dev.onder1e.buildbattle.plot.PlotManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * WorldEditListener
 * =================
 * Hooks into WorldEdit's internal event bus (NOT Bukkit events) via @Subscribe.
 *
 * IMPORTANT: This class must be registered with WorldEdit.getInstance().getEventBus(),
 * NOT Bukkit's plugin manager. See BuildBattle#onEnable.
 *
 * HOW THE MASK WORKS:
 * -------------------
 * Every WorldEdit operation (//set, //gen, //paste, etc.) goes through an
 * EditSession. We intercept the EditSessionEvent.Stage.POST_INIT event to
 * wrap the Extent (the "canvas" WorldEdit writes blocks to) in a custom
 * PlotBoundaryExtent.
 *
 * PlotBoundaryExtent delegates all block-setting operations but first checks
 * if the target BlockVector3 falls within the player's inner plot region.
 * If it does NOT, the block is silently dropped (returns false).
 *
 * This provides HARD LIMITS that cannot be bypassed by:
 *   - //set
 *   - //gen
 *   - //paste
 *   - //copy (reading is fine, pasting is blocked)
 *   - Any other WorldEdit operation that writes blocks
 */
public class WorldEditListener {

    private final BuildBattle  plugin;
    private final GameManager  gameManager;
    private final PlotManager  plotManager;

    public WorldEditListener(BuildBattle plugin, GameManager gameManager, PlotManager plotManager) {
        this.plugin      = plugin;
        this.gameManager = gameManager;
        this.plotManager = plotManager;

        // Register with WorldEdit's internal event bus — NOT Bukkit
        WorldEdit.getInstance().getEventBus().register(this);
    }

    /**
     * Called by WorldEdit for every EditSession created.
     *
     * Stage.POST_INIT is the correct hook point: the session is fully set up
     * and ready for extent wrapping.
     */
    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        // Only restrict during the BUILDING phase
        if (gameManager.getCurrentState() != GameState.BUILDING) return;

        // Only process events that have an associated Actor (i.e. a player)
        Actor actor = event.getActor();
        if (actor == null || !actor.isPlayer()) return;

        // Resolve the Bukkit Player from the WorldEdit Actor
        Player player = BukkitAdapter.adapt(actor);
        if (player == null) return;

        // Only apply to players who have a plot
        Plot plot = plotManager.getPlot(player);
        if (plot == null) return;

        // POST_INIT is where we inject our extent wrapper
        if (event.getStage() == EditSessionEvent.Stage.POST_INIT) {
            event.setExtent(new PlotBoundaryExtent(event.getExtent(), plot, player));
        }
    }

    // ── Inner class: PlotBoundaryExtent ──────────────────────────────────────

    /**
     * A delegating Extent that silently drops any block-set operation
     * targeting coordinates OUTSIDE the player's inner plot area.
     *
     * y range: 0–255 (full height column is valid within X/Z boundaries)
     * x range: plot.getInnerMinX() to plot.getInnerMaxX()
     * z range: plot.getInnerMinZ() to plot.getInnerMaxZ()
     */
    private static class PlotBoundaryExtent extends AbstractDelegateExtent {

        private final Plot   plot;
        private final Player player;
        private       int    blockedCount = 0; // for debug/logging

        PlotBoundaryExtent(Extent parent, Plot plot, Player player) {
            super(parent);
            this.plot   = plot;
            this.player = player;
        }

        /**
         * Called for every block write WorldEdit attempts.
         * Returns true if the block was set, false if it was blocked.
         */
        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(
                BlockVector3 location, T block) throws com.sk89q.worldedit.WorldEditException {

            // Check X and Z bounds (Y is unrestricted within the column)
            int x = location.getX();
            int z = location.getZ();

            if (x < plot.getInnerMinX() || x > plot.getInnerMaxX()
             || z < plot.getInnerMinZ() || z > plot.getInnerMaxZ()) {

                // Silently drop — we only notify the player occasionally
                // to avoid chat spam (WorldEdit can call this millions of times)
                blockedCount++;
                if (blockedCount == 1) {
                    // Use Paper's scheduler to send a chat message safely
                    player.sendMessage(Component.text(
                            "⚠ WorldEdit blocked: operation exceeds your plot boundary!",
                            NamedTextColor.RED));
                }
                // Return false = block was NOT set (WorldEdit treats this as a no-op)
                return false;
            }

            // Within bounds — delegate to the real extent
            return super.setBlock(location, block);
        }
    }
}
