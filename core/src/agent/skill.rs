//! 技能系统（ROADMAP 任务 9）：扫描 `<dir>/<name>/SKILL.md`，解析 frontmatter，注入系统提示词。
//!
//! 技能目录由平台提供（Android：App 私有目录 `filesDir/skills/`），core 只做读取与解析，
//! 不感知文件来源。注入策略：技能正文追加到系统提示词（数量少时直接生效）。

use std::fs;

/// 一个已加载的技能。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Skill {
    pub name: String,
    pub description: String,
    pub content: String,
}

/// 解析 `SKILL.md`：以 `---` 开头的前置块（`name:` / `description:`）+ 正文。
/// 解析失败（缺 name、无合法 frontmatter）返回 `None`。
pub fn parse_skill_md(raw: &str) -> Option<Skill> {
    let raw = raw.trim_start_matches('\u{feff}'); // 容忍 BOM
    let lines: Vec<&str> = raw.lines().collect();
    if lines.first()?.trim() != "---" {
        return None;
    }
    let mut name = None;
    let mut description = None;
    let mut idx = 1;
    while idx < lines.len() {
        let line = lines[idx].trim();
        if line == "---" {
            idx += 1;
            break;
        }
        if let Some(v) = line.strip_prefix("name:") {
            name = Some(v.trim().to_string());
        } else if let Some(v) = line.strip_prefix("description:") {
            description = Some(v.trim().to_string());
        }
        idx += 1;
    }
    let name = name?;
    if name.is_empty() {
        return None;
    }
    let content = lines[idx..].join("\n").trim().to_string();
    Some(Skill {
        name,
        description: description.unwrap_or_default(),
        content,
    })
}

/// 扫描技能根目录：`<dir>/<name>/SKILL.md`。目录不存在或为空返回空列表；
/// 无法解析或缺少 `SKILL.md` 的子目录被跳过（不视为错误）。
pub fn load_skills(dir: &str) -> Vec<Skill> {
    let Ok(entries) = fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let md = path.join("SKILL.md");
        let Ok(raw) = fs::read_to_string(md) else {
            continue;
        };
        if let Some(s) = parse_skill_md(&raw) {
            out.push(s);
        }
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_frontmatter_and_body() {
        let raw = "---\nname: code-review\ndescription: 审查代码改动\n---\n步骤：\n1. 读 diff\n2. 给结论\n";
        let s = parse_skill_md(raw).expect("应解析成功");
        assert_eq!(s.name, "code-review");
        assert_eq!(s.description, "审查代码改动");
        assert!(s.content.contains("读 diff"));
    }

    #[test]
    fn rejects_missing_frontmatter_or_name() {
        assert!(parse_skill_md("no frontmatter").is_none());
        assert!(parse_skill_md("---\ndescription: 无名\n---\n正文").is_none());
    }

    #[test]
    fn tolerates_bom_and_extra_frontmatter_keys() {
        let raw = "\u{feff}---\nname: x\nauthor: me\n---\nbody";
        let s = parse_skill_md(raw).expect("应容忍 BOM 与未知 key");
        assert_eq!(s.name, "x");
        assert_eq!(s.content, "body");
    }

    #[test]
    fn loads_only_valid_skill_dirs() {
        let root = std::env::temp_dir().join(format!("yaya_skill_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("ok")).unwrap();
        fs::create_dir_all(root.join("bad")).unwrap();
        fs::create_dir_all(root.join("no_md")).unwrap();
        fs::write(
            root.join("ok").join("SKILL.md"),
            "---\nname: ok\ndescription: d\n---\nbody",
        )
        .unwrap();
        fs::write(root.join("bad").join("SKILL.md"), "not frontmatter").unwrap();
        // no_md 目录没有 SKILL.md，应被跳过

        let skills = load_skills(root.to_str().unwrap());
        assert_eq!(skills.len(), 1);
        assert_eq!(skills[0].name, "ok");
        let _ = fs::remove_dir_all(&root);
    }
}
