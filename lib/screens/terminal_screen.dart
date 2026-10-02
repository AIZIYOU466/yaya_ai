import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../platform/agent_channel.dart';
import '../widgets/glass_card.dart';

class TerminalScreen extends ConsumerStatefulWidget {
  const TerminalScreen({super.key});

  @override
  ConsumerState<TerminalScreen> createState() => _TerminalScreenState();
}

class _TerminalScreenState extends ConsumerState<TerminalScreen> {
  final TextEditingController _commandController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final List<String> _output = [];
  bool _isRunning = false;
  StreamSubscription<String>? _commandSubscription;

  @override
  void dispose() {
    _commandController.dispose();
    _scrollController.dispose();
    _commandSubscription?.cancel();
    super.dispose();
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

  Future<void> _startContainer() async {
    final ok = await AgentChannel.startContainer();
    if (mounted) {
      setState(() {
        _isRunning = ok;
      });
      if (!ok) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('启动容器失败，请先下载 Debian rootfs')),
        );
      }
    }
  }

  Future<void> _stopContainer() async {
    await AgentChannel.stopContainer();
    if (mounted) {
      setState(() {
        _isRunning = false;
      });
    }
  }

  Future<void> _executeCommand() async {
    final command = _commandController.text.trim();
    if (command.isEmpty || !_isRunning) return;

    setState(() {
      _output.add('\$ $command');
    });
    _commandController.clear();
    _scrollToBottom();

    _commandSubscription?.cancel();
    _commandSubscription = AgentChannel.executeCommand(command).listen(
      (line) {
        setState(() {
          _output.add(line);
        });
        _scrollToBottom();
      },
      onDone: () {
        setState(() {
          _output.add('');
        });
        _scrollToBottom();
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('终端容器'),
        actions: [
          IconButton(
            icon: Icon(_isRunning ? Icons.stop : Icons.play_arrow),
            onPressed: () {
              if (_isRunning) {
                _stopContainer();
              } else {
                _startContainer();
              }
            },
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: Container(
              color: Colors.black87,
              padding: const EdgeInsets.all(8),
              child: ListView.builder(
                controller: _scrollController,
                itemCount: _output.length,
                itemBuilder: (context, index) {
                  return Text(
                    _output[index],
                    style: const TextStyle(
                      color: Colors.white,
                      fontFamily: 'monospace',
                      fontSize: 14,
                    ),
                  );
                },
              ),
            ),
          ),
          const AnimatedDivider(),
          Padding(
            padding: const EdgeInsets.all(8.0),
            child: Row(
              children: [
                Expanded(
                  child: MaskedInput(
                    controller: _commandController,
                    hintText: '输入命令...',
                    onSubmitted: _executeCommand,
                  ),
                ),
                const SizedBox(width: 8),
                GlassCard(
                  onTap: _executeCommand,
                  child: const Icon(
                    Icons.play_arrow,
                    color: Colors.white,
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
