use serde_json::json;

/// 采集屏幕节点树
pub fn capture_screen_tree() -> serde_json::Value {
    #[cfg(target_os = "windows")]
    {
        return capture_windows();
    }
    #[cfg(target_os = "macos")]
    {
        return capture_macos();
    }
    #[cfg(target_os = "linux")]
    {
        return capture_linux();
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
    {
        return json!({"error": "unsupported platform"});
    }
}

/// 执行操作
pub fn execute_action(action_type: &str, target: Option<&str>, text: Option<&str>) -> bool {
    #[cfg(target_os = "windows")]
    {
        return execute_windows(action_type, target, text);
    }
    #[cfg(target_os = "macos")]
    {
        return execute_macos(action_type, target, text);
    }
    #[cfg(target_os = "linux")]
    {
        return execute_linux(action_type, target, text);
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
    {
        return false;
    }
}

// ===== Windows: UI Automation + Win32 API =====
#[cfg(target_os = "windows")]
fn capture_windows() -> serde_json::Value {
    // 使用 uiautomation crate 采集 UI 树
    json!({"platform": "windows", "note": "UI Automation not yet implemented"})
}

#[cfg(target_os = "windows")]
fn execute_windows(action_type: &str, target: Option<&str>, text: Option<&str>) -> bool {
    false
}

// ===== macOS: Accessibility API =====
#[cfg(target_os = "macos")]
fn capture_macos() -> serde_json::Value {
    json!({"platform": "macos", "note": "Accessibility API not yet implemented"})
}

#[cfg(target_os = "macos")]
fn execute_macos(action_type: &str, target: Option<&str>, text: Option<&str>) -> bool {
    false
}

// ===== Linux: AT-SPI =====
#[cfg(target_os = "linux")]
fn capture_linux() -> serde_json::Value {
    json!({"platform": "linux", "note": "AT-SPI not yet implemented"})
}

#[cfg(target_os = "linux")]
fn execute_linux(action_type: &str, target: Option<&str>, text: Option<&str>) -> bool {
    false
}
