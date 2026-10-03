//! MCP 客户端抽象（AGENTS.md R10）：工具并入统一工具层，进程与 JSON-RPC 由平台实现。
//!
//! 模型可见的工具名为 `mcp__<server>__<tool>`，避免与内置工具及各服务器互相冲突。
//! Android 侧由 Kotlin 管理服务器进程（stdio）并经 JNI 实现本 trait。

use serde_json::Value;

/// 模型可见的 MCP 工具名前缀。
pub const MCP_PREFIX: &str = "mcp__";

/// 服务器名与工具名之间在模型可见名中的分隔符。
const SEP: &str = "__";

/// 一个 MCP 服务器暴露的工具。
#[derive(Debug, Clone, PartialEq)]
pub struct McpTool {
    pub server: String,
    pub name: String,
    pub description: String,
    /// 服务器给出的 `inputSchema`。
    pub parameters: Value,
}

/// 平台侧 MCP 客户端：负责服务器进程生命周期与 JSON-RPC 往返。
pub trait McpClient: Send {
    /// 列出当前已启用服务器暴露的全部工具。
    fn list_tools(&mut self) -> Result<Vec<McpTool>, String>;

    /// 调用工具，返回面向模型的结果文本。
    fn call_tool(&mut self, server: &str, tool: &str, args: &Value) -> Result<String, String>;
}

/// 合成模型可见的工具名：`mcp__<server>__<tool>`。
///
/// 服务器名与工具名均不得包含 `__`，否则无法无歧义地解析回来，返回 Err。
pub fn qualify(server: &str, tool: &str) -> Result<String, String> {
    if server.is_empty() || tool.is_empty() {
        return Err("MCP 服务器名与工具名不能为空".to_string());
    }
    if server.contains(SEP) || tool.contains(SEP) {
        return Err(format!("MCP 名称不得包含 `{SEP}`：{server} / {tool}"));
    }
    Ok(format!("{MCP_PREFIX}{server}{SEP}{tool}"))
}

/// 解析模型可见的 MCP 工具名；非 MCP 名或格式非法返回 `None`。
pub fn parse(name: &str) -> Option<(&str, &str)> {
    let rest = name.strip_prefix(MCP_PREFIX)?;
    let (server, tool) = rest.split_once(SEP)?;
    if server.is_empty() || tool.is_empty() || tool.contains(SEP) {
        return None;
    }
    Some((server, tool))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn qualify_and_parse_round_trip() {
        let full = qualify("files", "read_file").unwrap();
        assert_eq!(full, "mcp__files__read_file");
        assert_eq!(parse(&full), Some(("files", "read_file")));
    }

    #[test]
    fn qualify_rejects_double_underscore() {
        assert!(qualify("a__b", "t").is_err());
        assert!(qualify("s", "a__b").is_err());
        assert!(qualify("", "t").is_err());
        assert!(qualify("s", "").is_err());
    }

    #[test]
    fn parse_rejects_non_mcp_and_malformed_names() {
        assert_eq!(parse("tap_node"), None);
        assert_eq!(parse("mcp__srv"), None);
        assert_eq!(parse("mcp__"), None);
        assert_eq!(parse("mcp____t"), None);
        assert_eq!(parse("mcp__srv__"), None);
        assert_eq!(parse("mcp__srv__a__b"), None);
    }
}
