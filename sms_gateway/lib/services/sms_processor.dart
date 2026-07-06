import 'package:telephony/telephony.dart';

import '../models/gateway_response.dart';
import '../models/log_entry.dart';
import 'activity_log.dart';
import 'gateway_api.dart';

class SmsProcessor {
  SmsProcessor({
    GatewayApi? gatewayApi,
    ActivityLog? activityLog,
  })  : _gatewayApi = gatewayApi ?? GatewayApi(),
        _activityLog = activityLog ?? ActivityLog.instance;

  final GatewayApi _gatewayApi;
  final ActivityLog _activityLog;

  static final Map<String, DateTime> _recentMessages = {};

  static const Duration dedupWindow = Duration(seconds: 10);

  Future<void> processIncomingSms(
    SmsMessage message, {
    required bool useBackgroundTelephony,
  }) async {
    final sender = message.address ?? '';
    final body = message.body ?? '';

    if (sender.isEmpty || body.isEmpty) {
      return;
    }

    final dedupKey = '$sender::$body';
    final now = DateTime.now();
    final lastSeen = _recentMessages[dedupKey];
    if (lastSeen != null && now.difference(lastSeen) < dedupWindow) {
      return;
    }
    _recentMessages[dedupKey] = now;
    _pruneDedupCache(now);

    try {
      final response = await _gatewayApi.forwardSms(
        from: sender,
        message: body,
      );

      final telephony =
          useBackgroundTelephony ? Telephony.backgroundInstance : Telephony.instance;
      await telephony.sendSms(to: sender, message: response.reply);

      await _activityLog.add(
        LogEntry(
          timestamp: now,
          sender: sender,
          inbound: body,
          reply: response.reply,
          status: 'sent',
        ),
      );
    } on GatewayApiException catch (error) {
      await _activityLog.add(
        LogEntry(
          timestamp: now,
          sender: sender,
          inbound: body,
          status: 'gateway_error',
          error: error.message,
        ),
      );
    } catch (error) {
      await _activityLog.add(
        LogEntry(
          timestamp: now,
          sender: sender,
          inbound: body,
          status: 'failed',
          error: error.toString(),
        ),
      );
    }
  }

  void _pruneDedupCache(DateTime now) {
    _recentMessages.removeWhere(
      (_, seenAt) => now.difference(seenAt) > dedupWindow,
    );
  }
}
