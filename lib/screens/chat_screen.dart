import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:permission_handler/permission_handler.dart';

import '../models.dart';
import '../providers.dart';
import '../services/ai_service.dart';
import '../widgets/glass_card.dart';

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key});

  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final TextEditingController _messageController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final List<ChatMessage> _messages = [];
  bool _isSending = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _requestPermissions();
    });
  }

  @override
  void dispose() {
    _messageController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _requestPermissions() async {
    await Permission.storage.request();
  }

  Future<void> _sendMessage() async {
    final message = _messageController.text.trim();
    if (message.isEmpty || _isSending) return;

    setState(() {
      _isSending = true;
      _messages.add(ChatMessage(role: 'user', content: message));
      _messageController.clear();
    });

    _scrollToBottom();

    try {
      final config = await ref.read(aiConfigProvider.future);
      final aiService = AIService();

      final subscription = aiService.streamChat(
        _messages,
        model: config.config?.modelName,
        baseUrl: config.config?.baseUrl,
        apiKey: config.config?.apiKey,
      ).listen((chunk) {
        setState(() {
          if (_messages.isNotEmpty && _messages.last.role == 'assistant') {
            _messages.last = ChatMessage(
              role: 'assistant',
              content: _messages.last.content + chunk,
            );
          } else {
            _messages.add(ChatMessage(role: 'assistant', content: chunk));
          }
        });
        _scrollToBottom();
      });

      await subscription.asFuture();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('发送失败: $e')),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isSending = false;
        });
      }
    }
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
        title: const Text('YAYai'),
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
              itemCount: _messages.length,
              itemBuilder: (context, index) {
                final message = _messages[index];
                return ChatBubble(
                  message: message.content,
                  isUser: message.role == 'user',
                );
              },
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
                    hintText: '输入消息...',
                    onSubmitted: _sendMessage,
                  ),
                ),
                const SizedBox(width: 8),
                GlassCard(
                  onTap: _isSending ? null : _sendMessage,
                  child: Icon(
                    _isSending ? Icons.hourglass_empty : Icons.send,
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
}
