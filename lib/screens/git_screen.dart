import 'dart:convert';

import 'package:flutter/material.dart';

import '../platform/agent_channel.dart';

/// Git 版本管理（ROADMAP 任务 23）：状态 / 分支 / 提交三标签页。
/// 所有命令经 proot 容器对工作区 `/workspace` 执行（见 GitHost.kt）。
class GitScreen extends StatefulWidget {
  const GitScreen({super.key});

  @override
  State<GitScreen> createState() => _GitScreenState();
}

enum _Env { loading, noGit, noRepo, ready }

class _StatusEntry {
  _StatusEntry(this.path, this.staged, this.unstaged);
  final String path;
  final String staged; // porcelain X 列
  final String unstaged; // porcelain Y 列
  bool get isUntracked => staged == '?' && unstaged == '?';
  bool get isStaged => staged != ' ' && staged != '?';
  bool get isModified => unstaged != ' ' && unstaged != '?' && !isUntracked;
}

class _Branch {
  _Branch(this.name, this.isCurrent);
  final String name;
  final bool isCurrent;
}

class _Commit {
  _Commit(this.hash, this.subject);
  final String hash;
  final String subject;
}

class _GitScreenState extends State<GitScreen> {
  _Env _env = _Env.loading;
  String _envMessage = '';
  bool _busy = false;

  List<_StatusEntry> _staged = [];
  List<_StatusEntry> _modified = [];
  List<_StatusEntry> _untracked = [];
  List<_Branch> _branches = [];
  String _currentBranch = '';
  List<_Commit> _commits = [];

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    setState(() => _env = _Env.loading);
    final res = await AgentChannel.gitDetect();
    final m = jsonDecode(res) as Map<String, dynamic>;
    final gitOk = m['gitOk'] == true;
    final isRepo = m['isRepo'] == true;
    if (!gitOk) {
      setState(() {
        _env = _Env.noGit;
        _envMessage = '终端容器未安装 git，请先在「终端」页执行：\napk add git';
      });
      return;
    }
    if (!isRepo) {
      setState(() => _env = _Env.noRepo);
      return;
    }
    setState(() => _env = _Env.ready);
    await _loadAll();
  }

  Future<void> _loadAll() async {
    setState(() => _busy = true);
    await Future.wait([_loadStatus(), _loadBranches(), _loadLog()]);
    if (mounted) setState(() => _busy = false);
  }

  Future<Map<String, dynamic>> _run(List<String> args) async {
    final res = await AgentChannel.gitRun(args);
    return jsonDecode(res) as Map<String, dynamic>;
  }

  Future<void> _loadStatus() async {
    final m = await _run(['status', '--porcelain=v1', '-z']);
    if (m['ok'] != true) return;
    final staged = <_StatusEntry>[];
    final modified = <_StatusEntry>[];
    final untracked = <_StatusEntry>[];
    for (final rec in (m['output'] as String).split('\u0000')) {
      if (rec.length < 3) continue;
      final x = rec[0];
      final y = rec[1];
      final path = rec.substring(3);
      if (path.isEmpty) continue; // 重命名记录的第二段（仅新路径），忽略
      final e = _StatusEntry(path, x, y);
      if (e.isUntracked) {
        untracked.add(e);
      } else if (e.isStaged) {
        staged.add(e);
      } else {
        modified.add(e);
      }
    }
    if (!mounted) return;
    setState(() {
      _staged = staged;
      _modified = modified;
      _untracked = untracked;
    });
  }

  Future<void> _loadBranches() async {
    final m = await _run(['branch']);
    if (m['ok'] != true) return;
    final branches = <_Branch>[];
    var current = '';
    for (final l in (m['output'] as String).split('\n')) {
      if (l.trim().isEmpty) continue;
      final isCur = l.startsWith('*');
      final name = l.substring(2).trim();
      if (isCur) current = name;
      branches.add(_Branch(name, isCur));
    }
    if (!mounted) return;
    setState(() {
      _branches = branches;
      _currentBranch = current;
    });
  }

  Future<void> _loadLog() async {
    final m = await _run(['log', '--oneline', '--decorate', '-50']);
    if (m['ok'] != true) {
      if (mounted) setState(() => _commits = const []);
      return;
    }
    final commits = <_Commit>[];
    for (final l in (m['output'] as String).split('\n')) {
      final t = l.trim();
      if (t.isEmpty) continue;
      final sp = t.indexOf(' ');
      if (sp <= 0) continue;
      commits.add(_Commit(t.substring(0, sp), t.substring(sp + 1)));
    }
    if (!mounted) return;
    setState(() => _commits = commits);
  }

  // ── 状态页操作 ──────────────────────────────

  Future<void> _stage(String path) async {
    await _run(['add', '--', path]);
    await _loadAll();
  }

  Future<void> _unstage(String path) async {
    await _run(['restore', '--staged', '--', path]);
    await _loadAll();
  }

  Future<void> _unstageAll() async {
    await _run(['restore', '--staged', '.']);
    await _loadAll();
  }

  Future<void> _stageAll() async {
    await _run(['add', '-A']);
    await _loadAll();
  }

  Future<void> _restore(String path) async {
    final ok = await _confirm('回退', '丢弃 $path 的工作区改动（不可恢复）？');
    if (!ok) return;
    await _run(['restore', '--', path]);
    await _loadAll();
  }

  Future<void> _restoreAll() async {
    final ok = await _confirm('全部回退', '丢弃所有未暂存改动（不可恢复）？已暂存内容不受影响。');
    if (!ok) return;
    await _run(['restore', '.']);
    await _loadAll();
  }

  Future<void> _commit() async {
    final msg = await _prompt('提交', '提交信息', '');
    if (msg == null || msg.trim().isEmpty) return;
    await _run(['add', '-A']);
    await _run(['commit', '-m', msg.trim()]);
    await _loadAll();
  }

  // ── 分支页操作 ──────────────────────────────

  Future<void> _checkout(String name) async {
    final ok = await _confirm('切换分支', '切换到 $name？（有未提交改动可能被阻止）');
    if (!ok) return;
    await _run(['checkout', name]);
    await _loadAll();
  }

  Future<void> _newBranch() async {
    final name = await _prompt('新建分支', '分支名', '');
    if (name == null || name.trim().isEmpty) return;
    await _run(['checkout', '-b', name.trim()]);
    await _loadAll();
  }

  Future<void> _deleteBranch(String name) async {
    if (name == _currentBranch) {
      _toast('不能删除当前分支');
      return;
    }
    final ok = await _confirm('删除分支', '删除分支 $name？（仅能删已合并分支）');
    if (!ok) return;
    await _run(['branch', '-d', name]);
    await _loadAll();
  }

  Future<void> _showCommit(_Commit c) async {
    final m = await _run(['show', '--stat', '--oneline', c.hash]);
    if (m['ok'] != true) {
      _toast(m['message']?.toString() ?? '无法读取提交');
      return;
    }
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (ctx) => SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.of(ctx).size.height * 0.6,
          ),
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: SelectableText(
              m['output'] as String? ?? '',
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12, height: 1.4),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _showDiff(String path, {bool cached = false}) async {
    final m = await _run(cached ? ['diff', '--cached', '--', path] : ['diff', '--', path]);
    if (m['ok'] != true) {
      _toast(m['message']?.toString() ?? '无法读取差异');
      return;
    }
    final text = (m['output'] as String? ?? '').trim();
    if (!mounted) return;
    if (text.isEmpty) {
      _toast('无差异');
      return;
    }
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (ctx) => SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: MediaQuery.of(ctx).size.height * 0.7),
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: SelectableText(
              text,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12, height: 1.4),
            ),
          ),
        ),
      ),
    );
  }

  // ── 通用 ───────────────────────────────────

  Future<bool> _confirm(String title, String body) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('确定')),
        ],
      ),
    );
    return ok == true;
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
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          TextButton(onPressed: () => Navigator.pop(ctx, c.text.trim()), child: const Text('确定')),
        ],
      ),
    );
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  String _statusLabel(_StatusEntry e) {
    final b = StringBuffer();
    if (e.staged.isNotEmpty) {
      b.write('${e.staged}  ');
    }
    if (e.unstaged.isNotEmpty && e.unstaged != ' ') {
      b.write(e.unstaged);
    }
    return b.toString().trim();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Git'),
        actions: [
          IconButton(
            icon: _busy
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.refresh),
            tooltip: '刷新',
            onPressed: _busy ? null : _loadAll,
          ),
        ],
      ),
      body: _body(context),
    );
  }

  Widget _body(BuildContext context) {
    switch (_env) {
      case _Env.loading:
        return const Center(child: CircularProgressIndicator());
      case _Env.noGit:
        return Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.terminal, size: 48, color: Colors.grey),
                const SizedBox(height: 16),
                Text(_envMessage, textAlign: TextAlign.center),
                const SizedBox(height: 16),
                FilledButton(onPressed: _init, child: const Text('重新检测')),
              ],
            ),
          ),
        );
      case _Env.noRepo:
        return Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.folder_open, size: 48, color: Colors.grey),
              const SizedBox(height: 16),
              const Text('工作区尚未初始化为 git 仓库'),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: () async {
                  await _run(['init']);
                  await _loadAll();
                  if (mounted) setState(() => _env = _Env.ready);
                },
                icon: const Icon(Icons.play_arrow),
                label: const Text('初始化仓库'),
              ),
            ],
          ),
        );
      case _Env.ready:
        return DefaultTabController(
          length: 3,
          child: Column(
            children: [
              const TabBar(
                tabs: [
                  Tab(icon: Icon(Icons.sync), text: '状态'),
                  Tab(icon: Icon(Icons.account_tree_outlined), text: '分支'),
                  Tab(icon: Icon(Icons.history), text: '提交'),
                ],
              ),
              Expanded(
                child: TabBarView(
                  children: [_statusTab(), _branchTab(), _logTab()],
                ),
              ),
            ],
          ),
        );
    }
  }

  // ── 状态 tab ───────────────────────────────

  Widget _statusTab() {
    return ListView(
      padding: const EdgeInsets.only(bottom: 24),
      children: [
        _sectionHeader(
          '已暂存（${_staged.length}）',
          action: TextButton(
            onPressed: _staged.isEmpty ? null : _unstageAll,
            child: const Text('全部取消暂存'),
          ),
        ),
        if (_staged.isEmpty) _empty('无已暂存更改') else _group(_staged, staged: true),

        _sectionHeader(
          '已修改（${_modified.length}）',
          action: TextButton(
            onPressed: _modified.isEmpty ? null : _restoreAll,
            child: const Text('全部回退'),
          ),
        ),
        if (_modified.isEmpty) _empty('无已修改更改') else _group(_modified, staged: false),

        _sectionHeader('未跟踪（${_untracked.length}）'),
        if (_untracked.isEmpty) _empty('无未跟踪文件') else _group(_untracked, staged: false),

        Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: () async {
                    await _stageAll();
                    _toast('已全部暂存');
                  },
                  icon: const Icon(Icons.add_task),
                  label: const Text('全部暂存'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton.icon(
                  onPressed: _commit,
                  icon: const Icon(Icons.commit),
                  label: const Text('提交'),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _group(List<_StatusEntry> entries, {required bool staged}) {
    return Column(
      children: [
        for (final e in entries)
          ListTile(
            dense: true,
            leading: SizedBox(
              width: 28,
              child: Text(
                _statusLabel(e),
                style: TextStyle(
                  fontSize: 11,
                  fontFamily: 'monospace',
                  color: e.isStaged
                      ? Colors.green
                      : e.isModified
                          ? Colors.orange
                          : Colors.grey,
                ),
              ),
            ),
            title: Text(e.path, maxLines: 1, overflow: TextOverflow.ellipsis),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  icon: Icon(e.isStaged ? Icons.undo : Icons.add_circle_outline, size: 20),
                  tooltip: e.isStaged ? '取消暂存' : '暂存',
                  onPressed: () => e.isStaged ? _unstage(e.path) : _stage(e.path),
                ),
                IconButton(
                  icon: const Icon(Icons.visibility_outlined, size: 20),
                  tooltip: '查看差异',
                  onPressed: () => _showDiff(e.path, cached: e.isStaged),
                ),
                if (e.isModified && !e.isStaged)
                  IconButton(
                    icon: const Icon(Icons.restore, size: 20),
                    tooltip: '回退该文件',
                    onPressed: () => _restore(e.path),
                  ),
              ],
            ),
          ),
      ],
    );
  }

  Widget _sectionHeader(String title, {Widget? action}) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Row(
        children: [
          Expanded(child: Text(title, style: const TextStyle(fontWeight: FontWeight.bold))),
          if (action != null) action,
        ],
      ),
    );
  }

  Widget _empty(String text) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Text(text, style: const TextStyle(fontSize: 12, color: Colors.grey)),
    );
  }

  // ── 分支 tab ───────────────────────────────

  Widget _branchTab() {
    return ListView(
      padding: const EdgeInsets.only(bottom: 24),
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              const Icon(Icons.account_tree_outlined, size: 18),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  '当前分支：$_currentBranch',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              TextButton.icon(
                onPressed: _newBranch,
                icon: const Icon(Icons.add),
                label: const Text('新建'),
              ),
            ],
          ),
        ),
        if (_branches.isEmpty)
          _empty('无分支')
        else
          for (final b in _branches)
            ListTile(
              dense: true,
              leading: Icon(
                b.isCurrent ? Icons.fork_right : Icons.account_tree_outlined,
                color: b.isCurrent ? Colors.green : Colors.grey,
              ),
              title: Text(b.name),
              trailing: b.isCurrent
                  ? const Text('当前', style: TextStyle(fontSize: 12, color: Colors.green))
                  : Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        IconButton(
                          icon: const Icon(Icons.swap_horiz, size: 20),
                          tooltip: '切换',
                          onPressed: () => _checkout(b.name),
                        ),
                        IconButton(
                          icon: const Icon(Icons.delete_outline, size: 20),
                          tooltip: '删除',
                          onPressed: () => _deleteBranch(b.name),
                        ),
                      ],
                    ),
            ),
      ],
    );
  }

  // ── 提交 tab ───────────────────────────────

  Widget _logTab() {
    if (_commits.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.history, size: 40, color: Colors.grey),
            const SizedBox(height: 12),
            const Text('暂无提交'),
            const SizedBox(height: 8),
            TextButton(onPressed: _commit, child: const Text('创建首个提交')),
          ],
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.only(bottom: 24),
      itemCount: _commits.length,
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (ctx, i) {
        final c = _commits[i];
        return ListTile(
          dense: true,
          leading: const Icon(Icons.circle_outlined, size: 16),
          title: Text(c.subject, maxLines: 2, overflow: TextOverflow.ellipsis),
          subtitle: Text(c.hash, style: const TextStyle(fontSize: 11, fontFamily: 'monospace')),
          onTap: () => _showCommit(c),
        );
      },
    );
  }
}