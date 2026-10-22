//! Tool implementations for one bot.

use std::{collections::VecDeque, sync::Arc, time::Duration};

use azalea::{
    BlockPos, Client, Vec3, WalkDirection,
    block::BlockState,
    core::{direction::Direction, hit_result::HitResult},
    inventory::ItemStack,
    pathfinder::{
        PathfinderClientExt, PathfinderOpts,
        goals::{BlockPosGoal, RadiusGoal, XZGoal},
    },
    registry::builtin::{BlockKind, ItemKind},
};
use parking_lot::Mutex;
use serde_json::{Value, json};
use tokio::task::JoinHandle;

/// Degrees per mouse "pixel" at the default sensitivity (0.5): every rotation is a multiple of this, like a real mouse.
const MOUSE_STEP: f32 = 0.15;
/// Upper bound on turning speed: 30 degrees per tick = 600 degrees per second.
const MAX_TURN_PER_TICK: f32 = 30.0;

#[derive(Clone)]
pub struct BotHandle {
    pub client: Client,
    inner: Arc<Inner>,
}

struct Inner {
    task: Mutex<Option<JoinHandle<Result<String, String>>>>,
    status: Mutex<String>,
    chat: Mutex<VecDeque<(u64, String)>>,
    chat_seq: Mutex<u64>,
    sneak: Mutex<bool>,
}

type R = Result<String, String>;

pub(crate) fn err<E: std::fmt::Debug>(e: E) -> String {
    format!("{e:?}")
}

impl BotHandle {
    pub fn new(client: Client) -> Self {
        Self {
            client,
            inner: Arc::new(Inner {
                task: Mutex::new(None),
                status: Mutex::new("idle".into()),
                chat: Mutex::new(VecDeque::new()),
                chat_seq: Mutex::new(0),
                sneak: Mutex::new(false),
            }),
        }
    }

    pub fn push_chat(&self, line: String) {
        let mut seq = self.inner.chat_seq.lock();
        *seq += 1;
        let mut chat = self.inner.chat.lock();
        chat.push_back((*seq, line));
        while chat.len() > 300 {
            chat.pop_front();
        }
    }

    /// Release every held input, as the Fabric mod does when a task ends.
    pub(crate) fn release(&self) {
        let c = &self.client;
        c.walk(WalkDirection::None);
        let _ = c.set_jumping(false);
        c.left_click_mine(false);
        c.stop_pathfinding();
        let _ = c.set_crouching(*self.inner.sneak.lock());
    }

    /// Run a long action as its own task (so `stop` can cancel it) and wait up to `timeout` for the result.
    async fn run<F>(&self, name: &str, timeout: f64, fut: F) -> R
    where
        F: std::future::Future<Output = R> + Send + 'static,
    {
        self.stop_current();
        // Let the release (walk none, stop pathfinding) be processed before the new action's inputs; otherwise both
        // land in the same update and the release wins (Azalea handles walk events after sprint/goto events).
        self.ticks(1).await;
        *self.inner.status.lock() = name.to_string();
        let me = self.clone();
        let label = name.to_string();
        let handle = tokio::spawn(async move {
            let r = fut.await;
            me.release();
            *me.inner.status.lock() = format!("idle (last: {label}: {})", r.as_ref().map_or_else(|e| format!("FAILED {e}"), |o| format!("OK {o}")));
            r
        });
        *self.inner.task.lock() = None;
        let mut handle = handle;
        match tokio::time::timeout(Duration::from_secs_f64(timeout), &mut handle).await {
            Ok(Ok(r)) => r,
            Ok(Err(e)) => Err(format!("cancelled: {e}")),
            Err(_) => {
                *self.inner.task.lock() = Some(handle);
                Ok(format!("still running: {name}; call stop or get_status"))
            }
        }
    }

    pub(crate) async fn run_pub<F>(&self, name: &str, timeout: f64, fut: F) -> R
    where
        F: std::future::Future<Output = R> + Send + 'static,
    {
        self.run(name, timeout, fut).await
    }

    fn stop_current(&self) {
        if let Some(h) = self.inner.task.lock().take() {
            h.abort();
        }
        self.release();
    }

    // ------------------------------------------------------------------ primitives

    pub(crate) async fn ticks(&self, n: usize) {
        let mut rx = self.client.get_tick_broadcaster();
        for _ in 0..n {
            if rx.recv().await.is_err() {
                return;
            }
        }
    }

    pub(crate) fn block(&self, pos: BlockPos) -> Option<BlockState> {
        self.client.world().ok()?.read().get_block_state(pos)
    }

    pub(crate) fn block_name(&self, pos: BlockPos) -> String {
        self.block(pos).map_or("unloaded".into(), |s| BlockKind::from(s).to_string())
    }

    /// Turn like a mouse: eased steps, every step a whole number of sensitivity steps, one step per tick.
    pub(crate) async fn turn_to(&self, yaw: f32, pitch: f32) -> Result<(), String> {
        let pitch = pitch.clamp(-90.0, 90.0);
        let mut rx = self.client.get_tick_broadcaster();
        for _ in 0..80 {
            let d = self.client.direction().map_err(err)?;
            let dy = wrap(yaw - d.y_rot());
            let dp = pitch - d.x_rot();
            if dy.abs() < MOUSE_STEP && dp.abs() < MOUSE_STEP {
                return Ok(());
            }
            let sy = quantize(ease(dy));
            let sp = quantize(ease(dp));
            self.client
                .set_direction(d.y_rot() + sy, (d.x_rot() + sp).clamp(-90.0, 90.0))
                .map_err(err)?;
            if rx.recv().await.is_err() {
                return Err("disconnected".into());
            }
        }
        Ok(())
    }

    pub(crate) async fn look_at(&self, point: Vec3) -> Result<(), String> {
        let eye = self.client.eye_position().map_err(err)?;
        let (yaw, pitch) = angles(eye, point);
        self.turn_to(yaw, pitch).await
    }

    pub(crate) fn crosshair_block(&self) -> Option<(BlockPos, Direction)> {
        match self.client.hit_result().ok()? {
            HitResult::Block(b) if !b.miss => Some((b.block_pos, b.direction)),
            _ => None,
        }
    }

    pub(crate) fn describe_crosshair(&self) -> String {
        match self.client.hit_result() {
            Ok(HitResult::Block(b)) if !b.miss => format!("block {} at {} face {:?}", self.block_name(b.block_pos), fmt_pos(b.block_pos), b.direction),
            Ok(HitResult::Entity(_)) => "an entity".into(),
            _ => "nothing".into(),
        }
    }

    pub(crate) fn inventory_slots(&self) -> Vec<ItemStack> {
        self.client.menu().map(|m| m.slots()).unwrap_or_default()
    }

    /// Select the hotbar slot holding `item`. Returns an error if it is not on the hotbar.
    pub(crate) async fn select_item(&self, item: ItemKind) -> Result<(), String> {
        let slots = self.inventory_slots();
        for i in 0..9u8 {
            if let Some(ItemStack::Present(s)) = slots.get(36 + i as usize) {
                if s.kind == item {
                    if self.client.selected_hotbar_slot().map_err(err)? != i {
                        self.client.set_selected_hotbar_slot(i);
                        self.ticks(2).await;
                    }
                    return Ok(());
                }
            }
        }
        Err(format!("no {item} on the hotbar"))
    }

    // ------------------------------------------------------------------ tools

    pub async fn call(&self, name: &str, a: &Value) -> R {
        let c = self.client.clone();
        match name {
            "get_status" => Ok(self.status()),
            "debug_movement" => {
                let m = c.movement_state().map_err(err)?;
                Ok(format!(
                    "move_direction {:?}, move_vector {:?}, trying_to_sprint {}, trying_to_crouch {}\nvelocity: {:?}",
                    m.move_direction,
                    m.move_vector,
                    m.trying_to_sprint,
                    m.trying_to_crouch,
                    c.component::<azalea::entity::Physics>().ok().map(|p| p.velocity)
                ))
            }
            "get_inventory" => {
                let slots = self.inventory_slots();
                let selected = c.selected_hotbar_slot().unwrap_or(0);
                let mut out = format!("selected hotbar slot: {selected}\n");
                for (idx, label) in (36..45).map(|i| (i, format!("hotbar {}", i - 36))).chain((9..36).map(|i| (i, format!("inv {i}")))) {
                    if let Some(ItemStack::Present(s)) = slots.get(idx) {
                        out += &format!("{label}: {}x {}\n", s.count, s.kind);
                    }
                }
                Ok(out.trim_end().to_string())
            }
            "look" => {
                let me = self.clone();
                let a = a.clone();
                self.run("look", 10.0, async move {
                    if let (Some(x), Some(y), Some(z)) = (f(&a, "x"), f(&a, "y"), f(&a, "z")) {
                        me.look_at(Vec3::new(x, y, z)).await?;
                    } else {
                        let d = me.client.direction().map_err(err)?;
                        let yaw = f(&a, "yaw").map_or(d.y_rot(), |v| v as f32);
                        let pitch = f(&a, "pitch").map_or(d.x_rot(), |v| v as f32);
                        me.turn_to(yaw, pitch).await?;
                    }
                    let d = me.client.direction().map_err(err)?;
                    Ok(format!("facing yaw={:.2} pitch={:.2}", d.y_rot(), d.x_rot()))
                })
                .await
            }
            "walk" => {
                let me = self.clone();
                let a = a.clone();
                let ticks = i(&a, "ticks").unwrap_or(20).clamp(1, 400) as usize;
                self.run("walk", ticks as f64 / 20.0 + 10.0, async move {
                    let (fw, bk, l, r) = (b(&a, "forward"), b(&a, "back"), b(&a, "left"), b(&a, "right"));
                    let dir = match (fw && !bk, bk && !fw, l && !r, r && !l) {
                        (true, _, true, _) => WalkDirection::ForwardLeft,
                        (true, _, _, true) => WalkDirection::ForwardRight,
                        (_, true, true, _) => WalkDirection::BackwardLeft,
                        (_, true, _, true) => WalkDirection::BackwardRight,
                        (true, ..) => WalkDirection::Forward,
                        (_, true, ..) => WalkDirection::Backward,
                        (_, _, true, _) => WalkDirection::Left,
                        (_, _, _, true) => WalkDirection::Right,
                        _ => WalkDirection::None,
                    };
                    if b(&a, "sprint") && fw {
                        me.client.sprint(azalea::SprintDirection::Forward);
                    } else {
                        me.client.walk(dir);
                    }
                    me.client.set_jumping(b(&a, "jump")).map_err(err)?;
                    me.client.set_crouching(b(&a, "sneak") || *me.inner.sneak.lock()).map_err(err)?;
                    me.ticks(ticks).await;
                    if b(&a, "sneak") {
                        // Stop first and stay crouched until momentum dies down, so we cannot coast off an edge.
                        me.client.walk(WalkDirection::None);
                        me.ticks(4).await;
                    }
                    let p = me.client.position().map_err(err)?;
                    Ok(format!("done after {ticks} ticks; now at {:.2} {:.2} {:.2}", p.x, p.y, p.z))
                })
                .await
            }
            "goto" => {
                let (x, z) = (i(a, "x").ok_or("missing x")?, i(a, "z").ok_or("missing z")?);
                let y = i(a, "y");
                let range = f(a, "range").unwrap_or(0.0);
                let opts = PathfinderOpts::new().allow_mining(b(a, "allow_mining"));
                let me = self.clone();
                self.run("goto", f(a, "timeout_seconds").unwrap_or(120.0), async move {
                    match (y, range > 0.0) {
                        (Some(y), true) => me.client.goto_with_opts(RadiusGoal::new(BlockPos::new(x as i32, y as i32, z as i32).center(), range as f32), opts).await,
                        (Some(y), false) => me.client.goto_with_opts(BlockPosGoal(BlockPos::new(x as i32, y as i32, z as i32)), opts).await,
                        (None, _) => me.client.goto_with_opts(XZGoal { x: x as i32, z: z as i32 }, opts).await,
                    }
                    let p = me.client.position().map_err(err)?;
                    Ok(format!("arrived at {:.1} {:.1} {:.1}", p.x, p.y, p.z))
                })
                .await
            }
            "stop" => {
                self.stop_current();
                Ok("stopped".into())
            }
            "sneak" => {
                *self.inner.sneak.lock() = b(a, "on");
                c.set_crouching(b(a, "on")).map_err(err)?;
                Ok(if b(a, "on") { "sneaking (toggle on)" } else { "not sneaking" }.into())
            }
            "equip" => {
                let item = item_kind(a)?;
                let me = self.clone();
                self.run("equip", 10.0, async move {
                    me.select_item(item).await?;
                    Ok(format!("holding {item}"))
                })
                .await
            }
            "mine_block" => {
                let pos = block_pos(a)?;
                let me = self.clone();
                self.run("mine", f(a, "timeout_seconds").unwrap_or(60.0), async move { me.mine(pos).await }).await
            }
            "place_block" => {
                let pos = block_pos(a)?;
                let item = a.get("item").map(|_| item_kind(a)).transpose()?;
                let me = self.clone();
                self.run("place", 30.0, async move { me.place(pos, item).await }).await
            }
            "find_blocks" => {
                let kind: BlockKind = s(a, "block").ok_or("missing block")?.parse().map_err(|_| "unknown block id".to_string())?;
                let radius = f(a, "radius").unwrap_or(24.0);
                let limit = i(a, "limit").unwrap_or(10) as usize;
                let origin = c.position().map_err(err)?;
                let world = c.world().map_err(err)?;
                let found: Vec<BlockPos> = world
                    .read()
                    .find_blocks(origin, &kind.into())
                    .take_while(|p| p.center().distance_to(origin) <= radius)
                    .take(limit)
                    .collect();
                if found.is_empty() {
                    return Ok(format!("none found within {radius}"));
                }
                Ok(found.iter().map(|p| format!("{} ({:.1} away)", fmt_pos(*p), p.center().distance_to(origin))).collect::<Vec<_>>().join("\n"))
            }
            "get_blocks" => {
                let (x1, y1, z1) = (i(a, "x1").ok_or("x1")?, i(a, "y1").ok_or("y1")?, i(a, "z1").ok_or("z1")?);
                let (x2, y2, z2) = (i(a, "x2").ok_or("x2")?, i(a, "y2").ok_or("y2")?, i(a, "z2").ok_or("z2")?);
                let vol = ((x1 - x2).abs() + 1) * ((y1 - y2).abs() + 1) * ((z1 - z2).abs() + 1);
                if vol > 4096 {
                    return Err(format!("box too large ({vol} > 4096)"));
                }
                let mut out = String::new();
                let mut air = 0;
                for y in (y1.min(y2)..=y1.max(y2)).rev() {
                    for z in z1.min(z2)..=z1.max(z2) {
                        for x in x1.min(x2)..=x1.max(x2) {
                            let p = BlockPos::new(x as i32, y as i32, z as i32);
                            match self.block(p) {
                                Some(st) if st.is_air() => air += 1,
                                Some(st) => out += &format!("{x} {y} {z} {}\n", BlockKind::from(st)),
                                None => out += &format!("{x} {y} {z} unloaded\n"),
                            }
                        }
                    }
                }
                Ok(format!("{out}{air} air blocks omitted"))
            }
            "send_chat" => {
                let msg = s(a, "message").ok_or("missing message")?;
                c.chat(msg);
                Ok("sent".into())
            }
            "read_chat" => {
                let after = i(a, "after").unwrap_or(0) as u64;
                let lines: Vec<String> = self.inner.chat.lock().iter().filter(|(s, _)| *s > after).map(|(s, l)| format!("#{s} {l}")).collect();
                Ok(if lines.is_empty() { "no messages".into() } else { lines.join("\n") })
            }
            "build_module" | "verify_module" | "bridge" | "restock" => crate::build::call(self, name, a).await,
            _ => Err(format!("unknown tool {name}")),
        }
    }

    pub(crate) fn status(&self) -> String {
        let c = &self.client;
        let p = c.position().unwrap_or_default();
        let d = c.direction().ok();
        let held = self.inventory_slots().get(36 + c.selected_hotbar_slot().unwrap_or(0) as usize).cloned();
        format!(
            "position: {:.2} {:.2} {:.2}\nfacing: yaw {:.2} pitch {:.2}\nhealth: {:.1}, food: {}\nheld: {}\ncrosshair: {}\nsneak toggle: {}\ntask: {}",
            p.x,
            p.y,
            p.z,
            d.map_or(0.0, |d| d.y_rot()),
            d.map_or(0.0, |d| d.x_rot()),
            c.health().unwrap_or(0.0),
            c.hunger().map_or(0, |h| h.food),
            match held {
                Some(ItemStack::Present(s)) => format!("{}x {}", s.count, s.kind),
                _ => "empty".into(),
            },
            self.describe_crosshair(),
            *self.inner.sneak.lock(),
            self.inner.status.lock()
        )
    }

    /// Look at the visible face nearest the eye, wait until our own crosshair raycast is on the block, then hold
    /// left click (vanilla-style mining of whatever is under the crosshair) until it breaks.
    async fn mine(&self, pos: BlockPos) -> R {
        let before = self.block(pos).ok_or("block not loaded")?;
        if before.is_air() {
            return Err(format!("nothing to mine at {}", fmt_pos(pos)));
        }
        let eye = self.client.eye_position().map_err(err)?;
        if eye.distance_to(pos.center()) > 4.8 {
            return Err(format!("out of reach ({:.1} blocks)", eye.distance_to(pos.center())));
        }
        let mut on_target = false;
        for face in faces_toward(pos, eye) {
            self.look_at(face_point(pos, face)).await?;
            self.ticks(2).await;
            if self.crosshair_block().map(|(p, _)| p) == Some(pos) {
                on_target = true;
                break;
            }
        }
        if !on_target {
            return Err(format!("block not visible; crosshair on {}", self.describe_crosshair()));
        }
        self.client.left_click_mine(true);
        let mut rx = self.client.get_tick_broadcaster();
        for _ in 0..(20 * 30) {
            if rx.recv().await.is_err() {
                break;
            }
            let now = self.block(pos);
            if now.map(BlockKind::from) != Some(BlockKind::from(before)) {
                self.client.left_click_mine(false);
                return Ok(format!("mined {} at {}", BlockKind::from(before), fmt_pos(pos)));
            }
            if self.crosshair_block().map(|(p, _)| p) != Some(pos) {
                self.client.left_click_mine(false);
                return Err(format!("crosshair moved off the block (now on {})", self.describe_crosshair()));
            }
        }
        self.client.left_click_mine(false);
        Err("block did not break in time".into())
    }

    /// Right-click the face of an adjacent block, but only once our own raycast hits exactly that face.
    pub(crate) async fn place(&self, target: BlockPos, item: Option<ItemKind>) -> R {
        let before = self.block(target).ok_or("target not loaded")?;
        if !before.is_air() && !is_replaceable(before) {
            return Err(format!("target occupied by {}", BlockKind::from(before)));
        }
        if let Some(item) = item {
            self.select_item(item).await?;
        }
        let eye = self.client.eye_position().map_err(err)?;
        for dir in [Direction::Down, Direction::North, Direction::South, Direction::West, Direction::East, Direction::Up] {
            let support = target.offset_with_direction(dir);
            let Some(st) = self.block(support) else { continue };
            if st.is_air() || is_replaceable(st) {
                continue;
            }
            let face = opposite(dir);
            let point = face_point(support, face);
            if eye.distance_to(point) > 4.5 || (point - eye).dot(normal(face)) >= 0.0 {
                continue;
            }
            self.look_at(point).await?;
            self.ticks(2).await;
            if self.crosshair_block() != Some((support, face)) {
                continue;
            }
            self.client.start_use_item();
            self.ticks(4).await;
            let now = self.block(target);
            if now != Some(before) {
                return Ok(format!("placed {} at {}", now.map_or("?".into(), |s| BlockKind::from(s).to_string()), fmt_pos(target)));
            }
            return Err("clicked but nothing was placed".into());
        }
        Err(format!("no visible adjacent face within reach; crosshair on {}", self.describe_crosshair()))
    }
}

// ---------------------------------------------------------------------- tool table

pub fn tool_list() -> Value {
    let o = |props: Value, req: &[&str]| json!({"type": "object", "properties": props, "required": req});
    let n = |d: &str| json!({"type": "number", "description": d});
    let int = |d: &str| json!({"type": "integer", "description": d});
    let bo = |d: &str| json!({"type": "boolean", "description": d});
    let st = |d: &str| json!({"type": "string", "description": d});
    json!([
        {"name": "get_status", "description": "Position, rotation, health, food, held item, crosshair target and current action.", "inputSchema": o(json!({}), &[])},
        {"name": "get_inventory", "description": "List hotbar (0-8) and main inventory items.", "inputSchema": o(json!({}), &[])},
        {"name": "look", "description": "Turn the camera smoothly, like a mouse: x,y,z point or yaw/pitch degrees.", "inputSchema": o(json!({"x": n("x"), "y": n("y"), "z": n("z"), "yaw": n("yaw"), "pitch": n("pitch")}), &[])},
        {"name": "walk", "description": "Hold movement keys for ticks (20 = 1 s).", "inputSchema": o(json!({"forward": bo("W"), "back": bo("S"), "left": bo("A"), "right": bo("D"), "jump": bo("space"), "sneak": bo("shift"), "sprint": bo("sprint"), "ticks": int("1-400")}), &[])},
        {"name": "goto", "description": "Pathfind and walk to x,y,z (y optional). Does not break blocks unless allow_mining.", "inputSchema": o(json!({"x": int("x"), "y": int("y (optional)"), "z": int("z"), "range": n("accept within this distance"), "allow_mining": bo("let the pathfinder dig (default false)"), "timeout_seconds": n("default 120")}), &["x", "z"])},
        {"name": "stop", "description": "Cancel the running action and release all inputs.", "inputSchema": o(json!({}), &[])},
        {"name": "sneak", "description": "Toggle sneaking on/off; persists across actions.", "inputSchema": o(json!({"on": bo("sneak?")}), &["on"])},
        {"name": "equip", "description": "Select the hotbar slot holding an item.", "inputSchema": o(json!({"item": st("item id, e.g. minecraft:sand")}), &["item"])},
        {"name": "mine_block", "description": "Aim at a block (must be visible and in reach) and hold left click until it breaks.", "inputSchema": o(json!({"x": int("x"), "y": int("y"), "z": int("z"), "timeout_seconds": n("default 60")}), &["x", "y", "z"])},
        {"name": "place_block", "description": "Place the held (or given) item at x,y,z by right-clicking a visible face of an adjacent block.", "inputSchema": o(json!({"x": int("x"), "y": int("y"), "z": int("z"), "item": st("item id to select first")}), &["x", "y", "z"])},
        {"name": "find_blocks", "description": "Nearest blocks of a type in loaded chunks.", "inputSchema": o(json!({"block": st("block id"), "radius": n("default 24"), "limit": int("default 10")}), &["block"])},
        {"name": "get_blocks", "description": "Non-air blocks in a box (max 4096).", "inputSchema": o(json!({"x1": int(""), "y1": int(""), "z1": int(""), "x2": int(""), "y2": int(""), "z2": int("")}), &["x1", "y1", "z1", "x2", "y2", "z2"])},
        {"name": "send_chat", "description": "Send a chat message or /command (for talking to humans; bots coordinate through the job board, never chat).", "inputSchema": o(json!({"message": st("text")}), &["message"])},
        {"name": "read_chat", "description": "Read received chat lines after a sequence number.", "inputSchema": o(json!({"after": int("seq")}), &[])}
    ]).as_array().unwrap().iter().cloned().chain(crate::build::tool_list()).collect::<Vec<_>>().into()
}

// ---------------------------------------------------------------------- helpers

pub(crate) fn f(a: &Value, k: &str) -> Option<f64> {
    a.get(k).and_then(Value::as_f64)
}
pub(crate) fn i(a: &Value, k: &str) -> Option<i64> {
    a.get(k).and_then(|v| v.as_i64().or_else(|| v.as_f64().map(|f| f.floor() as i64)))
}
pub(crate) fn b(a: &Value, k: &str) -> bool {
    a.get(k).and_then(Value::as_bool).unwrap_or(false)
}
pub(crate) fn s<'a>(a: &'a Value, k: &str) -> Option<&'a str> {
    a.get(k).and_then(Value::as_str)
}

fn block_pos(a: &Value) -> Result<BlockPos, String> {
    Ok(BlockPos::new(
        i(a, "x").ok_or("missing x")? as i32,
        i(a, "y").ok_or("missing y")? as i32,
        i(a, "z").ok_or("missing z")? as i32,
    ))
}

fn item_kind(a: &Value) -> Result<ItemKind, String> {
    let raw = s(a, "item").ok_or("missing item")?;
    let id = if raw.contains(':') { raw.to_string() } else { format!("minecraft:{raw}") };
    id.parse().map_err(|_| format!("unknown item {id}"))
}

pub(crate) fn fmt_pos(p: BlockPos) -> String {
    format!("{} {} {}", p.x, p.y, p.z)
}

pub(crate) fn wrap(mut d: f32) -> f32 {
    while d > 180.0 {
        d -= 360.0;
    }
    while d < -180.0 {
        d += 360.0;
    }
    d
}

/// Eased fraction of the remaining error, capped per tick.
pub(crate) fn ease(err: f32) -> f32 {
    let step = (err * 0.45).abs().max(err.abs().min(1.0)).min(MAX_TURN_PER_TICK);
    step.copysign(err)
}

pub(crate) fn quantize(step: f32) -> f32 {
    let px = (step / MOUSE_STEP).round();
    let px = if px == 0.0 && step != 0.0 { step.signum() } else { px };
    px * MOUSE_STEP
}

pub(crate) fn angles(eye: Vec3, p: Vec3) -> (f32, f32) {
    let (dx, dy, dz) = (p.x - eye.x, p.y - eye.y, p.z - eye.z);
    let h = (dx * dx + dz * dz).sqrt();
    let yaw = (dz.atan2(dx).to_degrees() - 90.0) as f32;
    let pitch = (-dy.atan2(h).to_degrees()) as f32;
    (wrap(yaw), pitch)
}

pub(crate) fn normal(d: Direction) -> Vec3 {
    match d {
        Direction::Down => Vec3::new(0.0, -1.0, 0.0),
        Direction::Up => Vec3::new(0.0, 1.0, 0.0),
        Direction::North => Vec3::new(0.0, 0.0, -1.0),
        Direction::South => Vec3::new(0.0, 0.0, 1.0),
        Direction::West => Vec3::new(-1.0, 0.0, 0.0),
        Direction::East => Vec3::new(1.0, 0.0, 0.0),
    }
}

pub(crate) fn opposite(d: Direction) -> Direction {
    match d {
        Direction::Down => Direction::Up,
        Direction::Up => Direction::Down,
        Direction::North => Direction::South,
        Direction::South => Direction::North,
        Direction::West => Direction::East,
        Direction::East => Direction::West,
    }
}

pub(crate) fn face_point(pos: BlockPos, face: Direction) -> Vec3 {
    let n = normal(face);
    pos.center() + Vec3::new(n.x * 0.499, n.y * 0.499, n.z * 0.499)
}

/// Faces of `pos` that point toward the eye, nearest-looking first.
pub(crate) fn faces_toward(pos: BlockPos, eye: Vec3) -> Vec<Direction> {
    let mut faces: Vec<(f64, Direction)> = [Direction::Down, Direction::Up, Direction::North, Direction::South, Direction::West, Direction::East]
        .into_iter()
        .filter(|d| (eye - face_point(pos, *d)).dot(normal(*d)) > 0.0)
        .map(|d| (face_point(pos, d).distance_to(eye), d))
        .collect();
    faces.sort_by(|a, b| a.0.total_cmp(&b.0));
    faces.into_iter().map(|(_, d)| d).collect()
}

pub(crate) fn is_replaceable(s: BlockState) -> bool {
    matches!(
        BlockKind::from(s),
        BlockKind::Water | BlockKind::Lava | BlockKind::ShortGrass | BlockKind::TallGrass | BlockKind::Fern | BlockKind::LargeFern | BlockKind::DeadBush | BlockKind::Snow | BlockKind::Vine
    )
}
