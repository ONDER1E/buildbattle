package dev.onder1e.buildbattle.game;

/**
 * Represents every possible state of the Build Battle state machine.
 *
 * Flow: LOBBY → WORD_SELECTION → BUILDING → VOTING → RESULTS → RESET → LOBBY
 */
public enum GameState {

    /** Waiting in the barrier-box lobby. Players toggle /ready. */
    LOBBY,

    /** Three theme options are presented; players vote via /choose. */
    WORD_SELECTION,

    /** Plots are generated; players build. WorldEdit masks are active. */
    BUILDING,

    /** Each build is shown in turn; players score via /vote <1-10>. */
    VOTING,

    /** Final scores are displayed to everyone. */
    RESULTS,

    /**
     * Transient cleanup state: plots are deleted, scores reset, and the
     * machine immediately transitions back to LOBBY.
     */
    RESET
}
