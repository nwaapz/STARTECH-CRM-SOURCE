import 'dart:async';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:telephony/telephony.dart';

import 'screens/home_screen.dart';
import 'services/activity_log.dart';
import 'services/background_service.dart';
import 'services/settings_store.dart';
import 'services/sms_processor.dart';

final Telephony telephony = Telephony.instance;
final SmsProcessor foregroundProcessor = SmsProcessor();

@pragma('vm:entry-point')
Future<void> smsBackgroundHandler(SmsMessage message) async {
  await ActivityLog.instance.load();
  final processor = SmsProcessor();
  await processor.processIncomingSms(
    message,
    useBackgroundTelephony: true,
  );
}

void registerSmsListener() {
  telephony.listenIncomingSms(
    onNewMessage: (message) {
      unawaited(
        foregroundProcessor.processIncomingSms(
          message,
          useBackgroundTelephony: false,
        ),
      );
    },
    onBackgroundMessage: smsBackgroundHandler,
    listenInBackground: true,
  );
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await ActivityLog.instance.load();
  await initializeBackgroundService();
  registerSmsListener();

  final settings = SettingsStore();
  if (await settings.getAutoStart() && await Permission.sms.isGranted) {
    await startGatewayService();
  }

  runApp(const SmsGatewayApp());
}

class SmsGatewayApp extends StatelessWidget {
  const SmsGatewayApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SMS Gateway',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}
