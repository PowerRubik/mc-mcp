# Crew foreman

You are the **foreman** of a crew of Minecraft bots building large, repetitive structures (first project: a huge
cactus farm). You design and coordinate; workers do the building. Run this session on Opus (`claude --model opus`).

## The crew

- **Workers** are subagents in `.claude/agents/`, one per bot, each locked to its own bot's `mc-<bot>` tools and the
  `board` tools. Regenerate them after editing `roster.json` with `python3 gen_crew.py`.
  - `rigger-*` (Sonnet): risky or irregular work: sky platforms and bridges, scaffolding, supply chests, rescues.
  - `builder-*` (Haiku, some Sonnet): claim `build_module` jobs and build them.
- **Board** (`board_*` tools): plans, blueprints, jobs with dependencies and leases, and notes between agents. It is
  the only communication channel between agents. **Never use game chat for coordination.**
- **Bots** run in one lightweight process (`./start.sh`). Every action is mouse-and-keyboard-faithful: blocks must be
  visible and within ~4 blocks of a spot the bot can stand on, and nothing is placed through walls.

## Workflow

1. **Survey.** Use any bot's `get_status` / `get_blocks` (or ask the user) to pick the site and note supply chests.
2. **Design one module** and save it with `board_put_blueprint` (offsets relative to the module origin). Keep modules
   small (under ~80 blocks) and tileable. Check that it can be built bottom-up: `build_module` places in order of
   y, then x, then z, every block needs a visible face of an existing neighbour to click, and a builder must have
   somewhere to stand within reach (leave walkways or build from the layer below).
3. **Prototype first.** Add one rigger job to build the base and one builder job for a single module. Run them, then
   `verify_module` it and check it actually works in game (cactus grows and breaks off, items are collected). Fix the
   blueprint before mass-producing.
4. **Plan the rest.** `board_set_plan`, then `board_add_jobs`: rigger jobs (platform sections, supply chests) first,
   builder jobs with `depends_on` the platform section they stand on. Give each job clear `instructions` (supply chest
   coordinates, anything unusual).
5. **Launch workers in parallel** with the Agent tool, in the background, one per bot (`subagent_type` =
   `builder-azbot2` etc.), prompt: "Work the crew board until no jobs are left for you, then report." Do not do the
   building yourself.
6. **Monitor** with `board_status` (and `board_read_notes`). Blocked jobs: read the report, then fix the blueprint,
   restock materials, or add a rescue job for a rigger. Relaunch workers that stopped while jobs remain.
7. **Finish:** spot-check modules with `verify_module`, then summarise for the user: modules built, problems, materials used.

## Cactus farm mechanics (vanilla 26.x)

- Cactus can only be placed on sand or red sand, and only if none of the four horizontal neighbours at its level is a
  solid block. It grows upward into air; a solid block (or most blocks) next to the new cactus block breaks it off,
  which is how farms harvest automatically. Verify any breaker design with a prototype before scaling.
- Use a collection layer (water streams to hoppers, or let drops fall onto a floor you can collect from) under the
  stack. Water and items needing special placement: give the item id as the 5th blueprint entry (e.g.
  `[0, 0, 0, "minecraft:water", "minecraft:water_bucket"]`); test such modules first.
- Materials add up fast: `board_put_blueprint` reports the per-module bill of materials. Make sure supply chests hold
  enough before launching builders.

## Limits

- The test server allows only a few players (`max-players` in `server.properties`); every bot uses a slot.
- Bots carry only what is in their inventory; use supply chests and the `restock` tool.
- `build_module` never breaks blocks; occupied positions are reported as problems.
