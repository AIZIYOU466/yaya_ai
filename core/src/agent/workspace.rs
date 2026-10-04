//! 工作区文件系统（ROADMAP 任务 21）：让 AI 直接在工作区读写文件、开发软件。
//!
//! 唯一规范源在 core：工具 schema、参数校验、行/字节窗口截断、patch 解析与应用、
//! 整写预演（diff + 改动比例阈值）都在此；平台只提供 [`FileAccess`] 实现
//! （Android：`filesDir/workspace/` 内，含路径穿越校验，写入时自动备份）。

use serde_json::{json, Value};

use super::model::ToolSpec;

pub const TOOL_FILE_LIST: &str = "file_list";
pub const TOOL_FILE_READ: &str = "file_read";
pub const TOOL_FILE_PATCH: &str = "file_patch";
pub const TOOL_FILE_WRITE: &str = "file_write";
pub const TOOL_FILE_EDIT: &str = "file_edit";
pub const TOOL_FILE_DELETE: &str = "file_delete";

/// 单次读取上限：行数与字节数（UTF-8），超出截断并提示用 `start_line` 续读。
pub const MAX_READ_LINES: usize = 2000;
pub const MAX_READ_BYTES: usize = 200 * 1024;

/// 整写预演的安全阈值：改动比例（(新增+删除)行 / 原行数）超过则拒绝 file_write，
/// 强制改用 file_patch 分 hunk 表达局部改动，防止模型以整写规避精确编辑。
pub const MAX_WRITE_RATIO: f64 = 0.6;

/// patch 行号锚点的容差（±N 行）：行号仅作软定位起点，允许轻微漂移后按上下文精确定位。
pub const ANCHOR_TOLERANCE: usize = 5;

/// 生成的 unified diff 上下文字段（变更段前后保留的未改动行数）。
pub const DIFF_CONTEXT: usize = 3;

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
    /// 目标是否存在（供整写预演判断新建/覆盖）。
    fn exists(&mut self, path: &str) -> Result<bool, String>;
    /// 写入文件（自动创建父目录）；返回是否新建（false = 覆盖已有）。
    fn write(&mut self, path: &str, content: &str, overwrite: bool) -> Result<bool, String>;
    /// 删除文件（或空目录）。
    fn delete(&mut self, path: &str) -> Result<(), String>;
}

// ---------- 编辑安全网：行级 diff / unified diff / patch ----------

/// 行级 diff 操作（LCS 对齐）。
#[derive(Debug, Clone, PartialEq, Eq)]
enum DiffOp {
    Equal(String),
    Delete(String),
    Insert(String),
}

/// 行级 diff：裁剪公共前后缀后，对中间段做 LCS 对齐。
fn diff_lines(old: &str, new: &str) -> Vec<DiffOp> {
    let old_lines: Vec<&str> = old.lines().collect();
    let new_lines: Vec<&str> = new.lines().collect();
    let mut ops = Vec::new();
    let mut i = 0;
    while i < old_lines.len() && i < new_lines.len() && old_lines[i] == new_lines[i] {
        ops.push(DiffOp::Equal(old_lines[i].to_string()));
        i += 1;
    }
    let mut j = 0;
    while j < old_lines.len() - i
        && j < new_lines.len() - i
        && old_lines[old_lines.len() - 1 - j] == new_lines[new_lines.len() - 1 - j]
    {
        j += 1;
    }
    ops.extend(lcs_ops(
        &old_lines[i..old_lines.len() - j],
        &new_lines[i..new_lines.len() - j],
    ));
    for k in (0..j).rev() {
        ops.push(DiffOp::Equal(old_lines[old_lines.len() - 1 - k].to_string()));
    }
    ops
}

/// 中间段 LCS 对齐；规模超限时退化为整段替换（统计仍正确）。
fn lcs_ops(old: &[&str], new: &[&str]) -> Vec<DiffOp> {
    if old.is_empty() {
        return new.iter().map(|l| DiffOp::Insert((*l).to_string())).collect();
    }
    if new.is_empty() {
        return old.iter().map(|l| DiffOp::Delete((*l).to_string())).collect();
    }
    const MAX_LCS_CELLS: usize = 1_000_000;
    if old.len() * new.len() > MAX_LCS_CELLS {
        let mut ops: Vec<DiffOp> = old.iter().map(|l| DiffOp::Delete((*l).to_string())).collect();
        ops.extend(new.iter().map(|l| DiffOp::Insert((*l).to_string())));
        return ops;
    }
    let (m, n) = (old.len(), new.len());
    let mut dp = vec![vec![0u32; n + 1]; m + 1];
    for i in (0..m).rev() {
        for j in (0..n).rev() {
            dp[i][j] = if old[i] == new[j] {
                dp[i + 1][j + 1] + 1
            } else {
                dp[i + 1][j].max(dp[i][j + 1])
            };
        }
    }
    let mut ops = Vec::new();
    let (mut i, mut j) = (0, 0);
    while i < m && j < n {
        if old[i] == new[j] {
            ops.push(DiffOp::Equal(old[i].to_string()));
            i += 1;
            j += 1;
        } else if dp[i + 1][j] >= dp[i][j + 1] {
            ops.push(DiffOp::Delete(old[i].to_string()));
            i += 1;
        } else {
            ops.push(DiffOp::Insert(new[j].to_string()));
            j += 1;
        }
    }
    while i < m {
        ops.push(DiffOp::Delete(old[i].to_string()));
        i += 1;
    }
    while j < n {
        ops.push(DiffOp::Insert(new[j].to_string()));
        j += 1;
    }
    ops
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
            name: TOOL_FILE_PATCH.into(),
            description: "对文件应用 unified diff 补丁（首选精确编辑工具，优于 file_write 整写；多 hunk 一次调用、原子应用——任一 hunk 不匹配则整批拒绝、文件不变）。格式：\n--- a/路径\n+++ b/路径\n@@ -行号,行数 +行号,行数 @@\n 上下文行（空格开头）\n-删除行\n+新增行\n\n匹配失败时先 file_read 确认原文再重试；不要因为匹配麻烦而改用 file_write 整文件覆盖".into(),
            parameters: json!({"type":"object","properties":{"path":{"type":"string"},"patch":{"type":"string"}},"required":["path","patch"],"additionalProperties":false}),
        },
        ToolSpec {
            name: TOOL_FILE_WRITE.into(),
            description: "写入完整文件内容（自动创建父目录）；overwrite=false 时目标已存在则报错。注意：覆盖已有文件会先预演计算改动比例，超过阈值（约 60%）将被拒绝——大范围改动请改用 file_patch 分 hunk 表达".into(),
            parameters: json!({"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"overwrite":{"type":"boolean"}},"required":["path","content"],"additionalProperties":false}),
        },
        ToolSpec {
            name: TOOL_FILE_EDIT.into(),
            description: "对文件做单点替换（old_string → new_string，仅替换唯一一处；出现多处或零处时报错并给指导）。匹配失败时先 file_read 确认原文再重试".into(),
            parameters: json!({"type":"object","properties":{"path":{"type":"string"},"old_string":{"type":"string"},"new_string":{"type":"string"}},"required":["path","old_string","new_string"],"additionalProperties":false}),
        },
        ToolSpec {
            name: TOOL_FILE_DELETE.into(),
            description: "删除文件（或空目录）；不可恢复，需用户确认".into(),
            parameters: json!({"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}),
        },
    ]
}

/// 从 ops 统计 (新增行, 删除行)。
fn stats_from_ops(ops: &[DiffOp]) -> (usize, usize) {
    let mut added = 0;
    let mut removed = 0;
    for op in ops {
        match op {
            DiffOp::Equal(_) => {}
            DiffOp::Delete(_) => removed += 1,
            DiffOp::Insert(_) => added += 1,
        }
    }
    (added, removed)
}

/// 推进行号：Equal 同时推进两边，Delete 只进旧行，Insert 只进新行。
fn advance(op: &DiffOp, old_line: &mut usize, new_line: &mut usize) {
    match op {
        DiffOp::Equal(_) => {
            *old_line += 1;
            *new_line += 1;
        }
        DiffOp::Delete(_) => *old_line += 1,
        DiffOp::Insert(_) => *new_line += 1,
    }
}

/// 把两个版本渲染为 unified diff 文本（变更段带前后 [`DIFF_CONTEXT`] 行上下文）；无变更时返回空串。
fn render_unified_diff(path: &str, old: &str, new: &str) -> String {
    let ops = diff_lines(old, new);
    let change_pos: Vec<usize> = ops
        .iter()
        .enumerate()
        .filter(|(_, op)| matches!(op, DiffOp::Delete(_) | DiffOp::Insert(_)))
        .map(|(i, _)| i)
        .collect();
    if change_pos.is_empty() {
        return String::new();
    }
    let ctx = DIFF_CONTEXT;
    // 合并相邻变更段：间隔 <= 2*ctx 的行并入同一 hunk
    let mut hunks: Vec<(usize, usize)> = Vec::new();
    let mut start = change_pos[0].saturating_sub(ctx);
    let mut end = change_pos[0] + 1;
    for &p in &change_pos[1..] {
        if p - end <= 2 * ctx {
            end = p + 1;
        } else {
            hunks.push((start, end));
            start = p.saturating_sub(ctx);
            end = p + 1;
        }
    }
    hunks.push((start, end));
    let mut out = String::new();
    out.push_str(&format!("--- a/{path}\n+++ b/{path}\n"));
    let (mut old_line, mut new_line) = (1usize, 1usize);
    let mut i = 0;
    for (hstart, hend) in hunks {
        while i < hstart {
            advance(&ops[i], &mut old_line, &mut new_line);
            i += 1;
        }
        let (mut old_cnt, mut new_cnt) = (0usize, 0usize);
        for k in hstart..hend {
            match &ops[k] {
                DiffOp::Equal(_) => {
                    old_cnt += 1;
                    new_cnt += 1;
                }
                DiffOp::Delete(_) => old_cnt += 1,
                DiffOp::Insert(_) => new_cnt += 1,
            }
        }
        out.push_str(&format!("@@ -{old_line},{old_cnt} +{new_line},{new_cnt} @@\n"));
        while i < hend {
            match &ops[i] {
                DiffOp::Equal(l) => {
                    out.push_str(&format!(" {l}\n"));
                    old_line += 1;
                    new_line += 1;
                }
                DiffOp::Delete(l) => {
                    out.push_str(&format!("-{l}\n"));
                    old_line += 1;
                }
                DiffOp::Insert(l) => {
                    out.push_str(&format!("+{l}\n"));
                    new_line += 1;
                }
            }
            i += 1;
        }
    }
    out
}

/// patch 中的一行。
#[derive(Debug, Clone, PartialEq, Eq)]
enum PatchLine {
    /// 上下文行（必须以空格前缀匹配文件内容）。
    Ctx(String),
    /// 删除行（- 前缀）。
    Del(String),
    /// 新增行（+ 前缀）。
    Ins(String),
}

/// 解析出的一个 hunk。
#[derive(Debug, Clone)]
struct PatchHunk {
    /// 行号锚点（旧文件 1-based）；None 表示无锚点（全文搜索定位）。
    old_start: Option<usize>,
    lines: Vec<PatchLine>,
}

/// patch 应用统计。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct PatchStats {
    pub hunks: usize,
    pub added: usize,
    pub removed: usize,
}

/// 从 `@@ -N,M +N',M' @@` 后缀解析旧文件行号锚点（1-based）；解析不出返回 None。
fn parse_anchor(rest: &str) -> Option<usize> {
    let s = rest.trim_start().strip_prefix('-')?;
    let num: String = s.chars().take_while(|c| c.is_ascii_digit()).collect();
    if num.is_empty() {
        None
    } else {
        num.parse().ok()
    }
}

/// 解析 unified diff 文本 → hunk 列表；格式错误返回带行号的 Err。
/// 兼容裸 `@@` 无行号锚点、`*** Begin/End Patch` 包裹、`---/+++` 路径头。
fn parse_patch(patch: &str) -> Result<Vec<PatchHunk>, String> {
    let mut hunks: Vec<PatchHunk> = Vec::new();
    let mut cur: Option<PatchHunk> = None;
    for (no, raw) in patch.lines().enumerate() {
        let line = raw.strip_suffix('\r').unwrap_or(raw);
        let trimmed = line.trim_start();
        if trimmed.starts_with("*** Begin Patch")
            || trimmed.starts_with("*** End Patch")
            || trimmed.starts_with("*** Update File:")
            || line.starts_with("--- ")
            || line.starts_with("+++ ")
            || line.is_empty()
        {
            continue;
        }
        if let Some(rest) = trimmed.strip_prefix("@@") {
            if let Some(h) = cur.take() {
                if !h.lines.is_empty() {
                    hunks.push(h);
                }
            }
            cur = Some(PatchHunk {
                old_start: parse_anchor(rest),
                lines: Vec::new(),
            });
        } else {
            let h = cur.as_mut().ok_or_else(|| {
                format!("patch 第 {} 行：hunk 必须以 @@ 开头，实际为 {line:?}", no + 1)
            })?;
            if let Some(ctx) = line.strip_prefix(' ') {
                h.lines.push(PatchLine::Ctx(ctx.to_string()));
            } else if let Some(del) = line.strip_prefix('-') {
                h.lines.push(PatchLine::Del(del.to_string()));
            } else if let Some(ins) = line.strip_prefix('+') {
                h.lines.push(PatchLine::Ins(ins.to_string()));
            } else {
                return Err(format!(
                    "patch 第 {} 行无法解析：{line:?}（上下文行以空格开头，删除行以 - 开头，新增行以 + 开头）",
                    no + 1
                ));
            }
        }
    }
    if let Some(h) = cur.take() {
        if !h.lines.is_empty() {
            hunks.push(h);
        }
    }
    if hunks.is_empty() {
        return Err("patch 中没有任何 hunk（至少需要一个 @@ 段）".into());
    }
    Ok(hunks)
}

/// hunk 的保持行（上下文 + 删除行，保序）是否与 lines[pos..] 完全一致。
fn hunk_matches(lines: &[String], pos: usize, keep: &[String]) -> bool {
    if pos + keep.len() > lines.len() {
        return false;
    }
    keep.iter().enumerate().all(|(k, want)| lines[pos + k] == *want)
}

/// 解析并应用 unified diff 补丁，返回 (新内容, 统计)。
/// 原子：任一 hunk 匹配失败、多义匹配或区间重叠，整批拒绝、内容不变。
fn apply_patch_to_content(content: &str, patch: &str) -> Result<(String, PatchStats), String> {
    let hunks = parse_patch(patch)?;
    let lines: Vec<String> = content.lines().map(|s| s.to_string()).collect();
    struct Applied {
        pos: usize,
        keep_len: usize,
        replacement: Vec<String>,
        added: usize,
        removed: usize,
    }
    let mut applied: Vec<Applied> = Vec::new();
    for (idx, h) in hunks.iter().enumerate() {
        let keep: Vec<String> = h
            .lines
            .iter()
            .filter_map(|l| match l {
                PatchLine::Ctx(s) | PatchLine::Del(s) => Some(s.clone()),
                PatchLine::Ins(_) => None,
            })
            .collect();
        if keep.is_empty() {
            return Err(format!("hunk {} 没有上下文或删除行，无法定位", idx + 1));
        }
        let candidates: Vec<usize> = match h.old_start {
            Some(anchor) => {
                let lo = anchor.saturating_sub(1 + ANCHOR_TOLERANCE);
                let hi = (anchor + ANCHOR_TOLERANCE).min(lines.len());
                (lo..=hi).filter(|&p| hunk_matches(&lines, p, &keep)).collect()
            }
            None => lines
                .iter()
                .enumerate()
                .filter(|(p, l)| **l == keep[0] && hunk_matches(&lines, *p, &keep))
                .map(|(p, _)| p)
                .collect(),
        };
        match candidates.len() {
            0 => {
                let mut msg = format!("hunk {} 匹配失败", idx + 1);
                msg.push_str(&format!("\n期望匹配（{} 行）：", keep.len()));
                for l in &keep {
                    msg.push('\n');
                    msg.push_str(l);
                }
                match h.old_start {
                    Some(anchor) => {
                        let lo = anchor.saturating_sub(1).min(lines.len());
                        let hi = (anchor + 3).min(lines.len());
                        if lo < hi {
                            msg.push_str(&format!("\n实际内容（第 {} 行附近）：", lo + 1));
                            for (k, l) in lines[lo..hi].iter().enumerate() {
                                msg.push_str(&format!("\nL{}: {}", lo + 1 + k, l));
                            }
                        }
                        msg.push_str("\n建议：行号可能有漂移，请 file_read 确认原文后补充完整上下文行再重试。");
                    }
                    None => {
                        msg.push_str("\n文件中未找到该上下文。建议先 file_read 确认原文，再重试 patch。");
                    }
                }
                return Err(msg);
            }
            1 => {
                let pos = candidates[0];
                let mut replacement = Vec::new();
                let mut added = 0;
                let mut removed = 0;
                for l in &h.lines {
                    match l {
                        PatchLine::Ctx(s) => replacement.push(s.clone()),
                        PatchLine::Ins(s) => {
                            replacement.push(s.clone());
                            added += 1;
                        }
                        PatchLine::Del(_) => removed += 1,
                    }
                }
                applied.push(Applied {
                    pos,
                    keep_len: keep.len(),
                    replacement,
                    added,
                    removed,
                });
            }
            _ => {
                let mut msg = format!(
                    "hunk {} 上下文匹配到 {} 处，存在歧义：",
                    idx + 1,
                    candidates.len()
                );
                for &p in &candidates {
                    let start = p.saturating_sub(1);
                    let end = (p + keep.len() + 1).min(lines.len());
                    msg.push_str(&format!("\n候选位置（第 {} 行起）：", p + 1));
                    for (k, l) in lines[start..end].iter().enumerate() {
                        msg.push_str(&format!("\nL{}: {}", start + 1 + k, l));
                    }
                }
                msg.push_str("\n建议：补充更多上下文行，或用行号锚点 @@ -N,M 限定。");
                return Err(msg);
            }
        }
    }
    // 全部定位成功：从后往前应用，避免行号偏移
    let mut new_lines = lines.clone();
    let mut stats = PatchStats {
        hunks: applied.len(),
        added: 0,
        removed: 0,
    };
    applied.sort_by_key(|a| std::cmp::Reverse(a.pos));
    for w in applied.windows(2) {
        if w[0].pos < w[1].pos + w[1].keep_len {
            return Err("hunk 定位区间重叠，无法安全应用".into());
        }
    }
    for ap in applied {
        new_lines.splice(ap.pos..ap.pos + ap.keep_len, ap.replacement.iter().cloned());
        stats.added += ap.added;
        stats.removed += ap.removed;
    }
    Ok((new_lines.join("\n"), stats))
}

/// 单点替换：old 出现 0 处或 ≥2 处时报错（含诊断），1 处时替换。
fn edit_single(content: &str, old: &str, new: &str) -> Result<String, String> {
    if old.is_empty() {
        return Err("old_string 不能为空".into());
    }
    let byte_to_line =
        |pos: usize| content[..pos.min(content.len())].matches('\n').count() + 1;
    let first = content.find(old).ok_or_else(|| {
        "未找到目标片段。建议先 file_read 确认原文，再重试 file_edit，或改用 file_patch 按上下文匹配。"
            .to_string()
    })?;
    if content[first + old.len()..].contains(old) {
        let mut positions = Vec::new();
        let mut from = 0;
        while let Some(p) = content[from..].find(old) {
            positions.push(from + p);
            if positions.len() >= 5 {
                break;
            }
            from = from + p + old.len();
        }
        let pos_str = positions
            .iter()
            .map(|&p| format!("第 {} 行", byte_to_line(p)))
            .collect::<Vec<_>>()
            .join("、");
        return Err(format!(
            "old_string 出现 {} 处（{}），存在歧义。请改用 file_patch 明确指定位置，或提供更长、唯一的 old_string。",
            positions.len(),
            pos_str
        ));
    }
    let mut out =
        String::with_capacity(content.len() + new.len().saturating_sub(old.len()));
    out.push_str(&content[..first]);
    out.push_str(new);
    out.push_str(&content[first + old.len()..]);
    Ok(out)
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
        TOOL_FILE_PATCH => {
            let (path, patch) = match (need("path"), need("patch")) {
                (Ok(p), Ok(t)) => (p, t),
                (Err(e), _) => return Some((false, e)),
                (_, Err(e)) => return Some((false, e)),
            };
            let content = match file.read(&path) {
                Ok(c) => c,
                Err(e) => return Some((false, e)),
            };
            match apply_patch_to_content(&content, &patch) {
                Ok((next, stats)) => match file.write(&path, &next, true) {
                    Ok(_) => Some((
                        true,
                        format!(
                            "已应用补丁 {path}：{} 个 hunk（+{} -{}）。若与预期不符可回滚备份。",
                            stats.hunks, stats.added, stats.removed
                        ),
                    )),
                    Err(e) => Some((false, e)),
                },
                Err(e) => Some((false, e)),
            }
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
            let exists = match file.exists(&path) {
                Ok(b) => b,
                Err(e) => return Some((false, e)),
            };
            if !exists {
                return Some(match file.write(&path, &content, overwrite) {
                    Ok(_) => (
                        true,
                        format!("已创建文件 {path}（{} 行）", content.lines().count()),
                    ),
                    Err(e) => (false, e),
                });
            }
            if !overwrite {
                return Some((false, format!("文件已存在: {path}（overwrite=false 拒绝覆盖）")));
            }
            // 覆盖路径：先预演（diff + 改动比例），超阈值拒绝
            let old = match file.read(&path) {
                Ok(c) => c,
                Err(e) => return Some((false, format!("整写预演需读取原文件失败：{e}"))),
            };
            let ops = diff_lines(&old, &content);
            let (added, removed) = stats_from_ops(&ops);
            let total = old.lines().count();
            let ratio = if total == 0 {
                0.0
            } else {
                (added + removed) as f64 / total as f64
            };
            let diff = render_unified_diff(&path, &old, &content);
            if ratio > MAX_WRITE_RATIO {
                return Some((false, format!(
                    "整写被拒：改动比例 {:.0}%（+{added} -{removed}，共 {total} 行），超过阈值 {:.0}%。\
                     \n大范围改动请改用 file_patch 分 hunk 表达，或先 file_read 确认原文再决定。\
                     \n预演 diff：\n{diff}",
                    ratio * 100.0,
                    MAX_WRITE_RATIO * 100.0
                )));
            }
            Some(match file.write(&path, &content, true) {
                Ok(_) => (
                    true,
                    format!(
                        "已覆盖文件 {path}（+{added} -{removed}，改动 {:.0}%，共 {total} 行）。\nDiff：\n{diff}",
                        ratio * 100.0
                    ),
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
            let content = match file.read(&path) {
                Ok(c) => c,
                Err(e) => return Some((false, e)),
            };
            match edit_single(&content, &old, &new) {
                Ok(next) => match file.write(&path, &next, true) {
                    Ok(_) => Some((true, format!("已更新文件 {path}"))),
                    Err(e) => Some((false, e)),
                },
                Err(e) => Some((false, e)),
            }
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
        fn exists(&mut self, path: &str) -> Result<bool, String> {
            Ok(self.files.contains_key(path))
        }
        fn write(&mut self, path: &str, content: &str, _overwrite: bool) -> Result<bool, String> {
            let created = !self.files.contains_key(path);
            self.files.insert(path.to_string(), content.to_string());
            Ok(created)
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

    #[test]
    fn patch_applies_single_hunk_and_reports_stats() {
        let mut fs = FakeFs::with(&[("a.txt", "line1\nline2\nline3")]);
        let patch = "@@ -1,3 +1,3 @@\n line1\n-line2\n+line2b\n line3";
        let (ok, msg) = dispatch(
            TOOL_FILE_PATCH,
            &json!({"path":"a.txt","patch":patch}),
            &mut fs,
        )
        .unwrap();
        assert!(ok, "{msg}");
        assert!(msg.contains("1 个 hunk"), "{msg}");
        let (_, content) = dispatch(TOOL_FILE_READ, &json!({"path":"a.txt"}), &mut fs).unwrap();
        assert!(content.contains("line2b"));
    }

    #[test]
    fn patch_multi_hunk_atomic_rollback_on_failure() {
        let mut fs = FakeFs::with(&[("a.txt", "a\nb\nc\nd\ne")]);
        // 第一个 hunk 匹配，第二个 hunk（锚点 9）不匹配；应整批拒绝、文件不变
        let patch = "@@ -1,1 +1,1 @@\n-a\n+A\n@@ -9,1 +9,1 @@\n-zzz\n+Z\n";
        let (ok, msg) = dispatch(
            TOOL_FILE_PATCH,
            &json!({"path":"a.txt","patch":patch}),
            &mut fs,
        )
        .unwrap();
        assert!(!ok, "第二个 hunk 不匹配应整批拒绝");
        assert!(msg.contains("hunk 2 匹配失败"), "{msg}");
        let (_, content) = dispatch(TOOL_FILE_READ, &json!({"path":"a.txt"}), &mut fs).unwrap();
        assert_eq!(content, "a\nb\nc\nd\ne", "原子性：失败时文件不得变化");
    }

    #[test]
    fn patch_ambiguous_context_rejected() {
        let mut fs = FakeFs::with(&[("a.txt", "x\nfoo\nx\nfoo\nx")]);
        // 无锚点行号，上下文仅 foo → 匹配到 2 处，报多义
        let (ok, msg) = dispatch(
            TOOL_FILE_PATCH,
            &json!({"path":"a.txt","patch":"@@\n-foo\n+bar\n"}),
            &mut fs,
        )
        .unwrap();
        assert!(!ok);
        assert!(msg.contains("匹配到 2 处"), "{msg}");
    }

    #[test]
    fn patch_anchor_tolerance_allows_line_drift() {
        let mut fs = FakeFs::with(&[
            ("a.txt", "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10"),
        ]);
        // 真实位置第 7 行；锚点给 5（漂移 2 行，容差内应仍定位成功）
        let patch = "@@ -5,3 +5,3 @@\n l6\n-l7\n+l7b\n l8\n";
        let (ok, msg) = dispatch(
            TOOL_FILE_PATCH,
            &json!({"path":"a.txt","patch":patch}),
            &mut fs,
        )
        .unwrap();
        assert!(ok, "{msg}");
        let (_, content) = dispatch(TOOL_FILE_READ, &json!({"path":"a.txt"}), &mut fs).unwrap();
        assert!(content.contains("l7b"));
    }

    #[test]
    fn patch_malformed_rejected_with_position() {
        let mut fs = FakeFs::with(&[("a.txt", "x")]);
        let (ok, msg) = dispatch(
            TOOL_FILE_PATCH,
            &json!({"path":"a.txt","patch":"@@\nbad line without prefix\n"}),
            &mut fs,
        )
        .unwrap();
        assert!(!ok);
        assert!(msg.contains("无法解析"), "{msg}");
    }

    #[test]
    fn render_diff_has_unified_format() {
        let diff = render_unified_diff("a.txt", "1\n2\n3", "1\n2b\n3");
        assert!(diff.starts_with("--- a/a.txt\n+++ b/a.txt\n"), "{diff}");
        assert!(diff.contains("@@ -1,2 +1,2 @@"), "{diff}");
        assert!(diff.contains("-2\n+2b"), "{diff}");
    }

    #[test]
    fn write_overwrite_rejected_when_ratio_too_high() {
        let mut fs = FakeFs::with(&[("a.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10")]);
        let new_content = "x1\nx2\nx3\nx4\nx5\n6\nx7\nx8\nx9\nx10";
        let (ok, msg) = dispatch(
            TOOL_FILE_WRITE,
            &json!({"path":"a.txt","content":new_content}),
            &mut fs,
        )
        .unwrap();
        assert!(!ok, "改动比例高应拒绝");
        assert!(msg.contains("整写被拒"), "{msg}");
        let (_, content) = dispatch(TOOL_FILE_READ, &json!({"path":"a.txt"}), &mut fs).unwrap();
        assert!(content.starts_with("1\n2\n3"), "拒绝时文件不得变化");
    }

    #[test]
    fn write_overwrite_small_change_allowed_with_diff() {
        let mut fs = FakeFs::with(&[("a.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10")]);
        let new_content = "1\n2\n3\n4\n5\n6b\n7\n8\n9\n10";
        let (ok, msg) = dispatch(
            TOOL_FILE_WRITE,
            &json!({"path":"a.txt","content":new_content}),
            &mut fs,
        )
        .unwrap();
        assert!(ok, "{msg}");
        assert!(msg.contains("+1 -1"), "{msg}");
        assert!(msg.contains("Diff"), "{msg}");
    }

    #[test]
    fn edit_single_occurrence_works() {
        let mut fs = FakeFs::with(&[("a.txt", "foo\nbar\nbaz")]);
        let (ok, _) = dispatch(
            TOOL_FILE_EDIT,
            &json!({"path":"a.txt","old_string":"foo","new_string":"FOO"}),
            &mut fs,
        )
        .unwrap();
        assert!(ok);
        let (_, content) = dispatch(TOOL_FILE_READ, &json!({"path":"a.txt"}), &mut fs).unwrap();
        assert_eq!(content, "FOO\nbar\nbaz");
    }

    #[test]
    fn edit_multi_occurrence_rejected() {
        let mut fs = FakeFs::with(&[("a.txt", "foo\nfoo")]);
        let (ok, msg) = dispatch(
            TOOL_FILE_EDIT,
            &json!({"path":"a.txt","old_string":"foo","new_string":"FOO"}),
            &mut fs,
        )
        .unwrap();
        assert!(!ok);
        assert!(msg.contains("歧义"), "{msg}");
    }

    #[test]
    fn edit_missing_rejected_with_guidance() {
        let mut fs = FakeFs::with(&[("a.txt", "bar")]);
        let (ok, msg) = dispatch(
            TOOL_FILE_EDIT,
            &json!({"path":"a.txt","old_string":"foo","new_string":"FOO"}),
            &mut fs,
        )
        .unwrap();
        assert!(!ok);
        assert!(msg.contains("file_read"), "{msg}");
        assert!(msg.contains("file_patch"), "{msg}");
    }

    #[test]
    fn edit_single_rejects_empty_old() {
        assert!(edit_single("abc", "", "x").is_err());
    }
}
