//! Lightweight Minecraft bots for the mcmcp tool set, built on Azalea.
//!
//! One process runs a swarm of bots; each bot gets its own MCP endpoint at `http://127.0.0.1:<port>/mcp/<name>`.
//! Actions go through the same honest paths as the Fabric mod: camera turns are stepped per tick in multiples of the
//! mouse sensitivity step, and mining/placing only happen once the bot's own crosshair raycast is on the target.

mod actions;
mod build;
mod mcp;

use std::{collections::HashMap, env, sync::Arc, time::Duration};

use azalea::{prelude::*, swarm::prelude::*};
use parking_lot::Mutex;

/// Live bots by lowercase username, filled in as they log in.
pub type Bots = Arc<Mutex<HashMap<String, actions::BotHandle>>>;

static BOTS: std::sync::OnceLock<Bots> = std::sync::OnceLock::new();

#[derive(Clone, Component, Default)]
struct State;

#[derive(Clone, Resource, Default)]
struct SwarmState;

#[tokio::main]
async fn main() -> AppExit {
    let args: Vec<String> = env::args().collect();
    let arg = |flag: &str, default: &str| {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
            .unwrap_or_else(|| default.to_string())
    };
    let accounts: Vec<String> = arg("--accounts", "AzBot1").split(',').map(str::to_string).collect();
    let server = arg("--server", "localhost");
    let port: u16 = arg("--port", "25610").parse().expect("--port");
    build::set_blueprint_dir(arg("--blueprints", "blueprints"));

    let bots: Bots = Arc::default();
    BOTS.set(bots.clone()).ok();
    tokio::spawn(mcp::serve(bots, port));

    let mut builder = SwarmBuilder::new().set_handler(handle).set_swarm_handler(swarm_handle);
    for name in &accounts {
        builder = builder.add_account_with_state(Account::offline(name), State);
    }
    builder
        .join_delay(Duration::from_millis(1500))
        .set_swarm_state(SwarmState)
        .start(server.as_str())
        .await
}

async fn handle(bot: Client, event: Event, _state: State) -> eyre::Result<()> {
    match event {
        Event::Login => {
            let name = bot.username();
            eprintln!("[mcmcp-azalea] {name} logged in");
            if let Some(bots) = BOTS.get() {
                bots.lock().insert(name.to_lowercase(), actions::BotHandle::new(bot.clone()));
            }
        }
        Event::Chat(m) => {
            if let Some(bots) = BOTS.get() {
                if let Some(h) = bots.lock().get(&bot.username().to_lowercase()) {
                    h.push_chat(m.message().to_string());
                }
            }
        }
        _ => {}
    }
    Ok(())
}

async fn swarm_handle(_swarm: Swarm, _event: SwarmEvent, _state: SwarmState) -> eyre::Result<()> {
    Ok(())
}
