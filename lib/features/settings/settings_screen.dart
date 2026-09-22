import 'package:flutter/material.dart';
import '../../core/build_info.dart';
import '../../core/state/runtime_controller.dart';
import '../diagnostics/diagnostics_screen.dart';
import '../device/device_info_screen.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({required this.controller, super.key});
  final RuntimeController controller;

  @override
  Widget build(BuildContext context) {
    return CustomScrollView(
      slivers: [
        const SliverAppBar.large(title: Text('Settings')),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 120),
          sliver: SliverList.list(
            children: [
              _Section(
                title: 'Display',
                children: [
                  SegmentedButton<int>(
                    segments: const [
                      ButtonSegment(value: 60, label: Text('60 Hz')),
                      ButtonSegment(value: 90, label: Text('90 Hz')),
                      ButtonSegment(value: 120, label: Text('120 Hz')),
                    ],
                    selected: {controller.refreshRate},
                    onSelectionChanged: (value) =>
                        controller.setRefreshRate(value.first),
                  ),
                  const SizedBox(height: 18),
                  Row(
                    children: [
                      const Expanded(child: Text('Desktop scale')),
                      Text(
                        '${controller.desktopScale.toStringAsFixed(2)}×',
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ],
                  ),
                  Slider(
                    value: controller.desktopScale,
                    min: .75,
                    max: 2,
                    divisions: 10,
                    onChanged: controller.setDesktopScale,
                  ),
                ],
              ),
              const SizedBox(height: 14),
              _Section(
                title: 'Input',
                children: [
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(
                        value: 'trackpad',
                        icon: Icon(Icons.mouse_outlined),
                        label: Text('Trackpad'),
                      ),
                      ButtonSegment(
                        value: 'direct',
                        icon: Icon(Icons.touch_app_outlined),
                        label: Text('Direct'),
                      ),
                    ],
                    selected: {controller.inputMode},
                    onSelectionChanged: (value) =>
                        controller.setInputMode(value.first),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    controller.inputMode == 'direct'
                        ? 'Direct: the Linux desktop follows your finger like a touchscreen.'
                        : 'Trackpad: move the cursor with one finger, tap to click, hold to drag, two-finger scroll, and two-finger tap to right-click.',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
              const SizedBox(height: 14),
              _Section(
                title: 'App',
                children: [
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    value: controller.darkMode,
                    title: const Text('Dark mode'),
                    onChanged: controller.setDarkMode,
                  ),
                  const Divider(),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    value: controller.deviceInfoInLinux,
                    title: const Text('Open device info inside Linux'),
                    subtitle: const Text(
                      'Use KDE Info Center for the desktop info button instead of the Android panel.',
                    ),
                    onChanged: controller.setDeviceInfoInLinux,
                  ),
                  const Divider(),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.monitor_heart_outlined),
                    title: const Text('Diagnostics'),
                    subtitle:
                        const Text('Runtime state, display transport and logs'),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () => Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) =>
                            DiagnosticsScreen(controller: controller),
                      ),
                    ),
                  ),
                  const Divider(),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.developer_board_rounded),
                    title: const Text('Device info'),
                    subtitle: Text(
                      controller.deviceInfoInLinux
                          ? 'Open KDE Info Center inside Linux'
                          : 'CPU, GPU, RAM, storage and system in the app',
                    ),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () async {
                      if (controller.deviceInfoInLinux) {
                        try {
                          await controller.openLinuxDeviceInfo();
                        } catch (error) {
                          if (!context.mounted) return;
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(
                              content: Text(
                                'Could not open Linux device info: $error',
                              ),
                            ),
                          );
                        }
                        return;
                      }
                      if (!context.mounted) return;
                      await Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) =>
                              DeviceInfoScreen(controller: controller),
                        ),
                      );
                    },
                  ),
                ],
              ),
              const SizedBox(height: 14),
              _Section(
                title: 'About',
                children: [
                  _InfoRow(
                    label: 'Version',
                    value: BuildInfo.version,
                  ),
                  const SizedBox(height: 12),
                  _InfoRow(label: 'Build', value: 'CI #${BuildInfo.build}'),
                  const SizedBox(height: 12),
                  _InfoRow(label: 'Commit', value: BuildInfo.shortGitSha),
                  const SizedBox(height: 12),
                  _InfoRow(
                    label: 'Linux runtime',
                    value: 'ProRoot ${BuildInfo.prorootVersion}',
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 110,
          child: Text(label, style: Theme.of(context).textTheme.labelLarge),
        ),
        Expanded(
          child: SelectableText(
            value,
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
        ),
      ],
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.children});
  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              title,
              style: Theme.of(context)
                  .textTheme
                  .titleMedium
                  ?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 18),
            ...children,
          ],
        ),
      ),
    );
  }
}
