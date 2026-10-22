//! Deterministic building tools, so agents work in modules instead of individual blocks.
//!
//! Blueprints are JSON files in the shared blueprint directory (the job board writes them there):
//! `{"name": "cactus_cell", "blocks": [[dx, dy, dz, "minecraft:sand"], [dx, dy, dz, "minecraft:tripwire", "minecraft:string"], ...]}`
//! The 4th entry is the block that should end up in the world; an optional 5th is the item to place it with when the
//! ids differ.

use std::{collections::BTreeMap, path::PathBuf, sync::OnceLock, time::Duration};

use azalea::{
    BlockPos, WalkDirection,
    core::direction::Direction,
    inventory::{
        ItemStack,
        operations::{ClickOperation, QuickMoveClick, SwapClick},
    },
    pathfinder::{PathfinderClientExt, PathfinderOpts, goals::Goal},
    registry::builtin::{BlockKind, ItemKind},
};
use serde_json::{Value, json};

use crate::actions::{BotHandle, angles, err, face_point, faces_toward, fmt_pos, i, is_replaceable, s};

static DIR: OnceLock<PathBuf> = OnceLock::new();

pub fn set_blueprint_dir(dir: String) {
    DIR.set(PathBuf::from(dir)).ok();
}

struct Entry {
    offset: BlockPos,
    block: BlockKind,
    item: ItemKind,
}

fn load(name: &str) -> Result<Vec<Entry>, String> {
    if name.contains('/') || name.contains("..") {
        return Err("invalid blueprint name".into());
    }
    let path = DIR.get().cloned().unwrap_or_else(|| "blueprints".into()).join(format!("{name}.json"));
    let raw = std::fs::read_to_string(&path).map_err(|e| format!("cannot read blueprint {}: {e}", path.display()))?;
    let v: Value = serde_json::from_str(&raw).map_err(|e| format!("bad blueprint json: {e}"))?;
    let mut out = Vec::new();
    for e in v.get("blocks").and_then(Value::as_array).ok_or("blueprint has no blocks array")? {
        let a = e.as_array().ok_or("each block must be an array")?;
        let n = |k: usize| a.get(k).and_then(Value::as_i64).ok_or(format!("bad offset in {e}"));
        let id = |k: usize| a.get(k).and_then(Value::as_str).map(|s| if s.contains(':') { s.to_string() } else { format!("minecraft:{s}") });
        let block_id = id(3).ok_or(format!("missing block id in {e}"))?;
        let block: BlockKind = block_id.parse().map_err(|_| format!("unknown block {block_id}"))?;
        let item_id = id(4).unwrap_or(block_id.clone());
        let item: ItemKind = item_id.parse().map_err(|_| format!("no item for {block_id}; give the item id as 5th entry"))?;
        out.push(Entry { offset: BlockPos::new(n(0)? as i32, n(1)? as i32, n(2)? as i32), block, item });
    }
    Ok(out)
}

/// Stand anywhere the target is in reach, but not inside it or directly on top of it.
#[derive(Debug)]
struct Reach {
    target: BlockPos,
    reach: f32,
}

impl Goal for Reach {
    fn heuristic(&self, n: BlockPos) -> f32 {
        let d = n.center().distance_to(self.target.center()) as f32;
        (d - self.reach + 1.0).max(0.0)
    }

    fn success(&self, n: BlockPos) -> bool {
        let t = self.target;
        if n == t || n.up(1) == t || n.down(1) == t {
            return false;
        }
        let eye = n.center().up(1.12);
        (eye.distance_to(t.center()) as f32) <= self.reach
    }
}

fn count(h: &BotHandle, item: ItemKind) -> i32 {
    h.inventory_slots()
        .iter()
        .skip(9)
        .take(36)
        .map(|s| match s {
            ItemStack::Present(d) if d.kind == item => d.count,
            _ => 0,
        })
        .sum()
}

/// Get `item` into a hotbar slot and select it: number-key swap from the main inventory if needed.
async fn ensure_hotbar(h: &BotHandle, item: ItemKind) -> Result<(), String> {
    if h.select_item(item).await.is_ok() {
        return Ok(());
    }
    let slots = h.inventory_slots();
    let src = (9..36).find(|&i| matches!(slots.get(i), Some(ItemStack::Present(d)) if d.kind == item)).ok_or(format!("no {item} in inventory"))?;
    let selected = h.client.selected_hotbar_slot().map_err(err)?;
    let target = (0..9u8).find(|&k| matches!(slots.get(36 + k as usize), Some(ItemStack::Empty) | None)).unwrap_or(selected);
    let inv = h.client.open_inventory().map_err(err)?.ok_or("another container is open")?;
    h.ticks(3).await;
    inv.click(ClickOperation::Swap(SwapClick { source_slot: src as u16, target_slot: target }));
    h.ticks(3).await;
    inv.close();
    h.ticks(2).await;
    h.select_item(item).await
}

async fn get_in_reach(h: &BotHandle, target: BlockPos) -> Result<(), String> {
    let eye = h.client.eye_position().map_err(err)?;
    let feet = BlockPos::from(h.client.position().map_err(err)?);
    if eye.distance_to(target.center()) <= 4.0 && feet != target && feet.up(1) != target {
        return Ok(());
    }
    let opts = PathfinderOpts::new().allow_mining(false);
    tokio::time::timeout(Duration::from_secs(30), h.client.goto_with_opts(Reach { target, reach: 4.0 }, opts))
        .await
        .map_err(|_| format!("could not get within reach of {}", fmt_pos(target)))
}

pub async fn call(h: &BotHandle, name: &str, a: &Value) -> Result<String, String> {
    match name {
        "build_module" | "verify_module" => {
            let bp = s(a, "blueprint").ok_or("missing blueprint")?.to_string();
            let origin = BlockPos::new(i(a, "x").ok_or("x")? as i32, i(a, "y").ok_or("y")? as i32, i(a, "z").ok_or("z")? as i32);
            let entries = load(&bp)?;
            if name == "verify_module" {
                return Ok(verify(h, &bp, origin, &entries));
            }
            let me = h.clone();
            h.run_pub("build_module", 600.0, async move { build(&me, &bp, origin, entries).await }).await
        }
        "bridge" => {
            let dir = match s(a, "direction").unwrap_or("") {
                "north" => Direction::North,
                "south" => Direction::South,
                "east" => Direction::East,
                "west" => Direction::West,
                _ => return Err("direction must be north, south, east or west".into()),
            };
            let len = i(a, "length").unwrap_or(1).clamp(1, 64) as usize;
            let item = parse_item(s(a, "item").ok_or("missing item")?)?;
            let me = h.clone();
            h.run_pub("bridge", 20.0 + len as f64 * 6.0, async move { bridge(&me, dir, len, item).await }).await
        }
        "restock" => {
            let chest = BlockPos::new(i(a, "x").ok_or("x")? as i32, i(a, "y").ok_or("y")? as i32, i(a, "z").ok_or("z")? as i32);
            let item = parse_item(s(a, "item").ok_or("missing item")?)?;
            let want = i(a, "count").unwrap_or(64).max(1) as i32;
            let me = h.clone();
            h.run_pub("restock", 60.0, async move { restock(&me, chest, item, want).await }).await
        }
        _ => Err(format!("unknown tool {name}")),
    }
}

fn parse_item(raw: &str) -> Result<ItemKind, String> {
    let id = if raw.contains(':') { raw.to_string() } else { format!("minecraft:{raw}") };
    id.parse().map_err(|_| format!("unknown item {id}"))
}

fn verify(h: &BotHandle, bp: &str, origin: BlockPos, entries: &[Entry]) -> String {
    let (mut ok, mut diffs) = (0, Vec::new());
    for e in entries {
        let p = origin + e.offset;
        match h.block(p) {
            Some(st) if BlockKind::from(st) == e.block => ok += 1,
            Some(st) => diffs.push(format!("{}: want {} found {}", fmt_pos(p), e.block, BlockKind::from(st))),
            None => diffs.push(format!("{}: not loaded", fmt_pos(p))),
        }
    }
    let shown: Vec<_> = diffs.iter().take(15).cloned().collect();
    format!(
        "{bp} at {}: {ok}/{} correct{}{}",
        fmt_pos(origin),
        entries.len(),
        if diffs.is_empty() { String::new() } else { format!(", {} wrong:\n", diffs.len()) },
        shown.join("\n")
    )
}

async fn build(h: &BotHandle, bp: &str, origin: BlockPos, mut entries: Vec<Entry>) -> Result<String, String> {
    entries.sort_by_key(|e| (e.offset.y, e.offset.x, e.offset.z));
    let (mut placed, mut already) = (0, 0);
    let mut missing: BTreeMap<String, i32> = BTreeMap::new();
    let mut problems = Vec::new();
    for e in &entries {
        let pos = origin + e.offset;
        let Some(state) = h.block(pos) else {
            problems.push(format!("{} not loaded", fmt_pos(pos)));
            continue;
        };
        if BlockKind::from(state) == e.block {
            already += 1;
            continue;
        }
        if !state.is_air() && !is_replaceable(state) {
            problems.push(format!("{} occupied by {}", fmt_pos(pos), BlockKind::from(state)));
            continue;
        }
        if count(h, e.item) == 0 {
            *missing.entry(e.item.to_string()).or_default() += 1;
            continue;
        }
        let attempt = async {
            ensure_hotbar(h, e.item).await?;
            get_in_reach(h, pos).await?;
            h.place(pos, None).await
        };
        match attempt.await {
            Ok(_) => placed += 1,
            Err(err) => {
                problems.push(format!("{}: {err}", fmt_pos(pos)));
                if problems.len() >= 12 {
                    problems.push("stopping: too many problems".into());
                    break;
                }
            }
        }
    }
    let total = entries.len();
    let mut out = format!("{bp} at {}: placed {placed}, already correct {already}, of {total}", fmt_pos(origin));
    if !missing.is_empty() {
        out += &format!("\nmissing items: {}", missing.iter().map(|(k, v)| format!("{v}x {k}")).collect::<Vec<_>>().join(", "));
    }
    if !problems.is_empty() {
        out += &format!("\nproblems:\n{}", problems.join("\n"));
    }
    let done = placed + already == total;
    if done { Ok(out) } else { Err(out) }
}

fn step(d: Direction) -> (i32, i32) {
    match d {
        Direction::North => (0, -1),
        Direction::South => (0, 1),
        Direction::West => (-1, 0),
        Direction::East => (1, 0),
        _ => (0, 0),
    }
}

/// Sneak-bridge like a player: face away from the build direction, back up to the edge (sneaking stops us falling
/// off), look steeply down at the side of the block underfoot, place, step back onto the new block, repeat.
async fn bridge(h: &BotHandle, dir: Direction, len: usize, item: ItemKind) -> Result<String, String> {
    let (dx, dz) = step(dir);
    let back_yaw = angles(azalea::Vec3::ZERO, azalea::Vec3::new(-dx as f64, 0.0, -dz as f64)).0;
    h.client.set_crouching(true).map_err(err)?;
    h.ticks(2).await;
    let mut built = 0;
    for _ in 0..len {
        let pos = h.client.position().map_err(err)?;
        let under = BlockPos::from(pos).down(1);
        if h.block(under).is_none_or(|s| s.is_air()) {
            return Err(format!("not standing on a block after {built} placed"));
        }
        let next = BlockPos::new(under.x + dx, under.y, under.z + dz);
        if h.block(next).is_some_and(|s| !s.is_air() && !is_replaceable(s)) {
            // Already solid: just back onto it.
            h.client.walk(WalkDirection::Backward);
            h.ticks(6).await;
            h.client.walk(WalkDirection::None);
            continue;
        }
        if count(h, item) == 0 {
            return Err(format!("out of {item} after {built} placed"));
        }
        ensure_hotbar(h, item).await?;
        h.turn_to(back_yaw, 84.0).await?;
        h.client.walk(WalkDirection::Backward);
        let mut aimed = false;
        let mut rx = h.client.get_tick_broadcaster();
        for _ in 0..60 {
            if h.crosshair_block() == Some((under, dir)) {
                aimed = true;
                break;
            }
            // Once our eye is past the edge, tilt to the middle of the side face.
            let eye = h.client.eye_position().map_err(err)?;
            let plane = if dx != 0 { under.x as f64 + if dx > 0 { 1.0 } else { 0.0 } } else { under.z as f64 + if dz > 0 { 1.0 } else { 0.0 } };
            let past = if dx != 0 { (eye.x - plane) * dx as f64 } else { (eye.z - plane) * dz as f64 };
            if past > 0.02 {
                let pitch = ((eye.y - (under.y as f64 + 0.5)).atan2(past)).to_degrees() as f32;
                let d = h.client.direction().map_err(err)?;
                let delta = crate::actions::quantize(crate::actions::ease(pitch - d.x_rot()));
                h.client.set_direction(d.y_rot(), (d.x_rot() + delta).clamp(-90.0, 90.0)).map_err(err)?;
            }
            if rx.recv().await.is_err() {
                break;
            }
        }
        h.client.walk(WalkDirection::None);
        if !aimed {
            return Err(format!("could not get the crosshair onto the edge after {built} placed (crosshair on {})", h.describe_crosshair()));
        }
        h.client.start_use_item();
        h.ticks(4).await;
        if h.block(next).is_none_or(|s| s.is_air()) {
            return Err(format!("placing failed after {built} placed"));
        }
        built += 1;
        // Step back onto the new block (still sneaking, so we cannot walk off its far edge).
        h.client.walk(WalkDirection::Backward);
        for _ in 0..30 {
            h.ticks(1).await;
            let p = h.client.position().map_err(err)?;
            if BlockPos::from(p).down(1) == next && ((p.x - (next.x as f64 + 0.5)).abs() < 0.3 || dx == 0) && ((p.z - (next.z as f64 + 0.5)).abs() < 0.3 || dz == 0) {
                break;
            }
        }
        h.client.walk(WalkDirection::None);
    }
    Ok(format!("bridged {built} block(s) {dir:?}; now at {}", fmt_pos(BlockPos::from(h.client.position().map_err(err)?))))
}

/// Open a chest by really clicking it, then shift-click stacks of `item` out until we have taken `want`.
async fn restock(h: &BotHandle, chest: BlockPos, item: ItemKind, want: i32) -> Result<String, String> {
    get_in_reach(h, chest).await?;
    let eye = h.client.eye_position().map_err(err)?;
    let mut on = false;
    for face in faces_toward(chest, eye) {
        h.look_at(face_point(chest, face)).await?;
        h.ticks(2).await;
        if h.crosshair_block().map(|(p, _)| p) == Some(chest) {
            on = true;
            break;
        }
    }
    if !on {
        return Err(format!("chest not visible; crosshair on {}", h.describe_crosshair()));
    }
    h.client.start_use_item();
    let container = h.client.wait_for_container_open(Some(40)).await.map_err(err)?.ok_or("container did not open")?;
    h.ticks(4).await;
    let before = count(h, item);
    let contents = container.contents().unwrap_or_default();
    let mut taken = 0;
    for (idx, stack) in contents.iter().enumerate() {
        if taken >= want {
            break;
        }
        if let ItemStack::Present(d) = stack {
            if d.kind == item {
                container.click(ClickOperation::QuickMove(QuickMoveClick::Left { slot: idx as u16 }));
                taken += d.count;
                h.ticks(3).await;
            }
        }
    }
    container.close();
    h.ticks(2).await;
    let got = count(h, item) - before;
    if got <= 0 {
        return Err(format!("no {item} taken (chest empty or inventory full)"));
    }
    Ok(format!("took {got}x {item}; now carrying {}", count(h, item)))
}

pub fn tool_list() -> Vec<Value> {
    vec![
        json!({"name": "build_module", "description": "Build a whole blueprint (from the shared blueprint folder) with its origin at x,y,z: bottom-up, walking into reach, pulling items from the inventory to the hotbar, placing each block through the crosshair. Skips blocks already correct, so it is safe to re-run. Returns placed/missing/problems.",
            "inputSchema": {"type": "object", "properties": {"blueprint": {"type": "string"}, "x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}}, "required": ["blueprint", "x", "y", "z"]}}),
        json!({"name": "verify_module", "description": "Compare the world against a blueprint at x,y,z; returns counts and up to 15 differences.",
            "inputSchema": {"type": "object", "properties": {"blueprint": {"type": "string"}, "x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}}, "required": ["blueprint", "x", "y", "z"]}}),
        json!({"name": "bridge", "description": "Sneak-bridge out from the block you stand on: place `length` blocks in a straight line north/south/east/west at your feet level-1, walking backwards like a player. Safe at height (sneaking prevents falling off).",
            "inputSchema": {"type": "object", "properties": {"direction": {"type": "string"}, "length": {"type": "integer"}, "item": {"type": "string"}}, "required": ["direction", "item"]}}),
        json!({"name": "restock", "description": "Walk to a chest at x,y,z, open it by clicking it, and shift-click out up to `count` of an item.",
            "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}, "z": {"type": "integer"}, "item": {"type": "string"}, "count": {"type": "integer"}}, "required": ["x", "y", "z", "item"]}}),
    ]
}
