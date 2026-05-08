package dev.onder1e.buildbattle.listener;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.game.GameManager;
import dev.onder1e.buildbattle.game.GameState;
import dev.onder1e.buildbattle.plot.Plot;
import dev.onder1e.buildbattle.plot.PlotManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

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
        // Must have a player actor
        Actor actor = event.getActor();
        if (actor == null || !actor.isPlayer()) return;

        // BukkitAdapter.adapt(Actor) returns CommandSender — cast via BukkitPlayer
        if (!(actor instanceof com.sk89q.worldedit.bukkit.BukkitPlayer bukkitPlayer)) return;
        Player player = bukkitPlayer.getPlayer();
        if (player == null) return;

        // Admins bypass all WE restrictions
        if (player.hasPermission("buildbattle.admin")) return;

        // Wrap the extent — this fires for EVERY stage so wrapping is always safe
        event.setExtent(new PlotBoundaryExtent(event.getExtent(), player));
    }

    private class PlotBoundaryExtent extends AbstractDelegateExtent {

        private final Player player;
        private int blockedCount = 0;

        PlotBoundaryExtent(Extent parent, Player player) {
            super(parent);
            this.player = player;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(
                BlockVector3 pos, T block) throws com.sk89q.worldedit.WorldEditException {

            int x = pos.x();
            int y = pos.y();
            int z = pos.z();

            Location loc = new Location(player.getWorld(), x, y, z);

            // Block WE inside the lobby entirely
            if (plugin.isInsideLobby(loc)) {
                sendBlockedMessage();
                return false;
            }

            // Outside BUILDING phase — block all WE
            if (gameManager.getCurrentState() != GameState.BUILDING) {
                sendBlockedMessage();
                return false;
            }

            // Must have a plot
            Plot plot = plotManager.getPlot(player);
            if (plot == null) {
                sendBlockedMessage();
                return false;
            }

            // Must be within inner plot XZ boundary (Y unrestricted)
            if (x < plot.getInnerMinX() || x > plot.getInnerMaxX()
             || z < plot.getInnerMinZ() || z > plot.getInnerMaxZ()) {
                sendBlockedMessage();
                return false;
            }

            return super.setBlock(pos, block);
        }

        private void sendBlockedMessage() {
            blockedCount++;
            if (blockedCount == 1) {
                player.sendMessage(Component.text(
                    "WorldEdit blocked outside your plot!", NamedTextColor.RED));
            }
        }
    }
}