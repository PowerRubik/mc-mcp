#!/usr/bin/env bash
# Start the crew board and the Azalea bot swarm for the bots in roster.json.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p logs
ACCOUNTS=$(python3 -c 'import json; print(",".join(b["name"] for b in json.load(open("roster.json"))["bots"]))')
PORT=$(python3 -c 'import json; print(json.load(open("roster.json"))["bot_port"])')
SERVER=$(python3 -c 'import json; print(json.load(open("roster.json"))["server"])')
BOARD_PORT=$(python3 -c 'import json; print(json.load(open("roster.json"))["board_port"])')
python3 board/board.py --port "$BOARD_PORT" > logs/board.log 2>&1 &
echo "board pid $!"
../target/debug/mcmcp-azalea --accounts "$ACCOUNTS" --server "$SERVER" --port "$PORT" --blueprints board/blueprints > logs/bots.log 2>&1 &
echo "bots pid $! ($ACCOUNTS)"
