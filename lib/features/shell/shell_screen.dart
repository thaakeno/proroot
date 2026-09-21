import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

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

  bool get _desktopSelected => _index == 1;

  Future<void> _select(int index) async {
    if (_index == index) return;

    final enteringDesktop = index == 1;
    if (enteringDesktop) {
      await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    } else if (_desktopSelected) {
      await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }

    if (!mounted) return;
    setState(() => _index = index);
  }

  void _openDesktop() {
    _select(1);
  }

  @override
  void dispose() {
    if (_desktopSelected) {
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    final pages = <Widget>[
      HomeScreen(controller: controller, onOpenDesktop: _openDesktop),
      DesktopScreen(
        controller: controller,
        onExitDesktop: () => _select(0),
      ),
      AppsScreen(controller: controller, onOpenDesktop: _openDesktop),
      InstallScreen(controller: controller),
      SettingsScreen(controller: controller),
    ];

    return Scaffold(
      body: SafeArea(
        top: !_desktopSelected,
        bottom: false,
        child: IndexedStack(index: _index, children: pages),
      ),
      bottomNavigationBar: _desktopSelected
          ? null
          : NavigationBar(
              selectedIndex: _index,
              onDestinationSelected: _select,
              destinations: const [
                NavigationDestination(
                  icon: Icon(Icons.space_dashboard_outlined),
                  selectedIcon: Icon(Icons.space_dashboard),
                  label: 'Home',
                ),
                NavigationDestination(
                  icon: Icon(Icons.desktop_windows_outlined),
                  selectedIcon: Icon(Icons.desktop_windows),
                  label: 'Desktop',
                ),
                NavigationDestination(
                  icon: Icon(Icons.apps_outlined),
                  selectedIcon: Icon(Icons.apps_rounded),
                  label: 'Apps',
                ),
                NavigationDestination(
                  icon: Icon(Icons.downloading_outlined),
                  selectedIcon: Icon(Icons.downloading),
                  label: 'Setup',
                ),
                NavigationDestination(
                  icon: Icon(Icons.tune_outlined),
                  selectedIcon: Icon(Icons.tune),
                  label: 'Settings',
                ),
              ],
            ),
    );
  }
}
