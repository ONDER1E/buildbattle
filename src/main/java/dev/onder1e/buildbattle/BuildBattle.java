package dev.onder1e.buildbattle;

import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import dev.onder1e.buildbattle.commands.AddWordCommand;
import dev.onder1e.buildbattle.commands.ChooseCommand;
import dev.onder1e.buildbattle.commands.ConfigCommand;
import dev.onder1e.buildbattle.commands.DoneCommand;
import dev.onder1e.buildbattle.commands.ForceChooseCommand;
import dev.onder1e.buildbattle.commands.ForceEndCommand;
import dev.onder1e.buildbattle.commands.ForceStartCommand;
import dev.onder1e.buildbattle.commands.PauseResumeCommand;
import dev.onder1e.buildbattle.commands.PvPReadyCommand;
import dev.onder1e.buildbattle.commands.ReadyCommand;
import dev.onder1e.buildbattle.commands.RemoveWordCommand;
import dev.onder1e.buildbattle.commands.SafeErasePlotsCommand;
import dev.onder1e.buildbattle.commands.SetPlotBlockCommand;
import dev.onder1e.buildbattle.commands.VoteCommand;
import dev.onder1e.buildbattle.game.GameManager;
import dev.onder1e.buildbattle.listener.PlayerListener;
import dev.onder1e.buildbattle.listener.WorldEditListener;
import dev.onder1e.buildbattle.packet.PacketHandler;
import dev.onder1e.buildbattle.plot.PlotManager;

/**
 * BuildBattle - Main plugin entry point.
 */
public final class BuildBattle extends JavaPlugin {

    private static BuildBattle instance;
    private World gameWorld;

    private PlotManager   plotManager;
    private GameManager   gameManager;
    private PacketHandler packetHandler;
    
    /**
     * Stored to resolve "New instance ignored" hint.
     */
    @SuppressWarnings("unused")
    private WorldEditListener worldEditListener;

    // ── Lobby dimensions ─────────────────────────────────────────────────────────
    public static final int LOBBY_SIZE    = 60;
    public static final int LOBBY_HEIGHT  = 15;
    public static final int LOBBY_FLOOR_Y = 64;

    private Location lobbySpawn;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.gameWorld = setupVoidWorld();
        lobbySpawn = buildLobby(gameWorld);

        Bukkit.getOnlinePlayers().forEach(p -> {
            clearInventory(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.teleport(lobbySpawn);
        });

        plotManager   = new PlotManager(this, gameWorld);
        packetHandler = new PacketHandler(this);
        gameManager   = new GameManager(this, plotManager, packetHandler);

        plotManager.createLobbyRegion(getLobbyMinX(), getLobbyMinZ(), getLobbyMaxX(), getLobbyMaxZ());
        plotManager.ensureGlobalFlags();

        Bukkit.getPluginManager().registerEvents(new PlayerListener(this, gameManager), this);
        
        // Assigned to field to resolve diagnostic hints
        this.worldEditListener = WorldEditListener.register(this, gameManager, plotManager);

        registerCommands();
        getLogger().info("BuildBattle enabled successfully!");
    }

    public World getGameWorld() {
        return gameWorld;
    }

    @Override
    public void onDisable() {
        if (gameManager   != null) gameManager.forceReset();
        if (packetHandler != null) packetHandler.unregister();
        getLogger().info("BuildBattle disabled.");
    }

    public void clearWoolInLobby(World world) {
        int ox = -(LOBBY_SIZE + 100);
        int oz = -(LOBBY_SIZE / 2);
        int oy = LOBBY_FLOOR_Y;
        int s  = LOBBY_SIZE;
        int h  = LOBBY_HEIGHT;

        for (int y = oy - 1; y <= oy + h + 1; y++) {
            for (int x = ox; x <= ox + s; x++) {
                for (int z = oz; z <= oz + s; z++) {
                    Material mat = world.getBlockAt(x, y, z).getType();
                    if (mat.name().endsWith("_WOOL")) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    // ── World setup ───────────────────────────────────────────────────────────

    private World setupVoidWorld() {
        WorldCreator creator = new WorldCreator("buildbattle")
                .generator(new VoidChunkGenerator())
                .generateStructures(false);
        World world = Bukkit.createWorld(creator);
        Objects.requireNonNull(world, "Failed to create/load buildbattle world!");
        world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
        world.setPVP(false);
        world.setTime(6000);
        return world;
    }

    public Location buildLobby(World world) {
        int ox = -(LOBBY_SIZE + 100);
        int oz = -(LOBBY_SIZE / 2);
        int oy = LOBBY_FLOOR_Y;
        int s  = LOBBY_SIZE;
        int h  = LOBBY_HEIGHT;

        // Glass floor and barriers
        for (int x = ox; x <= ox + s; x++) {
            for (int z = oz; z <= oz + s; z++) {
                world.getBlockAt(x, oy, z).setType(Material.GLASS, false);
                world.getBlockAt(x, oy - 1, z).setType(Material.BARRIER, false);
                world.getBlockAt(x, oy + h + 1, z).setType(Material.BARRIER, false);
            }
        }

        // Glass walls
        for (int y = oy + 1; y <= oy + h; y++) {
            for (int x = ox; x <= ox + s; x++) {
                world.getBlockAt(x, y, oz).setType(Material.GLASS, false);
                world.getBlockAt(x, y, oz + s).setType(Material.GLASS, false);
            }
            for (int z = oz + 1; z < oz + s; z++) {
                world.getBlockAt(ox, y, z).setType(Material.GLASS, false);
                world.getBlockAt(ox + s, y, z).setType(Material.GLASS, false);
            }
        }

        // Iron bar perimeter
        for (int x = ox; x <= ox + s; x++) {
            world.getBlockAt(x, oy, oz).setType(Material.IRON_BARS, false);
            world.getBlockAt(x, oy, oz + s).setType(Material.IRON_BARS, false);
        }
        for (int z = oz + 1; z < oz + s; z++) {
            world.getBlockAt(ox, oy, z).setType(Material.IRON_BARS, false);
            world.getBlockAt(ox + s, oy, z).setType(Material.IRON_BARS, false);
        }

        Location spawn = new Location(world, ox + s / 2.0 + 0.5, oy + 1, oz + s / 2.0 + 0.5);
        world.setSpawnLocation(spawn);
        return spawn;
    }

    public boolean isInsideLobby(Location loc) {
        // Check if the world is the buildbattle world
        if (!loc.getWorld().getName().equals("buildbattle")) return false;

        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        int ox = -(LOBBY_SIZE + 100);
        int oz = -(LOBBY_SIZE / 2);

        // Returns true if the block is part of the floor, walls, or ceiling box
        return x >= ox && x <= ox + LOBBY_SIZE &&
            z >= oz && z <= oz + LOBBY_SIZE &&
            y >= LOBBY_FLOOR_Y - 1 && y <= LOBBY_FLOOR_Y + LOBBY_HEIGHT + 1;
    }

    // ── Inventory management ──────────────────────────────────────────────────

    public static void clearInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
    }

    // ── Command registration ──────────────────────────────────────────────────

    private void registerCommands() {
        Objects.requireNonNull(getCommand("ready")).setExecutor(new ReadyCommand(gameManager));
        Objects.requireNonNull(getCommand("force_start")).setExecutor(new ForceStartCommand(gameManager));
        Objects.requireNonNull(getCommand("choose")).setExecutor(new ChooseCommand(gameManager));
        Objects.requireNonNull(getCommand("force_choose")).setExecutor(new ForceChooseCommand(gameManager));
        Objects.requireNonNull(getCommand("done")).setExecutor(new DoneCommand(gameManager));
        Objects.requireNonNull(getCommand("force_end")).setExecutor(new ForceEndCommand(gameManager));
        Objects.requireNonNull(getCommand("vote")).setExecutor(new VoteCommand(gameManager));
        Objects.requireNonNull(getCommand("addword")).setExecutor(new AddWordCommand(this, gameManager));
        Objects.requireNonNull(getCommand("removeword")).setExecutor(new RemoveWordCommand(this, gameManager));
        Objects.requireNonNull(getCommand("setplotblock")).setExecutor(new SetPlotBlockCommand(gameManager));
        PauseResumeCommand pauseResume = new PauseResumeCommand(gameManager);
        Objects.requireNonNull(getCommand("pause")).setExecutor(pauseResume);
        Objects.requireNonNull(getCommand("resume")).setExecutor(pauseResume);
        Objects.requireNonNull(getCommand("config")).setExecutor(new ConfigCommand(gameManager));
        Objects.requireNonNull(getCommand("safe_erase_plots")).setExecutor(new SafeErasePlotsCommand(this, gameManager));
        Objects.requireNonNull(getCommand("pvpready")).setExecutor(new PvPReadyCommand(gameManager));
    }

    // ── Lobby bounds ──────────────────────────────────────────────────────────

    public int getLobbyMinX() { return -(LOBBY_SIZE + 100) + 1; }
    public int getLobbyMaxX() { return getLobbyMinX() + LOBBY_SIZE - 1; }
    public int getLobbyMinZ() { return -(LOBBY_SIZE / 2) + 1; }
    public int getLobbyMaxZ() { return getLobbyMinZ() + LOBBY_SIZE - 1; }
    public int getLobbyCeilY() { return LOBBY_FLOOR_Y + LOBBY_HEIGHT; }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public static BuildBattle getInstance() { return instance; }
    public PlotManager   getPlotManager()   { return plotManager; }
    public GameManager   getGameManager()   { return gameManager; }
    public PacketHandler getPacketHandler() { return packetHandler; }
    public Location      getLobbySpawn()    { return lobbySpawn; }

    // ── Void chunk generator ─────────────────────────────────────────────────

    public static class VoidChunkGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(WorldInfo w, java.util.Random r, int cx, int cz, ChunkData d) {}
        @Override
        public void generateSurface(WorldInfo w, java.util.Random r, int cx, int cz, ChunkData d) {}
        @Override
        public void generateBedrock(WorldInfo w, java.util.Random r, int cx, int cz, ChunkData d) {}
    }
}