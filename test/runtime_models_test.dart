import 'package:flutter_test/flutter_test.dart';
import 'package:proroot_pc/core/models/linux_app.dart';
import 'package:proroot_pc/core/models/runtime_snapshot.dart';

void main() {
  group('RuntimeSnapshot', () {
    test('parses native status maps', () {
      final snapshot = RuntimeSnapshot.fromMap({
        'phase': 'running',
        'progress': 0.75,
        'message': 'KDE Plasma is running',
        'downloadedBytes': 128,
        'totalBytes': 256,
        'speedBytesPerSecond': 64,
        'elapsedSeconds': 12,
        'etaSeconds': 34,
        'stageProgress': 0.5,
        'stageDetail': 'Downloading file 2 of 4',
        'stageDownloadedBytes': 500,
        'stageTotalBytes': 1000,
        'stageSpeedBytesPerSecond': 100,
        'completedItems': 2,
        'totalItems': 4,
        'installed': true,
        'running': true,
        'detail': 'ok',
      });

      expect(snapshot.phase, RuntimePhase.running);
      expect(snapshot.progress, 0.75);
      expect(snapshot.message, 'KDE Plasma is running');
      expect(snapshot.downloadedBytes, 128);
      expect(snapshot.totalBytes, 256);
      expect(snapshot.speedBytesPerSecond, 64);
      expect(snapshot.elapsedSeconds, 12);
      expect(snapshot.etaSeconds, 34);
      expect(snapshot.stageProgress, 0.5);
      expect(snapshot.stageDetail, 'Downloading file 2 of 4');
      expect(snapshot.stageDownloadedBytes, 500);
      expect(snapshot.stageTotalBytes, 1000);
      expect(snapshot.stageSpeedBytesPerSecond, 100);
      expect(snapshot.completedItems, 2);
      expect(snapshot.totalItems, 4);
      expect(snapshot.installed, isTrue);
      expect(snapshot.running, isTrue);
      expect(snapshot.detail, 'ok');
    });

    test('clamps progress and rejects unknown native phases safely', () {
      final high = RuntimeSnapshot.fromMap({
        'phase': 'future-phase',
        'progress': 9,
      });
      final low = RuntimeSnapshot.fromMap({
        'phase': 'ready',
        'progress': -3,
      });

      expect(high.phase, RuntimePhase.failed);
      expect(high.progress, 1);
      expect(low.phase, RuntimePhase.ready);
      expect(low.progress, 0);
    });

    test('copyWith preserves untouched runtime state', () {
      const original = RuntimeSnapshot(
        phase: RuntimePhase.ready,
        progress: 1,
        message: 'Ready',
        downloadedBytes: 1,
        totalBytes: 1,
        speedBytesPerSecond: 0,
        elapsedSeconds: 5,
        etaSeconds: 10,
        stageProgress: 0.25,
        stageDetail: 'Resolving packages',
        stageDownloadedBytes: 25,
        stageTotalBytes: 100,
        stageSpeedBytesPerSecond: 5,
        completedItems: 1,
        totalItems: 4,
        installed: true,
        running: false,
        detail: 'stable',
      );

      final changed = original.copyWith(
        phase: RuntimePhase.starting,
        message: 'Starting',
      );

      expect(changed.phase, RuntimePhase.starting);
      expect(changed.message, 'Starting');
      expect(changed.installed, isTrue);
      expect(changed.running, isFalse);
      expect(changed.elapsedSeconds, 5);
      expect(changed.etaSeconds, 10);
      expect(changed.stageProgress, 0.25);
      expect(changed.stageDetail, 'Resolving packages');
      expect(changed.stageDownloadedBytes, 25);
      expect(changed.stageTotalBytes, 100);
      expect(changed.stageSpeedBytesPerSecond, 5);
      expect(changed.completedItems, 1);
      expect(changed.totalItems, 4);
      expect(changed.detail, 'stable');
    });
  });

  group('LinuxApp', () {
    test('parses launcher metadata including resolved icon path', () {
      final app = LinuxApp.fromMap({
        'id': 'brave-browser.desktop',
        'name': 'Brave Browser',
        'genericName': 'Web Browser',
        'icon': 'brave-browser',
        'iconPath': '/data/user/0/dev.thaakeno.proroot/files/machine/rootfs/usr/share/icons/brave.svg',
        'categories': ['Network', 'WebBrowser'],
      });

      expect(app.id, 'brave-browser.desktop');
      expect(app.name, 'Brave Browser');
      expect(app.genericName, 'Web Browser');
      expect(app.icon, 'brave-browser');
      expect(app.iconPath, contains('brave.svg'));
      expect(app.categories, ['Network', 'WebBrowser']);
    });

    test('handles incomplete desktop metadata without throwing', () {
      final app = LinuxApp.fromMap({
        'id': 'minimal.desktop',
        'name': 'Minimal',
      });

      expect(app.genericName, isNull);
      expect(app.icon, isNull);
      expect(app.iconPath, isNull);
      expect(app.categories, isEmpty);
    });
  });
}
