import 'package:flutter/material.dart';
import '../../core/state/runtime_controller.dart';
import '../apps/apps_screen.dart';
import '../desktop/desktop_screen.dart';
import '../home/home_screen.dart';
import '../install/install_screen.dart';
import '../settings/settings_screen.dart';

class ShellScreen extends StatefulWidget {
  const ShellScreen({required this.controller, super.key});
  final RuntimeController controller;

  @override
  State<ShellScreen> createState() => _ShellScreenState();
}

class _ShellScreenState extends State<ShellScreen> {
  int _index = 0;

  void _openDesktop() => setState(() => _index = 1);

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    final pages = <Widget>[
      HomeScreen(controller: controller, onOpenDesktop: _openDesktop),
      DesktopScreen(controller: controller),
      AppsScreen(controller: controller, onOpenDesktop: _openDesktop),
      InstallScreen(controller: controller),
      SettingsScreen(controller: controller),
    ];

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: IndexedStack(index: _index, children: pages),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (value) => setState(() => _index = value),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.space_dashboard_outlined), selectedIcon: Icon(Icons.space_dashboard), label: 'Home'),
          NavigationDestination(icon: Icon(Icons.desktop_windows_outlined), selectedIcon: Icon(Icons.desktop_windows), label: 'Desktop'),
          NavigationDestination(icon: Icon(Icons.apps_outlined), selectedIcon: Icon(Icons.apps_rounded), label: 'Apps'),
          NavigationDestination(icon: Icon(Icons.downloading_outlined), selectedIcon: Icon(Icons.downloading), label: 'Setup'),
          NavigationDestination(icon: Icon(Icons.tune_outlined), selectedIcon: Icon(Icons.tune), label: 'Settings'),
        ],
      ),
    );
  }
}
