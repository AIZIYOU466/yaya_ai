//! Kotlin ↔ Rust Agent Core 的 JNI 桥（AGENTS.md R5/R6）。
//!
//! 循环机、状态机、路由、工具层、OpenAI 解析全部在 `yaya-core`；本 crate 只做两件事：
//! 1. 用 Kotlin 实现的 `ActionExecutor` / `ModelBackend` / `McpClient`（经回调注入循环机）
//! 2. 组装后端与配置，调用 `run_loop`，把事件回推给 Kotlin
//!
//! Kotlin 侧 `com.yaya.ai.AgentHost` 需实现：
//! `executeAction(json: String): String`、`generatePrompt(requestJson: String): String`、
//! `onEvent(json: String)`。

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::sys::jstring;
use jni::{JNIEnv, JavaVM};
use serde_json::Value;

use yaya_core::agent::executor::{Action, ActionExecutor};
use yaya_core::agent::local_parse;
use yaya_core::agent::mcp::{McpClient, McpTool};
use yaya_core::agent::model::{GenerateRequest, ModelBackend, ModelOutput};
use yaya_core::agent::router::{Backend, RouteHints};
use yaya_core::agent::{run_loop, AgentCore, Event, RunConfig};

/// 单任务取消标志（里程碑一为单任务模型）。
static CANCELLED: AtomicBool = AtomicBool::new(false);

/// 满足 `with_local_frame` 的 `E: From<jni::errors::Error>` 约束，同时保留自定义字符串错误信息。
struct JniErr(String);
impl From<jni::errors::Error> for JniErr {
    fn from(e: jni::errors::Error) -> Self {
        JniErr(format!("{e}"))
    }
}
impl From<String> for JniErr {
    fn from(s: String) -> Self {
        JniErr(s)
    }
}

struct HostInner {
    vm: JavaVM,
    host: GlobalRef,
}

/// 指向 Kotlin `AgentHost` 实例的句柄，实现三个平台 trait 中的两个（观察/执行）。
#[derive(Clone)]
struct JniHost(Arc<HostInner>);

impl JniHost {
    fn env(&self) -> Result<JNIEnv<'_>, String> {
        self.0
            .vm
            .get_env()
            .map_err(|e| format!("获取 JNIEnv 失败: {e}"))
    }

    fn describe(env: &mut JNIEnv, method: &str, err: jni::errors::Error) -> JniErr {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
        JniErr(format!("调用 Kotlin {method} 失败: {err}"))
    }

    /// 从 Kotlin 方法返回值提取字符串；`null` 视为空串。
    fn read_str(env: &mut JNIEnv, method: &str, obj: JObject) -> Result<String, JniErr> {
        if obj.is_null() {
            return Ok(String::new());
        }
        let jstr: JString = obj.into();
        env.get_string(&jstr)
            .map(|v| v.into())
            .map_err(|e| JniErr(format!("读取 {method} 返回值失败: {e}")))
    }

    /// 调用无参 Kotlin 方法。局部引用在 local frame 内释放，防长任务泄漏。
    fn call_str0(&self, method: &str) -> Result<String, String> {
        let mut env = self.env()?;
        env.with_local_frame(16, |env| -> Result<String, JniErr> {
            let res = env
                .call_method(self.0.host.as_obj(), method, "()Ljava/lang/String;", &[])
                .map_err(|e| Self::describe(env, method, e))?;
            let obj = res
                .l()
                .map_err(|e| JniErr(format!("{method} 返回值非对象: {e}")))?;
            Self::read_str(env, method, obj)
        })
        .map_err(|JniErr(s)| s)
    }

    /// 调用单字符串参数 Kotlin 方法。
    fn call_str(&self, method: &str, arg: &str) -> Result<String, String> {
        let mut env = self.env()?;
        env.with_local_frame(16, |env| -> Result<String, JniErr> {
            let jarg: JObject = env
                .new_string(arg)
                .map_err(|e| JniErr(format!("构造字符串失败: {e}")))?
                .into();
            let res = env
                .call_method(
                    self.0.host.as_obj(),
                    method,
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    &[JValue::Object(&jarg)],
                )
                .map_err(|e| Self::describe(env, method, e))?;
            let obj = res
                .l()
                .map_err(|e| JniErr(format!("{method} 返回值非对象: {e}")))?;
            Self::read_str(env, method, obj)
        })
        .map_err(|JniErr(s)| s)
    }

    fn call_void(&self, method: &str, arg: &str) {
        let Ok(mut env) = self.env() else { return };
        if let Err(JniErr(e)) = env.with_local_frame(16, |env| -> Result<(), JniErr> {
            let jarg: JObject = env
                .new_string(arg)
                .map_err(|e| JniErr(format!("构造字符串失败: {e}")))?
                .into();
            env.call_method(
                self.0.host.as_obj(),
                method,
                "(Ljava/lang/String;)V",
                &[JValue::Object(&jarg)],
            )
            .map_err(|e| Self::describe(env, method, e))?;
            Ok(())
        }) {
            eprintln!("[yaya-jni] {method} 回调失败: {e}");
        }
    }
}

impl ActionExecutor for JniHost {
    fn execute(&mut self, action: &Action) -> Result<String, String> {
        let json = serde_json::to_string(action).map_err(|e| format!("动作序列化失败: {e}"))?;
        let out = self.call_str("executeAction", &json)?;
        if out.trim().is_empty() {
            return Ok("执行成功".to_string());
        }
        let v: Value = serde_json::from_str(&out).unwrap_or(Value::Null);
        let ok = v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false);
        let msg = v
            .get("message")
            .and_then(|m| m.as_str())
            .unwrap_or("")
            .to_string();
        if ok {
            Ok(if msg.is_empty() {
                "执行成功".to_string()
            } else {
                msg
            })
        } else {
            Err(if msg.is_empty() {
                "执行失败".to_string()
            } else {
                msg
            })
        }
    }
}

/// 端侧本地模型后端（经 Kotlin `generate` 调 llama.cpp）。
/// 仅在 Kotlin 报告端侧可用（非 STUB）且配置了模型路径时注册。
struct LocalBackend {
    host: JniHost,
    model_path: String,
}

impl ModelBackend for LocalBackend {
    fn generate(
        &mut self,
        req: &GenerateRequest,
        on_token: &mut dyn FnMut(&str) -> Result<(), String>,
    ) -> Result<ModelOutput, String> {
        // 端侧小模型无 OpenAI tool_calls 字段：用 ChatML 模板渲染工具 schema 与历史，
        // 再从纯文本输出解析 <tool_call> 块（core/src/agent/local_parse.rs）。
        let system = req
            .messages
            .iter()
            .find(|m| m.role == "system")
            .and_then(|m| m.content.as_ref().map(|c| c.text_str().to_string()))
            .unwrap_or_default();
        let prompt = local_parse::render_prompt(&system, &req.tools, &req.messages);
        let payload = serde_json::to_string(&serde_json::json!({
            "prompt": prompt,
            "model": req.model,
            "model_path": self.model_path,
        }))
        .map_err(|e| format!("请求序列化失败: {e}"))?;
        let out = self.host.call_str("generatePrompt", &payload)?;
        let v: Value = serde_json::from_str(&out).map_err(|e| format!("生成结果解析失败: {e}"))?;
        let ok = v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false);
        let text = v
            .get("text")
            .and_then(|t| t.as_str())
            .unwrap_or("")
            .to_string();
        // 端侧生成失败必须显式报错（Kotlin 经 {ok,text} 信封返回），绝不伪装成模型输出。
        if !ok {
            return Err(if text.is_empty() {
                "端侧模型推理失败".to_string()
            } else {
                text
            });
        }
        if !text.is_empty() {
            on_token(&text)?;
        }
        let tool_calls = local_parse::extract_tool_calls(&text);
        Ok(ModelOutput { text, tool_calls })
    }

    fn backend(&self) -> Backend {
        Backend::Jni
    }
}

/// MCP 客户端（经 Kotlin `McpProcessManager` 管理服务器进程与 JSON-RPC）。
struct JniMcp {
    host: JniHost,
}

impl McpClient for JniMcp {
    fn list_tools(&mut self) -> Result<Vec<McpTool>, String> {
        let out = self.host.call_str0("mcpListTools")?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("MCP 工具列表解析失败: {e}"))?;
        if !v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false) {
            let msg = v
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("未知错误");
            return Err(msg.to_string());
        }
        let tools = v
            .get("tools")
            .and_then(|t| t.as_array())
            .cloned()
            .unwrap_or_default();
        let mut out_tools = Vec::with_capacity(tools.len());
        for t in tools {
            let server = t.get("server").and_then(|s| s.as_str()).unwrap_or("");
            let name = t.get("name").and_then(|s| s.as_str()).unwrap_or("");
            if server.is_empty() || name.is_empty() {
                continue;
            }
            out_tools.push(McpTool {
                server: server.to_string(),
                name: name.to_string(),
                description: t
                    .get("description")
                    .and_then(|s| s.as_str())
                    .unwrap_or("")
                    .to_string(),
                parameters: t
                    .get("inputSchema")
                    .cloned()
                    .unwrap_or_else(|| serde_json::json!({"type": "object"})),
            });
        }
        Ok(out_tools)
    }

    fn call_tool(&mut self, server: &str, tool: &str, args: &Value) -> Result<String, String> {
        let payload = serde_json::to_string(&serde_json::json!({
            "server": server,
            "tool": tool,
            "arguments": args,
        }))
        .map_err(|e| format!("MCP 调用参数序列化失败: {e}"))?;
        let out = self.host.call_str("mcpCallTool", &payload)?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("MCP 调用结果解析失败: {e}"))?;
        let ok = v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false);
        let msg = v
            .get("message")
            .and_then(|m| m.as_str())
            .unwrap_or("")
            .to_string();
        if ok {
            Ok(if msg.is_empty() {
                "调用成功".to_string()
            } else {
                msg
            })
        } else {
            Err(if msg.is_empty() {
                "MCP 调用失败".to_string()
            } else {
                msg
            })
        }
    }
}

fn jstring_of(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|j| j.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn read_string(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map(|v| v.into())
        .map_err(|e| format!("读取入参失败: {e}"))
}

/// Kotlin: `external fun nativeRunLoop(taskId: String, prompt: String, configJson: String, host: Any): String`
#[no_mangle]
pub extern "system" fn Java_com_yaya_ai_AgentHost_nativeRunLoop(
    mut env: JNIEnv,
    _this: JObject,
    task_id: JString,
    prompt: JString,
    config_json: JString,
    host: JObject,
) -> jstring {
    CANCELLED.store(false, Ordering::SeqCst);

    let result = catch_unwind(AssertUnwindSafe(|| -> Result<String, String> {
        let _task_id = read_string(&mut env, &task_id)?;
        let prompt = read_string(&mut env, &prompt)?;
        let config_str = read_string(&mut env, &config_json)?;

        let vm = env
            .get_java_vm()
            .map_err(|e| format!("获取 JavaVM 失败: {e}"))?;
        let host_ref = env
            .new_global_ref(&host)
            .map_err(|e| format!("创建 GlobalRef 失败: {e}"))?;
        let jhost = JniHost(Arc::new(HostInner { vm, host: host_ref }));

        let config: Value = serde_json::from_str(&config_str).unwrap_or(Value::Null);
        let base_url = config.get("baseUrl").and_then(|v| v.as_str()).unwrap_or("");
        let api_key = config.get("apiKey").and_then(|v| v.as_str()).unwrap_or("");
        let model = config.get("model").and_then(|v| v.as_str());
        let max_steps = config
            .get("maxSteps")
            .and_then(|v| v.as_u64())
            .unwrap_or(12) as usize;
        let local_available = config
            .get("localAvailable")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        // 端侧 .gguf 路径；为空视为端侧不可用（与 STUB 同语义，避免选中后才报错）。
        let model_path = config.get("modelPath").and_then(|v| v.as_str()).unwrap_or("");
        let network_ok = config
            .get("networkOk")
            .and_then(|v| v.as_bool())
            .unwrap_or(true);
        // 有任一启用的 MCP 服务器时才注册 MCP 客户端（否则工具集与不启用时完全一致）。
        let mcp_enabled = config
            .get("mcpServers")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .any(|s| s.get("enabled").and_then(|e| e.as_bool()).unwrap_or(false))
            })
            .unwrap_or(false);

        let mut core = AgentCore::new(Box::new(jhost.clone()));

        if local_available && !model_path.is_empty() {
            core.register_backend(Box::new(LocalBackend {
                host: jhost.clone(),
                model_path: model_path.to_string(),
            }));
        }
        #[cfg(feature = "cloud-http")]
        if !base_url.trim().is_empty() {
            let cloud = yaya_core::agent::cloud::CloudBackend::new(
                base_url,
                api_key,
                model.unwrap_or("gpt-4o-mini"),
            )?;
            core.register_backend(Box::new(cloud));
        }
        #[cfg(not(feature = "cloud-http"))]
        let _ = (base_url, api_key);

        if mcp_enabled {
            core.register_mcp(Box::new(JniMcp {
                host: jhost.clone(),
            }));
        }

        core.hints = RouteHints {
            network_ok,
            ..Default::default()
        };

        let cfg = RunConfig {
            model: model.map(|s| s.to_string()),
            max_steps,
            ..Default::default()
        };

        let mut on_event = |ev: Event| -> Result<(), String> {
            if CANCELLED.load(Ordering::SeqCst) {
                return Err("已取消".to_string());
            }
            jhost.call_void("onEvent", &ev.to_json());
            Ok(())
        };

        run_loop(&mut core, &prompt, &cfg, &mut on_event)
    }))
    .unwrap_or_else(|_| Err("Rust Core panic (uncaught)".to_string()));

    let text = match result {
        Ok(text) => text,
        Err(e) => format!("ERROR: {e}"),
    };
    jstring_of(&mut env, &text)
}

/// Kotlin: `external fun nativeCancel()`
#[no_mangle]
pub extern "system" fn Java_com_yaya_ai_AgentHost_nativeCancel(_env: JNIEnv, _this: JObject) {
    CANCELLED.store(true, Ordering::SeqCst);
}
