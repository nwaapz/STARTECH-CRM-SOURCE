import 'dart:async';
import 'dart:ui';

import 'package:flutter_background_service/flutter_background_service.dart';

const String serviceNotificationChannelId = 'sms_gateway_channel';

Future<void> initializeBackgroundService() async {
  final service = FlutterBackgroundService();

  await service.configure(
    androidConfiguration: AndroidConfiguration(
      onStart: onServiceStart,
      autoStart: false,
      isForegroundMode: true,
      autoStartOnBoot: true,
      notificationChannelId: serviceNotificationChannelId,
      initialNotificationTitle: 'SMS Gateway',
      initialNotificationContent: 'Gateway is active',
      foregroundServiceTypes: [AndroidForegroundType.dataSync],
    ),
    iosConfiguration: IosConfiguration(),
  );
}

Future<bool> isGatewayServiceRunning() async {
  final service = FlutterBackgroundService();
  return service.isRunning();
}

Future<void> startGatewayService() async {
  final service = FlutterBackgroundService();
  if (!await service.isRunning()) {
    await service.startService();
  }
}

Future<void> stopGatewayService() async {
  final service = FlutterBackgroundService();
  service.invoke('stop');
}

@pragma('vm:entry-point')
void onServiceStart(ServiceInstance service) async {
  DartPluginRegistrant.ensureInitialized();

  if (service is AndroidServiceInstance) {
    service.setForegroundNotificationInfo(
      title: 'SMS Gateway',
      content: 'Listening for incoming SMS',
    );
  }

  service.on('stop').listen((_) {
    service.stopSelf();
  });
}
