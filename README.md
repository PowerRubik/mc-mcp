# mc-mcp

Let AI agents play Minecraft Java **26.2** through [MCP](https://modelcontextprotocol.io), using only what a player
could do: real key presses, mouse-like camera turns, and clicks on whatever the crosshair actually hits.

| Folder | What it is | Memory per bot |
|---|---|---|
| [`fabric/`](fabric/) | Fabric client mod: the real game client exposes itself as an MCP server. Every packet comes from vanilla code, so this is the most anticheat-faithful option. Also has no-x-ray mode, fullbright, elytra/spear combat (experimental). | ~1.4 GB (game client) |
| [`azalea/`](azalea/) | Lightweight headless bots on [Azalea](https://github.com/azalea-rs/azalea) (Rust), with the same style of MCP tools plus module building (`build_module`, `verify_module`, `bridge`, `restock`). | ~23 MB |
| [`azalea/crew/`](azalea/crew/) | Multi-agent crew for big repetitive builds: an Opus foreman, Sonnet riggers and Haiku builders as Claude Code subagents, coordinating through a local job-board MCP server (never game chat). | — |

## Status

Tested against a local server running GrimAC (verbose, experimental checks on):

- **fabric**: movement, pathfinding (incl. no-x-ray exploration), mining, placing, inventory and chest GUIs, `hold_click`. No flags in normal use.
  `fight`, `fly_to` and the `sneak` toggle are implemented but **not yet tested in game**.
- **azalea**: looking, walking, sprinting, sneaking, pathfinding and mining: no flags. `build_module`, `bridge` and `restock`
  are implemented and handle missing items correctly, but **placing has not been tested with real items yet**.
  Azalea does not model item-use slowdown, swimming or entity pushing, so expect differences there.
- **crew**: the job board (plans, blueprints, dependencies, leases, notes) is tested; the subagents have **not yet run a full build**.

See each folder's README / `crew/CLAUDE.md` for setup.
