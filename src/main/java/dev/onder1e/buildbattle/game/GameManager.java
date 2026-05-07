package dev.onder1e.buildbattle.game;

import dev.onder1e.buildbattle.BuildBattle;
import dev.onder1e.buildbattle.packet.PacketHandler;
import dev.onder1e.buildbattle.plot.Plot;
import dev.onder1e.buildbattle.plot.PlotManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GameManager — central state machine.
 *
 * Fixes v1.0.1:
 *  FIX 1 — setPlotBlock(): players can replace their floor material via /setplotblock.
 *  FIX 2 — Permissions handled in plugin.yml (buildbattle.play default: true).
 *  FIX 3 — Results phase uses a configurable lobby_return_timer countdown before reset.
 *           enterReset() teleports all players to lobby and restores ADVENTURE mode.
 *  FIX 4 — currentVotingPlot exposed so PlayerListener can confine spectators.
 *  FIX 5 — Every phase transition broadcasts context-aware command help.
 *  FIX 6 — enterWordSelection() prints all 3 numbered theme options clearly.
 */
public class GameManager {

    private final BuildBattle   plugin;
    private final PlotManager   plotManager;
    private final PacketHandler packetHandler;

    private GameState currentState = GameState.LOBBY;

    private final Set<UUID>            readyPlayers    = new HashSet<>();
    private final Set<UUID>            participants    = new LinkedHashSet<>();

    // ── Word selection ────────────────────────────────────────────────────────
    private List<String>               themeCandidates = new ArrayList<>();
    private final Map<String, Integer> themeVotes      = new HashMap<>();
    private final Set<UUID>            themeVoters     = new HashSet<>();
    private String                     selectedTheme   = "";

    // ── Voting ────────────────────────────────────────────────────────────────
    private int  votingPlotIndex  = 0;
    private Plot currentVotingPlot = null; // FIX 4

    // ── Timers ────────────────────────────────────────────────────────────────
    private BukkitTask countdownTask;

    // ── Config ────────────────────────────────────────────────────────────────
    private int buildTimerMinutes;
    private int wordSelectionSeconds;
    private int votingSeconds;
    private int minPlayers;
    private int lobbyReturnSeconds; // FIX 3

    public GameManager(BuildBattle plugin, PlotManager plotManager, PacketHandler packetHandler) {
        this.plugin        = plugin;
        this.plotManager   = plotManager;
        this.packetHandler = packetHandler;
        reloadConfig();
    }

    public void reloadConfig() {
        buildTimerMinutes    = plugin.getConfig().getInt("game_timer",           15);
        wordSelectionSeconds = plugin.getConfig().getInt("word_selection_timer", 30);
        votingSeconds        = plugin.getConfig().getInt("voting_timer",         30);
        minPlayers           = plugin.getConfig().getInt("min_players",           2);
        lobbyReturnSeconds   = plugin.getConfig().getInt("lobby_return_timer",   30); // FIX 3
    }

    // =========================================================================
    // ── LOBBY ─────────────────────────────────────────────────────────────────
    // =========================================================================

    public void addPlayer(Player player) {
        participants.add(player.getUniqueId());
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(plugin.getLobbySpawn());
        broadcast(Component.text(player.getName() + " joined! ("
                + participants.size() + " players)", NamedTextColor.YELLOW));
        sendLobbyHelp(player); // FIX 5
        checkAutoStart();
    }

    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        participants.remove(uuid);
        readyPlayers.remove(uuid);
        themeVoters.remove(uuid);
        Plot plot = plotManager.getPlot(uuid);
        if (plot != null) plot.setDone(true);
    }

    public boolean toggleReady(Player player) {
        if (currentState != GameState.LOBBY) {
            player.sendMessage(Component.text("You can only ready up in the lobby!", NamedTextColor.RED));
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (readyPlayers.contains(uuid)) {
            readyPlayers.remove(uuid);
            broadcast(Component.text(player.getName() + " is no longer ready.", NamedTextColor.GRAY));
            return false;
        } else {
            readyPlayers.add(uuid);
            broadcast(Component.text(player.getName() + " is READY! ["
                    + readyPlayers.size() + "/" + participants.size() + "]", NamedTextColor.GREEN));
            checkAutoStart();
            return true;
        }
    }

    private void checkAutoStart() {
        if (currentState != GameState.LOBBY) return;
        if (participants.size() < minPlayers) return;
        if (readyPlayers.containsAll(participants)) transitionTo(GameState.WORD_SELECTION);
    }

    public void forceStart(Player admin) {
        if (currentState != GameState.LOBBY) {
            admin.sendMessage(Component.text("Game is not in LOBBY state.", NamedTextColor.RED));
            return;
        }
        if (participants.isEmpty()) {
            admin.sendMessage(Component.text("No players in the lobby!", NamedTextColor.RED));
            return;
        }
        broadcast(Component.text("[Admin] " + admin.getName() + " force-started!", NamedTextColor.GOLD));
        transitionTo(GameState.WORD_SELECTION);
    }

    // =========================================================================
    // ── WORD_SELECTION ────────────────────────────────────────────────────────
    // =========================================================================

    // FIX 6: clear numbered theme listing + /choose instructions
    private void enterWordSelection() {
        cancelCountdown();
        themeVotes.clear();
        themeVoters.clear();
        themeCandidates = pickThemeCandidates(3);
        themeCandidates.forEach(t -> themeVotes.put(t, 0));

        broadcast(sep());
        broadcast(Component.text("         VOTE FOR YOUR THEME", NamedTextColor.GOLD, TextDecoration.BOLD));
        broadcast(sep());
        for (int i = 0; i < themeCandidates.size(); i++) {
            broadcast(Component.text(
                    "  [" + (i + 1) + "]  " + themeCandidates.get(i).toUpperCase(),
                    NamedTextColor.AQUA, TextDecoration.BOLD));
        }
        broadcast(Component.empty());
        broadcast(Component.text("  /choose <1|2|3>  or  /choose <name>  to vote",
                NamedTextColor.YELLOW));
        broadcast(Component.text("  " + wordSelectionSeconds + "s to vote — most votes wins!",
                NamedTextColor.GRAY));
        broadcast(sep());

        startCountdown(wordSelectionSeconds, () -> {
            selectedTheme = pickWinningTheme();
            broadcast(Component.text("Theme: " + selectedTheme.toUpperCase(),
                    NamedTextColor.GREEN, TextDecoration.BOLD));
            transitionTo(GameState.BUILDING);
        });
    }

    public void castThemeVote(Player player, String input) {
        if (currentState != GameState.WORD_SELECTION) {
            player.sendMessage(Component.text("Theme voting is not active right now.", NamedTextColor.RED));
            return;
        }
        String matched = resolveTheme(input);
        if (matched == null) {
            player.sendMessage(Component.text(
                    "Unknown option. Choices: " + String.join(" | ", themeCandidates),
                    NamedTextColor.RED));
            return;
        }
        themeVoters.add(player.getUniqueId());
        themeVotes.merge(matched, 1, Integer::sum);
        player.sendMessage(Component.text("Voted for: " + matched.toUpperCase(), NamedTextColor.GREEN));
        broadcast(Component.text(player.getName() + " voted!", NamedTextColor.GRAY));
    }

    public void forceTheme(Player admin, String theme) {
        if (currentState != GameState.WORD_SELECTION) {
            admin.sendMessage(Component.text("Not in WORD_SELECTION phase.", NamedTextColor.RED));
            return;
        }
        cancelCountdown();
        selectedTheme = theme;
        broadcast(Component.text("[Admin] Theme forced: " + theme.toUpperCase(), NamedTextColor.GOLD));
        transitionTo(GameState.BUILDING);
    }

    private String resolveTheme(String input) {
        try {
            int idx = Integer.parseInt(input) - 1;
            if (idx >= 0 && idx < themeCandidates.size()) return themeCandidates.get(idx);
        } catch (NumberFormatException ignored) {}
        String lower = input.toLowerCase();
        for (String c : themeCandidates) {
            if (c.toLowerCase().contains(lower)) return c;
        }
        return null;
    }

    private String pickWinningTheme() {
        return themeVotes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(themeCandidates.get(0));
    }

    private List<String> pickThemeCandidates(int count) {
        List<String> pool = getThemePool();
        Collections.shuffle(pool);
        return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
    }

    public List<String> getThemePool() {
        String raw = plugin.getConfig().getString("themes", "house, castle");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    // =========================================================================
    // ── BUILDING ──────────────────────────────────────────────────────────────
    // =========================================================================

    private void enterBuilding() {
        cancelCountdown();
        List<Player> activePlayers = getOnlineParticipants();
        if (activePlayers.isEmpty()) {
            broadcast(Component.text("Not enough players — returning to lobby.", NamedTextColor.RED));
            transitionTo(GameState.RESET);
            return;
        }

        plotManager.generatePlots(activePlayers);
        packetHandler.enableBuildingFilter(plotManager);

        for (Player player : activePlayers) {
            Plot plot = plotManager.getPlot(player);
            if (plot == null) continue;
            player.setGameMode(GameMode.CREATIVE);
            player.teleport(plot.getCentreLocation(plotManager.getWorld()));
        }

        broadcast(sep());
        broadcast(Component.text("         BUILD BATTLE", NamedTextColor.GOLD, TextDecoration.BOLD));
        broadcast(sep());
        broadcast(Component.text("  Theme : " + selectedTheme.toUpperCase(),
                NamedTextColor.AQUA, TextDecoration.BOLD));
        broadcast(Component.text("  Time  : " + buildTimerMinutes + " minutes", NamedTextColor.YELLOW));
        broadcast(Component.empty());
        // FIX 5: building command help
        broadcast(Component.text("  Commands:", NamedTextColor.WHITE, TextDecoration.BOLD));
        broadcast(Component.text("  /done                    — finish early", NamedTextColor.GRAY));
        broadcast(Component.text("  /setplotblock <material> — change floor block", NamedTextColor.GRAY));
        broadcast(sep());

        startCountdown(buildTimerMinutes * 60, () -> {
            broadcast(Component.text("Time's up! Moving to voting...",
                    NamedTextColor.RED, TextDecoration.BOLD));
            transitionTo(GameState.VOTING);
        });
    }

    public void markDone(Player player) {
        if (currentState != GameState.BUILDING) {
            player.sendMessage(Component.text("You can only use /done during building.", NamedTextColor.RED));
            return;
        }
        Plot plot = plotManager.getPlot(player);
        if (plot == null) { player.sendMessage(Component.text("You don't have a plot!", NamedTextColor.RED)); return; }
        if (plot.isDone()) { player.sendMessage(Component.text("Already marked done.", NamedTextColor.GRAY)); return; }
        plot.setDone(true);
        player.setGameMode(GameMode.SPECTATOR);
        broadcast(Component.text(player.getName() + " finished building!", NamedTextColor.GREEN));
        if (plotManager.getOrderedPlots().stream().allMatch(Plot::isDone)) {
            broadcast(Component.text("Everyone is done — starting voting early!", NamedTextColor.GOLD));
            transitionTo(GameState.VOTING);
        }
    }

    // FIX 1: /setplotblock — replace the y=64 floor of the player's plot
    public void setPlotBlock(Player player, String materialName) {
        if (currentState != GameState.BUILDING) {
            player.sendMessage(Component.text("You can only change your floor during building.",
                    NamedTextColor.RED));
            return;
        }
        Plot plot = plotManager.getPlot(player);
        if (plot == null) {
            player.sendMessage(Component.text("You don't have a plot!", NamedTextColor.RED));
            return;
        }
        Material mat = Material.matchMaterial(materialName.toUpperCase().replace("-", "_"));
        if (mat == null || !mat.isBlock() || !mat.isSolid()) {
            player.sendMessage(Component.text(
                    "'" + materialName + "' is not a valid solid block.", NamedTextColor.RED));
            player.sendMessage(Component.text(
                    "Examples: stone, oak_planks, sand, dirt, snow_block", NamedTextColor.GRAY));
            return;
        }
        World world = plotManager.getWorld();
        for (int x = plot.getInnerMinX(); x <= plot.getInnerMaxX(); x++) {
            for (int z = plot.getInnerMinZ(); z <= plot.getInnerMaxZ(); z++) {
                world.getBlockAt(x, 64, z).setType(mat, false);
            }
        }
        player.sendMessage(Component.text(
                "Floor changed to " + mat.name().toLowerCase() + "!", NamedTextColor.GREEN));
        broadcast(Component.text(player.getName() + " changed their floor to "
                + mat.name().toLowerCase() + ".", NamedTextColor.GRAY));
    }

    // =========================================================================
    // ── VOTING ────────────────────────────────────────────────────────────────
    // =========================================================================

    private void enterVoting() {
        cancelCountdown();
        votingPlotIndex = 0;
        getOnlineParticipants().forEach(p -> p.setGameMode(GameMode.SPECTATOR));

        broadcast(sep());
        broadcast(Component.text("           VOTING PHASE", NamedTextColor.GOLD, TextDecoration.BOLD));
        broadcast(sep());
        // FIX 5: voting command help
        broadcast(Component.text("  /vote <1-10> — score the current build", NamedTextColor.YELLOW));
        broadcast(Component.text("  You cannot vote on your own build.", NamedTextColor.GRAY));
        broadcast(sep());

        advanceVoting();
    }

    private void advanceVoting() {
        List<Plot> plots = plotManager.getOrderedPlots();
        if (votingPlotIndex >= plots.size()) {
            currentVotingPlot = null;
            transitionTo(GameState.RESULTS);
            return;
        }

        Plot currentPlot = plots.get(votingPlotIndex);
        currentVotingPlot = currentPlot; // FIX 4: expose for spectator confinement

        Player owner = Bukkit.getPlayer(currentPlot.getOwnerUUID());
        String ownerName = owner != null ? owner.getName() : "Unknown";

        broadcast(Component.text("Now viewing: " + ownerName + "'s build  ("
                + (votingPlotIndex + 1) + "/" + plots.size() + ")", NamedTextColor.AQUA, TextDecoration.BOLD));

        Location centre = currentPlot.getCentreLocation(plotManager.getWorld());
        centre.add(0, 5, 0);
        List<Player> online = getOnlineParticipants();
        online.forEach(p -> p.teleport(centre));
        packetHandler.refreshVotingChunks(currentPlot, online);

        broadcast(Component.text("  /vote <1-10>  —  " + votingSeconds + "s to vote!",
                NamedTextColor.YELLOW));

        startCountdown(votingSeconds, () -> {
            votingPlotIndex++;
            advanceVoting();
        });
    }

    public void castVote(Player voter, int score) {
        if (currentState != GameState.VOTING) {
            voter.sendMessage(Component.text("Voting is not active right now.", NamedTextColor.RED));
            return;
        }
        if (score < 1 || score > 10) {
            voter.sendMessage(Component.text("Score must be between 1 and 10.", NamedTextColor.RED));
            return;
        }
        List<Plot> plots = plotManager.getOrderedPlots();
        if (votingPlotIndex >= plots.size()) {
            voter.sendMessage(Component.text("No plot is being voted on.", NamedTextColor.RED));
            return;
        }
        Plot currentPlot = plots.get(votingPlotIndex);
        if (currentPlot.getOwnerUUID().equals(voter.getUniqueId())) {
            voter.sendMessage(Component.text("You cannot vote on your own build!", NamedTextColor.RED));
            return;
        }
        if (!currentPlot.addVote(voter.getUniqueId(), score)) {
            voter.sendMessage(Component.text("You already voted on this build!", NamedTextColor.GRAY));
            return;
        }
        voter.sendMessage(Component.text("Voted " + score + "/10!", NamedTextColor.GREEN));
        broadcast(Component.text(voter.getName() + " voted!", NamedTextColor.GRAY));
    }

    // =========================================================================
    // ── RESULTS ───────────────────────────────────────────────────────────────
    // =========================================================================

    // FIX 3: configurable lobby_return_timer countdown shown in chat
    private void enterResults() {
        cancelCountdown();
        packetHandler.disableFilter();
        currentVotingPlot = null;

        broadcast(sep());
        broadcast(Component.text("            RESULTS", NamedTextColor.GOLD, TextDecoration.BOLD));
        broadcast(sep());
        broadcast(Component.text("  Theme: " + selectedTheme.toUpperCase(), NamedTextColor.AQUA));
        broadcast(Component.empty());

        List<Plot> ranked = new ArrayList<>(plotManager.getOrderedPlots());
        ranked.sort((a, b) -> Double.compare(b.getAverageScore(), a.getAverageScore()));

        String[] medals = {"§6🥇", "§7🥈", "§c🥉"};
        int pos = 1;
        for (Plot plot : ranked) {
            Player owner = Bukkit.getPlayer(plot.getOwnerUUID());
            String name  = owner != null ? owner.getName() : "Unknown";
            String avg   = String.format("%.1f", plot.getAverageScore());
            String prefix = pos <= 3 ? medals[pos - 1] + " " : "   ";
            broadcast(Component.text("  " + prefix + "#" + pos + "  " + name
                    + "  —  " + avg + "/10  (" + plot.getVoteCount() + " votes)",
                    NamedTextColor.YELLOW));
            pos++;
        }

        broadcast(Component.empty());
        if (!ranked.isEmpty()) {
            Player winner = Bukkit.getPlayer(ranked.get(0).getOwnerUUID());
            broadcast(Component.text("  Winner: " + (winner != null ? winner.getName() : "Unknown") + "!",
                    NamedTextColor.GOLD, TextDecoration.BOLD));
        }
        broadcast(Component.empty());
        broadcast(Component.text("  Returning to lobby in " + lobbyReturnSeconds + "s...",
                NamedTextColor.GRAY));
        broadcast(sep());

        // Use the shared countdown so players see the timer ticking down
        startCountdown(lobbyReturnSeconds, () -> transitionTo(GameState.RESET));
    }

    // =========================================================================
    // ── RESET ─────────────────────────────────────────────────────────────────
    // =========================================================================

    // FIX 3: grab participants BEFORE clearing, teleport them all to lobby
    private void enterReset() {
        cancelCountdown();
        packetHandler.disableFilter();
        currentVotingPlot = null;

        broadcast(Component.text("Resetting — see you in the lobby!", NamedTextColor.GRAY));
        plotManager.destroyAllPlots();

        List<Player> online = getOnlineParticipants(); // snapshot before clear

        participants.clear();
        readyPlayers.clear();
        themeVotes.clear();
        themeVoters.clear();
        themeCandidates.clear();
        selectedTheme   = "";
        votingPlotIndex = 0;

        // Teleport everyone back and restore adventure mode
        for (Player p : online) {
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(plugin.getLobbySpawn());
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> transitionTo(GameState.LOBBY), 20L);
    }

    public void forceReset() { transitionTo(GameState.RESET); }

    // =========================================================================
    // ── State dispatcher ──────────────────────────────────────────────────────
    // =========================================================================

    public void transitionTo(GameState newState) {
        plugin.getLogger().info("[GameManager] " + currentState + " -> " + newState);
        currentState = newState;
        switch (newState) {
            case LOBBY          -> enterLobby();
            case WORD_SELECTION -> enterWordSelection();
            case BUILDING       -> enterBuilding();
            case VOTING         -> enterVoting();
            case RESULTS        -> enterResults();
            case RESET          -> enterReset();
        }
    }

    private void enterLobby() {
        broadcast(sep());
        broadcast(Component.text("        BUILD BATTLE  —  LOBBY", NamedTextColor.GOLD, TextDecoration.BOLD));
        broadcast(sep());
        // FIX 5: lobby commands
        broadcast(Component.text("  /ready — toggle your ready status", NamedTextColor.YELLOW));
        broadcast(Component.text("  Waiting for " + minPlayers + "+ players...", NamedTextColor.GRAY));
        broadcast(sep());
    }

    // FIX 5: per-player help sent on join
    private void sendLobbyHelp(Player player) {
        player.sendMessage(sep());
        player.sendMessage(Component.text("  Welcome to Build Battle!", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("  /ready — toggle your ready status", NamedTextColor.YELLOW));
        player.sendMessage(sep());
    }

    // =========================================================================
    // ── Admin force-end ───────────────────────────────────────────────────────
    // =========================================================================

    public void forceEnd(Player admin) {
        switch (currentState) {
            case WORD_SELECTION -> {
                broadcast(Component.text("[Admin] " + admin.getName()
                        + " force-ended theme voting.", NamedTextColor.GOLD));
                cancelCountdown();
                selectedTheme = themeCandidates.isEmpty() ? "house" : pickWinningTheme();
                transitionTo(GameState.BUILDING);
            }
            case BUILDING -> {
                broadcast(Component.text("[Admin] " + admin.getName()
                        + " ended building.", NamedTextColor.GOLD));
                cancelCountdown();
                transitionTo(GameState.VOTING);
            }
            case VOTING -> {
                broadcast(Component.text("[Admin] " + admin.getName()
                        + " ended voting.", NamedTextColor.GOLD));
                cancelCountdown();
                currentVotingPlot = null;
                transitionTo(GameState.RESULTS);
            }
            case RESULTS -> {
                broadcast(Component.text("[Admin] " + admin.getName()
                        + " skipped to reset.", NamedTextColor.GOLD));
                cancelCountdown();
                transitionTo(GameState.RESET);
            }
            default -> admin.sendMessage(
                    Component.text("Cannot force-end in state: " + currentState, NamedTextColor.RED));
        }
    }

    // =========================================================================
    // ── Countdown ─────────────────────────────────────────────────────────────
    // =========================================================================

    private void startCountdown(int totalSeconds, Runnable onExpire) {
        cancelCountdown();
        int[] remaining = {totalSeconds};
        Set<Integer> alertAt = Set.of(60, 30, 10, 5, 4, 3, 2, 1);
        countdownTask = new BukkitRunnable() {
            @Override public void run() {
                if (remaining[0] <= 0) { cancel(); onExpire.run(); return; }
                if (alertAt.contains(remaining[0])) {
                    broadcast(Component.text("  " + remaining[0] + "s...", NamedTextColor.YELLOW));
                }
                remaining[0]--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null && !countdownTask.isCancelled()) countdownTask.cancel();
    }

    // ── Separator ─────────────────────────────────────────────────────────────
    private Component sep() {
        return Component.text("  " + "─".repeat(38), NamedTextColor.DARK_GRAY);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void broadcast(Component msg) { Bukkit.broadcast(msg); }

    private List<Player> getOnlineParticipants() {
        return participants.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public GameState    getCurrentState()      { return currentState; }
    public Set<UUID>    getReadyPlayers()      { return Collections.unmodifiableSet(readyPlayers); }
    public Set<UUID>    getParticipants()      { return Collections.unmodifiableSet(participants); }
    public String       getSelectedTheme()     { return selectedTheme; }
    public int          getBuildTimerMinutes() { return buildTimerMinutes; }
    public void         setBuildTimerMinutes(int m) { this.buildTimerMinutes = m; }
    public List<String> getThemeCandidates()   { return Collections.unmodifiableList(themeCandidates); }
    public Plot         getCurrentVotingPlot() { return currentVotingPlot; } // FIX 4
}
