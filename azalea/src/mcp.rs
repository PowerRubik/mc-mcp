//! MCP over Streamable HTTP (JSON responses), one endpoint per bot: POST /mcp/<botname>.

use axum::{
    Json, Router,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::post,
};
use serde_json::{Value, json};

use crate::{Bots, actions};

pub async fn serve(bots: Bots, port: u16) {
    let app = Router::new().route("/mcp/{bot}", post(handle).get(not_allowed)).with_state(bots);
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", port)).await.expect("bind MCP port");
    eprintln!("[mcmcp-azalea] MCP listening on http://127.0.0.1:{port}/mcp/<bot>");
    axum::serve(listener, app).await.expect("MCP server");
}

async fn not_allowed() -> StatusCode {
    StatusCode::METHOD_NOT_ALLOWED
}

fn origin_ok(headers: &HeaderMap) -> bool {
    match headers.get("origin").and_then(|o| o.to_str().ok()) {
        None | Some("null") => true,
        Some(o) => o.starts_with("http://localhost") || o.starts_with("http://127.0.0.1"),
    }
}

async fn handle(State(bots): State<Bots>, Path(bot): Path<String>, headers: HeaderMap, Json(msg): Json<Value>) -> Response {
    if !origin_ok(&headers) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let id = msg.get("id").cloned();
    let method = msg.get("method").and_then(Value::as_str).unwrap_or("").to_string();
    let Some(id) = id.filter(|v| !v.is_null()) else {
        return StatusCode::ACCEPTED.into_response(); // notification
    };
    let params = msg.get("params").cloned().unwrap_or(json!({}));
    let result = match method.as_str() {
        "initialize" => Ok(json!({
            "protocolVersion": params.get("protocolVersion").and_then(Value::as_str).unwrap_or("2025-06-18"),
            "capabilities": {"tools": {"listChanged": false}},
            "serverInfo": {"name": format!("mcmcp-azalea/{bot}"), "version": env!("CARGO_PKG_VERSION")},
            "instructions": "Controls one lightweight Minecraft bot. Camera turns are mouse-like and mining/placing only happen once the crosshair is really on the target, so blocks must be visible and within reach. Coordinate with other bots through the job board, never through chat."
        })),
        "ping" => Ok(json!({})),
        "tools/list" => Ok(json!({"tools": actions::tool_list()})),
        "tools/call" => {
            let name = params.get("name").and_then(Value::as_str).unwrap_or("").to_string();
            let args = params.get("arguments").cloned().unwrap_or(json!({}));
            let handle = bots.lock().get(&bot.to_lowercase()).cloned();
            let (text, is_error) = match handle {
                None => (format!("bot {bot} is not connected"), true),
                Some(h) => match h.call(&name, &args).await {
                    Ok(t) => (t, false),
                    Err(e) => (e, true),
                },
            };
            Ok(json!({"content": [{"type": "text", "text": text}], "isError": is_error}))
        }
        _ => Err(json!({"code": -32601, "message": format!("method not found: {method}")})),
    };
    let body = match result {
        Ok(r) => json!({"jsonrpc": "2.0", "id": id, "result": r}),
        Err(e) => json!({"jsonrpc": "2.0", "id": id, "error": e}),
    };
    Json(body).into_response()
}
