import 'dart:async';
import 'package:flutter/services.dart';
import '../models/linux_app.dart';
import '../models/runtime_snapshot.dart';

class RuntimeBridge {
  static const _method = MethodChannel('dev.thaakeno.proroot/runtime');
  static const _events = EventChannel('dev.thaakeno.proroot/runtime_events');

  Stream<RuntimeSnapshot> get events => _events
      .receiveBroadcastStream()
      .where((event) => event is Map)
      .map((event) => RuntimeSnapshot.fromMap(event as Map<dynamic, dynamic>));

  Future<RuntimeSnapshot> status() async {
    final map = await _method.invokeMapMethod<dynamic, dynamic>('status');
    return RuntimeSnapshot.fromMap(map ?? const {});
  }

  Future<void> install() => _method.invokeMethod<void>('install');
  Future<void> start() => _method.invokeMethod<void>('start');
  Future<void> stop() => _method.invokeMethod<void>('stop');
  Future<void> reset() => _method.invokeMethod<void>('reset');

  Future<String> exec(String command) async {
    return (await _method.invokeMethod<String>('exec', {'command': command})) ?? '';
  }

  Future<List<LinuxApp>> desktopApps() async {
    final raw = await _method.invokeListMethod<dynamic>('desktopApps') ?? const [];
    return raw
        .whereType<Map>()
        .map((value) => LinuxApp.fromMap(value))
        .where((app) => app.id.isNotEmpty && app.name.isNotEmpty)
        .toList(growable: false);
  }

  Future<void> launchDesktopApp(String desktopId) {
    return _method.invokeMethod<void>('launchDesktopApp', {'desktopId': desktopId});
  }

  Future<bool> showKeyboard() async {
    return await _method.invokeMethod<bool>('showKeyboard') ?? false;
  }

  Future<bool> setPointerCapture(bool enabled) async {
    return await _method.invokeMethod<bool>(
          'setPointerCapture',
          {'enabled': enabled},
        ) ??
        false;
  }

  Future<Map<String, dynamic>> diagnostics() async {
    final raw = await _method.invokeMapMethod<dynamic, dynamic>('diagnostics') ?? const {};
    return raw.map((key, value) => MapEntry('$key', value));
  }

  Future<void> setDisplayOptions({
    required int refreshRate,
    required double scale,
    required String inputMode,
  }) {
    return _method.invokeMethod<void>('setDisplayOptions', {
      'refreshRate': refreshRate,
      'scale': scale,
      'inputMode': inputMode,
    });
  }
}
