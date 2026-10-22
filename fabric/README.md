# mcmcp: Minecraft 26.2 as an MCP server

A client-side Fabric mod for Minecraft Java **26.2** that exposes the running game as an
[MCP](https://modelcontextprotocol.io) server, so an AI agent (Claude Code, Claude Desktop, ...) can play.

The design rule: **the agent only ever produces input, never packets.** Vanilla code turns that input into
exactly the packets a human would send.

| Agent action | How it reaches the game |
|---|---|
| Walking, jumping, sneaking, sprinting | `KeyMapping.isDown()` reports the key as held. `KeyboardInput`, sprint logic, step-up etc. are untouched vanilla. |
| Camera | Eased toward the target each frame, converted to whole mouse "pixels" and applied with `MouseHandler.turnPlayer`'s exact math. Every rotation delta is a multiple of the player's own sensitivity step, the way a real mouse produces it. |
| Mining, attacking, placing, using | A real click or hold of the attack/use key. Vanilla raycasts from the crosshair, so the agent first turns until the crosshair is on the exact block face or entity, within normal reach. No reaching through walls. |
| Hotbar / inventory | Hotbar number keys. Main-inventory items are fetched by opening the inventory, pausing briefly, doing a number-key swap (the packet `AbstractContainerScreen` sends), and closing it. |
| Chat | `ClientPacketListener.sendChat` / `sendCommand`, same as the chat screen. |

## Install

1. Build: `./gradlew build` (Gradle downloads a JDK 25 by itself). The jar is `build/libs/mcmcp-<version>.jar`.
2. Put it in your 26.2 instance's `mods/` folder together with [Fabric API](https://modrinth.com/mod/fabric-api).
3. Start the game. The log says `MCP server listening on http://127.0.0.1:25599/mcp`.
4. Register it with your MCP client, e.g. Claude Code:

   ```sh
   claude mcp add --transport http minecraft http://127.0.0.1:25599/mcp
   ```

The server only listens on loopback and rejects browser requests from non-local origins.

## Tools

| Tool | What it does |
|---|---|
| `get_status` | Position, facing, health/food, held item, crosshair target, open screen, fps and tick timing, current task |
| `get_inventory`, `get_screen` | Inventory contents; open GUI with slot indices, custom names and lore for `click_slot` |
| `look` | Turn to a point (`x,y,z`) or to `yaw`/`pitch` |
| `walk` | Hold movement keys for N ticks |
| `goto` | A* pathfinding + walking (walk, diagonals, 1-block jumps, drops up to 3, swimming). `y` is optional |
| `explore` | No-x-ray mode: walk to the nearest seen spot that borders unseen space, optionally in one direction |
| `mine_block` | Picks the best hotbar tool, aims, holds left click until the block breaks |
| `place_block` | Right-clicks a visible face of an adjacent block; optional `item` gets equipped first |
| `interact_block`, `interact_entity`, `attack_entity` | Right-/left-click with the crosshair on the target (attacks wait for full cooldown) |
| `hold_click` | Press and hold left or right mouse for N ticks at the current crosshair: continuous mining, held use, bow, shield... |
| `use_item`, `equip`, `drop_item`, `swap_hands` | Held-item actions |
| `open_inventory`, `click_slot`, `close_screen` | Container interaction |
| `send_chat`, `read_chat` | Chat and commands; received messages are buffered with sequence numbers |
| `find_blocks`, `get_blocks`, `list_entities` | World queries (loaded chunks only) |
| `wait_for_task`, `stop`, `respawn` | Task control |

Only one action runs at a time; starting another cancels it. Long actions return when finished or after
`timeout_seconds`, then keep running in the background.

## No-x-ray mode and fullbright

With **no-x-ray mode** on (the default), the agent only knows blocks the player has actually seen. Every tick, a
jittered grid of rays is cast through the camera's field of view. Rays stop at opaque blocks, or at the occlusion shape
of partial ones (slabs, carpet...), and every voxel they pass is remembered. Light level is ignored. Effects:

* `find_blocks` / `get_blocks` only report seen blocks; unseen ones are counted as "never seen".
* `list_entities` only lists entities in line of sight.
* `goto` plans through unseen space optimistically (assuming open, flat ground, at extra cost), looks along its route
  and replans as the real terrain comes into view. It only climbs onto steps it has seen.
* `explore` searches only seen ground for the nearest edge of the known area, walks there, and does a look-around
  sweep (including looking down into holes) when it has nothing to go on.

**Fullbright** renders the world fully lit, like night vision, so whoever watches the window sees what the agent can
see. It is client-side rendering only; nothing about it reaches the server.

Both are yours, not the agent's, to switch:

* in game: `/mcmcp noxray on|off|toggle` and `/mcmcp fullbright on|off|toggle` (client-side commands, never sent to the
  server; the agent's `send_chat` refuses `/mcmcp`)
* or edit `noXray` / `fullbright` in `config/mcmcp.json`. The file is reloaded within a second.

## Config: `config/mcmcp.json`

| Key | Default | Meaning |
|---|---|---|
| `host` / `port` | `127.0.0.1` / `25599` | MCP endpoint |
| `noXray` | `true` | Agent only knows blocks/entities it has seen (see above) |
| `fullbright` | `true` | Render fully lit (visual only) |
| `keepRunningUnfocused` | `true` | Don't open the pause menu on focus loss. It would release every key. |
| `skipRenderingWhenUnfocused` | `true` | While unfocused, skip drawing and presenting frames and run the loop at `unfocusedFps`. See below. |
| `unfocusedFps` | `60` | Frame-loop rate while unfocused (min 30) |
| `maxTurnSpeed` / `minTurnSpeed` | `540` / `40` | Camera turn speed bounds, deg/s |
| `clickIntervalTicks` | `3` | Delay before each container slot click |

### Why the unfocused-window handling matters

Client ticks run inside the render loop. Vanilla drops an idle client to 30 fps after a minute without input, and
to 10 fps after 10 minutes or when minimized. On Wayland, presenting a frame for a window the compositor isn't showing
can block for about a second. Either way, several ticks then run back-to-back and their movement packets reach the
server in bursts. Other players see stutter, and timer-style anticheat checks see a client that is behind.

World rendering stays on while any screen or overlay is open. On join and respawn, vanilla keeps the player frozen
(sending no movement packets) until their chunk section has been built for rendering. The resource-loading overlay
only goes away from its own render pass, and vanilla ignores clicks while it is up. Frames are never presented while
unfocused, screen or not.

With the defaults, an unfocused window does no rendering at all, and its loop runs at a steady 60 fps. Measured:
tick spacing 50.0 ms average / 50.2 ms max, zero back-to-back ticks, about 14% of one CPU core while walking.
Agent activity also counts as input for vanilla's AFK throttle, just as a key press does.

## Anticheat testing

The repo includes a test setup: `./gradlew runServer` starts an offline-mode server in `run-server/`, and
`./gradlew runClient --args="--quickPlayMultiplayer localhost:25565 --username AgentBot"` joins it. It was tested with
**GrimAC 2.3.75 (Fabric)** with verbose logging and experimental checks enabled:

* No flags from: looking around, walking/sprint-jumping/sneaking, several `goto` routes over mountain terrain,
  mining 11 blocks (stone by hand, grass) and placing blocks. Inventory GUI clicks and the equip-from-inventory swap
  were tested before Grim was installed and have not been checked against it yet.
* Grim inferred the agent's mouse sensitivity as exactly the configured value, on both axes.
* Positive control: a deliberate 0.6-block client-side teleport was flagged as `Simulation` immediately. So the
  checks are live.
* Flags seen only outside agent control, both since fixed or explained:
  * `NegativeTimer*` (experimental) bursts when the client stalled: the AFK fps throttle and the Wayland present
    stall, both fixed above, and once during a death (see below).
  * `Timer` in the first second after joining, before the agent had done anything (seen on 2 of ~7 joins). Not yet
    determined whether an unmodded client does the same on this setup.

**No anticheat can be guaranteed against.** Server owners also often prohibit automation regardless of how it looks.
Check the rules of any server you use this on.

## Caveats

* **Don't touch the game window while a task runs.** Your real mouse and keyboard mix with the agent's input. Moving
  the mouse during `mine_block` will pull the crosshair off the block.
* `goto` doesn't dig or build. It reports "no path" when the target is enclosed, for example a cave reachable only by
  a drop of more than 3 blocks. A ledge guard (crouching at unplanned edges, as players do) keeps momentum from carrying
  it off cliffs.
* While walking, the agent steers at a point 1.3 to 2 blocks ahead on the path (pure pursuit), and its gaze rests on
  the route 3.5 to 6 blocks ahead. That gives ~12° down on flat ground, looking up on climbs and down before drops.
* Pathing only sees loaded chunks. For far targets, call `goto` repeatedly; partial paths are followed automatically.
* Creative-mode inventory isn't supported for fetching items from the main inventory; keep them on the hotbar.
