import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../platform/agent_channel.dart';
import '../providers.dart';
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

  @override
  void dispose() {
    _eventSub?.cancel();
    _messageController.dispose();
    _scrollController.dispose();
    super.dispose();
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
    _eventSub = AgentChannel.agentEvents.listen(_onEvent, onError: (Object e) {
      _appendSystem('事件流错误: $e');
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
        'mcpServers': mcp.servers
            .where((s) => s.enabled)
            .map((s) => s.toJson())
            .toList(),
      };
      final started = await AgentChannel.startAgent(
        taskId: DateTime.now().millisecondsSinceEpoch.toString(),
        prompt: message,
        config: config,
      );
      if (!started) {
        if (!mounted) return;
        _appendSystem('启动失败：Rust Core 未就绪');
        setState(() => _running = false);
      }
    } catch (e) {
      if (!mounted) return;
      _appendSystem('启动异常: $e');
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
        setState(() => _appendAssistant(event['text'] as String? ?? ''));
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
      case 'done':
        setState(() {
          _status = 'done';
          _running = false;
        });
      case 'error':
        setState(() => _running = false);
        _appendSystem('错误: ${event['message']}');
    }
    _scrollToBottom();
  }

  void _appendAssistant(String text) {
    if (text.isEmpty) return;
    if (_items.isNotEmpty && _items.last.role == 'assistant') {
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
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_running ? 'YAYai · $_status' : 'YAYai'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () => context.push('/config'),
          ),
          IconButton(
            icon: const Icon(Icons.terminal),
            onPressed: () => context.push('/terminal'),
          ),
          IconButton(
            icon: const Icon(Icons.extension),
            onPressed: () => context.push('/mcp'),
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
                    hintText: '输入任务，例如「打开设置把字体调大」...',
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

  Widget _buildItem(_Item item) {
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
    return ChatBubble(message: item.text, isUser: item.isUser);
  }
}