import 'dart:convert';

import 'package:flutter/material.dart';

import '../platform/agent_channel.dart';
import '../widgets/syntax_highlighter.dart';
import 'editor_screen.dart';
import 'git_screen.dart';

/// 工作区文件浏览（ROADMAP 任务 22）：缩进树形目录，按需展开加载。
class FilesScreen extends StatefulWidget {
  const FilesScreen({super.key});

  @override
  State<FilesScreen> createState() => _FilesScreenState();
}

/// 目录树节点。[expanded]/[loading] 为运行时状态，跨重建保留。
class _Node {
  _Node(this.path, this.isDir);

  final String path;
  final bool isDir;
  bool expanded = false;
  bool loading = false;
  List<_Node> children = const [];
}

enum _Action { newFile, rename, delete }

class _FilesScreenState extends State<FilesScreen> {
  _Node _root = _Node('', true);
  String _rootAbs = '';
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    // 记录当前展开的目录路径，重建后恢复（避免操作后整树缩回根）。
    final expanded = <String>{};
    void collect(_Node n) {
      if (n.expanded) expanded.add(n.path);
      for (final c in n.children) {
        collect(c);
      }
    }

    collect(_root);
    setState(() {
      _loading = true;
    });
    _rootAbs = await AgentChannel.workspaceRoot();
    final root = _Node('', true)..expanded = true;
    root.children = await _list('');
    if (expanded.isNotEmpty) {
      await _restoreExpanded(root, expanded);
    }
    if (!mounted) return;
    setState(() {
      _root = root;
      _loading = false;
    });
  }

  /// 重建后恢复展开状态：递归加载原本展开的目录。
  Future<void> _restoreExpanded(_Node parent, Set<String> paths) async {
    for (final child in parent.children) {
      if (child.isDir && paths.contains(child.path)) {
        child.expanded = true;
        child.children = await _list(child.path);
        await _restoreExpanded(child, paths);
      }
    }
  }

  Future<List<_Node>> _list(String path) async {
    final raw = await AgentChannel.workspaceList(path);
    final m = jsonDecode(raw) as Map<String, dynamic>;
    if (m['ok'] != true) return const [];
    final content = m['content'] as String? ?? '';
    final nodes = <_Node>[];
    for (final name in content.split('\n')) {
      if (name.isEmpty) continue;
      final isDir = name.endsWith('/');
      final n = name.substring(0, name.length - (isDir ? 1 : 0));
      nodes.add(_Node(n, isDir));
    }
    // 目录在前、文件在后，同类按名称（WorkspaceFileAccess 已按名排序，这里补类型分组）。
    nodes.sort((a, b) {
      if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
      return a.path.compareTo(b.path);
    });
    return nodes;
  }

  Future<void> _expand(_Node node) async {
    if (!node.isDir) return;
    if (node.children.isNotEmpty) {
      setState(() => node.expanded = !node.expanded);
      return;
    }
    setState(() {
      node.loading = true;
      node.expanded = true;
    });
    node.children = await _list(node.path);
    node.loading = false;
    if (!mounted) return;
    setState(() {});
  }

  void _openFile(String path) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => EditorScreen(path: path),
        fullscreenDialog: true,
      ),
    );
  }

  void _onTap(_Node node) {
    if (node.isDir) {
      _expand(node);
    } else {
      _openFile(node.path);
    }
  }

  Future<void> _onLongPress(_Node node) async {
    final action = await showModalBottomSheet<_Action>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => _actionSheet(node),
    );
    if (action == null) return;
    await _perform(node, action);
  }

  Widget _actionSheet(_Node node) {
    return SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const SizedBox(height: 4),
          Center(
            child: Text(
              node.path,
              textAlign: TextAlign.center,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 13, color: Colors.grey),
            ),
          ),
          const Divider(),
          if (node.isDir) _actionItem(Icons.note_add_outlined, '新建文件', _Action.newFile),
          if (!node.isDir) _actionItem(Icons.edit, '重命名', _Action.rename),
          _actionItem(Icons.delete_outline, '删除', _Action.delete),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  Widget _actionItem(IconData icon, String label, _Action action) => ListTile(
        leading: Icon(icon),
        title: Text(label),
        onTap: () => Navigator.of(context).pop(action),
      );

  Future<void> _perform(_Node node, _Action action) async {
    switch (action) {
      case _Action.newFile:
        final name = await _prompt('新建文件', '文件名（可含路径，如 src/main.dart）', '');
        if (name == null || name.isEmpty) return;
        final rel = node.path.isEmpty ? name : '${node.path}/$name';
        if (rel.contains('..') || rel.startsWith('/')) {
          _toast('路径非法：$name');
          return;
        }
        await _afterWrite(await AgentChannel.workspaceWrite(rel, ''), '已创建 $name');
        break;

      case _Action.rename:
        final old = node.path;
        final name = await _prompt('重命名', '新文件名（同目录内）', node.path.split('/').last);
        if (name == null || name.isEmpty || name == node.path.split('/').last) return;
        if (name.contains('/') || name.contains('\\') || name.contains('..')) {
          _toast('文件名非法：$name');
          return;
        }
        final dir = old.contains('/') ? old.substring(0, old.lastIndexOf('/')) : '';
        final newPath = dir.isEmpty ? name : '$dir/$name';
        await _afterWrite(await _rename(old, newPath), '已重命名');
        break;

      case _Action.delete:
        final ok = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('删除'),
            content: Text('确定删除 ${node.path} ？此操作不可恢复。'),
            actions: [
              TextButton(onTap: () => Navigator.pop(ctx, false), child: const Text('取消')),
              TextButton(
                onTap: () => Navigator.pop(ctx, true),
                child: const Text('删除', style: TextStyle(color: Colors.red)),
              ),
            ],
          ),
        );
        if (ok != true) return;
        final res = await AgentChannel.workspaceDelete(node.path);
        final m = jsonDecode(res) as Map<String, dynamic>;
        if (m['ok'] != true) {
          _toast(m['message']?.toString() ?? '删除失败');
          return;
        }
        _toast('已删除 ${node.path}');
        _reload();
        break;
    }
  }

  /// WorkspaceFileAccess 无 rename 能力：读旧→写新→删旧。
  Future<String> _rename(String old, String newPath) async {
    final r = await AgentChannel.workspaceRead(old);
    final m = jsonDecode(r) as Map<String, dynamic>;
    if (m['ok'] != true) return r;
    final content = m['content'] as String? ?? '';
    final w = await AgentChannel.workspaceWrite(newPath, content);
    if ((jsonDecode(w) as Map<String, dynamic>)['ok'] != true) return w;
    return AgentChannel.workspaceDelete(old);
  }

  Future<void> _afterWrite(String res, String okMsg) async {
    final m = jsonDecode(res) as Map<String, dynamic>;
    if (m['ok'] != true) {
      _toast(m['message']?.toString() ?? '操作失败');
      return;
    }
    _toast(okMsg);
    _reload();
  }

  Future<String?> _prompt(String title, String label, String initial) async {
    final c = TextEditingController(text: initial);
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: c,
          autofocus: true,
          decoration: InputDecoration(labelText: label),
          onSubmitted: (v) => Navigator.pop(ctx, v.trim()),
        ),
        actions: [
          TextButton(onTap: () => Navigator.pop(ctx), child: const Text('取消')),
          TextButton(onTap: () => Navigator.pop(ctx, c.text.trim()), child: const Text('确定')),
        ],
      ),
    );
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Widget _buildNode(_Node node, int depth) {
    final colors = Theme.of(context).colorScheme;
    final indent = 8.0 + depth * 16.0;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        InkWell(
          onTap: () => _onTap(node),
          onLongPress: () => _onLongPress(node),
          child: Padding(
            padding: EdgeInsets.only(left: indent, top: 7, bottom: 7),
            child: Row(
              children: [
                SizedBox(
                  width: 20,
                  child: node.isDir
                      ? Icon(
                          node.expanded ? Icons.expand_more : Icons.chevron_right,
                          size: 18,
                          color: colors.onSurfaceVariant,
                        )
                      : null,
                ),
                const SizedBox(width: 4),
                _iconFor(node),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    node.path,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 14,
                      color: node.isDir ? colors.onSurface : colors.onSurfaceVariant,
                      fontWeight: node.isDir ? FontWeight.w500 : FontWeight.normal,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
        if (node.expanded && node.isDir) ...[
          if (node.loading)
            Padding(
              padding: EdgeInsets.only(left: indent + 28),
              child: SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            )
          else if (node.children.isEmpty)
            Padding(
              padding: EdgeInsets.only(left: indent + 28, bottom: 8),
              child: Text('（空目录）', style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant)),
            )
          else
            for (final c in node.children) _buildNode(c, depth + 1),
        ],
      ],
    );
  }

  Widget _iconFor(_Node node) {
    if (node.isDir) {
      return Icon(
        node.expanded ? Icons.folder_open : Icons.folder,
        size: 18,
        color: const Color(0xFF42A5F5),
      );
    }
    return switch (langFromPath(node.path)) {
      Lang.markdown => const Icon(Icons.article, size: 18, color: Color(0xFF42A5F5)),
      Lang.json => const Icon(Icons.data_object, size: 18, color: Color(0xFFFFCA28)),
      Lang.xml => const Icon(Icons.code, size: 18, color: Color(0xFFFF7043)),
      Lang.shell => const Icon(Icons.terminal, size: 18, color: Color(0xFF66BB6A)),
      Lang.dart => const Icon(Icons.code, size: 18, color: Color(0xFF42A5F5)),
      Lang.python => const Icon(Icons.code, size: 18, color: Color(0xFF9575CD)),
      Lang.kotlin => const Icon(Icons.code, size: 18, color: Color(0xFF26A69A)),
      Lang.java => const Icon(Icons.code, size: 18, color: Color(0xFFFF7043)),
      Lang.js => const Icon(Icons.code, size: 18, color: Color(0xFFFFCA28)),
      Lang.rust => const Icon(Icons.code, size: 18, color: Color(0xFFEF5350)),
      Lang.c => const Icon(Icons.code, size: 18, color: Color(0xFF78909C)),
      Lang.yaml => const Icon(Icons.code, size: 18, color: Color(0xFFEC407A)),
      Lang.text => const Icon(Icons.description, size: 18, color: Color(0xFF90A4AE)),
    };
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('文件'),
            if (_rootAbs.isNotEmpty)
              Text(
                _rootAbs,
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
          IconButton(
            icon: const Icon(Icons.account_tree_outlined),
            tooltip: 'Git 版本管理',
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => const GitScreen()),
            ),
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: '刷新',
            onPressed: _loading ? null : _reload,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _root.children.isEmpty
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.folder_open, size: 48, color: Colors.grey),
                      const SizedBox(height: 12),
                      Text(
                        '工作区为空',
                        style: TextStyle(color: colors.onSurfaceVariant, fontSize: 14),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '长按任意处，或让 AI 在此创建文件',
                        style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
                      ),
                    ],
                  ),
                )
              : ListView(
                  padding: const EdgeInsets.only(top: 8, bottom: 32),
                  children: [_buildTree()],
                ),
    );
  }

  Widget _buildTree() => Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final n in _root.children) _buildNode(n, 0),
        ],
      );
}
