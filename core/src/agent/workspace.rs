//! 工作区文件系统（ROADMAP 任务 21）：让 AI 直接在工作区读写文件、开发软件。
//!
//! 唯一规范源在 core：工具 schema、参数校验、行/字节窗口截断都在此；
//! 平台只提供 [`FileAccess`] 实现（Android：`filesDir/workspace/` 内，含路径穿越校验）。

use serde_json::{json, Value};

use super::model::ToolSpec;

pub const TOOL_FILE_LIST: &str = "file_list";
pub const TOOL_FILE_READ: &str = "file_read";
pub const TOOL_FILE_WRITE: &str = "file_write";
pub const TOOL_FILE_EDIT: &str = "file_edit";
pub const TOOL_FILE_DELETE: &str = "file_delete";

/// 单次读取上限：行数与字节数（UTF-8），超出截断并提示用 `start_line` 续读。
pub const MAX_READ_LINES: usize = 2000;
pub const MAX_READ_BYTES: usize = 200 * 1024;

/// 文件读取结果（供 core 组装返回文本）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileReadResult {
    pub content: String,
    pub total_lines: u32,
    pub read_lines: u32,
    pub truncated: bool,
}

/// 平台文件访问（Android：`WorkspaceFileAccess`，路径限制在工作区内）。
pub trait FileAccess: Send {
    /// 列出目录内容，返回面向模型的文本（条目名，目录带 `/` 后缀）。
    fn list(&mut self, path: &str) -> Result<String, String>;
    /// 读取文件全文（平台负责大小上限；行窗口由 core 截断）。
    fn read(&mut self, path: &str) -> Result<String, String>;
    /// 写入文件（自动创建父目录）；返回是否新建（false = 覆盖已有）。
    fn write(&mut self, path: &str, content: &str, overwrite: bool) -> Result<bool, String>;
    /// 局部替换：`old_string` 未命中返回 Err。
    fn edit(&mut self, path: &str, old_string: &str, new_string: &str) -> Result<(), String>;
    /// 删除文件（或空目录）。
    fn delete(&mut self, path: &str) -> Result<(), String>;
}

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: TOOL_FILE_LIST.into(),
            description: "列出工作区目录内容（相对工作区根的路径，如 `src/`；目录名带 `/` 后缀）".into(),
            parameters: json!({"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}),
        },
        ToolSpec {
            name: TOOL_FILE_READ.into(),
            description: "读取工作区文件内容；超出 2000 行或 200KB 时截断，可用 start_line 分段续读".into(),
            parameters: json!({"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer"}},"required":["path"],"additionalProperties":false}),
        },
        ToolSpec {
            name: TOOL_FILE_WRITE.into(),
            description: "写入完整文件内容（自动创建父目录）；overwrite=false 时目标已存在则报错".into(),
            parameters: json!({"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"overwrite":{"type":"boolean"}},"required":["path","content"],"additionalProperties":false}),
        },
        ToolSpec {
            name: TOOL_FILE_EDIT.into(),
            description: "对文件做局部替换（old_string → new_string，全部出现处）".into(),
            parameters: json!({"type":"object","properties":{"path":{"type":"string"},"old_string":{"type":"string"},"new_string":{"type":"string"}},"required":["path","old_string","new_string"],"additionalProperties":false}),
        },
        ToolSpec {
            name: TOOL_FILE_DELETE.into(),
            description: "删除文件（或空目录）；不可恢复，需用户确认".into(),
            parameters: json!({"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}),
        },
    ]
}

/// 按行窗口 + 字节上限截断全文；返回 (内容, 总行数, 实际行数, 是否截断)。
fn window(content: &str, start_line: u32) -> (String, u32, u32, bool) {
    let lines: Vec<&str> = content.lines().collect();
    let total = lines.len() as u32;
    if start_line > 1 && start_line as usize > lines.len() {
        return (String::new(), total, 0, false);
    }
    let start = (start_line.saturating_sub(1)) as usize;
    let mut out = String::new();
    let mut emitted = 0u32;
    let mut bytes = 0usize;
    for line in &lines[start..] {
        let line_bytes = line.len() + 1;
        if bytes + line_bytes > MAX_READ_BYTES && emitted > 0 {
            break;
        }
        if emitted > 0 {
            out.push('\n');
        }
        out.push_str(line);
        bytes += line_bytes;
        emitted += 1;
        if emitted as usize >= MAX_READ_LINES {
            break;
        }
    }
    let remaining = total - (start_line - 1);
    let truncated = emitted < remaining;
    (out, total, emitted, truncated)
}

/// 派发一次文件工具调用；非文件工具返回 `None`。
pub fn dispatch<'a>(
    name: &str,
    args: &Value,
    file: &'a mut dyn FileAccess,
) -> Option<(bool, String)> {
    let need = |k: &str| -> Result<String, String> {
        args.get(k)
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .ok_or_else(|| format!("缺少必填参数 {k}"))
    };
    match name {
        TOOL_FILE_LIST => {
            let path = match need("path") {
                Ok(p) => p,
                Err(e) => return Some((false, e)),
            };
            Some(match file.list(&path) {
                Ok(text) => (true, text),
                Err(e) => (false, e),
            })
        }
        TOOL_FILE_READ => {
            let path = match need("path") {
                Ok(p) => p,
                Err(e) => return Some((false, e)),
            };
            let start = args
                .get("start_line")
                .and_then(|v| v.as_u64())
                .map(|v| v as u32)
                .unwrap_or(1)
                .max(1);
            Some(match file.read(&path) {
                Ok(full) => {
                    let (content, total, read, truncated) = window(&full, start);
                    let mut msg = content;
                    if truncated {
                        msg.push_str(&format!(
                            "\n…（已达上限，共 {total} 行；从第 {} 行起用 start_line 续读）",
                            start + read
                        ));
                    }
                    (true, msg)
                }
                Err(e) => (false, e),
            })
        }
        TOOL_FILE_WRITE => {
            let (path, content) = match (need("path"), need("content")) {
                (Ok(p), Ok(c)) => (p, c),
                (Err(e), _) => return Some((false, e)),
                (_, Err(e)) => return Some((false, e)),
            };
            let overwrite = args
                .get("overwrite")
                .and_then(|v| v.as_bool())
                .unwrap_or(true);
            Some(match file.write(&path, &content, overwrite) {
                Ok(created) => (
                    true,
                    if created {
                        format!("已创建文件 {path}（{} 行）", content.lines().count())
                    } else {
                        format!("已覆盖文件 {path}（{} 行）", content.lines().count())
                    },
                ),
                Err(e) => (false, e),
            })
        }
        TOOL_FILE_EDIT => {
            let (path, old, new) =
                match (need("path"), need("old_string"), need("new_string")) {
                    (Ok(p), Ok(o), Ok(n)) => (p, o, n),
                    (Err(e), _, _) => return Some((false, e)),
                    (_, Err(e), _) => return Some((false, e)),
                    (_, _, Err(e)) => return Some((false, e)),
                };
            Some(match file.edit(&path, &old, &new) {
                Ok(()) => (true, format!("已更新文件 {path}")),
                Err(e) => (false, e),
            })
        }
        TOOL_FILE_DELETE => {
            let path = match need("path") {
                Ok(p) => p,
                Err(e) => return Some((false, e)),
            };
            Some(match file.delete(&path) {
                Ok(()) => (true, format!("已删除 {path}")),
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

    struct FakeFs {
        files: HashMap<String, String>,
        dirs: Vec<String>,
    }
    impl FakeFs {
        fn with(items: &[(&str, &str)]) -> Self {
            let mut files = HashMap::new();
            for (p, c) in items {
                files.insert(p.to_string(), c.to_string());
            }
            FakeFs {
                files,
                dirs: vec!["src".to_string(), "lib".to_string()],
            }
        }
    }
    impl FileAccess for FakeFs {
        fn list(&mut self, path: &str) -> Result<String, String> {
            if path == "" {
                let mut names: Vec<String> =
                    self.dirs.iter().map(|d| format!("{d}/")).collect();
                names.extend(self.files.keys().cloned());
                names.sort();
                Ok(names.join("\n"))
            } else {
                Err(format!("目录不存在: {path}"))
            }
        }
        fn read(&mut self, path: &str) -> Result<String, String> {
            self.files
                .get(path)
                .cloned()
                .ok_or_else(|| format!("文件不存在: {path}"))
        }
        fn write(&mut self, path: &str, content: &str, _overwrite: bool) -> Result<bool, String> {
            let created = !self.files.contains_key(path);
            self.files.insert(path.to_string(), content.to_string());
            Ok(created)
        }
        fn edit(&mut self, path: &str, old: &str, new: &str) -> Result<(), String> {
            let c = self
                .files
                .get_mut(path)
                .ok_or_else(|| format!("文件不存在: {path}"))?;
            if !c.contains(old) {
                return Err(format!("{path} 中未找到目标片段"));
            }
            *c = c.replace(old, new);
            Ok(())
        }
        fn delete(&mut self, path: &str) -> Result<(), String> {
            self.files
                .remove(path)
                .map(|_| ())
                .ok_or_else(|| format!("文件不存在: {path}"))
        }
    }

    #[test]
    fn write_read_edit_delete_roundtrip() {
        let mut fs = FakeFs::with(&[]);
        let (ok, msg) = dispatch(
            TOOL_FILE_WRITE,
            &json!({"path":"main.rs","content":"fn main() {}"}),
            &mut fs,
        )
        .unwrap();
        assert!(ok);
        assert!(msg.contains("已创建"));

        let (ok, msg) = dispatch(TOOL_FILE_READ, &json!({"path":"main.rs"}), &mut fs).unwrap();
        assert!(ok);
        assert_eq!(msg, "fn main() {}");

        let (ok, _) = dispatch(
            TOOL_FILE_EDIT,
            &json!({"path":"main.rs","old_string":"{}","new_string":"{ println!(\"hi\") }"}),
            &mut fs,
        )
        .unwrap();
        assert!(ok);

        let (ok, msg) = dispatch(TOOL_FILE_READ, &json!({"path":"main.rs"}), &mut fs).unwrap();
        assert!(ok);
        assert!(msg.contains("println!"));

        let (ok, _) = dispatch(TOOL_FILE_DELETE, &json!({"path":"main.rs"}), &mut fs).unwrap();
        assert!(ok);
        let (ok, msg) = dispatch(TOOL_FILE_READ, &json!({"path":"main.rs"}), &mut fs).unwrap();
        assert!(!ok);
        assert!(msg.contains("不存在"));
    }

    #[test]
    fn list_and_missing_params() {
        let mut fs = FakeFs::with(&[("a.txt", "x")]);
        let (ok, msg) = dispatch(TOOL_FILE_LIST, &json!({"path":""}), &mut fs).unwrap();
        assert!(ok);
        assert!(msg.contains("a.txt"));
        assert!(msg.contains("src/"));

        let (ok, msg) = dispatch(TOOL_FILE_WRITE, &json!({"path":"p"}), &mut fs).unwrap();
        assert!(!ok);
        assert!(msg.contains("缺少必填参数"));

        assert_eq!(dispatch("not_a_file_tool", &json!({}), &mut fs), None);
    }

    #[test]
    fn read_window_and_truncation() {
        let big = (0..5000).map(|i| format!("line {i}")).collect::<Vec<_>>().join("\n");
        let mut fs = FakeFs::with(&[("big.txt", &big)]);
        let (ok, msg) = dispatch(TOOL_FILE_READ, &json!({"path":"big.txt"}), &mut fs).unwrap();
        assert!(ok);
        assert!(msg.contains("已达上限"), "超长文件应截断提示");
        assert!(msg.lines().count() <= MAX_READ_LINES + 3, "截断行数受限于 MAX_READ_LINES");

        // start_line 分段续读
        let (ok, msg2) = dispatch(
            TOOL_FILE_READ,
            &json!({"path":"big.txt","start_line":4000}),
            &mut fs,
        )
        .unwrap();
        assert!(ok);
        assert!(msg2.contains("line 4000"));
    }
}
