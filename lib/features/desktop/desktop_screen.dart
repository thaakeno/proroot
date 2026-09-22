import 'package:flutter/material.dart';
import '../../core/models/runtime_snapshot.dart';
import '../../core/state/runtime_controller.dart';
import 'native_desktop_view.dart';

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
  bool _pointerCaptured = false;

  Future<void> _togglePointerCapture() async {
    final next = !_pointerCaptured;
    final changed = await widget.controller.setPointerCapture(next);
    if (changed && mounted) setState(() => _pointerCaptured = next);
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
    final keepSurfaceMounted =
        snapshot.running || snapshot.phase == RuntimePhase.starting;

    return Stack(
      fit: StackFit.expand,
      children: [
        if (keepSurfaceMounted)
          const Positioned.fill(child: NativeDesktopView())
        else
          Positioned.fill(
            child: _IdleDesktop(
              installed: snapshot.installed,
              onStart: widget.controller.start,
            ),
          ),
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
                tooltip: _pointerCaptured ? 'Release mouse' : 'Capture mouse',
                onPressed: _togglePointerCapture,
                color: _pointerCaptured
                    ? const Color(0xFF8DDBFF)
                    : Colors.white,
                icon: Icon(
                  _pointerCaptured
                      ? Icons.mouse_rounded
                      : Icons.mouse_outlined,
                ),
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
