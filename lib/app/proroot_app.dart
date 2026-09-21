import 'package:flutter/material.dart';
import '../core/platform/runtime_bridge.dart';
import '../core/state/runtime_controller.dart';
import '../core/theme/app_theme.dart';
import '../features/shell/shell_screen.dart';

class ProrootApp extends StatefulWidget {
  const ProrootApp({super.key});

  @override
  State<ProrootApp> createState() => _ProrootAppState();
}

class _ProrootAppState extends State<ProrootApp> {
  late final RuntimeController _controller;

  @override
  void initState() {
    super.initState();
    _controller = RuntimeController(RuntimeBridge())..initialize();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        return MaterialApp(
          debugShowCheckedModeBanner: false,
          title: 'Proroot',
          themeMode: _controller.darkMode ? ThemeMode.dark : ThemeMode.light,
          theme: AppTheme.light(),
          darkTheme: AppTheme.dark(),
          home: ShellScreen(controller: _controller),
        );
      },
    );
  }
}
