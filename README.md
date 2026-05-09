# BuildBattle

A production-ready **Build Battle** minigame plugin for **Paper 1.20.2**.

---

## Server Pack

- You can download the [Server Pack](https://github.com/ONDER1E/buildbattle/releases/download/v1.1.5/buildbattle_serverpack.zip)
- Fully plug and play
- Run from `start.bat` (Windows) or `start.sh` (Linux)

- You can PvP in the lobby
- enable it via /config lobbypvp true
- then you can do /pvpready to get pvp tools

---

## Features

- **Plug and play** - game is designed to begin from a clean vanilla paper installation
- **Full state machine** - `LOBBY - WORD_SELECTION - BUILDING - VOTING - RESULTS - RESET`
- **Dynamic plot generation** - plots generated on demand in the positive X direction, one per player, async chunk loading with no main-thread lag
- **WorldEdit protection** - Uses WorldGuard to prevent out of bounds edits
- **Packet-level isolation** - ProtocolLib intercepts `MAP_CHUNK` packets so players only receive chunks for their own plot during building; voting phase refreshes the correct chunks
- **PvP lobby** - 60x60 glass arena with full netherite kit (sword, pickaxe, bow, shield, poison arrows) given on join, cleared on game start
- **Plot customisation** - `/setplotblock` to change the y=64 floor material
- **Admin tools** - pause/resume countdown, live config editing, force-start/end/choose, theme pool management, emergency `/safe_erase_plots`
- **Disconnect safety** - join/leave during any phase handled cleanly; `WORD_SELECTION` resets if player count changes; late joiners park as lobby waiters and auto-join next round

---

## Requirements

| Dependency | Version | Where to get |
|---|---|---|
| Paper | 1.20.2 | [papermc.io](https://papermc.io/downloads) |
| FastAsyncWorldEdit | 7.3.x | [dev.enginehub.org](https://intellectualsites.github.io/download/fawe.html) |
| ProtocolLib | 2.11.x | [ci.dmulloy2.net](https://ci.dmulloy2.net/job/ProtocolLib/) |
| WorldGuard | 7.0.x | [modrinth](https://modrinth.com/plugin/worldguard/versions) |
| Java | 21+ | [adoptium.net](https://adoptium.net/temurin/releases?version=21&os=any&arch=any) |

---

## Permissions

- Players need these permission nodes to play (Already setup in the serverPack):
```
worldedit.brush.*
worldedit.region.*
worldedit.selection.*
fawe.permpack.basic
fawe.worldeditregion
fawe.worldguard
fawe.worldguard.member
worldedit.clipboard.copy
worldedit.clipboard.paste
worldedit.clipboard.rotate
worldedit.history.redo
worldedit.history.undo
worldedit.navigation.jumpto
worldedit.navigation.thru
worldedit.region.replace
worldedit.region.set
worldedit.selection.pos
worldedit.wand
```

---

## Building

```powershell
cd Z:\vscode\buildbattle
.\setup.ps1
# Output: target\BuildBattle-1.0.0.jar
```

Copy `BuildBattle-1.0.0.jar` (not `original-BuildBattle-1.0.0.jar`) plus WorldEdit and ProtocolLib into your server's `plugins/` folder.

---

## Configuration (`plugins/BuildBattle/config.yml`)

```yaml
game_timer: 15              # Build phase duration in minutes
word_selection_timer: 30    # Theme vote duration in seconds
voting_timer: 30            # Per-build vote duration in seconds
min_players: 2              # Minimum players required to start
lobby_return_timer: 30      # Results screen duration before lobby return
plot_size: 10               # Plot size in chunks (10 = 160Ã-160 blocks)
buffer_size: 2              # Iron wall width in chunks on each side
themes: spiderman, house, alien, ...
```

Changes can be applied live with `/config <key> <value>` - most take effect at the next phase transition.

---

## Commands

### Player commands (no permissions needed)

| Command | Description |
|---|---|
| `/ready` | Toggle ready status in the lobby |
| `/choose <1\|2\|3\|name>` | Vote for a theme during WORD_SELECTION |
| `/done` | Finish building early, enter spectator |
| `/vote <1-10>` | Score the current build during voting |
| `/setplotblock <material>` | Change your plot floor block (e.g. `stone`, `oak_planks`) |

### Admin commands (op required)

| Command | Description |
|---|---|
| `/force_start` | Skip lobby ready-check |
| `/force_choose <theme>` | Override theme vote |
| `/force_end` | End the current phase immediately |
| `/pause` | Freeze the active countdown |
| `/resume` | Resume a paused countdown |
| `/config <key> <value>` | Live-edit any config value |
| `/addword <theme>` | Add a theme to the pool (persists to config) |
| `/removeword <theme>` | Remove a theme from the pool |
| `/safe_erase_plots` | Emergency block-by-block plot wipe |

---

## Game flow

```
LOBBY
  Players join - receive netherite PvP kit - /ready to signal readiness
  All ready (or /force_start) -

WORD_SELECTION
  3 random themes shown - /choose to vote - 30s timer - winner picked
  Player join/leave during this phase - aborts back to LOBBY

BUILDING
  Plots generated async - players teleported to their plot in CREATIVE
  WorldEdit masked to inner plot only - /setplotblock available
  /done to finish early - all done or timer expires -

VOTING
  Each build shown in turn - everyone teleported to it in SPECTATOR
  /vote <1-10> - 30s per plot - all plots shown -

RESULTS
  Scores displayed - 30s countdown - RESET

RESET
  Plots regenerated to void - players returned to LOBBY
  Inventory cleared - netherite kit re-issued
```

---

## Architecture

```
BuildBattle.java          - Plugin entry, lobby construction, kit management
game/
  GameState.java          - Enum: LOBBY WORD_SELECTION BUILDING VOTING RESULTS RESET
  GameManager.java        - State machine, countdown, disconnect safety, config staging
plot/
  Plot.java               - Immutable plot model, chunk key math, vote recording
  PlotManager.java        - X-axis layout, async generation, batched cleanup
packet/
  PacketHandler.java      - ProtocolLib MAP_CHUNK interception + voting refresh
listener/
  PlayerListener.java     - Join/quit routing, block protection, movement confinement
  WorldEditListener.java  - EditSessionEvent extent injection for WE masking
commands/
  ...22 command classes
```

---

## Known limitations

- Plot cleanup uses `world.regenerateChunk()` (deprecated in Paper 1.20+) as it is the only reliable no-NMS approach on this version. `/safe_erase_plots` provides a block-by-block fallback.
- The packet isolation approach (sending fake air chunks) works for standard clients but may not fully block FreeCam mods that read memory directly.
