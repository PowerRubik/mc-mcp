# mcmcp-azalea

Headless Minecraft 26.2 bots built on Azalea, each exposed as an MCP server. One process runs several bots:

```sh
cargo build
./target/debug/mcmcp-azalea --accounts AzBot1,AzBot2 --server localhost --port 25610 --blueprints crew/board/blueprints
# MCP endpoints: http://127.0.0.1:25610/mcp/azbot1, .../mcp/azbot2
```

Actions stay close to what a player does: camera turns are stepped every tick in multiples of the mouse-sensitivity
step, and mining/placing/chest opening only happen once the bot's own crosshair raycast is on the target (Azalea's
built-in `block_interact` and forced mining skip that check; these tools do not use them).

Tools: `get_status`, `get_inventory`, `look`, `walk`, `goto` (Azalea pathfinder, no digging unless asked), `stop`,
`sneak`, `equip`, `mine_block`, `place_block`, `find_blocks`, `get_blocks`, `send_chat`, `read_chat`, and for building:
`build_module`, `verify_module`, `bridge`, `restock` (see `src/build.rs` for the blueprint format).

For multi-agent building see [`crew/`](crew/): `./crew/start.sh` starts the job board and the bots listed in
`crew/roster.json`; `python3 crew/gen_crew.py` regenerates `.mcp.json` and the subagent definitions; then run
`claude --model opus` inside `crew/` to act as the foreman.
