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

  bool get _desktopSelected => _index == 1;

  Future<void> _select(int index) async {
    if (_index == index) return;
    if (!mounted) return;
    setState(() => _index = index);
  }

  void _openDesktop() {
    _select(1);
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    final pages = <Widget>[
      HomeScreen(
        controller: controller,
        onOpenDesktop: _openDesktop,
        onOpenSetup: () => _select(3),
      ),
      DesktopScreen(controller: controller),
      AppsScreen(controller: controller, onOpenDesktop: _openDesktop),
      InstallScreen(controller: controller),
      SettingsScreen(controller: controller),
    ];

    return PopScope<Object?>(
      canPop: !_desktopSelected,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop && _desktopSelected) {
          _select(0);
        }
      },
      child: Scaffold(
        // The Linux SurfaceView must not be resized by the Android IME. Resizing
        // it caused Anland to tear down/reconnect repeatedly during keyboard
        // animations, which is exactly the black-screen/crash loop seen in logs.
        resizeToAvoidBottomInset: !_desktopSelected,
        body: SafeArea(
          top: true,
          bottom: false,
          child: IndexedStack(index: _index, children: pages),
        ),
        bottomNavigationBar: NavigationBar(
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
      ),
    );
  }
}
