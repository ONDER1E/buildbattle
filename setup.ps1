# ============================================================
# BuildBattle Plugin - Full Project Setup Script
# Run from the directory where you want the project created.
# Requirements: JDK 17+, Maven 3.8+ on PATH
# ============================================================

$ProjectRoot = "BuildBattle"
New-Item -ItemType Directory -Force -Path "$ProjectRoot/src/main/java/dev/ak/buildbattle/commands"  | Out-Null
New-Item -ItemType Directory -Force -Path "$ProjectRoot/src/main/java/dev/ak/buildbattle/game"       | Out-Null
New-Item -ItemType Directory -Force -Path "$ProjectRoot/src/main/java/dev/ak/buildbattle/plot"       | Out-Null
New-Item -ItemType Directory -Force -Path "$ProjectRoot/src/main/java/dev/ak/buildbattle/packet"     | Out-Null
New-Item -ItemType Directory -Force -Path "$ProjectRoot/src/main/java/dev/ak/buildbattle/listener"   | Out-Null
New-Item -ItemType Directory -Force -Path "$ProjectRoot/src/main/java/dev/ak/buildbattle/util"       | Out-Null
New-Item -ItemType Directory -Force -Path "$ProjectRoot/src/main/resources"                          | Out-Null

Write-Host "Directory structure created." -ForegroundColor Green

# ── pom.xml ───────────────────────────────────────────────────────────────────
@'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>dev.onder1e</groupId>
    <artifactId>BuildBattle</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    <name>BuildBattle</name>
    <properties>
        <java.version>17</java.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <repositories>
        <repository>
            <id>papermc</id>
            <url>https://repo.papermc.io/repository/maven-public/</url>
        </repository>
        <repository>
            <id>enginehub</id>
            <url>https://maven.enginehub.org/repo/</url>
        </repository>
        <repository>
            <id>dmulloy2-repo</id>
            <url>https://repo.dmulloy2.net/repository/public/</url>
        </repository>
    </repositories>
    <dependencies>
        <dependency>
            <groupId>io.papermc.paper</groupId>
            <artifactId>paper-api</artifactId>
            <version>1.20.4-R0.1-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.sk89q.worldedit</groupId>
            <artifactId>worldedit-bukkit</artifactId>
            <version>7.3.0-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
        <!-- ProtocolLib: hosted on dmulloy2-repo, NOT Maven Central -->
        <dependency>
            <groupId>com.comphenix.protocol</groupId>
            <artifactId>ProtocolLib</artifactId>
            <version>5.3.0</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>${java.version}</source>
                    <target>${java.version}</target>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.0</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>true</filtering>
            </resource>
        </resources>
    </build>
</project>
'@ | Set-Content -Path "$ProjectRoot/pom.xml" -Encoding UTF8

Write-Host "pom.xml written." -ForegroundColor Cyan

# ── plugin.yml ────────────────────────────────────────────────────────────────
@'
name: BuildBattle
version: '${project.version}'
main: dev.onder1e.buildbattle.BuildBattle
api-version: '1.20'
depend:
  - WorldEdit
  - ProtocolLib
description: A production-ready Build Battle plugin
authors: [ ak ]
commands:
  ready:
    description: Toggle your ready state in the lobby.
    usage: /ready
  force_start:
    description: (Admin) Force start the game immediately.
    usage: /force_start
    permission: buildbattle.admin
  choose:
    description: Choose a theme during the WORD_SELECTION phase.
    usage: /choose <theme>
  force_choose:
    description: (Admin) Force a specific theme.
    usage: /force_choose <theme>
    permission: buildbattle.admin
  done:
    description: Mark your build as done and enter spectator mode.
    usage: /done
  force_end:
    description: (Admin) Force end the current phase early.
    usage: /force_end
    permission: buildbattle.admin
  vote:
    description: Vote for the current build (1-10).
    usage: /vote <1-10>
  set_game_timer:
    description: (Admin) Set the game build timer in minutes.
    usage: /set_game_timer <minutes>
    permission: buildbattle.admin
  addword:
    description: (Admin) Add a theme word to the pool.
    usage: /addword <word>
    permission: buildbattle.admin
  removeword:
    description: (Admin) Remove a theme word from the pool.
    usage: /removeword <word>
    permission: buildbattle.admin
permissions:
  buildbattle.admin:
    description: Grants access to all admin commands.
    default: op
  buildbattle.play:
    description: Allows a player to participate in Build Battle.
    default: true
'@ | Set-Content -Path "$ProjectRoot/src/main/resources/plugin.yml" -Encoding UTF8

Write-Host "plugin.yml written." -ForegroundColor Cyan

# ── config.yml ────────────────────────────────────────────────────────────────
@'
game_timer: 15
plot_size: 10
buffer_size: 2
word_selection_timer: 30
voting_timer: 30
min_players: 2
themes: >
  spiderman, house, alien, rat, flute, castle, dragon, spaceship, pizza, robot,
  volcano, jungle, underwater, wizard, pirate, haunted mansion, futuristic city,
  time machine, dinosaur, treehouse, submarine, black hole, ninja, samurai,
  candy land, ancient ruins, steam engine, tornado, lighthouse, colosseum,
  desert island, snow globe, potion shop, giant mushroom, cloud city,
  laboratory, clockwork, enchanted forest, sunken ship, crystal cave,
  flying carpet, carnival, ancient egypt, coral reef, polar expedition,
  neon city, wind mill, chinese dragon, warrior elephant, grand mosque
'@ | Set-Content -Path "$ProjectRoot/src/main/resources/config.yml" -Encoding UTF8

Write-Host "config.yml written." -ForegroundColor Cyan

# ── Java sources ──────────────────────────────────────────────────────────────
# NOTE: The Java source files are written separately below.
# Each file is written using a here-string and Set-Content.

$BASE = "$ProjectRoot/src/main/java/dev/ak/buildbattle"

# ===== BuildBattle.java =====
@'
package dev.onder1e.buildbattle;

import dev.onder1e.buildbattle.commands.*;
import dev.onder1e.buildbattle.game.GameManager;
import dev.onder1e.buildbattle.listener.PlayerListener;
import dev.onder1e.buildbattle.listener.WorldEditListener;
import dev.onder1e.buildbattle.packet.PacketHandler;
import dev.onder1e.buildbattle.plot.PlotManager;
import org.bukkit.*;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Objects;

public final class BuildBattle extends JavaPlugin {
    private static BuildBattle instance;
    private PlotManager plotManager;
    private GameManager gameManager;
    private PacketHandler packetHandler;
    public static final int LOBBY_SIZE    = 20;
    public static final int LOBBY_FLOOR_Y = 64;
    private Location lobbySpawn;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        World gameWorld = setupVoidWorld();
        lobbySpawn = buildLobby(gameWorld);
        Bukkit.getOnlinePlayers().forEach(p -> {
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(lobbySpawn);
        });
        plotManager   = new PlotManager(this, gameWorld);
        packetHandler = new PacketHandler(this);
        gameManager   = new GameManager(this, plotManager, packetHandler);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this, gameManager), this);
        Bukkit.getPluginManager().registerEvents(new WorldEditListener(this, gameManager, plotManager), this);
        registerCommands();
        getLogger().info("BuildBattle enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager   != null) gameManager.forceReset();
        if (packetHandler != null) packetHandler.unregister();
    }

    private World setupVoidWorld() {
        WorldCreator creator = new WorldCreator("buildbattle")
            .type(WorldType.FLAT)
            .generator(new VoidChunkGenerator())
            .generateStructures(false);
        World world = Bukkit.createWorld(creator);
        Objects.requireNonNull(world);
        world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.MOB_SPAWNING, false);
        world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
        world.setTime(6000);
        return world;
    }

    private Location buildLobby(World world) {
        int ox = -LOBBY_SIZE / 2 - 30;
        int oy = LOBBY_FLOOR_Y;
        int oz = -LOBBY_SIZE / 2;
        int w  = LOBBY_SIZE;
        int h  = 10;
        for (int x = ox; x <= ox + w; x++) {
            for (int z = oz; z <= oz + w; z++) {
                for (int y = oy; y <= oy + h; y++) {
                    boolean isWall = (x == ox || x == ox + w || z == oz || z == oz + w
                                   || y == oy || y == oy + h);
                    if (isWall) world.getBlockAt(x, y, z).setType(org.bukkit.Material.BARRIER);
                }
            }
        }
        for (int x = ox + 1; x < ox + w; x++)
            for (int z = oz + 1; z < oz + w; z++)
                world.getBlockAt(x, oy, z).setType(org.bukkit.Material.GLASS);
        Location spawn = new Location(world, ox + w / 2.0 + 0.5, oy + 1, oz + w / 2.0 + 0.5);
        world.setSpawnLocation(spawn);
        return spawn;
    }

    private void registerCommands() {
        Objects.requireNonNull(getCommand("ready"))         .setExecutor(new ReadyCommand(gameManager));
        Objects.requireNonNull(getCommand("force_start"))   .setExecutor(new ForceStartCommand(gameManager));
        Objects.requireNonNull(getCommand("choose"))        .setExecutor(new ChooseCommand(gameManager));
        Objects.requireNonNull(getCommand("force_choose"))  .setExecutor(new ForceChooseCommand(gameManager));
        Objects.requireNonNull(getCommand("done"))          .setExecutor(new DoneCommand(gameManager));
        Objects.requireNonNull(getCommand("force_end"))     .setExecutor(new ForceEndCommand(gameManager));
        Objects.requireNonNull(getCommand("vote"))          .setExecutor(new VoteCommand(gameManager));
        Objects.requireNonNull(getCommand("set_game_timer")).setExecutor(new SetGameTimerCommand(this, gameManager));
        Objects.requireNonNull(getCommand("addword"))       .setExecutor(new AddWordCommand(this, gameManager));
        Objects.requireNonNull(getCommand("removeword"))    .setExecutor(new RemoveWordCommand(this, gameManager));
    }

    public static BuildBattle getInstance() { return instance; }
    public PlotManager   getPlotManager()   { return plotManager; }
    public GameManager   getGameManager()   { return gameManager; }
    public PacketHandler getPacketHandler() { return packetHandler; }
    public Location      getLobbySpawn()    { return lobbySpawn; }

    public static class VoidChunkGenerator extends ChunkGenerator {
        @Override
        public ChunkData generateChunkData(World world, java.util.Random random,
                                           int chunkX, int chunkZ, BiomeGrid biome) {
            return createChunkData(world);
        }
    }
}
'@ | Set-Content -Path "$BASE/BuildBattle.java" -Encoding UTF8

Write-Host "All Java sources written. Running Maven build..." -ForegroundColor Yellow

# ── Build ─────────────────────────────────────────────────────────────────────
Push-Location $ProjectRoot
mvn clean package -U -q
if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "============================================" -ForegroundColor Green
    Write-Host " BUILD SUCCESSFUL!" -ForegroundColor Green
    Write-Host " JAR: target/BuildBattle-1.0.0.jar" -ForegroundColor Green
    Write-Host "============================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "INSTALLATION:" -ForegroundColor Yellow
    Write-Host "  1. Copy target/BuildBattle-1.0.0.jar to your server's plugins/ folder."
    Write-Host "  2. Ensure WorldEdit and ProtocolLib jars are also in plugins/."
    Write-Host "  3. Start / restart your Paper 1.20+ server."
    Write-Host "  4. The plugin creates a 'buildbattle' world automatically."
    Write-Host "  5. Configure config.yml at plugins/BuildBattle/config.yml."
} else {
    Write-Host "Build FAILED. Check Maven output above." -ForegroundColor Red
}
Pop-Location
