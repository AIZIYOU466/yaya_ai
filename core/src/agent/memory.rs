//! 自动记忆（ROADMAP 任务 10）：跨会话持久化记忆条目。
//!
//! 记忆 = `{name, description, content}`。系统提示词注入 description 清单，
//! 正文按需经 `memory_read` 工具读取；持久化由平台 [`MemoryStore`] 提供
//! （Android：SQLite `memories` 表）。

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use super::model::ToolSpec;

/// 记忆元数据（供清单注入系统提示词）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MemoryMeta {
    pub name: String,
    pub description: String,
}

/// 平台记忆存储（Android：`AgentDatabase` 的 `memories` 表）。
pub trait MemoryStore: Send {
    fn list(&mut self) -> Result<Vec<MemoryMeta>, String>;
    fn read(&mut self, name: &str) -> Result<String, String>;
    fn save(&mut self, name: &str, description: &str, content: &str) -> Result<(), String>;
    fn edit(&mut self, name: &str, old_string: &str, new_string: &str) -> Result<(), String>;
    fn delete(&mut self, name: &str) -> Result<(), String>;
}

pub const TOOL_MEMORY_LIST: &str = "memory_list";
pub const TOOL_MEMORY_READ: &str = "memory_read";
pub const TOOL_MEMORY_SAVE: &str = "memory_save";
pub const TOOL_MEMORY_EDIT: &str = "memory_edit";
pub const TOOL_MEMORY_DELETE: &str = "memory_delete";

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: TOOL_MEMORY_LIST.into(),
            description: "列出所有长期记忆（name + description 清单）".into(),
            parameters: json!({"type":"object","properties":{},"additionalProperties":false}),
        },
        ToolSpec {
            name: TOOL_MEMORY_READ.into(),
            description: "读取一条长期记忆的正文".into(),
            parameters: json!({"type":"object","properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false}),
        },
        ToolSpec {
            name: TOOL_MEMORY_SAVE.into(),
            description: "保存或覆盖一条长期记忆（name + description + content）".into(),
            parameters: json!({"type":"object","properties":{"name":{"type":"string"},"description":{"type":"string"},"content":{"type":"string"}},"required":["name","description","content"],"additionalProperties":false}),
        },
        ToolSpec {
            name: TOOL_MEMORY_EDIT.into(),
            description: "对已有记忆正文做局部替换（old_string → new_string）".into(),
            parameters: json!({"type":"object","properties":{"name":{"type":"string"},"old_string":{"type":"string"},"new_string":{"type":"string"}},"required":["name","old_string","new_string"],"additionalProperties":false}),
        },
        ToolSpec {
            name: TOOL_MEMORY_DELETE.into(),
            description: "删除一条长期记忆".into(),
            parameters: json!({"type":"object","properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false}),
        },
    ]
}

/// 派发一次记忆工具调用；非记忆工具返回 `None`（交回内置工具层）。
pub fn dispatch<'a>(
    name: &str,
    args: &Value,
    store: &'a mut dyn MemoryStore,
) -> Option<(bool, String)> {
    let need = |k: &str| -> Result<String, String> {
        args.get(k)
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .ok_or_else(|| format!("缺少必填参数 {k}"))
    };
    match name {
        TOOL_MEMORY_LIST => Some(match store.list() {
            Ok(list) if list.is_empty() => (true, "（暂无记忆）".to_string()),
            Ok(list) => (
                true,
                list.iter()
                    .map(|m| format!("- {}: {}", m.name, m.description))
                    .collect::<Vec<_>>()
                    .join("\n"),
            ),
            Err(e) => (false, format!("列出记忆失败: {e}")),
        }),
        TOOL_MEMORY_READ => {
            let name = match need("name") {
                Ok(n) => n,
                Err(e) => return Some((false, e)),
            };
            Some(match store.read(&name) {
                Ok(content) => (true, content),
                Err(e) => (false, e),
            })
        }
        TOOL_MEMORY_SAVE => {
            let (name, desc, content) = match (need("name"), need("description"), need("content")) {
                (Ok(n), Ok(d), Ok(c)) => (n, d, c),
                (Err(e), _, _) => return Some((false, e)),
                (_, Err(e), _) => return Some((false, e)),
                (_, _, Err(e)) => return Some((false, e)),
            };
            Some(match store.save(&name, &desc, &content) {
                Ok(()) => (true, format!("已保存记忆 {name}")),
                Err(e) => (false, e),
            })
        }
        TOOL_MEMORY_EDIT => {
            let (name, old, new) = match (need("name"), need("old_string"), need("new_string")) {
                (Ok(n), Ok(o), Ok(nw)) => (n, o, nw),
                (Err(e), _, _) => return Some((false, e)),
                (_, Err(e), _) => return Some((false, e)),
                (_, _, Err(e)) => return Some((false, e)),
            };
            Some(match store.edit(&name, &old, &new) {
                Ok(()) => (true, format!("已更新记忆 {name}")),
                Err(e) => (false, e),
            })
        }
        TOOL_MEMORY_DELETE => {
            let name = match need("name") {
                Ok(n) => n,
                Err(e) => return Some((false, e)),
            };
            Some(match store.delete(&name) {
                Ok(()) => (true, format!("已删除记忆 {name}")),
                Err(e) => (false, e),
            })
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    struct FakeStore {
        mem: HashMap<String, (String, String)>,
    }
    impl FakeStore {
        fn with(items: &[(&str, &str, &str)]) -> Self {
            let mut mem = HashMap::new();
            for (n, d, c) in items {
                mem.insert(n.to_string(), (d.to_string(), c.to_string()));
            }
            FakeStore { mem }
        }
    }
    impl MemoryStore for FakeStore {
        fn list(&mut self) -> Result<Vec<MemoryMeta>, String> {
            Ok(self
                .mem
                .iter()
                .map(|(n, (d, _))| MemoryMeta {
                    name: n.clone(),
                    description: d.clone(),
                })
                .collect())
        }
        fn read(&mut self, name: &str) -> Result<String, String> {
            self.mem
                .get(name)
                .map(|(_, c)| c.clone())
                .ok_or_else(|| format!("记忆 {name} 不存在"))
        }
        fn save(&mut self, name: &str, description: &str, content: &str) -> Result<(), String> {
            self.mem
                .insert(name.to_string(), (description.to_string(), content.to_string()));
            Ok(())
        }
        fn edit(&mut self, name: &str, old: &str, new: &str) -> Result<(), String> {
            let (d, c) = self
                .mem
                .get_mut(name)
                .ok_or_else(|| format!("记忆 {name} 不存在"))?;
            if !c.contains(old) {
                return Err(format!("记忆 {name} 中未找到目标片段"));
            }
            *c = c.replace(old, new);
            let _ = d;
            Ok(())
        }
        fn delete(&mut self, name: &str) -> Result<(), String> {
            self.mem
                .remove(name)
                .map(|_| ())
                .ok_or_else(|| format!("记忆 {name} 不存在"))
        }
    }

    #[test]
    fn save_read_edit_delete_roundtrip() {
        let mut store = FakeStore::with(&[]);
        let (ok, msg) = dispatch(
            TOOL_MEMORY_SAVE,
            &json!({"name":"n","description":"d","content":"hello world"}),
            &mut store,
        )
        .unwrap();
        assert!(ok);
        assert!(msg.contains("已保存"));

        let (ok, msg) = dispatch(TOOL_MEMORY_READ, &json!({"name":"n"}), &mut store).unwrap();
        assert!(ok);
        assert_eq!(msg, "hello world");

        let (ok, msg) = dispatch(
            TOOL_MEMORY_EDIT,
            &json!({"name":"n","old_string":"world","new_string":"yaya"}),
            &mut store,
        )
        .unwrap();
        assert!(ok);
        assert!(msg.contains("已更新"));

        let (ok, msg) = dispatch(TOOL_MEMORY_READ, &json!({"name":"n"}), &mut store).unwrap();
        assert!(ok);
        assert_eq!(msg, "hello yaya");

        let (ok, _) = dispatch(TOOL_MEMORY_DELETE, &json!({"name":"n"}), &mut store).unwrap();
        assert!(ok);
        let (ok, msg) = dispatch(TOOL_MEMORY_READ, &json!({"name":"n"}), &mut store).unwrap();
        assert!(!ok);
        assert!(msg.contains("不存在"));
    }

    #[test]
    fn list_formats_and_empty_case() {
        let mut store = FakeStore::with(&[("a", "desc-a", "x"), ("b", "desc-b", "y")]);
        let (ok, msg) = dispatch(TOOL_MEMORY_LIST, &json!({}), &mut store).unwrap();
        assert!(ok);
        assert!(msg.contains("a: desc-a"));
        assert!(msg.contains("b: desc-b"));

        let mut empty = FakeStore::with(&[]);
        let (ok, msg) = dispatch(TOOL_MEMORY_LIST, &json!({}), &mut empty).unwrap();
        assert!(ok);
        assert!(msg.contains("暂无记忆"));
    }

    #[test]
    fn missing_params_and_unknown_tool() {
        let mut store = FakeStore::with(&[]);
        let (ok, msg) = dispatch(TOOL_MEMORY_SAVE, &json!({"name":"n"}), &mut store).unwrap();
        assert!(!ok);
        assert!(msg.contains("缺少必填参数"));

        assert_eq!(dispatch("not_a_memory_tool", &json!({}), &mut store), None);
    }
}
