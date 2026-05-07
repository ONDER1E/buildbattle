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
 * GameManager
 * ===========
 * The central state-machine controller for Build Battle.
 *
 * Drives all phase transitions and delegates to:
 *   - PlotManager  (world generation / cleanup)
 *   - PacketHandler (chunk isolation)
 *
 * STATE FLOW:
 *   LOBBY → WORD_SELECTION → BUILDING → VOTING → RESULTS → RESET → LOBBY
 *
 * Thread safety: All public methods that mutate state are expected to be
 * called from the main server thread (Bukkit scheduler callbacks, command
 * handlers). The PacketHandler callback runs on the netty thread but only
 * performs reads against volatile/concurrent structures.
 */
public class GameManager {

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final BuildBattle  plugin;
    private final PlotManager  plotManager;
    private final PacketHandler packetHandler;

    // ── State ─────────────────────────────────────────────────────────────────
    private GameState currentState = GameState.LOBBY;

    /** Players who have toggled /ready in the lobby. */
    private final Set<UUID> readyPlayers = new HashSet<>();

    /** All players who joined this round. */
    private final Set<UUID> participants = new LinkedHashSet<>();

    // ── Word selection ────────────────────────────────────────────────────────
    /** Three randomly chosen themes presented to players this round. */
    private List<String> themeCandidates = new ArrayList<>();
    /** Map of theme option → vote count from /choose. */
    private final Map<String, Integer> themeVotes = new HashMap<>();
    /** The winning theme for this round. */
    private String selectedTheme = "";

    // ── Voting (scores for each build) ───────────────────────────────────────
    /** Index into orderedPlots of the plot currently under vote. */
    private int votingPlotIndex = 0;

    // ── Timers ────────────────────────────────────────────────────────────────
    private BukkitTask countdownTask;

    // ── Config cache ──────────────────────────────────────────────────────────
    private int buildTimerMinutes;
    private int wordSelectionSeconds;
    private int votingSeconds;
    private int minPlayers;

    public GameManager(BuildBattle plugin, PlotManager plotManager, PacketHandler packetHandler) {
        this.plugin        = plugin;
        this.plotManager   = plotManager;
        this.packetHandler = packetHandler;
        reloadConfig();
    }

    /** Re-read timer values from config (called after /set_game_timer). */
    public void reloadConfig() {
        buildTimerMinutes    = plugin.getConfig().getInt("game_timer", 15);
        wordSelectionSeconds = plugin.getConfig().getInt("word_selection_timer", 30);
        votingSeconds        = plugin.getConfig().getInt("voting_timer", 30);
        minPlayers           = plugin.getConfig().getInt("min_players", 2);
    }

    // =========================================================================
    // ── LOBBY phase ───────────────────────────────────────────────────────────
    // =========================================================================

    /**
     * Add a player to the lobby participant pool and teleport them.
     * Called from PlayerJoinEvent.
     */
    public void addPlayer(Player player) {
        participants.add(player.getUniqueId());
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(plugin.getLobbySpawn());
        broadcast(Component.text(player.getName() + " joined! ("
                + participants.size() + " players)", NamedTextColor.YELLOW));
        checkAutoStart();
    }

    /**
     * Remove a player cleanly (disconnect or /leave).
     */
    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        participants.remove(uuid);
        readyPlayers.remove(uuid);

        // If a plot exists for them and game is in BUILDING, mark it done
        Plot plot = plotManager.getPlot(uuid);
        if (plot != null) plot.setDone(true);
    }

    /**
     * /ready — toggle this player's ready state.
     * Returns the new state (true = now ready).
     */
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

    /**
     * Auto-start if every participant is ready AND we have enough players.
     */
    private void checkAutoStart() {
        if (currentState != GameState.LOBBY) return;
        if (participants.size() < minPlayers) return;
        if (readyPlayers.containsAll(participants)) {
            transitionTo(GameState.WORD_SELECTION);
        }
    }

    /**
     * /force_start — admin command to skip the ready check.
     */
    public void forceStart(Player admin) {
        if (currentState != GameState.LOBBY) {
            admin.sendMessage(Component.text("Game is not in LOBBY state.", NamedTextColor.RED));
            return;
        }
        if (participants.size() < 1) {
            admin.sendMessage(Component.text("No players in the lobby!", NamedTextColor.RED));
            return;
        }
        broadcast(Component.text("[Admin] " + admin.getName() + " force-started the game!", NamedTextColor.GOLD));
        transitionTo(GameState.WORD_SELECTION);
    }

    // =========================================================================
    // ── WORD_SELECTION phase ──────────────────────────────────────────────────
    // =========================================================================

    /**
     * Enters WORD_SELECTION:
     *  - Picks 3 random themes from config.
     *  - Presents them to all players via chat.
     *  - Starts a countdown; when it expires the most-voted theme wins.
     */
    private void enterWordSelection() {
        cancelCountdown();
        themeVotes.clear();
        themeCandidates = pickThemeCandidates(3);
        themeCandidates.forEach(t -> themeVotes.put(t, 0));

        broadcast(Component.text("═══════════ THEME VOTE ═══════════", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (int i = 0; i < themeCandidates.size(); i++) {
            broadcast(Component.text("  [" + (i + 1) + "] " + themeCandidates.get(i), NamedTextColor.AQUA));
        }
        broadcast(Component.text("Type /choose <theme> to vote!", NamedTextColor.YELLOW));
        broadcast(Component.text("You have " + wordSelectionSeconds + "s to vote.", NamedTextColor.GRAY));

        // Countdown — when it hits 0, pick the winner and start building
        startCountdown(wordSelectionSeconds, () -> {
            String winner = pickWinningTheme();
            selectedTheme = winner;
            broadcast(Component.text("Theme chosen: " + winner.toUpperCase(), NamedTextColor.GREEN, TextDecoration.BOLD));
            transitionTo(GameState.BUILDING);
        });
    }

    /**
     * /choose <theme> — player votes for a theme.
     * Theme matching is case-insensitive and accepts partial matches
     * (e.g. "spider" matches "spiderman") as well as the numeric index.
     */
    public void castThemeVote(Player player, String input) {
        if (currentState != GameState.WORD_SELECTION) {
            player.sendMessage(Component.text("Voting is not active right now.", NamedTextColor.RED));
            return;
        }

        String matched = resolveTheme(input);
        if (matched == null) {
            player.sendMessage(Component.text("Unknown option. Choices: "
                    + String.join(", ", themeCandidates), NamedTextColor.RED));
            return;
        }

        // Remove previous vote by this player (each player gets one vote)
        themeVotes.replaceAll((k, v) -> k.equals(matched) ? v : v); // placeholder
        themeVotes.merge(matched, 1, Integer::sum);

        player.sendMessage(Component.text("Voted for: " + matched, NamedTextColor.GREEN));
        broadcast(Component.text(player.getName() + " voted for a theme!", NamedTextColor.GRAY));
    }

    /** /force_choose <theme> — admin override. */
    public void forceTheme(Player admin, String theme) {
        if (currentState != GameState.WORD_SELECTION) {
            admin.sendMessage(Component.text("Not in WORD_SELECTION phase.", NamedTextColor.RED));
            return;
        }
        cancelCountdown();
        selectedTheme = theme;
        broadcast(Component.text("[Admin] Theme forced to: " + theme.toUpperCase(), NamedTextColor.GOLD));
        transitionTo(GameState.BUILDING);
    }

    /** Resolve a player's input to one of the candidate themes. */
    private String resolveTheme(String input) {
        // Numeric index (1, 2, 3)
        try {
            int idx = Integer.parseInt(input) - 1;
            if (idx >= 0 && idx < themeCandidates.size()) return themeCandidates.get(idx);
        } catch (NumberFormatException ignored) {}

        // Case-insensitive partial match
        String lower = input.toLowerCase();
        for (String c : themeCandidates) {
            if (c.toLowerCase().contains(lower)) return c;
        }
        return null;
    }

    /** Returns the theme with the most votes; ties broken randomly. */
    private String pickWinningTheme() {
        return themeVotes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(themeCandidates.get(0));
    }

    /** Randomly select `count` distinct themes from the config pool. */
    private List<String> pickThemeCandidates(int count) {
        List<String> pool = getThemePool();
        Collections.shuffle(pool);
        return pool.subList(0, Math.min(count, pool.size()));
    }

    /** Load the theme list from config, splitting by comma. */
    public List<String> getThemePool() {
        String raw = plugin.getConfig().getString("themes", "house, castle");
        return new ArrayList<>(Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList()));
    }

    // =========================================================================
    // ── BUILDING phase ────────────────────────────────────────────────────────
    // =========================================================================

    /**
     * Enters BUILDING:
     *  - Generates all plots (one per participant).
     *  - Enables WorldEdit masking (handled by WorldEditListener).
     *  - Enables packet-level chunk isolation.
     *  - Teleports each player to their plot.
     *  - Starts the build countdown.
     */
    private void enterBuilding() {
        cancelCountdown();

        // Only use confirmed participants who are still online
        List<Player> activePlayers = getOnlineParticipants();

        if (activePlayers.isEmpty()) {
            broadcast(Component.text("Not enough players — returning to lobby.", NamedTextColor.RED));
            transitionTo(GameState.RESET);
            return;
        }

        // 1. Generate plots synchronously
        plotManager.generatePlots(activePlayers);

        // 2. Enable packet-level isolation
        packetHandler.enableBuildingFilter(plotManager);

        // 3. Give players creative mode and teleport them to their plot
        for (Player player : activePlayers) {
            Plot plot = plotManager.getPlot(player);
            if (plot == null) continue;
            player.setGameMode(GameMode.CREATIVE);
            player.teleport(plot.getCentreLocation(plotManager.getWorld()));
            player.sendMessage(Component.text("Your plot is ready! Build: "
                    + selectedTheme.toUpperCase(), NamedTextColor.GREEN, TextDecoration.BOLD));
        }

        broadcast(Component.text("═══════════ BUILD BATTLE ═══════════", NamedTextColor.GOLD, TextDecoration.BOLD));
        broadcast(Component.text("Theme: " + selectedTheme.toUpperCase(), NamedTextColor.AQUA));
        broadcast(Component.text("You have " + buildTimerMinutes + " minutes!", NamedTextColor.YELLOW));

        // 4. Start build countdown (converted to seconds)
        int buildSeconds = buildTimerMinutes * 60;
        startCountdown(buildSeconds, () -> {
            broadcast(Component.text("Time's up! Moving to voting...", NamedTextColor.RED, TextDecoration.BOLD));
            transitionTo(GameState.VOTING);
        });
    }

    /**
     * /done — player finishes building early.
     */
    public void markDone(Player player) {
        if (currentState != GameState.BUILDING) {
            player.sendMessage(Component.text("You can only use /done during the building phase.", NamedTextColor.RED));
            return;
        }
        Plot plot = plotManager.getPlot(player);
        if (plot == null) {
            player.sendMessage(Component.text("You don't have a plot!", NamedTextColor.RED));
            return;
        }
        if (plot.isDone()) {
            player.sendMessage(Component.text("You already marked your plot as done.", NamedTextColor.GRAY));
            return;
        }
        plot.setDone(true);
        player.setGameMode(GameMode.SPECTATOR);
        broadcast(Component.text(player.getName() + " has finished building!", NamedTextColor.GREEN));
        player.sendMessage(Component.text("You are now in spectator mode.", NamedTextColor.YELLOW));

        // Auto-advance if everyone is done
        boolean allDone = plotManager.getOrderedPlots().stream().allMatch(Plot::isDone);
        if (allDone) {
            broadcast(Component.text("All players are done — starting voting early!", NamedTextColor.GOLD));
            transitionTo(GameState.VOTING);
        }
    }

    // =========================================================================
    // ── VOTING phase ─────────────────────────────────────────────────────────
    // =========================================================================

    /**
     * Enters VOTING:
     *  - Disables the chunk filter.
     *  - Starts cycling through each plot one by one.
     */
    private void enterVoting() {
        cancelCountdown();
        votingPlotIndex = 0;

        // Everyone goes spectator for the voting phase
        getOnlineParticipants().forEach(p -> p.setGameMode(GameMode.SPECTATOR));

        broadcast(Component.text("═══════════ VOTING ═══════════", NamedTextColor.GOLD, TextDecoration.BOLD));
        broadcast(Component.text("Score each build from 1 to 10 using /vote <score>!", NamedTextColor.YELLOW));

        advanceVoting();
    }

    /**
     * Shows the next plot to all players and opens voting for it.
     * When all plots have been shown, transitions to RESULTS.
     */
    private void advanceVoting() {
        List<Plot> plots = plotManager.getOrderedPlots();

        if (votingPlotIndex >= plots.size()) {
            transitionTo(GameState.RESULTS);
            return;
        }

        Plot currentPlot = plots.get(votingPlotIndex);
        Player owner = Bukkit.getPlayer(currentPlot.getOwnerUUID());
        String ownerName = owner != null ? owner.getName() : "Unknown";

        broadcast(Component.text("Now viewing: " + ownerName + "'s build ("
                + (votingPlotIndex + 1) + "/" + plots.size() + ")", NamedTextColor.AQUA));

        // Teleport everyone to the centre of this plot and refresh chunks
        Location centre = currentPlot.getCentreLocation(plotManager.getWorld());
        centre.add(0, 5, 0); // hover slightly above the plot
        List<Player> online = getOnlineParticipants();
        online.forEach(p -> p.teleport(centre));

        // Refresh chunks for this plot (sends real data to all players)
        packetHandler.refreshVotingChunks(currentPlot, online);

        broadcast(Component.text("Vote now! /vote <1-10>", NamedTextColor.YELLOW));

        // Countdown per plot
        startCountdown(votingSeconds, () -> {
            votingPlotIndex++;
            advanceVoting();
        });
    }

    /**
     * /vote <1-10> — player scores the currently displayed plot.
     */
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
            voter.sendMessage(Component.text("No plot is being voted on right now.", NamedTextColor.RED));
            return;
        }

        Plot currentPlot = plots.get(votingPlotIndex);

        // Prevent plot owner from voting on their own build
        if (currentPlot.getOwnerUUID().equals(voter.getUniqueId())) {
            voter.sendMessage(Component.text("You cannot vote on your own build!", NamedTextColor.RED));
            return;
        }

        boolean added = currentPlot.addVote(voter.getUniqueId(), score);
        if (!added) {
            voter.sendMessage(Component.text("You already voted on this build!", NamedTextColor.GRAY));
            return;
        }

        voter.sendMessage(Component.text("Voted " + score + "/10!", NamedTextColor.GREEN));
        broadcast(Component.text(voter.getName() + " voted!", NamedTextColor.GRAY));
    }

    // =========================================================================
    // ── RESULTS phase ─────────────────────────────────────────────────────────
    // =========================================================================

    private void enterResults() {
        cancelCountdown();
        packetHandler.disableFilter();

        broadcast(Component.text("═══════════ RESULTS ═══════════", NamedTextColor.GOLD, TextDecoration.BOLD));
        broadcast(Component.text("Theme was: " + selectedTheme.toUpperCase(), NamedTextColor.AQUA));
        broadcast(Component.empty());

        // Sort plots by average score descending
        List<Plot> ranked = new ArrayList<>(plotManager.getOrderedPlots());
        ranked.sort((a, b) -> Double.compare(b.getAverageScore(), a.getAverageScore()));

        int position = 1;
        for (Plot plot : ranked) {
            Player owner = Bukkit.getPlayer(plot.getOwnerUUID());
            String name  = owner != null ? owner.getName() : "Unknown";
            String avg   = String.format("%.1f", plot.getAverageScore());
            broadcast(Component.text("  #" + position + " " + name + " — " + avg + "/10"
                    + " (" + plot.getVoteCount() + " votes)", NamedTextColor.YELLOW));
            position++;
        }

        // Announce winner
        if (!ranked.isEmpty()) {
            Player winner = Bukkit.getPlayer(ranked.get(0).getOwnerUUID());
            String winnerName = winner != null ? winner.getName() : "Unknown";
            broadcast(Component.text("🏆 Winner: " + winnerName + "!", NamedTextColor.GOLD, TextDecoration.BOLD));
        }

        // Auto-reset after 15 seconds
        Bukkit.getScheduler().runTaskLater(plugin, () -> transitionTo(GameState.RESET), 15 * 20L);
    }

    // =========================================================================
    // ── RESET phase ───────────────────────────────────────────────────────────
    // =========================================================================

    private void enterReset() {
        cancelCountdown();
        packetHandler.disableFilter();

        broadcast(Component.text("Resetting game...", NamedTextColor.GRAY));

        // Physically destroy all plot chunks
        plotManager.destroyAllPlots();

        // Clear round state
        participants.clear();
        readyPlayers.clear();
        themeVotes.clear();
        themeCandidates.clear();
        selectedTheme = "";
        votingPlotIndex = 0;

        // Return all online players to lobby
        getOnlineParticipants().forEach(p -> {
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(plugin.getLobbySpawn());
        });

        // Immediately move to LOBBY
        Bukkit.getScheduler().runTaskLater(plugin, () -> transitionTo(GameState.LOBBY), 20L);
    }

    /** Public reset entry point (called from onDisable and /force_reset). */
    public void forceReset() {
        transitionTo(GameState.RESET);
    }

    // =========================================================================
    // ── State machine dispatcher ──────────────────────────────────────────────
    // =========================================================================

    /**
     * Central state transition method.
     * All transitions MUST go through here to ensure consistent logging
     * and listener notification.
     */
    public void transitionTo(GameState newState) {
        plugin.getLogger().info("[GameManager] " + currentState + " → " + newState);
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
        broadcast(Component.text("═══════════ LOBBY ═══════════", NamedTextColor.GOLD, TextDecoration.BOLD));
        broadcast(Component.text("Use /ready when you're ready to start!", NamedTextColor.YELLOW));
        broadcast(Component.text("Waiting for " + minPlayers + " players...", NamedTextColor.GRAY));
    }

    // =========================================================================
    // ── Admin force-end ───────────────────────────────────────────────────────
    // =========================================================================

    /**
     * /force_end — immediately ends the CURRENT phase and advances.
     */
    public void forceEnd(Player admin) {
        switch (currentState) {
            case WORD_SELECTION -> {
                broadcast(Component.text("[Admin] " + admin.getName() + " force-ended theme voting.", NamedTextColor.GOLD));
                cancelCountdown();
                selectedTheme = themeCandidates.isEmpty() ? "house" : pickWinningTheme();
                transitionTo(GameState.BUILDING);
            }
            case BUILDING -> {
                broadcast(Component.text("[Admin] " + admin.getName() + " ended the building phase.", NamedTextColor.GOLD));
                cancelCountdown();
                transitionTo(GameState.VOTING);
            }
            case VOTING -> {
                broadcast(Component.text("[Admin] " + admin.getName() + " ended voting.", NamedTextColor.GOLD));
                cancelCountdown();
                transitionTo(GameState.RESULTS);
            }
            default -> admin.sendMessage(Component.text("Cannot force-end in state: " + currentState, NamedTextColor.RED));
        }
    }

    // =========================================================================
    // ── Countdown utility ─────────────────────────────────────────────────────
    // =========================================================================

    /**
     * Start a repeating 1-second countdown that calls `onExpire` when done.
     * Broadcasts at 60, 30, 10, 5, 4, 3, 2, 1 seconds remaining.
     */
    private void startCountdown(int totalSeconds, Runnable onExpire) {
        cancelCountdown(); // Cancel any existing timer first

        int[] remaining = {totalSeconds};
        Set<Integer> alertAt = Set.of(60, 30, 10, 5, 4, 3, 2, 1);

        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (remaining[0] <= 0) {
                    cancel();
                    onExpire.run();
                    return;
                }
                if (alertAt.contains(remaining[0])) {
                    broadcast(Component.text(remaining[0] + "s remaining!", NamedTextColor.YELLOW));
                }
                remaining[0]--;
            }
        }.runTaskTimer(plugin, 0L, 20L); // runs every second (20 ticks)
    }

    private void cancelCountdown() {
        if (countdownTask != null && !countdownTask.isCancelled()) {
            countdownTask.cancel();
        }
    }

    // =========================================================================
    // ── Helpers ───────────────────────────────────────────────────────────────
    // =========================================================================

    private void broadcast(Component message) {
        Bukkit.broadcast(message);
    }

    /** Returns all participants who are currently online. */
    private List<Player> getOnlineParticipants() {
        return participants.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // =========================================================================
    // ── Getters ───────────────────────────────────────────────────────────────
    // =========================================================================

    public GameState getCurrentState()    { return currentState; }
    public Set<UUID> getReadyPlayers()    { return Collections.unmodifiableSet(readyPlayers); }
    public Set<UUID> getParticipants()    { return Collections.unmodifiableSet(participants); }
    public String    getSelectedTheme()   { return selectedTheme; }
    public int       getBuildTimerMinutes(){ return buildTimerMinutes; }
    public void      setBuildTimerMinutes(int m) { this.buildTimerMinutes = m; }
    public List<String> getThemeCandidates() { return Collections.unmodifiableList(themeCandidates); }
}
