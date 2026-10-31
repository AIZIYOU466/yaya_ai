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
use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::sys::jstring;
use jni::{JNIEnv, JavaVM};
use serde_json::Value;

use yaya_core::agent::executor::{Action, ActionExecutor, ExecResult};
use yaya_core::agent::local_parse;
use yaya_core::agent::mcp::{McpClient, McpTool};
use yaya_core::agent::memory::{MemoryMeta, MemoryStore};
use yaya_core::agent::model::{BackendError, GenerateRequest, ModelBackend, ModelOutput};
use yaya_core::agent::permission::{ApprovalRequest, Approver, RunMode};
use yaya_core::agent::router::{Backend, RouteHints};
use yaya_core::agent::workspace::FileAccess;
use yaya_core::agent::{run_loop, AgentCore, Event, RunConfig};

/// 单任务取消标志（里程碑一为单任务模型）。
static CANCELLED: AtomicBool = AtomicBool::new(false);

/// 全局环境信号槽（JNI 层写入，core 决策；与 `AgentCore.signals` 同一实例）。
static SIGNALS: OnceLock<Arc<yaya_core::agent::SignalSlots>> = OnceLock::new();

/// 获取或初始化全局信号槽（运行时单例，跨任务持久）。
fn signals() -> &'static Arc<yaya_core::agent::SignalSlots> {
    SIGNALS.get_or_init(|| yaya_core::agent::SignalSlots::new())
}

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
    /// 取当前线程的 JNIEnv；并行子代理线程由 Rust 侧创建，首次调用时按 daemon 线程挂载
    /// （任务 32；线程退出由 JVM 自动解挂，不阻塞进程退出）。
    fn env(&self) -> Result<JNIEnv<'_>, String> {
        self.0
            .vm
            .get_env()
            .or_else(|_| self.0.vm.attach_current_thread_as_daemon())
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
    fn execute(&mut self, action: &Action) -> Result<ExecResult, String> {
        let json = serde_json::to_string(action).map_err(|e| format!("动作序列化失败: {e}"))?;
        let out = self.call_str("executeAction", &json)?;
        if out.trim().is_empty() {
            return Ok(ExecResult::text("执行成功"));
        }
        let v: Value = serde_json::from_str(&out).unwrap_or(Value::Null);
        let ok = v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false);
        let msg = v
            .get("message")
            .and_then(|m| m.as_str())
            .unwrap_or("")
            .to_string();
        // 可选退出码（Kotlin terminal 返回）；其余动作缺省 None。
        let exit_code = v
            .get("exit_code")
            .and_then(|c| c.as_i64())
            .and_then(|c| i32::try_from(c).ok());
        if ok {
            Ok(ExecResult {
                text: if msg.is_empty() {
                    "执行成功".to_string()
                } else {
                    msg
                },
                exit_code,
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

// ── 25 多提供商：配置解析与注册（主循环与并行子代理共用） ──

#[cfg(feature = "cloud-http")]
#[derive(Clone)]
struct ProviderCfg {
    base: String,
    key: String,
    model: String,
    proto: yaya_core::agent::provider::ApiProtocol,
}

/// 解析 `config.providers`（`[{baseUrl, apiKey, model, type}]`）；type ∈ openai/anthropic/gemini。
#[cfg(feature = "cloud-http")]
fn provider_cfgs(config: &Value) -> Vec<ProviderCfg> {
    config
        .get("providers")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|p| {
                    let base = p.get("baseUrl")?.as_str()?.trim();
                    if base.is_empty() {
                        return None;
                    }
                    Some(ProviderCfg {
                        base: base.to_string(),
                        key: p
                            .get("apiKey")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string(),
                        model: p
                            .get("model")
                            .and_then(|v| v.as_str())
                            .unwrap_or("gpt-4o-mini")
                            .to_string(),
                        proto: match p.get("type").and_then(|v| v.as_str()) {
                            Some("anthropic") => {
                                yaya_core::agent::provider::ApiProtocol::Anthropic
                            }
                            Some("gemini") => yaya_core::agent::provider::ApiProtocol::Gemini,
                            _ => yaya_core::agent::provider::ApiProtocol::OpenAi,
                        },
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

/// 注册云后端：`providers` 非空时每项注册一个端点实例；否则回退旧单端点（per-kind）。
#[cfg(feature = "cloud-http")]
fn register_cloud_backends(
    core: &mut AgentCore,
    providers: &[ProviderCfg],
    legacy: Option<&ProviderCfg>,
) -> Result<(), String> {
    if providers.is_empty() {
        if let Some(p) = legacy {
            let cloud = yaya_core::agent::cloud::CloudBackend::new(
                &p.base,
                &p.key,
                &p.model,
                signals().clone(),
            )?;
            core.register_backend(Box::new(cloud));
        }
        return Ok(());
    }
    for p in providers {
        let cloud = yaya_core::agent::cloud::CloudBackend::new_with_protocol(
            &p.base,
            &p.key,
            &p.model,
            p.proto,
            signals().clone(),
        )?;
        let id = yaya_core::agent::degrade::normalize_endpoint_id(&p.base);
        core.register_endpoint(id, Box::new(cloud));
    }
    Ok(())
}

/// 并行子代理环境工厂（任务 32）：为每个并行子代理重建一套独立 `AgentCore`，
/// 平台能力（执行/授权/记忆/文件/MCP）与后端注册与主循环一致。
struct JniSubagentFork {
    host: JniHost,
    #[cfg(feature = "cloud-http")]
    providers: Vec<ProviderCfg>,
    #[cfg(feature = "cloud-http")]
    legacy: Option<ProviderCfg>,
    local_model_path: Option<String>,
    mcp_enabled: bool,
    network_ok: bool,
}

impl yaya_core::agent::subagent::SubagentFork for JniSubagentFork {
    fn fork_env(&self, _parent_cfg: &RunConfig) -> Option<AgentCore> {
        let mut core = AgentCore::new(Box::new(self.host.clone()));
        core.register_approver(Box::new(JniApprover {
            host: self.host.clone(),
        }));
        core.register_memory_store(Box::new(JniMemoryStore {
            host: self.host.clone(),
        }));
        core.register_file_access(Box::new(JniFileAccess {
            host: self.host.clone(),
        }));
        if let Some(path) = &self.local_model_path {
            core.register_backend(Box::new(LocalBackend {
                host: self.host.clone(),
                model_path: path.clone(),
            }));
        }
        #[cfg(feature = "cloud-http")]
        if register_cloud_backends(&mut core, &self.providers, self.legacy.as_ref()).is_err() {
            // 后端配置非法：不静默降级为无后端（该子代理会显式失败）。
            return None;
        }
        if self.mcp_enabled {
            core.register_mcp(Box::new(JniMcp {
                host: self.host.clone(),
            }));
        }
        core.hints = RouteHints {
            network_ok: self.network_ok,
            ..Default::default()
        };
        core.signals = signals().clone();
        Some(core)
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
    ) -> Result<ModelOutput, BackendError> {
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
        .map_err(|e| BackendError::fatal(format!("请求序列化失败: {e}")))?;
        let out = self.host.call_str("generatePrompt", &payload).map_err(BackendError::fatal)?;
        let v: Value = serde_json::from_str(&out).map_err(|e| BackendError::fatal(format!("生成结果解析失败: {e}")))?;
        let ok = v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false);
        let text = v
            .get("text")
            .and_then(|t| t.as_str())
            .unwrap_or("")
            .to_string();
        // 端侧生成失败必须显式报错（Kotlin 经 {ok,text} 信封返回），绝不伪装成模型输出。
        if !ok {
            return Err(BackendError::fatal(if text.is_empty() {
                "端侧模型推理失败".to_string()
            } else {
                text
            }));
        }
        if !text.is_empty() {
            on_token(&text).map_err(BackendError::fatal)?;
        }
        let tool_calls = local_parse::extract_tool_calls(&text);
        Ok(ModelOutput {
            text,
            tool_calls,
            usage: None,
            warnings: vec![],
        })
    }

    fn backend(&self) -> Backend {
        Backend::Jni
    }
}

/// 授权确认（经 Kotlin `requestApproval` 弹窗等待用户选择）。
/// 回调失败或解析失败一律按拒绝处理，绝不静默放行。
struct JniApprover {
    host: JniHost,
}

impl Approver for JniApprover {
    fn approve(&mut self, request: &ApprovalRequest) -> bool {
        let payload = match serde_json::to_string(request) {
            Ok(p) => p,
            Err(_) => return false,
        };
        match self.host.call_str("requestApproval", &payload) {
            Ok(out) => serde_json::from_str::<Value>(&out)
                .ok()
                .and_then(|v| v.get("allow").and_then(|b| b.as_bool()))
                .unwrap_or(false),
            Err(_) => false,
        }
    }
}

/// 自动记忆存储（经 Kotlin `AgentDatabase` 的 `memories` 表）。
/// 所有回调失败均显式报错，绝不伪装成成功。
struct JniMemoryStore {
    host: JniHost,
}

impl MemoryStore for JniMemoryStore {
    fn list(&mut self) -> Result<Vec<MemoryMeta>, String> {
        let out = self.host.call_str0("memList")?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("记忆列表解析失败: {e}"))?;
        let items = v
            .get("items")
            .and_then(|a| a.as_array())
            .cloned()
            .unwrap_or_default();
        Ok(items
            .iter()
            .filter_map(|it| {
                let name = it.get("name")?.as_str()?.to_string();
                let description = it
                    .get("description")
                    .and_then(|d| d.as_str())
                    .unwrap_or("")
                    .to_string();
                Some(MemoryMeta { name, description })
            })
            .collect())
    }

    fn read(&mut self, name: &str) -> Result<String, String> {
        let out = self.host.call_str("memRead", name)?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("记忆读取解析失败: {e}"))?;
        if !v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false) {
            return Err(v
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("读取失败")
                .to_string());
        }
        Ok(v.get("content").and_then(|c| c.as_str()).unwrap_or("").to_string())
    }

    fn save(&mut self, name: &str, description: &str, content: &str) -> Result<(), String> {
        let payload = serde_json::to_string(&serde_json::json!({
            "name": name,
            "description": description,
            "content": content,
        }))
        .map_err(|e| format!("记忆保存参数序列化失败: {e}"))?;
        let out = self.host.call_str("memSave", &payload)?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("记忆保存解析失败: {e}"))?;
        if v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false) {
            Ok(())
        } else {
            Err(v
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("保存失败")
                .to_string())
        }
    }

    fn edit(&mut self, name: &str, old_string: &str, new_string: &str) -> Result<(), String> {
        let payload = serde_json::to_string(&serde_json::json!({
            "name": name,
            "old_string": old_string,
            "new_string": new_string,
        }))
        .map_err(|e| format!("记忆编辑参数序列化失败: {e}"))?;
        let out = self.host.call_str("memEdit", &payload)?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("记忆编辑解析失败: {e}"))?;
        if v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false) {
            Ok(())
        } else {
            Err(v
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("编辑失败")
                .to_string())
        }
    }

    fn delete(&mut self, name: &str) -> Result<(), String> {
        let out = self.host.call_str("memDelete", name)?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("记忆删除解析失败: {e}"))?;
        if v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false) {
            Ok(())
        } else {
            Err(v
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("删除失败")
                .to_string())
        }
    }
}

/// 工作区文件访问（经 Kotlin `WorkspaceFileAccess`，路径限制在 `filesDir/workspace/` 内）。
struct JniFileAccess {
    host: JniHost,
}

impl FileAccess for JniFileAccess {
    fn list(&mut self, path: &str) -> Result<String, String> {
        let out = self.host.call_str("wsList", path)?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("工作区列表解析失败: {e}"))?;
        if !v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false) {
            return Err(v
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("列出失败")
                .to_string());
        }
        Ok(v.get("content").and_then(|c| c.as_str()).unwrap_or("").to_string())
    }

    fn read(&mut self, path: &str) -> Result<String, String> {
        let out = self.host.call_str("wsRead", path)?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("工作区读取解析失败: {e}"))?;
        if !v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false) {
            return Err(v
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("读取失败")
                .to_string());
        }
        Ok(v.get("content").and_then(|c| c.as_str()).unwrap_or("").to_string())
    }

    fn exists(&mut self, path: &str) -> Result<bool, String> {
        let out = self.host.call_str("wsExists", path)?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("工作区存在性解析失败: {e}"))?;
        if !v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false) {
            return Err(v
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("查询失败")
                .to_string());
        }
        Ok(v.get("exists").and_then(|b| b.as_bool()).unwrap_or(false))
    }

    fn write(&mut self, path: &str, content: &str, overwrite: bool) -> Result<bool, String> {
        let payload = serde_json::to_string(&serde_json::json!({
            "path": path,
            "content": content,
            "overwrite": overwrite,
        }))
        .map_err(|e| format!("写入参数序列化失败: {e}"))?;
        let out = self.host.call_str("wsWrite", &payload)?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("写入结果解析失败: {e}"))?;
        if !v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false) {
            return Err(v
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("写入失败")
                .to_string());
        }
        Ok(v.get("created").and_then(|b| b.as_bool()).unwrap_or(false))
    }

    fn delete(&mut self, path: &str) -> Result<(), String> {
        let out = self.host.call_str("wsDelete", path)?;
        let v: Value =
            serde_json::from_str(&out).map_err(|e| format!("删除结果解析失败: {e}"))?;
        if v.get("ok").and_then(|b| b.as_bool()).unwrap_or(false) {
            Ok(())
        } else {
            Err(v
                .get("message")
                .and_then(|m| m.as_str())
                .unwrap_or("删除失败")
                .to_string())
        }
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
    // 任务 27：新 run（含 resume）开始时清除上一次的挂起意图；恢复任务由 loop_state 驱动。
    signals().clear_suspend();

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

        // 授权确认经平台弹窗（BUILD 模式下的不可逆工具会走到这里）。
        core.register_approver(Box::new(JniApprover {
            host: jhost.clone(),
        }));
        // 自动记忆（SQLite memories 表）；未注册时记忆工具不暴露。
        core.register_memory_store(Box::new(JniMemoryStore {
            host: jhost.clone(),
        }));
        // 工作区文件访问（filesDir/workspace/，路径穿越校验在 Kotlin 侧）。
        core.register_file_access(Box::new(JniFileAccess {
            host: jhost.clone(),
        }));
        // 技能目录：App 私有目录 filesDir/skills（Kotlin 保证存在）。
        let skills_dir = jhost.call_str0("skillsDir").unwrap_or_default();

        if local_available && !model_path.is_empty() {
            core.register_backend(Box::new(LocalBackend {
                host: jhost.clone(),
                model_path: model_path.to_string(),
            }));
        }
        // 25 多提供商：`providers` 数组 [{baseUrl, apiKey, model, type}] 每个注册一个
        // 独立 CloudBackend 实例（按端点 id）；缺省回退旧单端点字段。
        // 配置同时交给并行子代理工厂（任务 32），使子代理拥有同一套后端。
        #[cfg(feature = "cloud-http")]
        let provider_cfgs = provider_cfgs(&config);
        #[cfg(feature = "cloud-http")]
        let legacy_cfg = (!base_url.trim().is_empty()).then(|| ProviderCfg {
            base: base_url.to_string(),
            key: api_key.to_string(),
            model: model.unwrap_or("gpt-4o-mini").to_string(),
            proto: yaya_core::agent::provider::ApiProtocol::OpenAi,
        });
        #[cfg(feature = "cloud-http")]
        register_cloud_backends(&mut core, &provider_cfgs, legacy_cfg.as_ref())?;
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
        core.signals = signals().clone();

        // 32：并行子代理环境工厂（平台支持则为每个子代理派生独立 AgentCore）。
        core.register_subagent_fork(Box::new(JniSubagentFork {
            host: jhost.clone(),
            #[cfg(feature = "cloud-http")]
            providers: provider_cfgs.clone(),
            #[cfg(feature = "cloud-http")]
            legacy: legacy_cfg.clone(),
            local_model_path: (local_available && !model_path.is_empty())
                .then(|| model_path.to_string()),
            mcp_enabled,
            network_ok,
        }));

        let mode = match config.get("mode").and_then(|v| v.as_str()) {
            Some("plan") => RunMode::Plan,
            Some("auto") => RunMode::Auto,
            _ => RunMode::Build,
        };

        // 32：自定义子代理定义 + 同轮并行上限（缺省/非法回退默认值）。
        let subagents: Vec<yaya_core::agent::subagent::SubagentDef> = config
            .get("subagents")
            .and_then(|v| serde_json::from_value(v.clone()).ok())
            .unwrap_or_default();
        let max_parallel_subagents = config
            .get("maxParallelSubagents")
            .and_then(|v| v.as_u64())
            .map(|v| v as usize)
            .unwrap_or(yaya_core::agent::subagent::DEFAULT_MAX_PARALLEL)
            .clamp(1, yaya_core::agent::subagent::MAX_PARALLEL_LIMIT);

        let cfg = RunConfig {
            model: model.map(|s| s.to_string()),
            max_steps,
            mode,
            subagents,
            max_parallel_subagents,
            tool_allowlist: None,
            skills_dir: if skills_dir.is_empty() {
                None
            } else {
                Some(skills_dir)
            },
            mcp_tool_allowlist: config
                .get("mcpToolAllowlist")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|t| t.as_str().map(|s| s.to_string()))
                        .collect::<HashSet<_>>()
                }),
            capabilities: yaya_core::agent::capability::Capabilities::from_config(
                config.get("capabilities"),
            ),
            canary: config.get("canary").and_then(|v| v.as_bool()).unwrap_or(false),
            degrade_state: config.get("degradeState").cloned(),
            // 43：上次中断的循环光标检查点（平台落盘后注入，从断点续跑）。
            loop_state: config.get("loopState").cloned(),
            // 42/25：端点池——多提供商（providers）优先派生（全 Cloud kind），
            // 缺省用 `endpoints` 字段（[{baseUrl, kind}]，单点/混合配置）。空走原单后端路由。
            endpoints: {
                let providers = config
                    .get("providers")
                    .and_then(|v| v.as_array())
                    .cloned()
                    .unwrap_or_default();
                if !providers.is_empty() {
                    providers
                        .iter()
                        .filter_map(|p| {
                            let base = p.get("baseUrl")?.as_str()?;
                            Some(yaya_core::agent::pool::EndpointProfile::new(
                                base,
                                yaya_core::agent::router::Backend::Cloud,
                            ))
                        })
                        .collect()
                } else {
                    config
                        .get("endpoints")
                        .and_then(|v| v.as_array())
                        .map(|arr| {
                            arr.iter()
                                .filter_map(|e| {
                                    let base = e.get("baseUrl")?.as_str()?;
                                    let kind = match e.get("kind").and_then(|k| k.as_str())? {
                                        "jni" => yaya_core::agent::router::Backend::Jni,
                                        "desktop" => yaya_core::agent::router::Backend::Desktop,
                                        _ => yaya_core::agent::router::Backend::Cloud,
                                    };
                                    Some(yaya_core::agent::pool::EndpointProfile::new(base, kind))
                                })
                                .collect()
                        })
                        .unwrap_or_default()
                }
            },
            // 25：对话页选中的提供商端点（池选路优先，故障切换后解除）。
            preferred_endpoint: config
                .get("preferredEndpoint")
                .and_then(|v| v.as_str())
                .filter(|s| !s.trim().is_empty())
                .map(|s| s.to_string()),
            // 4C-2：云端 base_url 经归一化作行为历史桶键（无/空串归 "default"）。
            base_url: if base_url.trim().is_empty() {
                None
            } else {
                Some(base_url.to_string())
            },
            // 26：首条用户消息图片（data URL）。只接受 base64 图片，避免非图片
            // 载荷被转发给模型端点；UI 侧已压缩为 JPEG。
            user_images: config
                .get("userImages")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|v| v.as_str())
                        .map(str::trim)
                        .filter(|s| s.starts_with("data:image/") && s.contains(";base64,"))
                        .map(str::to_string)
                        .collect()
                })
                .unwrap_or_default(),
            ..Default::default()
        };

        let mut on_event = |ev: Event| -> Result<(), String> {
            if CANCELLED.load(Ordering::SeqCst) {
                return Err("已取消".to_string());
            }
            jhost.call_void("onEvent", &ev.to_json());
            Ok(())
        };

        let res = run_loop(&mut core, &prompt, &cfg, &mut on_event);
        // 任务结束（成功/失败）时，把降级状态快照经 onEvent 回传 Kotlin 持久化，
        // 供下次任务注入恢复（AGENTS.md R13：Rust Core 不持久化，持久化在 Android 侧）。
        // 复用 Event::DegradeSnapshot 走 on_event 闭包（含 CANCELLED 检查），
        // 避免手写 JSON 与 Event 序列化漂移；快照发送失败（如已取消）不覆盖任务结果。
        let _ = on_event(Event::DegradeSnapshot {
            state: core.degrade.snapshot().to_string(),
        });
        res
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

/// Kotlin: `external fun nativeSuspend()`（任务 27）
/// 请求挂起：core 在下一轮开始前保存 loop_state 并发 `Event::Suspended`，任务不丢弃。
#[no_mangle]
pub extern "system" fn Java_com_yaya_ai_AgentHost_nativeSuspend(_env: JNIEnv, _this: JObject) {
    signals().request_suspend();
}

/// Kotlin: `external fun nativeClearSuspend()`（任务 27）
/// 清除挂起意图（恢复任务前调用；挂起状态本身由 loop_state 表示）。
#[no_mangle]
pub extern "system" fn Java_com_yaya_ai_AgentHost_nativeClearSuspend(
    _env: JNIEnv,
    _this: JObject,
) {
    signals().clear_suspend();
}

/// Kotlin: `external fun nativeSetNetworkLost(lost: Boolean)`
/// 网络连接丢失（`NetworkCallback.onLost` 置位）/恢复（`onAvailable` 复位）。
/// 置位时 core 路由避开 cloud、流中中止超时等待，快速失败让退避重试介入。
#[no_mangle]
pub extern "system" fn Java_com_yaya_ai_AgentHost_nativeSetNetworkLost(
    _env: JNIEnv,
    _this: JObject,
    lost: jni::sys::jboolean,
) {
    signals().network_lost.store(lost != 0, Ordering::Relaxed);
}

/// Kotlin: `external fun nativeSetAppBackground(background: Boolean)`
/// App 前后台切换（`onStop`/`onStart`）。置位时不再发起新的模型生成，
/// 当前任务经 Notice 结束（SSE 无法真正暂停，恢复靠 R13 检查点重跑）。
#[no_mangle]
pub extern "system" fn Java_com_yaya_ai_AgentHost_nativeSetAppBackground(
    _env: JNIEnv,
    _this: JObject,
    background: jni::sys::jboolean,
) {
    signals().app_background.store(background != 0, Ordering::Relaxed);
}

/// Kotlin: `external fun nativeSetPowerSave(save: Boolean)`
/// 系统省电模式。置位时跳过金丝雀等额外请求（降频健康探测）。
#[no_mangle]
pub extern "system" fn Java_com_yaya_ai_AgentHost_nativeSetPowerSave(
    _env: JNIEnv,
    _this: JObject,
    save: jni::sys::jboolean,
) {
    signals().power_save.store(save != 0, Ordering::Relaxed);
}
