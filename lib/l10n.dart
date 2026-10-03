/// 轻量国际化（ROADMAP 任务 11）：key → 语言文本。
///
/// 不引入 flutter_localizations / gen-l10n（避免不可验证的构建依赖）；
/// Material 自带组件文案保持英文。用户可见文案经 [L10n.t] 按语言取用。
class L10n {
  static const zh = 'zh';
  static const en = 'en';

  static const _strings = <String, Map<String, String>>{
    // 运行模式
    'mode_build': {zh: 'BUILD · 正常开发', en: 'BUILD · Normal'},
    'mode_plan': {zh: 'PLAN · 只读规划', en: 'PLAN · Read-only'},
    'mode_auto': {zh: 'AUTO · 免授权', en: 'AUTO · No approval'},
    // 授权弹窗
    'approval_title': {zh: '需要授权', en: 'Approval required'},
    'approval_body': {
      zh: 'Agent 请求执行「\$tool」\n\n撤销成本：\$reversibility\n\n参数：\$args',
      en: 'Agent requests "\$tool"\n\nReversibility: \$reversibility\n\nArgs: \$args',
    },
    'approval_allow': {zh: '允许', en: 'Allow'},
    'approval_deny': {zh: '拒绝', en: 'Deny'},
    // 检查点
    'no_checkpoints': {zh: '暂无检查点', en: 'No checkpoints'},
    'checkpoint_option': {
      zh: '检查点 \$n（第 \$n 步前）',
      en: 'Checkpoint \$n (\$n steps ago)',
    },
    'rolled_back': {
      zh: '已回滚到检查点 \$n，后续消息将作为新任务继续',
      en: 'Rolled back to checkpoint \$n; new messages start a new task',
    },
    // 会话提示
    'hint': {
      zh: '输入指令，如「运行测试」「查看项目结构」「写一段代码」',
      en: 'Enter a command, e.g. "run tests", "view project structure"',
    },
    'event_stream_error': {zh: '事件流错误: \$e', en: 'Event stream error: \$e'},
    'start_failed': {zh: '启动失败：Rust Core 未就绪', en: 'Start failed: Rust Core not ready'},
    'start_exception': {zh: '启动异常: \$e', en: 'Start exception: \$e'},
    'error_prefix': {zh: '错误: \$e', en: 'Error: \$e'},
    'stats_title': {zh: '统计', en: 'Stats'},
  };

  /// 取文案；未知 key 返回 key 本身。`\$k` 占位符经 [args] 替换。
  static String t(String lang, String key, [Map<String, String>? args]) {
    var s = _strings[key]?[lang] ?? _strings[key]?[zh] ?? key;
    args?.forEach((k, v) {
      s = s.replaceAll('\$$k', v);
    });
    return s;
  }
}
