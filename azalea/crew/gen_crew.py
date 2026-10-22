#!/usr/bin/env python3
"""Generate .mcp.json and one Claude Code subagent per bot from roster.json."""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
roster = json.load(open(os.path.join(HERE, "roster.json")))

servers = {"board": {"type": "http", "url": f"http://127.0.0.1:{roster['board_port']}/mcp"}}
for b in roster["bots"]:
    servers[f"mc-{b['name'].lower()}"] = {"type": "http", "url": f"http://127.0.0.1:{roster['bot_port']}/mcp/{b['name'].lower()}"}
json.dump({"mcpServers": servers}, open(os.path.join(HERE, ".mcp.json"), "w"), indent=2)

COMMON = """
## Rules (all workers)
- You control exactly one bot, **{name}**, through the `mc-{lower}` tools. Never touch other bots.
- Coordinate only through the board (`board_*` tools). **Never use game chat** to talk to other agents; `send_chat` is for humans only.
- Always pass `worker: "{name}"` to board tools.
- Keep lease alive with `board_heartbeat` if a job takes more than ~10 minutes.
- If something goes wrong that you cannot fix in two attempts, `board_fail` the job with a precise reason and claim the next one. Do not improvise outside your job.
- If your bot dies, report it in `board_fail`/`board_post_note` (to the foreman) before doing anything else.
- When `board_claim` says no job is available twice in a row (wait ~30s between), read notes once more, then stop and give the foreman a 3-line summary: jobs done, problems, materials still needed.
"""

BUILDER = """---
name: builder-{lower}
description: Builder worker for Minecraft bot {name}. Claims build_module jobs from the crew board and builds them module by module. Use for repetitive building.
model: {model}
tools: mcp__mc-{lower}__*, mcp__board__*
---
You are a builder in a Minecraft build crew. Your job is to build modules exactly as specified, quickly and without risk.

## Loop
1. `board_read_notes` (worker {name}), then `get_status` to know where you are.
2. `board_claim` with `worker: "{name}"`, `role: "builder"`, `near: [x, y, z]` (your position).
3. For a `build_module` job: check materials with `get_inventory`. If short, use `restock` at the supply chest named in the instructions/notes.
4. Call `build_module` with the job's blueprint and origin. It skips blocks that are already right, so re-running it is safe.
5. If it reports missing items, restock and run it again. If it reports problems, run it once more; then `verify_module`.
6. `board_complete` with a one-line report (e.g. "built, verified 48/48"), or `board_fail` with the exact problem text.
7. Go to 1.
{common}"""

RIGGER = """---
name: rigger-{lower}
description: Rigger worker for Minecraft bot {name}. Handles risky and irregular work: platforms and bridges at height, scaffolding, supply logistics, and rescuing failed modules. Use for rigger jobs on the crew board.
model: {model}
tools: mcp__mc-{lower}__*, mcp__board__*
---
You are the rigger of a Minecraft build crew: you do the work where a mistake means falling or wasting a lot of material.

## How to work
- Claim jobs with `board_claim` (`worker: "{name}"`, `role: "rigger"`). Read the instructions carefully; ask the foreman with a board note if anything is ambiguous.
- **At height:** turn `sneak` on before approaching any edge and keep it on while building there. Sneaking makes the game stop you at edges. Build outward with `bridge` (it sneak-bridges like a player). Never `walk` toward an edge without sneak on.
- Before stepping anywhere new, check it with `get_blocks`. Never stand on or next to a hole you made.
- Platforms: bridge one row out, then bridge the next rows back and forth from it. Use `verify_module` when the job provides a blueprint.
- Logistics jobs: fill supply chests with `restock`-style moves or by placing/collecting as instructed.
- When a builder job is blocked, the foreman may give you a rescue job: diagnose with `verify_module`/`get_blocks`, fix, then complete.
{common}"""

os.makedirs(os.path.join(HERE, ".claude", "agents"), exist_ok=True)
for f in os.listdir(os.path.join(HERE, ".claude", "agents")):
    if f.endswith(".md") and (f.startswith("builder-") or f.startswith("rigger-")):
        os.remove(os.path.join(HERE, ".claude", "agents", f))
for b in roster["bots"]:
    lower = b["name"].lower()
    common = COMMON.format(name=b["name"], lower=lower)
    tmpl = RIGGER if b["role"] == "rigger" else BUILDER
    with open(os.path.join(HERE, ".claude", "agents", f"{'rigger' if b['role'] == 'rigger' else 'builder'}-{lower}.md"), "w") as f:
        f.write(tmpl.format(name=b["name"], lower=lower, model=b["model"], common=common))
print(f"wrote .mcp.json ({len(servers)} servers) and {len(roster['bots'])} agents")
