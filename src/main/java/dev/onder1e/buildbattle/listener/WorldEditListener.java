package dev.onder1e.buildbattle.listener;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;
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
 * REGISTRATION: Use WorldEditListener.register(...) to initialize.
 * Do NOT pass this to Bukkit's PluginManager - it does not implement Listener.
 *
 * HOW THE MASK WORKS:
 * -------------------
 * Every WorldEdit operation (//set, //gen, //paste, etc.) creates an EditSession.
 * We intercept EditSessionEvent to wrap the Extent in a PlotBoundaryExtent
 * that silently drops block-writes outside the player's inner 160x160 plot area.
 */
public class WorldEditListener {

    private final GameManager gameManager;
    private final PlotManager plotManager;

    private WorldEditListener(GameManager gameManager,
                               PlotManager plotManager) {
        this.gameManager = gameManager;
        this.plotManager = plotManager;
    }

    /**
     * Static factory method to safely construct and register the listener.
     * This avoids "Leaking this in constructor" warnings.
     */
    public static WorldEditListener register(GameManager gameManager, PlotManager plotManager) {
        WorldEditListener listener = new WorldEditListener(gameManager, plotManager);
        WorldEdit.getInstance().getEventBus().register(listener);
        return listener;
    }

    /**
     * Called by WorldEdit for every EditSession that is created.
     *
     * In WorldEdit 7.3+ the Stage inner enum was removed from EditSessionEvent.
     * We simply always wrap - WorldEdit fires this once per session, so wrapping
     * is safe and idempotent.
     */
    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        // Only restrict during the BUILDING phase
        if (gameManager.getCurrentState() != GameState.BUILDING) return;

        // Only process events that have an associated Actor (a real player)
        Actor actor = event.getActor();
        if (actor == null || !actor.isPlayer()) return;

        // FIX: BukkitAdapter.adapt(Actor) returns CommandSender, not Player.
        // We must use the BukkitPlayer wrapper type directly to get the Bukkit Player.
        if (!(actor instanceof BukkitPlayer bukkitPlayer)) return;
        Player player = bukkitPlayer.getPlayer();
        if (player == null) return;

        // Only apply to players who have an active plot
        Plot plot = plotManager.getPlot(player);
        if (plot == null) return;

        // Wrap the extent - all setBlock calls will pass through PlotBoundaryExtent first
        event.setExtent(new PlotBoundaryExtent(event.getExtent(), plot, player));
    }

    // ── Inner class: PlotBoundaryExtent ──────────────────────────────────────

    /**
     * Delegates all block-write calls to the parent Extent, but silently drops
     * any write that targets a block coordinate outside the player's inner plot.
     *
     * Checked bounds (block coordinates):
     * X: [plot.getInnerMinX(), plot.getInnerMaxX()]
     * Z: [plot.getInnerMinZ(), plot.getInnerMaxZ()]
     * Y: unrestricted (full build height allowed within the column)
     */
    private static class PlotBoundaryExtent extends AbstractDelegateExtent {

        private final Plot   plot;
        private final Player player;
        /** Track how many blocks were blocked to avoid spamming the player's chat. */
        private int blockedCount = 0;

        PlotBoundaryExtent(Extent parent, Plot plot, Player player) {
            super(parent);
            this.plot   = plot;
            this.player = player;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(
                BlockVector3 location, T block) throws com.sk89q.worldedit.WorldEditException {

            // FIX: Use x() and z() (non-deprecated in WE 7.3+)
            // instead of the removed/deprecated getX() / getZ() methods.
            int x = location.x();
            int z = location.z();

            if (x < plot.getInnerMinX() || x > plot.getInnerMaxX()
             || z < plot.getInnerMinZ() || z > plot.getInnerMaxZ()) {

                blockedCount++;
                // Notify the player only on the first blocked block to avoid spam
                if (blockedCount == 1) {
                    player.sendMessage(Component.text(
                            "WorldEdit blocked: operation exceeds your plot boundary!",
                            NamedTextColor.RED));
                }
                // Returning false signals WorldEdit the block was not placed
                return false;
            }

            return super.setBlock(location, block);
        }
    }
}