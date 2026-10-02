use actix_web::{post, get, web, App, HttpServer, HttpResponse};
use serde::{Deserialize, Serialize};
use serde_json::json;

mod platform;

#[derive(Deserialize)]
struct ChatRequest {
    messages: Vec<serde_json::Value>,
    #[serde(default = "default_model")]
    model: String,
}

fn default_model() -> String {
    "local".to_string()
}

#[derive(Serialize)]
struct ChatResponse {
    id: String,
    choices: Vec<serde_json::Value>,
}

#[post("/v1/chat/completions")]
async fn chat_completions(req: web::Json<ChatRequest>) -> HttpResponse {
    // 桌面端 llama.cpp 推理（当前 stub）
    let response = json!({
        "id": format!("chatcmpl-{}", chrono::Utc::now().timestamp()),
        "object": "chat.completion",
        "choices": [{
            "index": 0,
            "message": {
                "role": "assistant",
                "content": "[桌面端 llama.cpp 推理服务]"
            },
            "finish_reason": "stop"
        }]
    });
    HttpResponse::Ok().json(response)
}

#[get("/agent/observe")]
async fn observe_screen() -> HttpResponse {
    let tree = platform::capture_screen_tree();
    HttpResponse::Ok().json(tree)
}

#[derive(Deserialize)]
struct ExecuteRequest {
    action_type: String,
    target: Option<String>,
    text: Option<String>,
}

#[post("/agent/execute")]
async fn execute_action(req: web::Json<ExecuteRequest>) -> HttpResponse {
    let ok = platform::execute_action(&req.action_type, req.target.as_deref(), req.text.as_deref());
    HttpResponse::Ok().json(json!({"success": ok}))
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    println!("YAYai Agent Server starting on 0.0.0.0:8082");
    HttpServer::new(|| {
        App::new()
            .service(chat_completions)
            .service(observe_screen)
            .service(execute_action)
    })
    .bind("0.0.0.0:8082")?
    .run()
    .await
}
