import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';

import '../platform/agent_channel.dart';
import '../widgets/syntax_highlighter.dart';

/// 代码编辑器（ROADMAP 任务 22）。
///
/// 双模式：编辑模式为等宽 [TextField]，预览模式按语言渲染
/// （Markdown 用 [MarkdownWidget]，代码用语法高亮）。
class EditorScreen extends StatefulWidget {
  const EditorScreen({super.key, required this.path});

  /// 相对工作区根的路径。
  final String path;

  @override
  State<EditorScreen> createState() => _EditorScreenState();
}

enum _Mode { edit, preview }

enum _BackChoice { edit, discard, saveAndExit }

class _Hist {
  const _Hist(this.text, this.selection);
  final String text;
  final int selection;
}

class _EditorScreenState extends State<EditorScreen> {
  late final TextEditingController _ctrl;
  late final FocusNode _focus;
  late final Lang _lang;

  String _lastText = '';
  _Mode _mode = _Mode.edit;
  bool _loading = true;
  bool _saving = false;
  bool _dirty = false;
  bool _applyingHistory = false;
  String? _error;

  final List<_Hist> _undo = [];
  final List<_Hist> _redo = [];

  static const List<String> _symbols = [
    '{', '}', '[', ']', '(', ')', '"', '\u0027', '`', ';', ':', ',', '.',
    '/', '\\', '|', '&', '?', '#', '=', '<', '>', '*', '+', '-', '_',
  ];

  @override
  void initState() {
    super.initState();
    _lang = langFromPath(widget.path);
    _ctrl = TextEditingController();
    _focus = FocusNode();
    _load();
  }

  @override
  void dispose() {
    _ctrl.dispose();
    _focus.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final res = await AgentChannel.workspaceRead(widget.path);
    final m = jsonDecode(res) as Map<String, dynamic>;
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (m['ok'] != true) {
        _error = m['message']?.toString() ?? '无法读取文件';
      } else {
        _ctrl.text = m['content'] as String? ?? '';
        _lastText = _ctrl.text;
        // Markdown 默认进预览，代码默认进编辑。
        _mode = _lang == Lang.markdown ? _Mode.preview : _Mode.edit;
        if (_ctrl.text.length > 300000) {
          _toast('文件较大（>300KB），编辑/预览可能卡顿');
        }
      }
    });
  }

  void _onChanged(String v) {
    if (_applyingHistory || v == _lastText) return;
    if (_undo.isEmpty || _undo.last.text != _lastText) {
      _undo.add(_Hist(_lastText, _ctrl.selection.baseOffset));
      if (_undo.length > 100) _undo.removeAt(0);
    }
    _redo.clear();
    _lastText = v;
    if (!_dirty) {
      _dirty = true;
      setState(() {});
    }
  }

  void _undoAction() {
    if (_undo.isEmpty) return;
    _redo.add(_Hist(_lastText, _ctrl.selection.baseOffset));
    final h = _undo.removeLast();
    _applyHistory(h);
  }

  void _redoAction() {
    if (_redo.isEmpty) return;
    _undo.add(_Hist(_lastText, _ctrl.selection.baseOffset));
    final h = _redo.removeLast();
    _applyHistory(h);
  }

  void _applyHistory(_Hist h) {
    _applyingHistory = true;
    _ctrl.text = h.text;
    _ctrl.selection = TextSelection.collapsed(offset: h.selection.clamp(0, h.text.length) as int);
    _lastText = h.text;
    _applyingHistory = false;
    _dirty = true;
    setState(() {});
  }

  void _insert(String s) {
    final sel = _ctrl.selection;
    if (!_ctrl.selection.isValid) return;
    final old = _ctrl.text;
    if (_undo.isEmpty || _undo.last.text != old) {
      _undo.add(_Hist(old, sel.baseOffset));
      if (_undo.length > 100) _undo.removeAt(0);
    }
    _redo.clear();
    _applyingHistory = true;
    _ctrl.text = old.substring(0, sel.baseOffset) + s + old.substring(sel.extentOffset);
    _ctrl.selection = TextSelection.collapsed(offset: sel.baseOffset + s.length);
    _lastText = _ctrl.text;
    _applyingHistory = false;
    _dirty = true;
    setState(() {});
  }

  Future<void> _save() async {
    if (_saving) return;
    setState(() => _saving = true);
    final res = await AgentChannel.workspaceWrite(widget.path, _ctrl.text);
    final m = jsonDecode(res) as Map<String, dynamic>;
    if (!mounted) return;
    final ok = m['ok'] == true;
    setState(() {
      _saving = false;
      _dirty = !ok;
    });
    _toast(ok ? '已保存' : (m['message']?.toString() ?? '保存失败'));
  }

  Future<void> _confirmBack() async {
    if (!_dirty) {
      Navigator.of(context).pop();
      return;
    }
    final choice = await showDialog<_BackChoice>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('未保存的修改'),
        content: const Text('文件已修改但尚未保存。'),
        actions: [
          TextButton(
            onTap: () => Navigator.pop(ctx, _BackChoice.edit),
            child: const Text('继续编辑'),
          ),
          TextButton(
            onTap: () => Navigator.pop(ctx, _BackChoice.discard),
            child: const Text('直接退出', style: TextStyle(color: Colors.grey)),
          ),
          TextButton(
            onTap: () => Navigator.pop(ctx, _BackChoice.saveAndExit),
            child: const Text('保存并退出'),
          ),
        ],
      ),
    );
    switch (choice) {
      case _BackChoice.discard:
        _dirty = false;
        Navigator.of(context).pop();
        break;
      case _BackChoice.saveAndExit:
        await _save();
        if (_dirty) return;
        _dirty = false;
        Navigator.of(context).pop();
        break;
      case _BackChoice.edit:
        break;
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  TextStyle _monoStyle(ColorScheme cs) => TextStyle(
        fontFamily: 'monospace',
        fontFamilyFallback: const ['RobotoMono', 'Droid Sans Mono', 'sans-serif-mono'],
        fontSize: 13,
        height: 1.5,
        letterSpacing: 0.2,
        color: cs.onSurface,
      );

  Widget _editor() {
    final cs = Theme.of(context).colorScheme;
    return Container(
      color: cs.surface,
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      child: TextField(
        controller: _ctrl,
        focusNode: _focus,
        maxLines: null,
        keyboardType: TextInputType.multiline,
        textInputAction: TextInputAction.newline,
        enableSuggestions: false,
        autocorrect: false,
        onChanged: _onChanged,
        style: _monoStyle(cs),
        decoration: const InputDecoration(
          isCollapsed: true,
          border: InputBorder.none,
          contentPadding: EdgeInsets.zero,
        ),
      ),
    );
  }

  Widget _preview() {
    final cs = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final base = _monoStyle(cs);
    if (_lang == Lang.markdown) {
      return Padding(
        padding: const EdgeInsets.all(12),
        child: MarkdownWidget(data: _ctrl.text, selectable: true),
      );
    }
    final span = SyntaxHighlighter(_lang).render(_ctrl.text, base: base, dark: dark);
    return SingleChildScrollView(
      padding: const EdgeInsets.all(12),
      child: SelectableText.rich(span),
    );
  }

  Widget _symbolBar() {
    return Material(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: SizedBox(
        height: 40,
        child: ListView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
          children: [
            _sym('Tab', () => _insert('\t')),
            _sym('\u2423', () => _insert('  ')),
            for (final s in _symbols) _sym(s, () => _insert(s)),
          ],
        ),
      ),
    );
  }

  Widget _sym(String label, VoidCallback onTap) {
    final cs = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(6),
      child: Container(
        width: 38,
        height: 32,
        alignment: Alignment.center,
        child: Text(
          label,
          style: TextStyle(
            fontFamily: 'monospace',
            fontSize: 13,
            color: cs.onSurfaceVariant,
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final name = widget.path.split('/').last;
    return PopScope(
      canPop: !_dirty,
      onPopInvoked: (didPop) {
        if (didPop) return;
        _confirmBack();
      },
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(Icons.arrow_back),
            tooltip: '返回',
            onPressed: _confirmBack,
          ),
          title: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(name, maxLines: 1, overflow: TextOverflow.ellipsis),
              Text(
                langLabel(_lang) +
                    (_dirty ? '  ·  未保存' : '') +
                    (_mode == _Mode.preview ? '  ·  预览' : ''),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 11,
                  color: Colors.grey,
                  fontWeight: FontWeight.normal,
                ),
              ),
            ],
          ),
          actions: [
            if (_lang == Lang.markdown)
              IconButton(
                icon: Icon(_mode == _Mode.preview ? Icons.code : Icons.visibility),
                tooltip: _mode == _Mode.preview ? '编辑' : '预览',
                onPressed: () => setState(
                  () => _mode = _mode == _Mode.preview ? _Mode.edit : _Mode.preview,
                ),
              ),
            IconButton(
              icon: const Icon(Icons.undo),
              tooltip: '撤销',
              onPressed: _undo.isEmpty ? null : _undoAction,
            ),
            IconButton(
              icon: const Icon(Icons.redo),
              tooltip: '重做',
              onPressed: _redo.isEmpty ? null : _redoAction,
            ),
            IconButton(
              icon: _saving
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.save),
              tooltip: '保存',
              onPressed: _saving ? null : _save,
            ),
          ],
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(
                        _error!,
                        textAlign: TextAlign.center,
                        style: TextStyle(color: cs.error, fontSize: 14),
                      ),
                    ),
                  )
                : Column(
                    children: [
                      Expanded(child: _mode == _Mode.edit ? _editor() : _preview()),
                      if (_mode == _Mode.edit) _symbolBar(),
                    ],
                  ),
      ),
    );
  }
}
