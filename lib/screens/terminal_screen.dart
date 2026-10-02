import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../services/terminal_manager.dart';
import '../widgets/glass_card.dart';

class TerminalScreen extends ConsumerStatefulWidget {
  const TerminalScreen({super.key});

  @override
  ConsumerState<TerminalScreen> createState() => _TerminalScreenState();
}

class _TerminalScreenState extends ConsumerState<TerminalScreen> {
  final TextEditingController _commandController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  late TerminalManager _terminalManager;
  StreamSubscription<TerminalContainerState>? _stateSubscription;

  @override
  void initState() {
    super.initState();
    _terminalManager = TerminalManager();
    _stateSubscription = _terminalManager.stateStream.listen((state) {
      if (mounted) {
        setState(() {});
        _scrollToBottom();
      }
    });
  }

  @override
  void dispose() {
    _commandController.dispose();
    _scrollController.dispose();
    _stateSubscription?.cancel();
    _terminalManager.dispose();
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
    final success = await _terminalManager.startContainer();
    if (!success && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('启动容器失败: ${_terminalManager.state.error ?? "未知错误"}')),
      );
    }
  }

  Future<void> _stopContainer() async {
    await _terminalManager.stopContainer();
  }

  Future<void> _executeCommand() async {
    final command = _commandController.text.trim();
    if (command.isEmpty) return;

    await _terminalManager.executeCommand(command);
    _commandController.clear();
  }

  @override
  Widget build(BuildContext context) {
    final state = _terminalManager.state;

    return Scaffold(
      appBar: AppBar(
        title: const Text('终端容器'),
        actions: [
          IconButton(
            icon: Icon(
              state.isRunning ? Icons.stop : Icons.play_arrow,
            ),
            onPressed: () {
              if (state.isRunning) {
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
                itemCount: state.history.length,
                itemBuilder: (context, index) {
                  final line = state.history[index];
                  return Text(
                    line,
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
