package dev.onder1e.buildbattle.listener;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.game.GameManager;
import dev.onder1e.buildbattle.game.GameState;
import dev.onder1e.buildbattle.plot.Plot;
import dev.onder1e.buildbattle.plot.PlotManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;

public class WorldEditListener {

    private final BuildBattle plugin;
    private final GameManager gameManager;
    private final PlotManager plotManager;

    private WorldEditListener(BuildBattle plugin, GameManager gameManager, PlotManager plotManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.plotManager = plotManager;
    }

    public static WorldEditListener register(BuildBattle plugin, GameManager gameManager, PlotManager plotManager) {
        WorldEditListener listener = new WorldEditListener(plugin, gameManager, plotManager);
        WorldEdit.getInstance().getEventBus().register(listener);
        return listener;
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        Actor actor = event.getActor();
        if (actor == null || !actor.isPlayer()) return;

        Object adapted = BukkitAdapter.adapt(actor);
        if (!(adapted instanceof Player player)) return;

        // FIX: Check if the extent is an EditSession to access setMask
        if (event.getExtent() instanceof EditSession session) {
            session.setMask(new BuildBattleMask(player));
        }
    }

    /**
     * Implementing Mask directly instead of extending AbstractMask 
     * to avoid version-specific 'copy()' override issues.
     */
    private class BuildBattleMask implements Mask {
        private final Player player;

        public BuildBattleMask(Player player) {
            this.player = player;
        }

        @Override
        public boolean test(BlockVector3 vector) {
            Location loc = new Location(player.getWorld(), vector.x(), vector.y(), vector.z());

            // 1. Lobby Check
            if (plugin.isInsideLobby(loc)) return false;

            // 2. Admin Bypass
            if (player.hasPermission("buildbattle.admin")) return true;

            // 3. Phase Check
            if (gameManager.getCurrentState() != GameState.BUILDING) return false;

            // 4. Plot Boundary Check
            Plot plot = plotManager.getPlot(player);
            if (plot == null) return false;

            int x = vector.x();
            int z = vector.z();

            return x >= plot.getInnerMinX() && x <= plot.getInnerMaxX() &&
                   z >= plot.getInnerMinZ() && z <= plot.getInnerMaxZ();
        }

        // FIX: Return a generic Mask to satisfy the interface override
        @Override
        public Mask copy() {
            return new BuildBattleMask(player);
        }

        // Required by some WorldEdit 7 versions
        @Nullable
        @Override
        public Mask getToApply() {
            return this;
        }
    }
}