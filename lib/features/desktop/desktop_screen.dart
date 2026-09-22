import 'package:flutter/material.dart';
import '../../core/models/runtime_snapshot.dart';
import '../../core/state/runtime_controller.dart';
import '../device/device_info_screen.dart';

class DesktopScreen extends StatefulWidget {
  const DesktopScreen({
    required this.controller,
    super.key,
  });

  final RuntimeController controller;

  @override
  State<DesktopScreen> createState() => _DesktopScreenState();
}

class _DesktopScreenState extends State<DesktopScreen> {
  bool _controlsVisible = true;

  Future<void> _toggleInputMode() async {
    final next =
        widget.controller.inputMode == 'trackpad' ? 'direct' : 'trackpad';
    await widget.controller.setInputMode(next);
  }

  Future<void> _openDeviceInfo() async {
    if (widget.controller.deviceInfoInLinux) {
      try {
        await widget.controller.openLinuxDeviceInfo();
      } catch (error) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not open Linux device info: $error')),
        );
      }
      return;
    }

    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => DeviceInfoScreen(controller: widget.controller),
      ),
    );
  }

  void _showFailureDetails(RuntimeSnapshot snapshot) {
    final detail = snapshot.detail?.trim();
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Linux startup failed'),
        content: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 620),
          child: SingleChildScrollView(
            child: SelectableText(
              detail == null || detail.isEmpty ? snapshot.message : detail,
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final snapshot = widget.controller.snapshot;

    return Stack(
      fit: StackFit.expand,
      children: [
        if (!snapshot.running &&
            snapshot.phase != RuntimePhase.starting &&
            snapshot.phase != RuntimePhase.stopping &&
            snapshot.phase != RuntimePhase.failed)
          Positioned.fill(
            child: _IdleDesktop(
              installed: snapshot.installed,
              onStart: widget.controller.start,
            ),
          ),
        if (snapshot.phase == RuntimePhase.starting)
          Positioned.fill(child: _StartupOverlay(snapshot: snapshot)),
        if (snapshot.phase == RuntimePhase.stopping)
          const Positioned.fill(
            child: _RuntimeOverlay(
              title: 'Stopping Linux',
              subtitle: 'Closing the desktop and Linux services cleanly.',
              showProgress: true,
            ),
          ),
        if (snapshot.phase == RuntimePhase.failed)
          Positioned.fill(
            child: _FailureOverlay(
              snapshot: snapshot,
              onRetry: snapshot.installed
                  ? widget.controller.start
                  : widget.controller.install,
              retryLabel: snapshot.installed ? 'Retry' : 'Retry setup',
              onDetails: () => _showFailureDetails(snapshot),
            ),
          ),
        if (snapshot.running)
          _buildDesktopControls(),
      ],
    );
  }

  Widget _buildDesktopControls() {
    if (!_controlsVisible) {
      return Positioned(
        top: 10,
        right: 12,
        child: SafeArea(
          child: IconButton.filled(
            tooltip: 'Show desktop controls',
            onPressed: () => setState(() => _controlsVisible = true),
            icon: const Icon(Icons.tune_rounded),
          ),
        ),
      );
    }

    return Positioned(
      top: 10,
      right: 12,
      child: SafeArea(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
          decoration: BoxDecoration(
            color: const Color(0xE814171D),
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: Colors.white12),
            boxShadow: const [
              BoxShadow(
                blurRadius: 24,
                offset: Offset(0, 8),
                color: Color(0x44000000),
              ),
            ],
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 8),
                child: Icon(
                  Icons.circle,
                  size: 9,
                  color: Color(0xFF6EE7A6),
                ),
              ),
              IconButton(
                tooltip: 'Keyboard',
                onPressed: widget.controller.showKeyboard,
                color: Colors.white,
                icon: const Icon(Icons.keyboard_rounded),
              ),
              IconButton(
                tooltip: widget.controller.inputMode == 'trackpad'
                    ? 'Trackpad mode · tap for direct touch'
                    : 'Direct touch · tap for trackpad mode',
                onPressed: _toggleInputMode,
                color: widget.controller.inputMode == 'trackpad'
                    ? const Color(0xFF8DDBFF)
                    : Colors.white,
                icon: Icon(
                  widget.controller.inputMode == 'trackpad'
                      ? Icons.mouse_rounded
                      : Icons.touch_app_outlined,
                ),
              ),
              IconButton(
                tooltip: widget.controller.deviceInfoInLinux
                    ? 'Open KDE Info Center'
                    : 'Device info',
                onPressed: _openDeviceInfo,
                color: Colors.white,
                icon: const Icon(Icons.developer_board_rounded),
              ),
              IconButton(
                tooltip: 'Stop Linux',
                onPressed: widget.controller.stop,
                color: Colors.white,
                icon: const Icon(Icons.stop_rounded),
              ),
              IconButton(
                tooltip: 'Hide desktop controls',
                onPressed: () => setState(() => _controlsVisible = false),
                color: Colors.white,
                icon: const Icon(Icons.keyboard_arrow_up_rounded),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _IdleDesktop extends StatelessWidget {
  const _IdleDesktop({
    required this.installed,
    required this.onStart,
  });

  final bool installed;
  final Future<void> Function() onStart;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 340),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.desktop_access_disabled_rounded,
              size: 56,
              color: Colors.white70,
            ),
            const SizedBox(height: 16),
            Text(
              installed ? 'Linux is ready' : 'Linux is not installed',
              style: const TextStyle(
                color: Colors.white,
                fontSize: 22,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              installed
                  ? 'Start the machine to open the native Wayland desktop.'
                  : 'Finish setup before starting the Linux desktop.',
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white60),
            ),
            if (installed) ...[
              const SizedBox(height: 22),
              FilledButton.icon(
                onPressed: onStart,
                icon: const Icon(Icons.play_arrow_rounded),
                label: const Text('Start Linux'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _StartupOverlay extends StatelessWidget {
  const _StartupOverlay({required this.snapshot});

  final RuntimeSnapshot snapshot;

  String _duration(int seconds) {
    final minutes = seconds ~/ 60;
    final remainder = seconds % 60;
    if (minutes == 0) return '${remainder}s';
    return '${minutes}m ${remainder.toString().padLeft(2, '0')}s';
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final progress = snapshot.progress.clamp(0.0, 1.0);
    final percent = (progress * 100).round();
    final stages = snapshot.stageDetail
            ?.split('\n')
            .where((line) => line.trim().isNotEmpty)
            .take(5)
            .toList() ??
        const <String>[];
    final currentStep = snapshot.totalItems > 0
        ? '${(snapshot.completedItems + 1).clamp(1, snapshot.totalItems)}/${snapshot.totalItems}'
        : 'Boot';

    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            scheme.surface,
            Color.alphaBlend(
              scheme.primary.withValues(alpha: 0.10),
              scheme.surface,
            ),
            scheme.surfaceContainerLow,
          ],
        ),
      ),
      child: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 540),
            child: Container(
              margin: const EdgeInsets.symmetric(horizontal: 24),
              padding: const EdgeInsets.all(26),
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHigh.withValues(alpha: 0.94),
                borderRadius: BorderRadius.circular(30),
                border: Border.all(color: scheme.outlineVariant),
                boxShadow: [
                  BoxShadow(
                    blurRadius: 44,
                    offset: const Offset(0, 18),
                    color: Colors.black.withValues(alpha: 0.20),
                  ),
                ],
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Container(
                        width: 58,
                        height: 58,
                        decoration: BoxDecoration(
                          color: scheme.primaryContainer,
                          borderRadius: BorderRadius.circular(18),
                        ),
                        child: Icon(
                          Icons.desktop_windows_rounded,
                          color: scheme.onPrimaryContainer,
                          size: 30,
                        ),
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Booting Linux desktop',
                              style: Theme.of(context)
                                  .textTheme
                                  .headlineSmall
                                  ?.copyWith(fontWeight: FontWeight.w800),
                            ),
                            const SizedBox(height: 3),
                            Text(
                              'KDE Plasma · Anland · Wayland',
                              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                    color: scheme.onSurfaceVariant,
                                    fontWeight: FontWeight.w600,
                                  ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 26),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Expanded(
                        child: Text(
                          snapshot.message,
                          style: Theme.of(context).textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.w700,
                              ),
                        ),
                      ),
                      const SizedBox(width: 16),
                      Text(
                        '$percent%',
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                              color: scheme.primary,
                              fontWeight: FontWeight.w900,
                            ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  TweenAnimationBuilder<double>(
                    duration: const Duration(milliseconds: 320),
                    curve: Curves.easeOutCubic,
                    tween: Tween<double>(begin: 0, end: progress),
                    builder: (context, value, _) => LinearProgressIndicator(
                      value: value > 0 ? value : null,
                      minHeight: 10,
                      borderRadius: BorderRadius.circular(99),
                      backgroundColor: scheme.surfaceContainerHighest,
                    ),
                  ),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      _BootMetric(
                        icon: Icons.timer_outlined,
                        label: 'Elapsed',
                        value: _duration(snapshot.elapsedSeconds),
                      ),
                      const SizedBox(width: 10),
                      _BootMetric(
                        icon: Icons.layers_outlined,
                        label: 'Stage',
                        value: currentStep,
                      ),
                      const SizedBox(width: 10),
                      const _BootMetric(
                        icon: Icons.monitor_heart_outlined,
                        label: 'Display',
                        value: 'Live',
                      ),
                    ],
                  ),
                  if (stages.isNotEmpty) ...[
                    const SizedBox(height: 22),
                    Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: scheme.surfaceContainerLow,
                        borderRadius: BorderRadius.circular(18),
                      ),
                      child: Column(
                        children: [
                          for (var index = 0; index < stages.length; index++)
                            _BootStage(
                              text: stages[index],
                              last: index == stages.length - 1,
                            ),
                        ],
                      ),
                    ),
                  ],
                  const SizedBox(height: 16),
                  Text(
                    'The display transport stays mounted while Plasma finishes starting, so the desktop can appear without tearing down the Android surface.',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: scheme.onSurfaceVariant,
                          height: 1.35,
                        ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _BootMetric extends StatelessWidget {
  const _BootMetric({
    required this.icon,
    required this.label,
    required this.value,
  });

  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, size: 18, color: scheme.primary),
            const SizedBox(height: 7),
            Text(
              label,
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
            ),
            const SizedBox(height: 2),
            Text(
              value,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.labelLarge?.copyWith(
                    fontWeight: FontWeight.w800,
                  ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BootStage extends StatelessWidget {
  const _BootStage({required this.text, required this.last});

  final String text;
  final bool last;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final trimmed = text.trim();
    final active = trimmed.startsWith('›');
    final cleaned =
        trimmed.replaceFirst('✓', '').replaceFirst('›', '').trim();

    return Padding(
      padding: EdgeInsets.only(bottom: last ? 0 : 10),
      child: Row(
        children: [
          Icon(
            active ? Icons.pending_rounded : Icons.check_circle_rounded,
            size: 19,
            color: active ? scheme.primary : scheme.tertiary,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              cleaned,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    fontWeight: active ? FontWeight.w700 : FontWeight.w500,
                    color:
                        active ? scheme.onSurface : scheme.onSurfaceVariant,
                  ),
            ),
          ),
        ],
      ),
    );
  }
}

class _RuntimeOverlay extends StatelessWidget {
  const _RuntimeOverlay({
    required this.title,
    required this.subtitle,
    required this.showProgress,
  });

  final String title;
  final String subtitle;
  final bool showProgress;

  @override
  Widget build(BuildContext context) {
    return Center(
        child: Container(
          constraints: const BoxConstraints(maxWidth: 420),
          margin: const EdgeInsets.all(24),
          padding: const EdgeInsets.all(24),
          decoration: BoxDecoration(
            color: const Color(0xF0181A20),
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: Colors.white12),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (showProgress) ...[
                const LinearProgressIndicator(minHeight: 7),
                const SizedBox(height: 20),
              ],
              Text(
                title,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 21,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                subtitle,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: Colors.white60,
                  height: 1.35,
                ),
              ),
            ],
          ),
        ),
    );
  }
}

class _FailureOverlay extends StatelessWidget {
  const _FailureOverlay({
    required this.snapshot,
    required this.onRetry,
    required this.retryLabel,
    required this.onDetails,
  });

  final RuntimeSnapshot snapshot;
  final Future<void> Function() onRetry;
  final String retryLabel;
  final VoidCallback onDetails;

  @override
  Widget build(BuildContext context) {
    return Center(
        child: Container(
          constraints: const BoxConstraints(maxWidth: 460),
          margin: const EdgeInsets.all(24),
          padding: const EdgeInsets.all(24),
          decoration: BoxDecoration(
            color: const Color(0xF21B1C22),
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: const Color(0x55FF6B6B)),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(
                Icons.error_outline_rounded,
                size: 42,
                color: Color(0xFFFF7D7D),
              ),
              const SizedBox(height: 16),
              Text(
                snapshot.message.isEmpty
                    ? 'Linux startup failed'
                    : snapshot.message,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 21,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 10),
              const Text(
                'The desktop was not marked as running. Open the failure details before retrying if the same stage keeps failing.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white60, height: 1.35),
              ),
              const SizedBox(height: 22),
              Wrap(
                alignment: WrapAlignment.center,
                spacing: 10,
                runSpacing: 10,
                children: [
                  OutlinedButton.icon(
                    onPressed: onDetails,
                    icon: const Icon(Icons.description_outlined),
                    label: const Text('Failure details'),
                  ),
                  FilledButton.icon(
                    onPressed: onRetry,
                    icon: const Icon(Icons.refresh_rounded),
                    label: Text(retryLabel),
                  ),
                ],
              ),
            ],
          ),
        ),
    );
  }
}
