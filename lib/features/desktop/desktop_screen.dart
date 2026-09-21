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

  @override
  Widget build(BuildContext context) {
    final snapshot = widget.controller.snapshot;
    final showSurface = snapshot.running || snapshot.phase == RuntimePhase.starting;
    return ColoredBox(
      color: Colors.black,
      child: Stack(
        children: [
          if (showSurface)
            const Positioned.fill(
              child: NativeDesktopView(),
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
                        tooltip: _pointerCaptured
                            ? 'Release mouse'
                            : 'Capture mouse',
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
                        onPressed: () =>
                            setState(() => _controlsVisible = false),
                        color: Colors.white,
                        icon: const Icon(Icons.keyboard_arrow_up_rounded),
                      ),
                    ],
                  ),
                ),
              ),
            )
          else
            Positioned(
              top: 10,
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

}
