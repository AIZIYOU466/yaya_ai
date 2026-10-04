import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../platform/agent_channel.dart';
import '../providers.dart';
import '../l10n.dart';
import '../widgets/glass_card.dart';

/// 会话中的一条展示项。
class _Item {
  _Item({required this.role, required this.text, this.ok = true});

  final String role; // user | assistant | tool | system
  String text;
  final bool ok;

  bool get isUser => role == 'user';
  bool get isTool => role == 'tool';
  bool get isSystem => role == 'system';
  bool get isPolicy => role == 'policy';
}

/// 会话列表项（多会话管理，任务 24）。
class _Session {
  _Session({
    required this.id,
    required this.title,
    required this.updatedAt,
    required this.messageCount,
  });

  final String id;
  final String title;
  final int updatedAt;
  final int messageCount;
}

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key});

  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final TextEditingController _messageController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final List<_Item> _items = [];
  StreamSubscription<String>? _eventSub;
  bool _running = false;
  String _status = '';
  // 本次会话累计 token（Event::Usage 累加），用于成本透明。
  int _totalTokens = 0;
  // 当前会话 id（复用最近会话以便继续）；空则新会话。
  String _sessionId = '';
  // 恢复历史的条数：新任务的流式 assistant 不与历史气泡合并。
  int _historyCount = 0;

  // 流式 token 节流：合并到 StringBuffer，定时 flush，降低 Markdown 重建频率。
  static const _flushInterval = Duration(milliseconds: 40);
  final StringBuffer _pendingTokens = StringBuffer();
  Timer? _tokenTimer;

  @override
  void initState() {
    super.initState();
    _restoreRecent();
  }

  @override
  void dispose() {
    _tokenTimer?.cancel();
    _pendingTokens.clear();
    _eventSub?.cancel();
    _messageController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _onToken(String text) {
    if (text.isEmpty) return;
    _pendingTokens.write(text);
    _tokenTimer ??= Timer(_flushInterval, _flushTokens);
  }

  void _flushTokens() {
    _tokenTimer?.cancel();
    _tokenTimer = null;
    if (_pendingTokens.isEmpty) return;
    final chunk = _pendingTokens.toString();
    _pendingTokens.clear();
    if (!mounted) return;
    setState(() => _appendAssistant(chunk));
  }

  Future<void> _send() async {
    final message = _messageController.text.trim();
    if (message.isEmpty || _running) return;

    setState(() {
      _running = true;
      _status = 'starting';
      _items.add(_Item(role: 'user', text: message));
      _messageController.clear();
    });
    _scrollToBottom();

    // 先订阅事件流，再启动任务，避免漏掉早期事件。
    await _eventSub?.cancel();
    final lang = ref.read(languageProvider);
    _eventSub = AgentChannel.agentEvents.listen(_onEvent, onError: (Object e) {
      _appendSystem(L10n.t(lang, 'event_stream_error', {'e': '$e'}));
    });

    try {
      final cfg = await ref.read(aiConfigProvider.future);
      final mcp = await ref.read(mcpServersProvider.future);
      final config = <String, dynamic>{
        'baseUrl': cfg.config?.baseUrl ?? '',
        'apiKey': cfg.config?.apiKey ?? '',
        'model': cfg.config?.modelName ?? '',
        'modelPath': cfg.config?.modelPath ?? '',
        'localAvailable': await AgentChannel.localAvailable(),
        'networkOk': await AgentChannel.networkAvailable(),
        'maxSteps': 12,
        'mode': ref.read(agentModeProvider),
        'mcpServers': mcp.servers
            .where((s) => s.enabled)
            .map((s) => s.toJson())
            .toList(),
      };
      if (_sessionId.isEmpty) {
        _sessionId = DateTime.now().millisecondsSinceEpoch.toString();
      }
      final started = await AgentChannel.startAgent(
        taskId: _sessionId,
        prompt: message,
        config: config,
      );
      if (!started) {
        if (!mounted) return;
        _appendSystem(L10n.t(lang, 'start_failed'));
        setState(() => _running = false);
      }
    } catch (e) {
      if (!mounted) return;
      _appendSystem(L10n.t(lang, 'start_exception', {'e': '$e'}));
      setState(() => _running = false);
    }
  }

  void _onEvent(String json) {
    final Map<String, dynamic> event;
    try {
      event = jsonDecode(json) as Map<String, dynamic>;
    } catch (_) {
      return;
    }
    if (!mounted) return;

    switch (event['type']) {
      case 'token':
        _onToken(event['text'] as String? ?? '');
      case 'tool_call':
        setState(() => _items.add(_Item(
              role: 'tool',
              text: '调用 ${event['name']} ${jsonEncode(event['args'] ?? {})}',
            )));
      case 'tool_result':
        setState(() => _items.add(_Item(
              role: 'tool',
              text: (event['content'] as String? ?? '').trim(),
              ok: event['ok'] as bool? ?? false,
            )));
      case 'notice':
        _appendSystem(event['message'] as String? ?? '');
      case 'state':
        setState(() => _status = event['state'] as String? ?? '');
      case 'usage':
        setState(() {
          _totalTokens += (event['total_tokens'] as num?)?.toInt() ?? 0;
        });
      case 'done':
        _flushTokens();
        setState(() {
          _status = 'done';
          _running = false;
        });
      case 'error':
        _flushTokens();
        setState(() => _running = false);
        _appendSystem(
          L10n.t(ref.read(languageProvider), 'error_prefix', {'e': '${event['message']}'}),
        );
      case 'approval_request':
        _handleApproval(event);
    }
    _scrollToBottom();
  }

  void _appendAssistant(String text) {
    if (text.isEmpty) return;
    // 仅与本次任务产生的 assistant 气泡合并，不污染恢复的历史。
    if (_items.isNotEmpty &&
        _items.length > _historyCount &&
        _items.last.role == 'assistant') {
      _items.last.text += text;
    } else {
      _items.add(_Item(role: 'assistant', text: text));
    }
  }

  void _appendSystem(String text) {
    if (!mounted) return;
    setState(() => _items.add(_Item(role: 'system', text: text)));
    _scrollToBottom();
  }

  /// 授权确认弹窗：用户选择后经 [AgentChannel.respondApproval] 回传，
  /// Rust 循环机（后台线程）据此继续或回填拒绝。
  Future<void> _handleApproval(Map<String, dynamic> event) async {
    final id = event['id'] as String? ?? '';
    final tool = event['tool'] as String? ?? '';
    final args = event['args'];
    final reversibility = event['reversibility'] as String? ?? '';
    if (!mounted || id.isEmpty) return;
    final lang = ref.read(languageProvider);
    final allow = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        title: Text(L10n.t(lang, 'approval_title')),
        content: Text(
          L10n.t(lang, 'approval_body', {
            'tool': tool,
            'reversibility': reversibility,
            'args': jsonEncode(args),
          }),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(L10n.t(lang, 'approval_deny')),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(L10n.t(lang, 'approval_allow')),
          ),
        ],
      ),
    );
    await AgentChannel.respondApproval(id: id, allow: allow ?? false);
  }

  /// 重启后恢复最近会话的对话历史（用户已发新消息时不覆盖）。
  Future<void> _restoreRecent() async {
    try {
      final raw = await AgentChannel.loadRecentSession();
      // 用户已开始新输入时不覆盖（避免恢复历史清掉正在进行的对话）。
      if (raw == null || raw.isEmpty || !mounted || _items.isNotEmpty) return;
      final data = jsonDecode(raw) as Map<String, dynamic>;
      _sessionId = data['sessionId'] as String? ?? _sessionId;
      final messages = data['messages'] as List? ?? [];
      if (messages.isEmpty) return;
      setState(() {
        _items.clear();
        for (final m in messages) {
          final role = m['role'] as String? ?? '';
          final text = m['text'] as String? ?? '';
          final ok = m['ok'] as bool? ?? true;
          if (role == 'usage') continue; // 统计数据，不展示为消息。
          if (role == 'policy') {
            _items.add(_Item(role: 'policy', text: text, ok: true));
            continue;
          }
          if (text.isEmpty) continue;
          if (role == 'assistant' &&
              _items.isNotEmpty &&
              _items.last.role == 'assistant') {
            _items.last.text += text;
          } else {
            _items.add(_Item(role: role, text: text, ok: ok));
          }
        }
        _historyCount = _items.length;
      });
    } catch (_) {
      // 恢复失败不阻断使用。
    }
  }

  /// 会话管理弹层：新建 / 切换 / 重命名 / 删除（多会话，任务 24）。
  Future<void> _showSessions() async {
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (_) => StatefulBuilder(
        builder: (_, __) {
          return FutureBuilder<String>(
            future: AgentChannel.listSessions(),
            builder: (ctx, snap) {
              final sessions = <_Session>[];
              if (snap.hasData) {
                try {
                  for (final m in jsonDecode(snap.data!) as List) {
                    final s = m as Map<String, dynamic>;
                    sessions.add(_Session(
                      id: s['id'] as String? ?? '',
                      title: s['title'] as String? ?? '',
                      updatedAt: (s['updatedAt'] as num?)?.toInt() ?? 0,
                      messageCount: (s['messageCount'] as num?)?.toInt() ?? 0,
                    ));
                  }
                } catch (_) {}
              }
              return SafeArea(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 8, 4),
                      child: Row(
                        children: [
                          const Expanded(
                            child: Text('会话', style: TextStyle(fontWeight: FontWeight.bold)),
                          ),
                          TextButton.icon(
                            onPressed: () {
                              Navigator.pop(ctx);
                              _newSession();
                            },
                            icon: const Icon(Icons.add),
                            label: const Text('新建'),
                          ),
                        ],
                      ),
                    ),
                    Flexible(
                      child: sessions.isEmpty
                          ? const Padding(
                              padding: EdgeInsets.all(24),
                              child: Text('暂无会话'),
                            )
                          : ListView.builder(
                              shrinkWrap: true,
                              itemCount: sessions.length,
                              itemBuilder: (ctx, i) {
                                final s = sessions[i];
                                final isCurrent = s.id == _sessionId;
                                return ListTile(
                                  dense: true,
                                  selected: isCurrent,
                                  leading: Icon(
                                    isCurrent ? Icons.article : Icons.chat_bubble_outline,
                                  ),
                                  title: Text(
                                    s.title.isEmpty ? '未命名会话' : s.title,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                  subtitle: Text(
                                    '${s.messageCount} 条消息 · ${_formatTime(s.updatedAt)}',
                                  ),
                                  onTap: () {
                                    Navigator.pop(ctx);
                                    _switchTo(s.id);
                                  },
                                  trailing: Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      IconButton(
                                        icon: const Icon(Icons.edit_outlined, size: 18),
                                        tooltip: '重命名',
                                        onPressed: () {
                                          Navigator.pop(ctx);
                                          _renameSession(s.id, s.title);
                                        },
                                      ),
                                      IconButton(
                                        icon: const Icon(Icons.delete_outline, size: 18),
                                        tooltip: '删除',
                                        onPressed: () {
                                          Navigator.pop(ctx);
                                          _deleteSession(s.id);
                                        },
                                      ),
                                    ],
                                  ),
                                );
                              },
                            ),
                    ),
                  ],
                ),
              );
            },
          );
        },
      ),
    );
  }

  void _newSession() {
    if (_running) {
      _toast('任务运行中，无法新建会话');
      return;
    }
    setState(() {
      _sessionId = '';
      _items.clear();
      _historyCount = 0;
      _totalTokens = 0;
      _status = '';
    });
  }

  Future<void> _switchTo(String id) async {
    if (id == _sessionId) return;
    setState(() {
      _items.clear();
      _historyCount = 0;
      _totalTokens = 0;
      _status = '';
    });
    await _loadHistory(id);
  }

  /// 加载指定会话历史（并设为当前会话）。
  Future<void> _loadHistory(String id) async {
    try {
      final raw = await AgentChannel.loadSession(id);
      if (raw == null || raw.isEmpty || !mounted) return;
      final data = jsonDecode(raw) as Map<String, dynamic>;
      setState(() => _sessionId = data['sessionId'] as String? ?? id);
      final messages = data['messages'] as List? ?? [];
      setState(() {
        _items.clear();
        for (final m in messages) {
          final role = m['role'] as String? ?? '';
          final text = m['text'] as String? ?? '';
          final ok = m['ok'] as bool? ?? true;
          if (role == 'usage') continue;
          if (role == 'policy') {
            _items.add(_Item(role: 'policy', text: text, ok: true));
            continue;
          }
          if (text.isEmpty) continue;
          if (role == 'assistant' &&
              _items.isNotEmpty &&
              _items.last.role == 'assistant') {
            _items.last.text += text;
          } else {
            _items.add(_Item(role: role, text: text, ok: ok));
          }
        }
        _historyCount = _items.length;
      });
      _scrollToBottom();
    } catch (_) {
      // 加载失败不阻断。
    }
  }

  Future<void> _renameSession(String id, String oldTitle) async {
    final c = TextEditingController(text: oldTitle);
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('重命名会话'),
        content: TextField(
          controller: c,
          autofocus: true,
          onSubmitted: (v) => Navigator.pop(ctx, v.trim()),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          TextButton(onPressed: () => Navigator.pop(ctx, c.text.trim()), child: const Text('确定')),
        ],
      ),
    );
    if (name == null || name.isEmpty) return;
    await AgentChannel.renameSession(id, name);
  }

  Future<void> _deleteSession(String id) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除会话'),
        content: const Text('删除后该会话的所有消息与检查点将不可恢复。'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('删除', style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await AgentChannel.deleteSession(id);
    // 删除的是当前会话：清空后加载最近会话；否则什么都不用做。
    if (id == _sessionId) {
      setState(() {
        _sessionId = '';
        _items.clear();
        _historyCount = 0;
        _totalTokens = 0;
        _status = '';
      });
      final raw = await AgentChannel.loadRecentSession();
      if (raw != null && raw.isNotEmpty && mounted) {
        final data = jsonDecode(raw) as Map<String, dynamic>;
        final newId = data['sessionId'] as String? ?? '';
        if (newId.isNotEmpty && newId != id) {
          await _loadHistory(newId);
        }
      }
    }
  }

  String _formatTime(int millis) {
    final dt = DateTime.fromMillisecondsSinceEpoch(millis);
    final diff = DateTime.now().difference(dt);
    if (diff.inMinutes < 1) return '刚刚';
    if (diff.inHours < 1) return '${diff.inMinutes} 分钟前';
    if (diff.inDays < 1) return '${diff.inHours} 小时前';
    if (diff.inDays < 7) return '${diff.inDays} 天前';
    return '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')}';
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  /// 回滚到检查点：用该快照替换当前对话展示，后续消息作为新任务继续。
  Future<void> _showCheckpoints() async {
    if (_sessionId.isEmpty) return;
    final raw = await AgentChannel.checkpoints(_sessionId);
    final List<dynamic> list;
    try {
      list = jsonDecode(raw) as List;
    } catch (_) {
      return;
    }
    if (list.isEmpty) {
      _appendSystem(L10n.t(ref.read(languageProvider), 'no_checkpoints'));
      return;
    }
    if (!mounted) return;
    final selected = await showDialog<int>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('回滚到检查点'),
        children: [
          for (var i = 0; i < list.length; i++)
            SimpleDialogOption(
              onPressed: () => Navigator.pop(ctx, i),
              child: Text(L10n.t(
                ref.read(languageProvider),
                'checkpoint_option',
                {'n': '${list.length - i}'},
              )),
            ),
        ],
      ),
    );
    if (selected == null) return;
    final messages =
        (list[selected] as Map<String, dynamic>)['messages'] as List? ?? [];
    setState(() {
      _items.clear();
      _totalTokens = 0;
      _historyCount = 0;
      for (final m in messages) {
        final role = m['role'] as String? ?? '';
        final text = m['text'] as String? ?? '';
        final ok = m['ok'] as bool? ?? true;
        if (text.isEmpty) continue;
        _items.add(_Item(role: role, text: text, ok: ok));
      }
      _historyCount = _items.length;
    });
    _appendSystem(L10n.t(
      ref.read(languageProvider),
      'rolled_back',
      {'n': '${selected + 1}'},
    ));
  }

  Future<void> _showStats() async {
    final raw = await AgentChannel.getStats();
    final Map<String, dynamic> stats;
    try {
      stats = jsonDecode(raw) as Map<String, dynamic>;
    } catch (_) {
      return;
    }
    if (!mounted) return;
    showDialog(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: Text(L10n.t(ref.read(languageProvider), 'stats_title')),
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: stats.entries
                  .map(
                    (e) => Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Text(
                        '${e.key}: ${e.value}',
                        style: const TextStyle(fontSize: 14),
                      ),
                    ),
                  )
                  .toList(),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _showWorkspace() async {
    final root = await AgentChannel.workspaceRoot();
    final raw = await AgentChannel.workspaceList('');
    var data = <String, dynamic>{};
    try {
      data = jsonDecode(raw) as Map<String, dynamic>;
    } catch (_) {
      // 保持空 map：解析失败按空数据处理。
    }
    if (!mounted) return;
    final ok = data['ok'] as bool? ?? false;
    final content = data['content'] as String? ?? data['message'] as String? ?? '';
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('工作区'),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(root, style: const TextStyle(fontSize: 11, fontFamily: 'monospace')),
              const SizedBox(height: 12),
              if (!ok)
                Text(content, style: TextStyle(color: Theme.of(ctx).colorScheme.error))
              else if (content.isEmpty)
                const Text('（空工作区：让 AI 建文件，或在对话中说「在 workspace 里创建…」）')
              else
                Text(content, style: const TextStyle(fontSize: 12, fontFamily: 'monospace')),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('关闭')),
        ],
      ),
    );
  }

  Future<void> _stop() async {
    await AgentChannel.stopAgent();
    setState(() {
      _running = false;
      _status = 'stopped';
    });
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        // 流式高频滚动直接用 jumpTo，避免动画堆积。
        _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final mode = ref.watch(agentModeProvider);
    final lang = ref.watch(languageProvider);
    return Scaffold(
      appBar: AppBar(
        title: Text(_running ? 'YAYai · $_status' : 'YAYai'),
        actions: [
          IconButton(
            icon: const Icon(Icons.forum_outlined),
            tooltip: '会话',
            onPressed: _running ? null : _showSessions,
          ),
          IconButton(
            icon: const Icon(Icons.analytics_outlined),
            tooltip: 'Stats',
            onPressed: _showStats,
          ),
          IconButton(
            icon: const Icon(Icons.folder_outlined),
            tooltip: 'Workspace',
            onPressed: _showWorkspace,
          ),
          IconButton(
            icon: const Icon(Icons.translate),
            tooltip: 'Language',
            onPressed: () => ref
                .read(languageProvider.notifier)
                .set(lang == L10n.zh ? L10n.en : L10n.zh),
          ),
          if (!_running)
            PopupMenuButton<String>(
              initialValue: mode,
              onSelected: (m) => ref.read(agentModeProvider.notifier).set(m),
              itemBuilder: (_) => [
                PopupMenuItem(value: 'build', child: Text(L10n.t(lang, 'mode_build'))),
                PopupMenuItem(value: 'plan', child: Text(L10n.t(lang, 'mode_plan'))),
                PopupMenuItem(value: 'auto', child: Text(L10n.t(lang, 'mode_auto'))),
              ],
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Center(child: Text(mode.toUpperCase())),
              ),
            ),
          if (_sessionId.isNotEmpty && !_running)
            IconButton(
              icon: const Icon(Icons.history),
              tooltip: '回滚到检查点',
              onPressed: _showCheckpoints,
            ),
          if (_totalTokens > 0)
            Padding(
              padding: const EdgeInsets.only(right: 12),
              child: Center(
                child: Text(
                  '$_totalTokens tok',
                  style: Theme.of(context).textTheme.labelSmall,
                ),
              ),
            ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: ListView.builder(
              controller: _scrollController,
              padding: const EdgeInsets.all(8),
              itemCount: _items.length,
              itemBuilder: (context, index) => _buildItem(_items[index]),
            ),
          ),
          const AnimatedDivider(),
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: Row(
              children: [
                Expanded(
                  child: MaskedInput(
                    controller: _messageController,
                    hintText: L10n.t(lang, 'hint'),
                    onSubmitted: _send,
                  ),
                ),
                const SizedBox(width: 8),
                GlassCard(
                  onTap: _running ? _stop : _send,
                  child: Icon(
                    _running ? Icons.stop : Icons.send,
                    color: Theme.of(context).colorScheme.onSurface,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  MarkdownStyleSheet _mdStyle(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return MarkdownStyleSheet.fromTheme(Theme.of(context)).copyWith(
      codeblockDecoration: BoxDecoration(
        color: colors.surfaceContainerHighest.withOpacity(0.4),
        borderRadius: BorderRadius.circular(8),
      ),
      code: const TextStyle(
        fontFamily: 'monospace',
        fontSize: 13,
      ),
      blockSpacing: 8,
      blockquoteDecoration: BoxDecoration(
        color: colors.surfaceContainerHighest.withOpacity(0.3),
        borderRadius: BorderRadius.circular(4),
      ),
    );
  }

  Widget _buildAssistantBubble(_Item item) {
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Align(
        alignment: Alignment.centerLeft,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          constraints: const BoxConstraints(maxWidth: 560),
          decoration: BoxDecoration(
            color: colors.surfaceContainerHigh.withOpacity(0.5),
            borderRadius: BorderRadius.circular(12),
          ),
          child: MarkdownBody(
            data: item.text,
            selectable: true,
            styleSheet: _mdStyle(context),
          ),
        ),
      ),
    );
  }

  Widget _buildItem(_Item item) {
    if (item.isPolicy) {
      // 决策链记录（任务 18）：verdict/name/reason，小字灰色，供复盘。
      final colors = Theme.of(context).colorScheme;
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.policy_outlined, size: 14, color: colors.onSurfaceVariant.withOpacity(0.6)),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                item.text,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 11,
                  color: colors.onSurfaceVariant.withOpacity(0.7),
                  fontFamily: 'monospace',
                ),
              ),
            ),
          ],
        ),
      );
    }
    if (item.isTool) {
      final colors = Theme.of(context).colorScheme;
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              item.ok ? Icons.build_circle_outlined : Icons.error_outline,
              size: 14,
              color: item.ok ? colors.primary : colors.error,
            ),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                item.text,
                maxLines: 6,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
              ),
            ),
          ],
        ),
      );
    }
    if (item.isSystem) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        child: Text(
          item.text,
          style: TextStyle(fontSize: 12, color: Theme.of(context).colorScheme.error),
        ),
      );
    }
    if (item.isUser) {
      return ChatBubble(message: item.text, isUser: true);
    }
    return _buildAssistantBubble(item);
  }
}