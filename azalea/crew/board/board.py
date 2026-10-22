#!/usr/bin/env python3
"""Job board MCP server for a crew of Minecraft bot agents.

Agents coordinate here, never through game chat. State lives in state.json next to this file (atomic writes), and
blueprints are written to ./blueprints where the bot process reads them for build_module / verify_module.

Run:  python3 board.py [--port 25700]
MCP:  http://127.0.0.1:25700/mcp   (Streamable HTTP, JSON responses)
"""
import argparse
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(HERE, "state.json")
BLUEPRINTS = os.path.join(HERE, "blueprints")
LEASE_SECONDS = 15 * 60
LOCK = threading.Lock()


def load():
    if os.path.exists(STATE):
        with open(STATE) as f:
            return json.load(f)
    return {"plan": None, "jobs": [], "notes": [], "next_id": 1, "next_note": 1, "events": []}


def save(st):
    tmp = STATE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(st, f, indent=1)
    os.replace(tmp, STATE)


def event(st, text):
    st["events"].append({"t": time.strftime("%H:%M:%S"), "text": text})
    st["events"] = st["events"][-200:]


def expire_leases(st):
    now = time.time()
    for j in st["jobs"]:
        if j["status"] == "claimed" and j.get("lease_until", 0) < now:
            event(st, f"job {j['id']} lease expired ({j.get('claimed_by')}); reopened")
            j.update(status="open", claimed_by=None)


def job_line(j):
    where = f" at {j['origin'][0]} {j['origin'][1]} {j['origin'][2]}" if j.get("origin") else ""
    bp = f" [{j['blueprint']}]" if j.get("blueprint") else ""
    who = f" by {j['claimed_by']}" if j.get("claimed_by") else ""
    deps = f" after {j['depends_on']}" if j.get("depends_on") else ""
    return f"#{j['id']} {j['status']}{who}: {j['kind']}{bp}{where} (role {j['role']}, prio {j['priority']}){deps}"


# ---------------------------------------------------------------------------------------------------------- tools

def t_status(st, a):
    expire_leases(st)
    jobs = st["jobs"]
    counts = {}
    for j in jobs:
        counts[j["status"]] = counts.get(j["status"], 0) + 1
    out = [f"plan: {st['plan']['name'] if st['plan'] else 'none'}",
           "jobs: " + (", ".join(f"{k} {v}" for k, v in sorted(counts.items())) or "none")]
    claimed = [job_line(j) for j in jobs if j["status"] == "claimed"]
    if claimed:
        out.append("in progress:\n  " + "\n  ".join(claimed))
    problems = [job_line(j) + f" -- {j.get('report', '')}" for j in jobs if j["status"] in ("failed", "blocked")]
    if problems:
        out.append("needs attention:\n  " + "\n  ".join(problems[-10:]))
    if a.get("verbose"):
        out.append("all jobs:\n  " + "\n  ".join(job_line(j) for j in jobs))
    out.append("recent events:\n  " + "\n  ".join(f"{e['t']} {e['text']}" for e in st["events"][-8:]))
    return "\n".join(out)


def t_set_plan(st, a):
    st["plan"] = {"name": a["name"], "description": a.get("description", ""), "set_at": time.strftime("%Y-%m-%d %H:%M")}
    if a.get("clear_jobs"):
        st["jobs"] = []
    event(st, f"plan set: {a['name']}")
    return f"plan '{a['name']}' set" + (" (jobs cleared)" if a.get("clear_jobs") else "")


def t_put_blueprint(st, a):
    name = a["name"]
    if not name.replace("_", "").replace("-", "").isalnum():
        raise ValueError("blueprint name: letters, digits, _ and - only")
    blocks = a["blocks"]
    if isinstance(blocks, str):
        blocks = json.loads(blocks)
    for b in blocks:
        if not (isinstance(b, list) and len(b) in (4, 5) and all(isinstance(v, int) for v in b[:3])):
            raise ValueError(f"bad block entry {b}; use [dx, dy, dz, block_id] or [dx, dy, dz, block_id, item_id]")
    os.makedirs(BLUEPRINTS, exist_ok=True)
    with open(os.path.join(BLUEPRINTS, name + ".json"), "w") as f:
        json.dump({"name": name, "description": a.get("description", ""), "blocks": blocks}, f)
    items = {}
    for b in blocks:
        k = b[4] if len(b) == 5 else b[3]
        items[k] = items.get(k, 0) + 1
    event(st, f"blueprint {name} saved ({len(blocks)} blocks)")
    return f"blueprint {name}: {len(blocks)} blocks; materials per module: " + ", ".join(f"{v}x {k}" for k, v in sorted(items.items()))


def t_add_jobs(st, a):
    jobs = a["jobs"]
    if isinstance(jobs, str):
        jobs = json.loads(jobs)
    added = []
    for spec in jobs:
        j = {"id": st["next_id"], "kind": spec.get("kind", "build_module"), "role": spec.get("role", "builder"),
             "blueprint": spec.get("blueprint"), "origin": spec.get("origin"), "instructions": spec.get("instructions", ""),
             "priority": spec.get("priority", 5), "depends_on": spec.get("depends_on", []), "status": "open",
             "claimed_by": None, "attempts": 0, "report": ""}
        st["next_id"] += 1
        st["jobs"].append(j)
        added.append(j["id"])
    event(st, f"{len(added)} jobs added")
    return f"added {len(added)} jobs: #{added[0]}..#{added[-1]}" if added else "no jobs given"


def t_claim(st, a):
    expire_leases(st)
    worker, role = a["worker"], a.get("role")
    done = {j["id"] for j in st["jobs"] if j["status"] == "done"}
    mine = [j for j in st["jobs"] if j["status"] == "claimed" and j["claimed_by"] == worker]
    if mine:
        return "you already hold: " + job_line(mine[0]) + "\ninstructions: " + mine[0]["instructions"]
    candidates = [j for j in st["jobs"] if j["status"] == "open" and (not role or j["role"] == role)
                  and all(d in done for d in j.get("depends_on", []))]
    if not candidates:
        waiting = [j for j in st["jobs"] if j["status"] == "open" and (not role or j["role"] == role)]
        return "no job available" + (f" ({len(waiting)} waiting on dependencies)" if waiting else " (all done or taken)")
    near = a.get("near")
    def key(j):
        d = 0
        if near and j.get("origin"):
            d = sum((p - q) ** 2 for p, q in zip(near, j["origin"]))
        return (-j["priority"], d, j["id"])
    j = min(candidates, key=key)
    j.update(status="claimed", claimed_by=worker, claimed_at=time.time(), lease_until=time.time() + LEASE_SECONDS)
    j["attempts"] += 1
    event(st, f"{worker} claimed #{j['id']}")
    return job_line(j) + ("\ninstructions: " + j["instructions"] if j["instructions"] else "")


def owned(st, a):
    j = next((j for j in st["jobs"] if j["id"] == a["job_id"]), None)
    if j is None:
        raise ValueError(f"no job #{a['job_id']}")
    if j["claimed_by"] != a["worker"]:
        raise ValueError(f"job #{j['id']} is held by {j['claimed_by']}, not {a['worker']}")
    return j


def t_heartbeat(st, a):
    j = owned(st, a)
    j["lease_until"] = time.time() + LEASE_SECONDS
    if a.get("note"):
        j["progress"] = a["note"]
    return f"lease on #{j['id']} extended"


def t_complete(st, a):
    j = owned(st, a)
    j.update(status="done", report=a.get("report", ""), finished_at=time.time(), claimed_by=None)
    event(st, f"{a['worker']} finished #{j['id']}")
    return f"#{j['id']} done"


def t_fail(st, a):
    j = owned(st, a)
    retry = a.get("retry", True) and j["attempts"] < 3
    j.update(status="open" if retry else "blocked", report=a.get("reason", ""), claimed_by=None)
    event(st, f"{a['worker']} {'released' if retry else 'blocked'} #{j['id']}: {a.get('reason', '')[:80]}")
    return f"#{j['id']} " + ("reopened for retry" if retry else "marked blocked; the foreman must deal with it")


def t_update_job(st, a):
    j = next((j for j in st["jobs"] if j["id"] == a["job_id"]), None)
    if j is None:
        raise ValueError(f"no job #{a['job_id']}")
    for k in ("status", "role", "priority", "instructions", "origin", "blueprint", "depends_on"):
        if k in a:
            j[k] = a[k]
    if j["status"] == "open":
        j["claimed_by"] = None
    event(st, f"foreman updated #{j['id']}")
    return job_line(j)


def t_post_note(st, a):
    n = {"seq": st["next_note"], "t": time.strftime("%H:%M:%S"), "from": a["author"], "to": a.get("to", "all"), "text": a["text"]}
    st["next_note"] += 1
    st["notes"].append(n)
    st["notes"] = st["notes"][-300:]
    return f"note #{n['seq']} posted"


def t_read_notes(st, a):
    after, me = a.get("after", 0), a.get("worker")
    notes = [n for n in st["notes"] if n["seq"] > after and (not me or n["to"] in ("all", me) or n["from"] == me)]
    return "\n".join(f"#{n['seq']} {n['t']} {n['from']} -> {n['to']}: {n['text']}" for n in notes[-40:]) or "no notes"


S, I, A, O, B = ({"type": "string"}, {"type": "integer"}, {"type": "array"}, {"type": "object"}, {"type": "boolean"})


def schema(props, req=()):
    return {"type": "object", "properties": props, "required": list(req)}


TOOLS = {
    "board_status": (t_status, "Overview: plan, job counts, who is working on what, problems, recent events.", schema({"verbose": B})),
    "board_set_plan": (t_set_plan, "Foreman: name the current project (optionally clearing old jobs).", schema({"name": S, "description": S, "clear_jobs": B}, ["name"])),
    "board_put_blueprint": (t_put_blueprint, "Foreman: save a module blueprint for build_module. blocks = [[dx,dy,dz,block_id(,item_id)],...] relative to the module origin.", schema({"name": S, "description": S, "blocks": A}, ["name", "blocks"])),
    "board_add_jobs": (t_add_jobs, "Foreman: add jobs. Each: {kind, role (builder|rigger), blueprint, origin [x,y,z], instructions, priority (higher first), depends_on [job ids]}.", schema({"jobs": A}, ["jobs"])),
    "board_update_job": (t_update_job, "Foreman: change a job (status, role, priority, instructions, origin, blueprint, depends_on).", schema({"job_id": I, "status": S, "role": S, "priority": I, "instructions": S, "origin": A, "blueprint": S, "depends_on": A}, ["job_id"])),
    "board_claim": (t_claim, "Worker: take the next job for your role (highest priority, nearest to `near` if given). Leases expire after 15 min without heartbeat.", schema({"worker": S, "role": S, "near": A}, ["worker"])),
    "board_heartbeat": (t_heartbeat, "Worker: keep your lease alive during long jobs, with an optional progress note.", schema({"worker": S, "job_id": I, "note": S}, ["worker", "job_id"])),
    "board_complete": (t_complete, "Worker: mark your job done with a short report.", schema({"worker": S, "job_id": I, "report": S}, ["worker", "job_id"])),
    "board_fail": (t_fail, "Worker: give a job back with the reason (retry=false marks it blocked for the foreman).", schema({"worker": S, "job_id": I, "reason": S, "retry": B}, ["worker", "job_id", "reason"])),
    "board_post_note": (t_post_note, "Message other agents (to a worker name or 'all'). This is the only channel between agents; never use game chat.", schema({"author": S, "to": S, "text": S}, ["author", "text"])),
    "board_read_notes": (t_read_notes, "Read notes addressed to you or to all, after a sequence number.", schema({"worker": S, "after": I})),
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, body=None):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(code)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.reply(405)

    def do_POST(self):
        origin = self.headers.get("Origin")
        if origin and origin != "null" and not origin.startswith(("http://localhost", "http://127.0.0.1")):
            return self.reply(403)
        if urlparse(self.path).path != "/mcp":
            return self.reply(404)
        msg = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        mid, method, params = msg.get("id"), msg.get("method", ""), msg.get("params") or {}
        if mid is None:
            return self.reply(202)
        if method == "initialize":
            result = {"protocolVersion": params.get("protocolVersion", "2025-06-18"), "capabilities": {"tools": {"listChanged": False}},
                      "serverInfo": {"name": "crew-board", "version": "0.1.0"},
                      "instructions": "Shared job board for the bot crew. Claim -> do -> complete/fail. Talk to other agents with board notes, never game chat."}
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": [{"name": n, "description": d, "inputSchema": s} for n, (_, d, s) in TOOLS.items()]}
        elif method == "tools/call":
            name, args = params.get("name"), params.get("arguments") or {}
            if name not in TOOLS:
                return self.reply(200, {"jsonrpc": "2.0", "id": mid, "error": {"code": -32602, "message": f"unknown tool {name}"}})
            try:
                with LOCK:
                    st = load()
                    text = TOOLS[name][0](st, args)
                    save(st)
                result = {"content": [{"type": "text", "text": text}], "isError": False}
            except (KeyError, ValueError, json.JSONDecodeError) as e:
                result = {"content": [{"type": "text", "text": f"error: {e}"}], "isError": True}
        else:
            return self.reply(200, {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"method not found: {method}"}})
        self.reply(200, {"jsonrpc": "2.0", "id": mid, "result": result})


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=25700)
    port = ap.parse_args().port
    print(f"crew board on http://127.0.0.1:{port}/mcp (state: {STATE})", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
