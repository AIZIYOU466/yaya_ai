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