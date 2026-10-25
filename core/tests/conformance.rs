//! 平台契约测试套件（ROADMAP 任务 41）。
//!
//! 六个平台 trait（`ActionExecutor` / `ModelBackend` / `McpClient` / `MemoryStore` /
//! `FileAccess` / `Approver`）的「任何实现都必须通过」契约用例 + 参考内存实现。
//!
//! 用途：
//! - 新增平台（桌面 / CLI / 远程 / Android 的 Kotlin 实现）时，按此套件校验不漂移；
//! - 内存 mock 兼作 trait 用法样例（字段语义、错误约定）。
//!
//! 契约总则（AGENTS.md R15「不静默」）：失败必须返回 `Err`/`false`，不得 panic、不得假装成功。

use serde_json::{json, Value};
use yaya_core::agent::executor::{Action, ActionExecutor, ExecResult};
use yaya_core::agent::mcp::{McpClient, McpTool};
use yaya_core::agent::memory::{MemoryMeta, MemoryStore};
use yaya_core::agent::model::{
    Content, GenerateRequest, Message, ModelBackend, ModelOutput,
};
use yaya_core::agent::permission::{ApprovalRequest, Approver, Reversibility};
use yaya_core::agent::router::Backend;
use yaya_core::agent::workspace::FileAccess;

// ── 参考内存实现（mock 平台）──

/// 内存 ActionExecutor：记录收到的动作，终态返回成功文本。
#[derive(Default)]
struct MemExecutor {
    seen: Vec<Action>,
}

impl ActionExecutor for MemExecutor {
    fn execute(&mut self, action: &Action) -> Result<ExecResult, String> {
        self.seen.push(action.clone());
        Ok(ExecResult::text(format!("executed:{}", self.seen.len())))
    }
}

/// 内存 ModelBackend：固定输出，backend 稳定。
struct MemBackend {
    kind: Backend,
    stub: bool,
}

impl ModelBackend for MemBackend {
    fn generate(
        &mut self,
        _req: &GenerateRequest,
        on_token: &mut dyn FnMut(&str) -> Result<(), String>,
    ) -> Result<ModelOutput, yaya_core::agent::model::BackendError> {
        on_token("hi").map_err(yaya_core::agent::model::BackendError::fatal)?;
        Ok(ModelOutput {
            text: "hi".into(),
            tool_calls: vec![],
            usage: None,
            warnings: vec![],
        })
    }

    fn backend(&self) -> Backend {
        self.kind
    }

    fn is_stub(&self) -> bool {
        self.stub
    }
}

/// 内存 McpClient：固定一个工具；未知 server/tool 返回 Err。
struct MemMcp;

impl McpClient for MemMcp {
    fn list_tools(&mut self) -> Result<Vec<McpTool>, String> {
        Ok(vec![McpTool {
            server: "srv".into(),
            name: "echo".into(),
            description: "echo".into(),
            parameters: json!({"type": "object"}),
        }])
    }

    fn call_tool(&mut self, server: &str, tool: &str, args: &Value) -> Result<String, String> {
        if server == "srv" && tool == "echo" {
            Ok(args.to_string())
        } else {
            Err(format!("未知工具 {server}.{tool}"))
        }
    }
}

/// 内存 MemoryStore：以 name 为键。
#[derive(Default)]
struct MemMemories {
    items: Vec<(String, String, String)>, // (name, description, content)
}

impl MemoryStore for MemMemories {
    fn list(&mut self) -> Result<Vec<MemoryMeta>, String> {
        Ok(self
            .items
            .iter()
            .map(|(n, d, _)| MemoryMeta {
                name: n.clone(),
                description: d.clone(),
            })
            .collect())
    }

    fn read(&mut self, name: &str) -> Result<String, String> {
        self.items
            .iter()
            .find(|(n, _, _)| n == name)
            .map(|(_, _, c)| c.clone())
            .ok_or_else(|| format!("记忆不存在: {name}"))
    }

    fn save(&mut self, name: &str, description: &str, content: &str) -> Result<(), String> {
        if let Some(x) = self.items.iter_mut().find(|(n, _, _)| n == name) {
            x.1 = description.into();
            x.2 = content.into();
        } else {
            self.items
                .push((name.into(), description.into(), content.into()));
        }
        Ok(())
    }

    fn edit(&mut self, name: &str, old_string: &str, new_string: &str) -> Result<(), String> {
        let x = self
            .items
            .iter_mut()
            .find(|(n, _, _)| n == name)
            .ok_or_else(|| format!("记忆不存在: {name}"))?;
        if !x.2.contains(old_string) {
            return Err(format!("{name} 中未找到目标片段"));
        }
        x.2 = x.2.replace(old_string, new_string);
        Ok(())
    }

    fn delete(&mut self, name: &str) -> Result<(), String> {
        let before = self.items.len();
        self.items.retain(|(n, _, _)| n != name);
        if self.items.len() == before {
            return Err(format!("记忆不存在: {name}"));
        }
        Ok(())
    }
}

/// 内存 FileAccess：相对路径 + 内容表。
#[derive(Default)]
struct MemFiles {
    files: std::collections::HashMap<String, String>,
}

impl FileAccess for MemFiles {
    fn list(&mut self, _path: &str) -> Result<String, String> {
        let mut names: Vec<String> = self.files.keys().cloned().collect();
        names.sort();
        Ok(names.join("\n"))
    }

    fn read(&mut self, path: &str) -> Result<String, String> {
        self.files
            .get(path)
            .cloned()
            .ok_or_else(|| format!("文件不存在: {path}"))
    }

    fn exists(&mut self, path: &str) -> Result<bool, String> {
        Ok(self.files.contains_key(path))
    }

    fn write(&mut self, path: &str, content: &str, overwrite: bool) -> Result<bool, String> {
        let created = !self.files.contains_key(path);
        if !created && !overwrite {
            return Err(format!("文件已存在: {path}"));
        }
        self.files.insert(path.into(), content.into());
        Ok(created)
    }

    fn delete(&mut self, path: &str) -> Result<(), String> {
        if self.files.remove(path).is_none() {
            return Err(format!("文件不存在: {path}"));
        }
        Ok(())
    }
}

/// 固定策略 Approver：按预设列表返回，耗尽后拒绝（安全默认）。
struct FixedApprover {
    answers: Vec<bool>,
}

impl Approver for FixedApprover {
    fn approve(&mut self, _request: &ApprovalRequest) -> bool {
        if self.answers.is_empty() {
            false
        } else {
            self.answers.remove(0)
        }
    }
}

fn sample_request() -> GenerateRequest {
    GenerateRequest {
        messages: vec![Message::user("hi")],
        tools: vec![],
        model: None,
        max_tokens: Some(64),
        stream: true,
        tool_choice: None,
    }
}

// ── 契约用例 ──

#[test]
fn action_executor_contract() {
    let mut e = MemExecutor::default();
    // 契约：任意 Action 可执行且返回结果，不 panic；重复调用稳定。
    for a in [
        Action::Terminal {
            command: "ls".into(),
            timeout_ms: 1000,
        },
        Action::ClipboardRead,
        Action::ClipboardWrite { text: "x".into() },
        Action::Notify {
            title: "t".into(),
            body: "b".into(),
        },
    ] {
        let r1 = e.execute(&a);
        let r2 = e.execute(&a);
        assert!(r1.is_ok() && r2.is_ok(), "合法 Action 应返回 Ok: {a:?}");
    }
    assert_eq!(e.seen.len(), 8, "每次调用都应被记录（无副作用丢失）");
}

#[test]
fn model_backend_contract() {
    let mut b = MemBackend {
        kind: Backend::Cloud,
        stub: false,
    };
    // 契约：backend() 稳定；generate 的 on_token 被调用且返回输出。
    assert_eq!(b.backend(), Backend::Cloud);
    let mut tokens = String::new();
    let out = b
        .generate(&sample_request(), &mut |t| {
            tokens.push_str(t);
            Ok(())
        })
        .expect("mock 应成功");
    assert_eq!(tokens, "hi");
    assert_eq!(out.text, "hi");
    assert!(!b.is_stub());

    // stub 语义：is_stub 反映实现声明，路由据此避开。
    let stub = MemBackend {
        kind: Backend::Jni,
        stub: true,
    };
    assert!(stub.is_stub());
}

#[test]
fn mcp_client_contract() {
    let mut c = MemMcp;
    // 契约：list_tools 返回可用工具；call_tool 未知目标返回 Err（不静默）。
    let tools = c.list_tools().expect("list_tools 应成功");
    assert!(!tools.is_empty());
    assert!(tools.iter().all(|t| !t.name.is_empty() && !t.server.is_empty()));

    let ok = c.call_tool("srv", "echo", &json!({"v": 1})).unwrap();
    assert!(ok.contains("1"));
    assert!(c.call_tool("srv", "nope", &json!({})).is_err());
    assert!(c.call_tool("ghost", "echo", &json!({})).is_err());
}

#[test]
fn memory_store_contract() {
    let mut m = MemMemories::default();
    // 契约：save→read 往返；list 含 name+description；edit 子串替换；delete 后 read 失败。
    m.save("k", "desc", "hello world").unwrap();
    assert_eq!(m.read("k").unwrap(), "hello world");
    let list = m.list().unwrap();
    assert_eq!(list.len(), 1);
    assert_eq!(list[0].name, "k");
    assert_eq!(list[0].description, "desc");

    m.edit("k", "world", "there").unwrap();
    assert_eq!(m.read("k").unwrap(), "hello there");
    assert!(m.edit("k", "缺失片段", "x").is_err(), "未命中片段应 Err");
    assert!(m.edit("ghost", "a", "b").is_err());

    // 同名 save 覆盖（不新增）。
    m.save("k", "desc2", "replaced").unwrap();
    assert_eq!(m.list().unwrap().len(), 1);
    assert_eq!(m.read("k").unwrap(), "replaced");

    m.delete("k").unwrap();
    assert!(m.read("k").is_err(), "删除后读取应 Err");
    assert!(m.delete("k").is_err(), "重复删除应 Err");
}

#[test]
fn file_access_contract() {
    let mut f = MemFiles::default();
    // 契约：write 返回是否新建；exists 一致；read 往返；overwrite=false 拒绝覆盖。
    assert!(f.write("a/b.txt", "v1", true).unwrap(), "新文件应为 created");
    assert!(f.exists("a/b.txt").unwrap());
    assert_eq!(f.read("a/b.txt").unwrap(), "v1");

    assert!(f.write("a/b.txt", "v2", false).is_err(), "overwrite=false 拒绝覆盖");
    assert!(!f.write("a/b.txt", "v2", true).unwrap(), "覆盖应为 created=false");
    assert_eq!(f.read("a/b.txt").unwrap(), "v2");

    assert!(f.read("missing").is_err());
    assert!(!f.exists("missing").unwrap());

    let listing = f.list("").unwrap();
    assert!(listing.contains("a/b.txt"));
    f.delete("a/b.txt").unwrap();
    assert!(!f.exists("a/b.txt").unwrap());
    assert!(f.delete("a/b.txt").is_err(), "重复删除应 Err");
}

#[test]
fn approver_contract() {
    // 契约：approve 按实现返回；耗尽后默认拒绝（安全默认，不静默放行）。
    let req = ApprovalRequest {
        tool: "terminal_exec".into(),
        args: json!({"command": "rm -rf x"}),
        reversibility: Reversibility::Irreversible,
    };
    let mut allow = FixedApprover {
        answers: vec![true],
    };
    assert!(allow.approve(&req));
    assert!(!allow.approve(&req), "答案耗尽后应拒绝");
    let mut deny = FixedApprover { answers: vec![] };
    assert!(!deny.approve(&req));
}

/// 契约：六个 trait 都可通过引用作为 trait object 使用（平台经 JNI/Box 注入）。
#[test]
fn traits_are_object_safe() {
    let mut exec: Box<dyn ActionExecutor> = Box::new(MemExecutor::default());
    let _ = exec.execute(&Action::ClipboardRead);
    let mut mcp: Box<dyn McpClient> = Box::new(MemMcp);
    let _ = mcp.list_tools();
    let mut mem: Box<dyn MemoryStore> = Box::new(MemMemories::default());
    let _ = mem.list();
    let mut files: Box<dyn FileAccess> = Box::new(MemFiles::default());
    let _ = files.list("");
    let mut appr: Box<dyn Approver> = Box::new(FixedApprover { answers: vec![false] });
    let _ = appr.approve(&ApprovalRequest {
        tool: "t".into(),
        args: Value::Null,
        reversibility: Reversibility::Reversible,
    });
    let mut be: Box<dyn ModelBackend> = Box::new(MemBackend {
        kind: Backend::Cloud,
        stub: false,
    });
    let _ = be.generate(&sample_request(), &mut |_| Ok(()));
    // Content 用于多模态消息（契约：Content::Parts 可构造）。
    let _ = Content::text("x");
}