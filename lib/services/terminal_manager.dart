import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:path_provider/path_provider.dart';

/// 终端容器状态
class TerminalContainerState {
  final bool isRunning;
  final String? shellPrompt;
  final String? error;
  final List<String> history;

  TerminalContainerState({
    this.isRunning = false,
    this.shellPrompt,
    this.error,
    this.history = const [],
  });

  TerminalContainerState copyWith({
    bool? isRunning,
    String? shellPrompt,
    String? error,
    List<String>? history,
  }) {
    return TerminalContainerState(
      isRunning: isRunning ?? this.isRunning,
      shellPrompt: shellPrompt ?? this.shellPrompt,
      error: error ?? this.error,
      history: history ?? this.history,
    );
  }
}

/// 终端容器管理器（基于 proot）
class TerminalManager {
  Process? _process;
  String _workingDir = '';
  final String _debianRootfsDir = 'debian_rootfs';

  TerminalContainerState _state = TerminalContainerState();
  final _stateController = StreamController<TerminalContainerState>.broadcast();

  Stream<TerminalContainerState> get stateStream => _stateController.stream;
  TerminalContainerState get state => _state;

  TerminalManager();

  /// 初始化工作目录
  Future<void> init() async {
    final appDir = await getApplicationDocumentsDirectory();
    _workingDir = appDir.path;
    _debianRootfsDir = '$_workingDir/$debianRootfsDir';
  }

  /// 启动容器
  Future<bool> startContainer() async {
    try {
      await init();

      // 检查 rootfs 是否已安装
      if (!await _checkRootfsInstalled()) {
        _updateState(error: 'Debian rootfs 未安装，请先下载');
        return false;
      }

      // 构建 proot 命令
      final prootCmd = _buildProotCommand();
      _process = await Process.start(
        prootCmd[0],
        prootCmd.sublist(1),
        workingDirectory: _workingDir,
        mode: ProcessStartMode.normal,
      );

      // 监听输出
      _process!.stdout.transform(utf8.decoder).listen((data) {
        _handleOutput(data);
      });

      _process!.stderr.transform(utf8.decoder).listen((data) {
        _handleError(data);
      });

      _process!.exitCode.then((code) {
        _updateState(isRunning: false, error: '容器已退出 (code: $code)');
      });

      _updateState(isRunning: true, shellPrompt: '# ');
      return true;
    } catch (e) {
      _updateState(error: '启动容器失败: $e');
      return false;
    }
  }

  /// 停止容器
  Future<void> stopContainer() async {
    if (_process != null) {
      _process!.kill(ProcessSignal.sigterm);
      await _process!.exitCode;
      _process = null;
    }
    _updateState(isRunning: false);
  }

  /// 执行命令
  Future<void> executeCommand(String command) async {
    if (_process == null || !state.isRunning) {
      _updateState(error: '容器未运行');
      return;
    }

    _process!.stdin.writeln(command);
  }

  /// 检查 rootfs 是否已安装
  Future<bool> _checkRootfsInstalled() async {
    final dir = Directory(_debianRootfsDir);
    return dir.existsSync();
  }

  /// 构建 proot 命令
  List<String> _buildProotCommand() {
    return [
      'proot',
      '--rootfs=$_debianRootfsDir',
      '--bind=/dev',
      '--bind=/proc',
      '--bind=/sys',
      '--bind=/storage/emulated/0:/sdcard',
      '--workdir=$_workingDir',
      '/bin/bash',
      '-l',
    ];
  }

  /// 处理输出
  void _handleOutput(String data) {
    final lines = data.split('\n');
    final newHistory = [..._state.history, ...lines];
    _updateState(
      history: newHistory,
      shellPrompt: _extractShellPrompt(data),
    );
  }

  /// 处理错误
  void _handleError(String data) {
    final lines = data.split('\n');
    final newHistory = [..._state.history, ...lines];
    _updateState(
      history: newHistory,
      shellPrompt: _extractShellPrompt(data),
    );
  }

  /// 提取 shell 提示符
  String _extractShellPrompt(String data) {
    if (data.contains('# ')) {
      return '# ';
    } else if (data.contains('$ ')) {
      return '$ ';
    }
    return _state.shellPrompt ?? '';
  }

  /// 更新状态
  void _updateState({
    bool? isRunning,
    String? shellPrompt,
    String? error,
    List<String>? history,
  }) {
    _state = _state.copyWith(
      isRunning: isRunning,
      shellPrompt: shellPrompt,
      error: error,
      history: history,
    );
    _stateController.add(_state);
  }

  /// 下载 Debian rootfs
  Future<bool> downloadRootfs({String? url}) async {
    try {
      final rootfsUrl = url ?? 'https://github.com/termux/proot-distro/releases/download/v3.10.0/debian-aarch64-pd-v3.10.0.tar.xz';

      // 使用 http 下载
      final request = await HttpClient().getUrl(Uri.parse(rootfsUrl));
      final response = await request.close();

      final file = File('$_workingDir/debian.tar.xz');
      final sink = file.openWrite();
      await response.pipe(sink);
      await sink.close();

      // 解压
      final result = await Process.run('tar', ['-xJf', 'debian.tar.xz'], workingDirectory: _workingDir);
      if (result.exitCode != 0) {
        _updateState(error: '解压失败: ${result.stderr}');
        return false;
      }

      return true;
    } catch (e) {
      _updateState(error: '下载 rootfs 失败: $e');
      return false;
    }
  }

  /// 释放资源
  Future<void> dispose() async {
    await stopContainer();
    _stateController.close();
  }
}
