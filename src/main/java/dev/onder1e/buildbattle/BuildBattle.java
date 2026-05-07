package dev.onder1e.buildbattle;

import dev.onder1e.buildbattle.commands.*;
import dev.onder1e.buildbattle.game.GameManager;
import dev.onder1e.buildbattle.listener.PlayerListener;
import dev.onder1e.buildbattle.listener.WorldEditListener;
import dev.onder1e.buildbattle.packet.PacketHandler;
import dev.onder1e.buildbattle.plot.PlotManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * BuildBattle - Main Plugin Entry Point
 *
 * Lifecycle:
 *  onEnable  -> initialise void world, place lobby barrier box, wire up all managers.
 *  onDisable -> clean up plots, unregister packet handler.
 */
public final class BuildBattle extends JavaPlugin {

    // ── Singleton access ──────────────────────────────────────────────────────
    private static BuildBattle instance;

    // ── Core managers ────────────────────────────────────────────────────────
    private PlotManager  plotManager;
    private GameManager  gameManager;
    private PacketHandler packetHandler;

    // ── Lobby constants ───────────────────────────────────────────────────────
    /** Side length (blocks) of the hollow barrier-box lobby. */
    public static final int LOBBY_SIZE      = 20;
    /** Y coordinate of the lobby floor (inside the box). */
    public static final int LOBBY_FLOOR_Y   = 64;
    /** The exact spawn Location set inside the lobby. */
    private Location lobbySpawn;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Save / load configuration
        saveDefaultConfig();

        // 2. Set up the void game world
        World gameWorld = setupVoidWorld();

        // 3. Build the barrier lobby at world origin
        lobbySpawn = buildLobby(gameWorld);

        // 4. Teleport all currently online players to the lobby
        Bukkit.getOnlinePlayers().forEach(p -> {
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(lobbySpawn);
        });

        // 5. Initialise managers (order matters)
        plotManager   = new PlotManager(this, gameWorld);
        packetHandler = new PacketHandler(this);
        gameManager   = new GameManager(this, plotManager, packetHandler);

        // 6. Register event listeners
        // PlayerListener uses Bukkit events - registered normally.
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this, gameManager), this);
        // WorldEditListener uses WorldEdit's internal @Subscribe event bus, NOT Bukkit.
        // The constructor self-registers with WorldEdit.getInstance().getEventBus().register(this).
        new WorldEditListener(this, gameManager, plotManager);

        // 7. Register commands
        registerCommands();

        getLogger().info("BuildBattle enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.forceReset();
        }
        if (packetHandler != null) {
            packetHandler.unregister();
        }
        getLogger().info("BuildBattle disabled.");
    }

    // ── World setup ───────────────────────────────────────────────────────────

    /**
     * Creates (or loads) the dedicated "buildbattle" world using a void chunk
     * generator so there is no terrain — only what we place ourselves.
     */
    private World setupVoidWorld() {
        WorldCreator creator = new WorldCreator("buildbattle")
                .generator(new VoidChunkGenerator())  // all chunks are pure void air
                .generateStructures(false);

        World world = Bukkit.createWorld(creator);
        Objects.requireNonNull(world, "Failed to create/load buildbattle world!");

        // Clear default rules that interfere with gameplay
        world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
        world.setTime(6000); // always noon

        return world;
    }

    /**
     * Constructs a hollow 20×20×10 barrier-block box at y=64 at the world
     * origin and returns the spawn Location inside it.
     *
     * The lobby is placed NEGATIVE on the X axis so it does not overlap with
     * plot space which extends in the POSITIVE X direction.
     */
    private Location buildLobby(World world) {
        // Lobby origin: centre it at x= -50 so plots (x≥0) are never touching it
        int ox = -LOBBY_SIZE / 2 - 30; // e.g. -40
        int oy = LOBBY_FLOOR_Y;
        int oz = -LOBBY_SIZE / 2;
        int w  = LOBBY_SIZE;
        int h  = 10; // wall height

        for (int x = ox; x <= ox + w; x++) {
            for (int z = oz; z <= oz + w; z++) {
                for (int y = oy; y <= oy + h; y++) {
                    boolean isWall = (x == ox || x == ox + w || z == oz || z == oz + w
                            || y == oy || y == oy + h);
                    if (isWall) {
                        world.getBlockAt(x, y, z)
                             .setType(org.bukkit.Material.BARRIER);
                    }
                }
            }
        }

        // Floor decoration (glass so it looks nice)
        for (int x = ox + 1; x < ox + w; x++) {
            for (int z = oz + 1; z < oz + w; z++) {
                world.getBlockAt(x, oy, z)
                     .setType(org.bukkit.Material.GLASS);
            }
        }

        Location spawn = new Location(world, ox + w / 2.0 + 0.5, oy + 1, oz + w / 2.0 + 0.5);
        world.setSpawnLocation(spawn);
        return spawn;
    }

    // ── Command registration ──────────────────────────────────────────────────

    private void registerCommands() {
        Objects.requireNonNull(getCommand("ready"))          .setExecutor(new ReadyCommand(gameManager));
        Objects.requireNonNull(getCommand("force_start"))    .setExecutor(new ForceStartCommand(gameManager));
        Objects.requireNonNull(getCommand("choose"))         .setExecutor(new ChooseCommand(gameManager));
        Objects.requireNonNull(getCommand("force_choose"))   .setExecutor(new ForceChooseCommand(gameManager));
        Objects.requireNonNull(getCommand("done"))           .setExecutor(new DoneCommand(gameManager));
        Objects.requireNonNull(getCommand("force_end"))      .setExecutor(new ForceEndCommand(gameManager));
        Objects.requireNonNull(getCommand("vote"))           .setExecutor(new VoteCommand(gameManager));
        Objects.requireNonNull(getCommand("addword"))        .setExecutor(new AddWordCommand(this, gameManager));
        Objects.requireNonNull(getCommand("removeword"))     .setExecutor(new RemoveWordCommand(this, gameManager));
        Objects.requireNonNull(getCommand("setplotblock"))   .setExecutor(new SetPlotBlockCommand(gameManager));
        PauseResumeCommand pauseResume = new PauseResumeCommand(gameManager);
        Objects.requireNonNull(getCommand("pause"))          .setExecutor(pauseResume);
        Objects.requireNonNull(getCommand("resume"))         .setExecutor(pauseResume);
        Objects.requireNonNull(getCommand("config"))         .setExecutor(new ConfigCommand(gameManager));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public static BuildBattle getInstance() { return instance; }
    public PlotManager  getPlotManager()    { return plotManager; }
    public GameManager  getGameManager()    { return gameManager; }
    public PacketHandler getPacketHandler() { return packetHandler; }
    public Location     getLobbySpawn()     { return lobbySpawn; }

    // ── Void chunk generator (inner class for simplicity) ─────────────────────

    /**
     * A ChunkGenerator that produces completely empty (void) chunks.
     * Uses the modern Paper 1.20 API (generateNoise / generateSurface are no-ops).
     */
    public static class VoidChunkGenerator extends ChunkGenerator {
        // generateChunkData with BiomeGrid is removed in Paper 1.20.
        // Override generateNoise with an empty body — the chunk remains all air.
        @Override
        public void generateNoise(WorldInfo worldInfo, java.util.Random random,
                                  int chunkX, int chunkZ,
                                  ChunkData chunkData) {
            // Intentionally empty: leave every block as AIR to produce a void chunk.
        }

        @Override
        public void generateSurface(WorldInfo worldInfo, java.util.Random random,
                                    int chunkX, int chunkZ,
                                    ChunkData chunkData) {
            // Intentionally empty.
        }

        @Override
        public void generateBedrock(WorldInfo worldInfo, java.util.Random random,
                                    int chunkX, int chunkZ,
                                    ChunkData chunkData) {
            // Intentionally empty — no bedrock floor.
        }
    }
}
