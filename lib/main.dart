import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:permission_handler/permission_handler.dart';

import 'providers.dart';
import 'screens/chat_screen.dart';
import 'screens/config_screen.dart';
import 'screens/mcp_screen.dart';
import 'screens/terminal_screen.dart';

void main() {
  runApp(
    const ProviderScope(
      child: MyApp(),
    ),
  );
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'YAYai',
      theme: ThemeData(
        primarySwatch: Colors.blue,
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.blue,
          brightness: Brightness.light,
        ),
        brightness: Brightness.light,
      ),
      darkTheme: ThemeData(
        primarySwatch: Colors.blue,
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.blue,
          brightness: Brightness.dark,
        ),
        brightness: Brightness.dark,
      ),
      themeMode: ThemeMode.system,
      routerConfig: _router,
    );
  }
}

// 路由配置
final GoRouter _router = GoRouter(
  routes: [
    GoRoute(
      path: '/',
      builder: (context, state) => const ChatScreen(),
    ),
    GoRoute(
      path: '/config',
      builder: (context, state) => const ConfigScreen(),
    ),
    GoRoute(
      path: '/terminal',
      builder: (context, state) => const TerminalScreen(),
    ),
    GoRoute(
      path: '/mcp',
      builder: (context, state) => const MCPScreen(),
    ),
  ],
);

// 权限请求
Future<void> requestPermissions() async {
  // 请求存储权限
  await Permission.storage.request();

  // 请求前台服务权限（用于 proot 容器）
  await Permission.foregroundService.request();
}
