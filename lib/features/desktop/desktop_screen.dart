import 'package:flutter/material.dart';
import '../../core/state/runtime_controller.dart';

class DesktopScreen extends StatefulWidget {
  const DesktopScreen({required this.controller, super.key});
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

  @override
  Widget build(BuildContext context) {
    final running = widget.controller.snapshot.running;
    return ColoredBox(
      color: Colors.black,
      child: Stack(
        children: [
          if (running)
            const Positioned.fill(
              child: AndroidView(
                viewType: 'dev.thaakeno.proroot/display',
                layoutDirection: TextDirection.ltr,
              ),
            )
          else
            Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 320),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.desktop_access_disabled_rounded, size: 56, color: Colors.white70),
                    const SizedBox(height: 16),
                    const Text('Linux is not running', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 8),
                    const Text('Start the machine and the native Wayland desktop will appear here.', textAlign: TextAlign.center, style: TextStyle(color: Colors.white60)),
                    const SizedBox(height: 22),
                    FilledButton.icon(onPressed: widget.controller.start, icon: const Icon(Icons.play_arrow_rounded), label: const Text('Start Linux')),
                  ],
                ),
              ),
            ),
          if (_controlsVisible)
            Positioned(
              top: 12,
              left: 12,
              right: 12,
              child: SafeArea(
                child: Center(
                  child: Container(
                    constraints: const BoxConstraints(maxWidth: 720),
                    padding: const EdgeInsets.fromLTRB(12, 8, 6, 8),
                    decoration: BoxDecoration(
                      color: const Color(0xE814171D),
                      borderRadius: BorderRadius.circular(22),
                      border: Border.all(color: Colors.white12),
                      boxShadow: const [
                        BoxShadow(blurRadius: 28, offset: Offset(0, 10), color: Color(0x55000000)),
                      ],
                    ),
                    child: Row(
                      children: [
                        const Icon(Icons.circle, size: 10, color: Color(0xFF6EE7A6)),
                        const SizedBox(width: 9),
                        const Expanded(
                          child: Text(
                            'Debian 13 · Plasma',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(color: Colors.white, fontWeight: FontWeight.w800),
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
                          color: _pointerCaptured ? const Color(0xFF8DDBFF) : Colors.white,
                          icon: Icon(_pointerCaptured ? Icons.mouse_rounded : Icons.mouse_outlined),
                        ),
                        IconButton(onPressed: () => _launch('org.kde.konsole.desktop'), color: Colors.white, icon: const Icon(Icons.terminal_rounded)),
                        IconButton(onPressed: () => _launch('brave-browser.desktop'), color: Colors.white, icon: const Icon(Icons.language_rounded)),
                        IconButton(
                          tooltip: 'Hide controls',
                          onPressed: () => setState(() => _controlsVisible = false),
                          color: Colors.white,
                          icon: const Icon(Icons.keyboard_arrow_up_rounded),
                        ),
                        IconButton(onPressed: widget.controller.stop, color: Colors.white, icon: const Icon(Icons.stop_rounded)),
                      ],
                    ),
                  ),
                ),
              ),
            )
          else
            Positioned(
              top: 12,
              right: 12,
              child: SafeArea(
                child: IconButton.filled(
                  tooltip: 'Show desktop controls',
                  onPressed: () => setState(() => _controlsVisible = true),
                  icon: const Icon(Icons.tune_rounded),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Future<void> _launch(String desktopId) async {
    try {
      await widget.controller.launchApp(desktopId);
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not launch app: $error')),
      );
    }
  }
}
