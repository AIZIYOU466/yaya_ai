//! 子代理（ROADMAP 任务 8 / 32）：把子任务交给独立的循环实例执行（独立上下文）。
//!
//! 运行方式（任务 32）：
//! - 平台注入 [`SubagentFork`] 工厂时，同一轮模型请求产生的多个 `subagent` 调用
//!   经 [`run_subagents_parallel`] 用 `std::thread::scope` **并发**执行，并发上限由
//!   [`RunConfig::max_parallel_subagents`] 决定；每个子代理持有独立 `AgentCore`，
//!   事件先按子代理缓冲、join 后统一回传（避免跨线程回调 `on_event`）。
//! - 平台未注入工厂（或上限为 1）时回退**顺序递归**，行为与旧版一致。
//!
//! 子代理类型：`general`（默认，继承父级工具与提示词）+ 内置 `explore`（只读）+
//! 用户自定义定义（[`SubagentDef`]，可指定模型 / 工具白名单 / 专属指令 / 步数上限）。
//! 白名单只收窄不放宽：子任务仍受父级运行模式策略约束（BUILD 下不可逆工具仍需确认）。

use std::sync::{mpsc, Arc, Mutex};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use super::model::{ToolCall, ToolSpec};
use super::{memory, run_loop, tools, workspace, AgentCore, Event, RunConfig};

pub const TOOL_SUBAGENT: &str = "subagent";
/// 默认子代理名（无专属定义时使用）。
pub const AGENT_GENERAL: &str = "general";
/// 内置只读探索代理名。
pub const AGENT_EXPLORE: &str = "explore";

/// 只读工具集（内置 Explore 代理的白名单）：文件列/读、记忆列/读、剪贴板读。
pub const READ_ONLY_TOOLS: &[&str] = &[
    workspace::TOOL_FILE_LIST,
    workspace::TOOL_FILE_READ,
    memory::TOOL_MEMORY_LIST,
    memory::TOOL_MEMORY_READ,
    tools::TOOL_CLIPBOARD_READ,
];

/// 同轮并行子代理的默认上限。
pub const DEFAULT_MAX_PARALLEL: usize = 3;
/// 并行上限的硬上限（防移动端线程爆炸）。
pub const MAX_PARALLEL_LIMIT: usize = 8;

fn default_true() -> bool {
    true
}

/// 子代理定义（任务 32）：平台经 `config.subagents` 下发，用户可在设置内启停。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SubagentDef {
    /// 代理名（模型经 `subagent.agent` 参数选择）；`general` 为保留名，自定义定义会被忽略。
    pub name: String,
    /// 一句话说明（注入 `subagent` 工具描述，供模型选择）。
    #[serde(default)]
    pub description: String,
    /// 专属指令：追加在父级系统提示词之后（不替换，保留父级安全规则）。
    #[serde(default)]
    pub prompt: Option<String>,
    /// 专属模型：覆盖父级 `model`（多提供商下由平台按端点解析）。
    #[serde(default)]
    pub model: Option<String>,
    /// 工具白名单；`None` = 继承父级全部工具。只收窄不放宽（父级更严时取交集）。
    #[serde(default)]
    pub tools: Option<Vec<String>>,
    /// 是否启用（默认启用）。
    #[serde(default = "default_true")]
    pub enabled: bool,
    /// 默认步数上限（`subagent.max_steps` 参数优先，仍受父级上限钳制）。
    #[serde(default)]
    pub max_steps: Option<usize>,
}

/// 内置定义：`explore` 只读探索代理。
pub fn explore_def() -> SubagentDef {
    SubagentDef {
        name: AGENT_EXPLORE.to_string(),
        description: "只读探索代理：仅可列举/读取工作区文件与记忆，用于查找与理解现状".to_string(),
        prompt: Some(
            "你是只读探索代理：只允许读取与检索（文件列/读、记忆列/读、剪贴板读），\
             禁止任何写入、删除或命令执行。把发现的事实、文件路径与行号整理成简明结论返回。"
                .to_string(),
        ),
        model: None,
        tools: Some(READ_ONLY_TOOLS.iter().map(|s| s.to_string()).collect()),
        enabled: true,
        max_steps: Some(6),
    }
}

/// 有效定义集：内置定义 + 用户定义（同名用户定义覆盖内置；`general` 与空名忽略）。
pub fn effective_defs(custom: &[SubagentDef]) -> Vec<SubagentDef> {
    let mut defs = vec![explore_def()];
    for d in custom {
        if d.name.trim().is_empty() || d.name == AGENT_GENERAL {
            continue;
        }
        match defs.iter_mut().find(|x| x.name == d.name) {
            Some(slot) => {
                // 覆盖内置定义时只替换显式提供的字段：未填写的工具白名单/指令/步数
                // 保留内置值（例：平台只下发 {name:"explore",enabled:false} 时，
                // 重新启用后仍是只读白名单，不会被夹带成全工具）。
                let mut merged = d.clone();
                if merged.description.trim().is_empty() {
                    merged.description = slot.description.clone();
                }
                if merged.prompt.is_none() {
                    merged.prompt = slot.prompt.clone();
                }
                if merged.tools.is_none() {
                    merged.tools = slot.tools.clone();
                }
                if merged.max_steps.is_none() {
                    merged.max_steps = slot.max_steps;
                }
                *slot = merged;
            }
            None => defs.push(d.clone()),
        }
    }
    defs
}

/// `subagent` 工具的模型可见规格；描述列出当前启用的子代理。
pub fn specs(custom: &[SubagentDef]) -> Vec<ToolSpec> {
    let mut agents: Vec<String> = vec!["general（默认：继承当前全部工具与提示词）".to_string()];
    for d in effective_defs(custom).into_iter().filter(|d| d.enabled) {
        let desc = d.description.trim();
        if desc.is_empty() {
            agents.push(d.name);
        } else {
            agents.push(format!("{}（{desc}）", d.name));
        }
    }
    vec![ToolSpec {
        name: TOOL_SUBAGENT.into(),
        description: format!(
            "把子任务交给一个独立的子代理循环执行（独立上下文）；同一轮可发起多个 \
             subagent 调用并行执行。可用子代理：{}",
            agents.join("；")
        ),
        parameters: json!({
            "type": "object",
            "properties": {
                "task": {"type": "string", "description": "子任务指令"},
                "agent": {"type": "string", "description": "子代理名（省略为 general）"},
                "max_steps": {"type": "integer", "description": "子任务最大步数（默认 5）"}
            },
            "required": ["task"],
            "additionalProperties": false
        }),
    }]
}

/// 解析一次 `subagent` 调用：返回 `(task 指令, 子代理名, 子代理 RunConfig)`。
///
/// 子任务配置继承父级（system prompt / model / mode / skills / 端点池），
/// 步数取参数、定义默认值与父级上限的较小者；定义可覆盖模型与工具白名单。
/// 返回 `Err` 当缺少必填 `task` 或指定的子代理不存在/已停用。
pub fn resolve(
    call: &ToolCall,
    parent_cfg: &RunConfig,
) -> Result<(String, String, RunConfig), String> {
    let args: Value = serde_json::from_str(&call.function.arguments).unwrap_or(Value::Null);
    let task = args.get("task").and_then(|t| t.as_str()).unwrap_or("");
    if task.is_empty() {
        return Err("subagent 缺少必填参数 task".to_string());
    }
    let agent = args
        .get("agent")
        .and_then(|a| a.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(AGENT_GENERAL)
        .to_string();
    let defs = effective_defs(&parent_cfg.subagents);
    let def = if agent == AGENT_GENERAL {
        None
    } else {
        match defs.iter().find(|d| d.name == agent && d.enabled) {
            Some(d) => Some(d.clone()),
            None => {
                let available: Vec<String> = std::iter::once(AGENT_GENERAL.to_string())
                    .chain(defs.iter().filter(|d| d.enabled).map(|d| d.name.clone()))
                    .collect();
                return Err(format!(
                    "子代理 {agent} 不存在或已停用（可用：{}）",
                    available.join(" / ")
                ));
            }
        }
    };

    let want_steps = args
        .get("max_steps")
        .and_then(|s| s.as_u64())
        .map(|s| s as usize)
        .or_else(|| def.as_ref().and_then(|d| d.max_steps))
        .unwrap_or(5);

    // 白名单只收窄：父级已配置时取交集，避免子代理放宽父级限制。
    let tools_allow: Option<std::collections::HashSet<String>> = match (
        &parent_cfg.tool_allowlist,
        def.as_ref().and_then(|d| d.tools.as_ref()),
    ) {
        (Some(p), Some(c)) => Some(c.iter().filter(|t| p.contains(*t)).cloned().collect()),
        (Some(p), None) => Some(p.clone()),
        (None, Some(c)) => Some(c.iter().cloned().collect()),
        (None, None) => None,
    };

    let system_prompt = match &def {
        Some(d) if d.prompt.as_deref().is_some_and(|p| !p.trim().is_empty()) => format!(
            "{}\n\n【子代理：{}】{}\n{}",
            parent_cfg.system_prompt,
            d.name,
            d.description.trim(),
            d.prompt.as_deref().unwrap_or("").trim()
        ),
        _ => parent_cfg.system_prompt.clone(),
    };

    let cfg = RunConfig {
        system_prompt,
        model: def
            .as_ref()
            .and_then(|d| d.model.clone())
            .filter(|m| !m.trim().is_empty())
            .or_else(|| parent_cfg.model.clone()),
        max_steps: want_steps.min(parent_cfg.max_steps),
        max_tokens: parent_cfg.max_tokens,
        max_messages: parent_cfg.max_messages,
        mode: parent_cfg.mode,
        skills_dir: parent_cfg.skills_dir.clone(),
        mcp_tool_allowlist: parent_cfg.mcp_tool_allowlist.clone(),
        capabilities: parent_cfg.capabilities,
        canary: false,
        degrade_state: None,
        base_url: parent_cfg.base_url.clone(),
        // 子代理无独立检查点：不从父任务恢复；端点池沿用父配置（同一平台能力）。
        loop_state: None,
        endpoints: parent_cfg.endpoints.clone(),
        preferred_endpoint: parent_cfg.preferred_endpoint.clone(),
        // 子任务文本独立：不继承父任务的图片附件。
        user_images: vec![],
        // 递归子代理：不再派生自定义代理，且子代理内部并行上限为 1（防线程爆炸）。
        subagents: vec![],
        max_parallel_subagents: 1,
        tool_allowlist: tools_allow,
    };
    Ok((task.to_string(), agent, cfg))
}

/// 子代理运行标识（事件归属标记）：由平台侧 tool_call id 派生，跨端稳定可关联。
pub fn subagent_id(call: &ToolCall) -> String {
    if call.id.trim().is_empty() {
        "sub-unknown".to_string()
    } else {
        format!("sub-{}", call.id.trim())
    }
}

/// 事件 detail 摘要：单行、截断（防把整段产物塞进事件流）。
fn summarize(s: &str) -> String {
    let line = s.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut out: String = line.chars().take(160).collect();
    if line.chars().count() > 160 {
        out.push('…');
    }
    out
}

/// 执行一次 `subagent` 调用：递归运行子循环，返回 (是否成功, 最终文本)。
pub fn run_subagent(
    core: &mut AgentCore,
    call: &ToolCall,
    parent_cfg: &RunConfig,
    on_event: &mut dyn FnMut(Event) -> Result<(), String>,
) -> (bool, String) {
    let (task, agent, sub_cfg) = match resolve(call, parent_cfg) {
        Ok(v) => v,
        Err(e) => return (false, e),
    };
    let id = subagent_id(call);
    let _ = on_event(Event::Subagent {
        id: id.clone(),
        agent: agent.clone(),
        phase: "started".into(),
        detail: summarize(&task),
    });
    let (ok, text) = match run_loop(core, &task, &sub_cfg, on_event) {
        Ok(text) => (true, text),
        Err(e) => (false, e),
    };
    let _ = on_event(Event::Subagent {
        id,
        agent,
        phase: if ok { "finished" } else { "failed" }.into(),
        detail: summarize(&text),
    });
    (ok, text)
}

/// 子代理并行环境工厂（任务 32）：平台注入，为每个并行子代理派生一套独立 `AgentCore`
/// （同一组平台能力与后端注册）。返回 `None` 表示平台不支持派生（该调用显式失败）。
pub trait SubagentFork: Send + Sync {
    fn fork_env(&self, parent_cfg: &RunConfig) -> Option<AgentCore>;
}

/// 一次并行派发的调用计划（`index` = 该调用在原 tool_calls 数组中的下标）。
struct Plan {
    index: usize,
    id: String,
    agent: String,
    task: String,
    cfg: RunConfig,
}

/// 并行派发多个 `subagent` 调用（任务 32）。
///
/// `calls` 为同一轮模型请求产生的全部 subagent 调用（含其在工具调用数组中的索引）。
/// 每批最多 `max_parallel_subagents` 个并发（超出分批执行）；平台未注入
/// [`SubagentFork`] 时回退顺序递归。事件顺序确定性：同一批内先按调用下标重放
/// 子代理事件，再按调用顺序发 finished/failed。
///
/// 返回与 `calls` 同序的 `(索引, 是否成功, 最终文本)`；`Err` 仅在事件回调失败
/// （父级取消）时返回，此时结果不再有意义。
pub fn run_subagents_parallel(
    core: &mut AgentCore,
    calls: &[(usize, ToolCall)],
    parent_cfg: &RunConfig,
    on_event: &mut dyn FnMut(Event) -> Result<(), String>,
) -> Result<Vec<(usize, bool, String)>, String> {
    if calls.is_empty() {
        return Ok(vec![]);
    }
    let mut out: Vec<(usize, bool, String)> = Vec::with_capacity(calls.len());

    // 1）解析计划：参数非法/代理不存在 → 立即失败（不派发）。
    let mut plans: Vec<Plan> = Vec::new();
    for (index, call) in calls {
        match resolve(call, parent_cfg) {
            Ok((task, agent, cfg)) => plans.push(Plan {
                index: *index,
                id: subagent_id(call),
                agent,
                task,
                cfg,
            }),
            Err(e) => {
                on_event(Event::Subagent {
                    id: subagent_id(call),
                    agent: agent_name(call),
                    phase: "failed".into(),
                    detail: summarize(&e),
                })?;
                out.push((*index, false, e));
            }
        }
    }
    if plans.is_empty() {
        out.sort_by_key(|(i, _, _)| *i);
        return Ok(out);
    }

    // 2）派生并行环境（仅并行路径）：一并取出，避免与 `core` 的后续可变借用冲突。
    let parallel = core.subagent_fork.is_some() && parent_cfg.max_parallel_subagents > 1;
    let mut envs: Option<Vec<Option<AgentCore>>> = if parallel {
        core.subagent_fork
            .as_ref()
            .map(|f| plans.iter().map(|p| f.fork_env(&p.cfg)).collect())
    } else {
        None
    };

    if !parallel {
        // 顺序递归（平台不支持并行或上限为 1）：事件按调用顺序实时回传。
        for p in plans {
            on_event(Event::Subagent {
                id: p.id.clone(),
                agent: p.agent.clone(),
                phase: "started".into(),
                detail: summarize(&p.task),
            })?;
            let (ok, text) = match run_loop(core, &p.task, &p.cfg, on_event) {
                Ok(t) => (true, t),
                Err(e) => (false, e),
            };
            on_event(Event::Subagent {
                id: p.id.clone(),
                agent: p.agent.clone(),
                phase: if ok { "finished" } else { "failed" }.into(),
                detail: summarize(&text),
            })?;
            out.push((p.index, ok, text));
        }
        out.sort_by_key(|(i, _, _)| *i);
        return Ok(out);
    }

    let limit = parent_cfg
        .max_parallel_subagents
        .clamp(1, MAX_PARALLEL_LIMIT);
    let mut env_iter = envs.take().unwrap_or_default().into_iter();
    for batch in batch(&plans, limit) {
        // 事件缓冲：子线程不直接回调（on_event 可能持有平台状态），join 后按下标重放。
        let events: Arc<Mutex<Vec<(usize, Event)>>> = Arc::new(Mutex::new(Vec::new()));
        let (tx, rx) = mpsc::channel::<(usize, bool, String)>();
        for p in batch {
            on_event(Event::Subagent {
                id: p.id.clone(),
                agent: p.agent.clone(),
                phase: "started".into(),
                detail: summarize(&p.task),
            })?;
        }

        std::thread::scope(|scope| {
            for p in batch {
                let Some(mut sub_core) = env_iter.next().flatten() else {
                    let _ = tx.send((
                        p.index,
                        false,
                        "子代理无法派生独立环境（平台未提供并行能力）".to_string(),
                    ));
                    continue;
                };
                let tx = tx.clone();
                let evbuf = events.clone();
                let index = p.index;
                let task = p.task.clone();
                let cfg = p.cfg.clone();
                let mut sub = move |ev: Event| -> Result<(), String> {
                    evbuf.lock().unwrap().push((index, ev));
                    Ok(())
                };
                let _ = scope.spawn(move || {
                    let r = run_loop(&mut sub_core, &task, &cfg, &mut sub);
                    let _ = tx.send((index, r.is_ok(), r.unwrap_or_else(|e| e)));
                });
            }
        });
        drop(tx);

        // 3）确定性重放：批次内按调用下标逐个回传（started → 子代理事件 → finished），
        // 使每个事件都落在带 id 的 `Subagent` 跨度内（消费端可归属到具体子代理）。
        // 批次开始时的 started 已提前发过一次（UI 立即显示运行中），此处重复一次仅为标记归属。
        let mut buffered = std::mem::take(&mut *events.lock().unwrap());
        buffered.sort_by_key(|(i, _)| *i);
        let mut cursor = 0usize;
        let mut batch_out: Vec<(usize, bool, String)> = rx.iter().collect();
        batch_out.sort_by_key(|(i, _, _)| *i);
        for (index, ok, text) in batch_out {
            if let Some(p) = batch.iter().find(|p| p.index == index) {
                on_event(Event::Subagent {
                    id: p.id.clone(),
                    agent: p.agent.clone(),
                    phase: "started".into(),
                    detail: summarize(&p.task),
                })?;
                while cursor < buffered.len() && buffered[cursor].0 == index {
                    on_event(buffered[cursor].1.clone())?;
                    cursor += 1;
                }
                on_event(Event::Subagent {
                    id: p.id.clone(),
                    agent: p.agent.clone(),
                    phase: if ok { "finished" } else { "failed" }.into(),
                    detail: summarize(&text),
                })?;
            }
            out.push((index, ok, text));
        }
        // 防御：未对应到计划的缓冲事件不默默丢弃。
        while cursor < buffered.len() {
            on_event(buffered[cursor].1.clone())?;
            cursor += 1;
        }
    }
    out.sort_by_key(|(i, _, _)| *i);
    Ok(out)
}

/// 解析调用里的子代理名（仅用于失败事件标注，缺失/非法时为 `general`）。
fn agent_name(call: &ToolCall) -> String {
    let args: Value = serde_json::from_str(&call.function.arguments).unwrap_or(Value::Null);
    args.get("agent")
        .and_then(|a| a.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(AGENT_GENERAL)
        .to_string()
}

/// 切片分批（每批最多 `n` 个）。
fn batch<T>(items: &[T], n: usize) -> impl Iterator<Item = &[T]> {
    let n = n.max(1);
    (0..items.len()).step_by(n).map(move |i| {
        let end = (i + n).min(items.len());
        &items[i..end]
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::executor::{Action, ActionExecutor, ExecResult};
    use crate::agent::model::{BackendError, FunctionCall, GenerateRequest, ModelBackend, ModelOutput};
    use crate::agent::router::{Backend, RouteHints};

    struct NoopExecutor;
    impl ActionExecutor for NoopExecutor {
        fn execute(&mut self, _a: &Action) -> Result<ExecResult, String> {
            Ok(ExecResult {
                text: "done".into(),
                exit_code: Some(0),
            })
        }
    }

    fn call(id: &str, args: Value) -> ToolCall {
        ToolCall {
            id: id.into(),
            kind: "function".into(),
            function: FunctionCall {
                name: TOOL_SUBAGENT.into(),
                arguments: args.to_string(),
            },
        }
    }

    fn parent() -> RunConfig {
        RunConfig {
            model: Some("parent-model".into()),
            max_steps: 12,
            ..Default::default()
        }
    }

    fn core_with_backend(backend: Box<dyn ModelBackend>) -> AgentCore {
        let mut core = AgentCore::new(Box::new(NoopExecutor));
        core.register_backend(backend);
        core.hints = RouteHints {
            force: Some(Backend::Cloud),
            ..Default::default()
        };
        core
    }

    fn def(name: &str) -> SubagentDef {
        SubagentDef {
            name: name.into(),
            description: format!("{name} 代理"),
            prompt: None,
            model: None,
            tools: None,
            enabled: true,
            max_steps: None,
        }
    }

    #[test]
    fn spec_requires_task_and_lists_agents() {
        let s = specs(&[def("tester")]);
        assert_eq!(s.len(), 1);
        assert_eq!(s[0].name, TOOL_SUBAGENT);
        let required = s[0].parameters["required"].as_array().unwrap();
        assert!(required.iter().any(|v| v == "task"));
        let desc = s[0].description.clone();
        assert!(desc.contains("general"));
        assert!(desc.contains("explore"));
        assert!(desc.contains("tester"));
    }

    #[test]
    fn resolve_rejects_missing_task_and_unknown_agent() {
        assert!(resolve(&call("c1", json!({})), &parent()).is_err());
        assert!(resolve(&call("c2", json!({"task": "x"})), &parent()).is_ok());
        let err = resolve(&call("c3", json!({"task": "x", "agent": "nope"})), &parent())
            .err()
            .expect("未知代理应报错");
        assert!(err.contains("nope"), "错误需指明未知代理: {err}");
        assert!(err.contains("general"), "错误需列出可用代理: {err}");
    }

    #[test]
    fn overriding_builtin_keeps_read_only_tools() {
        // 平台只下发启停（不重复描述内置白名单/指令）：合并后仍为只读代理。
        let cfg = RunConfig {
            subagents: vec![SubagentDef {
                name: AGENT_EXPLORE.into(),
                description: String::new(),
                prompt: None,
                model: None,
                tools: None,
                enabled: true,
                max_steps: None,
            }],
            ..parent()
        };
        let (_, _, sub) = resolve(
            &call("c1", json!({"task": "看看", "agent": "explore"})),
            &cfg,
        )
        .unwrap();
        let allow = sub.tool_allowlist.expect("仍应有只读白名单");
        assert!(allow.contains(workspace::TOOL_FILE_READ));
        assert!(!allow.contains(tools::TOOL_TERMINAL_EXEC));
        assert!(sub.system_prompt.contains("只读探索代理"));
    }

    #[test]
    fn disabled_def_is_not_available() {
        let cfg = RunConfig {
            subagents: vec![SubagentDef {
                enabled: false,
                ..def("off")
            }],
            ..parent()
        };
        assert!(resolve(&call("c1", json!({"task": "x", "agent": "off"})), &cfg).is_err());
    }

    #[test]
    fn explore_is_read_only_and_keeps_parent_rules() {
        let (task, agent, cfg) =
            resolve(&call("c1", json!({"task": "读文件", "agent": "explore"})), &parent()).unwrap();
        assert_eq!(task, "读文件");
        assert_eq!(agent, "explore");
        let allow = cfg.tool_allowlist.expect("explore 应有工具白名单");
        assert!(allow.contains(workspace::TOOL_FILE_READ));
        assert!(!allow.contains(workspace::TOOL_FILE_WRITE));
        assert!(!allow.contains(tools::TOOL_TERMINAL_EXEC));
        assert!(!allow.contains(TOOL_SUBAGENT), "只读代理不得再派生子代理");
        // 专属指令追加在父级提示词之后：父级安全规则仍在。
        assert!(cfg.system_prompt.starts_with(&parent().system_prompt));
        assert!(cfg.system_prompt.contains("只读探索代理"));
        // 子代理内部不再并行、不再派生自定义代理。
        assert_eq!(cfg.max_parallel_subagents, 1);
        assert!(cfg.subagents.is_empty());
        assert_eq!(cfg.max_steps, 6);
    }

    #[test]
    fn custom_def_model_and_tools_override_and_narrow() {
        let cfg = RunConfig {
            subagents: vec![SubagentDef {
                prompt: Some("只写工作区".into()),
                model: Some("small-model".into()),
                tools: Some(vec![workspace::TOOL_FILE_WRITE.to_string()]),
                max_steps: Some(3),
                ..def("writer")
            }],
            tool_allowlist: Some(
                [
                    workspace::TOOL_FILE_WRITE.to_string(),
                    workspace::TOOL_FILE_READ.to_string(),
                ]
                .into_iter()
                .collect(),
            ),
            ..parent()
        };
        let (_, agent, sub) = resolve(
            &call("c1", json!({"task": "写", "agent": "writer"})),
            &cfg,
        )
        .unwrap();
        assert_eq!(agent, "writer");
        assert_eq!(sub.model.as_deref(), Some("small-model"));
        assert_eq!(sub.max_steps, 3);
        let allow = sub.tool_allowlist.unwrap();
        assert_eq!(allow.len(), 1, "交集只保留父级也允许的工具");
        assert!(allow.contains(workspace::TOOL_FILE_WRITE));
    }

    #[test]
    fn read_only_allowlist_removes_subagent_spec() {
        // Explore 白名单不含 subagent → 工具集收窄后该工具不可见（run.rs 用同一集合过滤）。
        let mut specs = specs(&[]);
        let allow: std::collections::HashSet<String> =
            READ_ONLY_TOOLS.iter().map(|s| s.to_string()).collect();
        specs.retain(|s| allow.contains(&s.name));
        assert!(specs.is_empty());
    }

    #[test]
    fn invalid_calls_fail_without_dispatch() {
        let mut core = AgentCore::new(Box::new(NoopExecutor));
        let calls = vec![
            (0usize, call("a", json!({"agent": "nope", "task": "x"}))),
            (1usize, call("b", json!({"agent": "explore"}))),
        ];
        let mut events = Vec::new();
        let out = run_subagents_parallel(&mut core, &calls, &parent(), &mut |e| {
            events.push(e);
            Ok(())
        })
        .unwrap();
        assert_eq!(out.len(), 2);
        assert!(out.iter().all(|(_, ok, _)| !ok));
        let phases: Vec<String> = events
            .iter()
            .filter_map(|e| match e {
                Event::Subagent { phase, id, .. } => Some(format!("{id}:{phase}")),
                _ => None,
            })
            .collect();
        assert_eq!(phases, vec!["sub-a:failed", "sub-b:failed"]);
    }

    #[test]
    fn def_parses_platform_camel_case_json() {
        // 与 Kotlin `SubagentConfig.toJson()` 的字段名对齐（camelCase）。
        let defs: Vec<SubagentDef> = serde_json::from_value(json!([
            {
                "name": "explore",
                "enabled": false
            },
            {
                "name": "writer",
                "description": "写文件",
                "prompt": "只写工作区",
                "model": "m2",
                "tools": ["file_write"],
                "enabled": true,
                "maxSteps": 4
            }
        ]))
        .expect("平台配置应可反序列化");
        assert_eq!(defs.len(), 2);
        assert!(!defs[0].enabled, "explore 的启停应生效");
        assert!(defs[0].tools.is_none(), "未下发的白名单保留 None（由内置合并）");
        assert_eq!(defs[1].max_steps, Some(4));
        assert_eq!(defs[1].tools.as_ref().map(|t| t.len()), Some(1));
    }

    #[test]
    fn summarize_truncates_single_line() {
        let s = summarize(&"a".repeat(500));
        assert!(s.ends_with('…'));
        assert!(s.chars().count() <= 161);
        assert!(!summarize("多行\n第二行").contains('\n'));
    }

    /// 脚本化后端：每次 generate 弹出一条预置输出（多线程安全）。
    struct ScriptedBackend {
        steps: Arc<Mutex<Vec<ModelOutput>>>,
        barrier: Option<Arc<std::sync::Barrier>>,
    }
    impl ModelBackend for ScriptedBackend {
        fn generate(
            &mut self,
            _req: &GenerateRequest,
            on_token: &mut dyn FnMut(&str) -> Result<(), String>,
        ) -> Result<ModelOutput, BackendError> {
            if let Some(b) = &self.barrier {
                // 两个子代理必须都到达此处：顺序执行会永远等不到 → 测试超时即证伪并发。
                b.wait();
            }
            let out = self.steps.lock().unwrap().pop().unwrap_or_default();
            if !out.text.is_empty() {
                on_token(&out.text).map_err(BackendError::fatal)?;
            }
            Ok(out)
        }
        fn backend(&self) -> Backend {
            Backend::Cloud
        }
    }

    struct ScriptedFork {
        steps: Arc<Mutex<Vec<ModelOutput>>>,
        barrier: Option<Arc<std::sync::Barrier>>,
    }
    impl SubagentFork for ScriptedFork {
        fn fork_env(&self, _cfg: &RunConfig) -> Option<AgentCore> {
            Some(core_with_backend(Box::new(ScriptedBackend {
                steps: self.steps.clone(),
                barrier: self.barrier.clone(),
            })))
        }
    }

    #[test]
    fn parallel_runs_concurrently_and_marks_events() {
        let steps: Arc<Mutex<Vec<ModelOutput>>> = Arc::new(Mutex::new(vec![
            ModelOutput {
                text: "子代理A 结果".into(),
                ..Default::default()
            },
            ModelOutput {
                text: "子代理B 结果".into(),
                ..Default::default()
            },
        ]));
        let barrier = Arc::new(std::sync::Barrier::new(2));
        let mut core = AgentCore::new(Box::new(NoopExecutor));
        core.register_subagent_fork(Box::new(ScriptedFork {
            steps: steps.clone(),
            barrier: Some(barrier),
        }));
        let cfg = RunConfig {
            max_parallel_subagents: 2,
            ..parent()
        };
        let calls = vec![
            (0usize, call("a", json!({"task": "任务 A"}))),
            (1usize, call("b", json!({"task": "任务 B"}))),
        ];
        let mut events = Vec::new();
        let out = run_subagents_parallel(&mut core, &calls, &cfg, &mut |e| {
            events.push(e);
            Ok(())
        })
        .unwrap();
        assert_eq!(out.len(), 2);
        assert!(
            out.iter().all(|(_, ok, _)| *ok),
            "两个子代理都应成功: {out:?}"
        );
        let texts: Vec<String> = out.iter().map(|(_, _, t)| t.clone()).collect();
        assert!(texts.contains(&"子代理A 结果".to_string()));
        assert!(texts.contains(&"子代理B 结果".to_string()));
        let started_ids: std::collections::HashSet<String> = events
            .iter()
            .filter_map(|e| match e {
                Event::Subagent { id, phase, .. } if phase == "started" => Some(id.clone()),
                _ => None,
            })
            .collect();
        let finished: Vec<String> = events
            .iter()
            .filter_map(|e| match e {
                Event::Subagent { id, phase, .. } if phase == "finished" => Some(id.clone()),
                _ => None,
            })
            .collect();
        assert_eq!(started_ids.len(), 2, "两个子代理各应有 started 标记");
        assert_eq!(finished.len(), 2);
        assert!(events.iter().any(|e| matches!(
            e,
            Event::Subagent { id, agent, .. } if id == "sub-a" && agent == "general"
        )));
        // 每个事件都落在带 id 的跨度内：最后一个 Subagent 事件应为 finished（两个都收尾）。
        let phases: Vec<&str> = events
            .iter()
            .filter_map(|e| match e {
                Event::Subagent { phase, .. } => Some(phase.as_str()),
                _ => None,
            })
            .collect();
        assert_eq!(phases.iter().filter(|p| **p == "finished").count(), 2);
        assert_eq!(phases.last().copied(), Some("finished"));
    }

    #[test]
    fn batch_splits_without_loss() {
        let items = vec![1, 2, 3, 4, 5];
        let sizes: Vec<usize> = batch(&items, 2).map(|s| s.len()).collect();
        assert_eq!(sizes, vec![2, 2, 1]);
        let flat: Vec<i32> = batch(&items, 3).flatten().copied().collect();
        assert_eq!(flat, items);
    }

    /// 顺序路径（未注册 fork）也能完成子任务。
    #[test]
    fn sequential_path_without_fork_completes() {
        let steps: Arc<Mutex<Vec<ModelOutput>>> = Arc::new(Mutex::new(vec![ModelOutput {
            text: "顺序结果".into(),
            ..Default::default()
        }]));
        let mut core = core_with_backend(Box::new(ScriptedBackend {
            steps,
            barrier: None,
        }));
        let calls = vec![(0usize, call("a", json!({"task": "任务"})))];
        let mut events = Vec::new();
        let out = run_subagents_parallel(&mut core, &calls, &parent(), &mut |e| {
            events.push(e);
            Ok(())
        })
        .unwrap();
        assert_eq!(out, vec![(0usize, true, "顺序结果".to_string())]);
        assert!(events
            .iter()
            .any(|e| matches!(e, Event::Subagent { phase, .. } if phase == "started")));
    }
}
