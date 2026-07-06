import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/gateway_response.dart';
import 'settings_store.dart';

class GatewayApi {
  GatewayApi({SettingsStore? settingsStore})
      : _settingsStore = settingsStore ?? SettingsStore();

  final SettingsStore _settingsStore;

  static const Duration timeout = Duration(seconds: 30);

  Future<GatewayResponse> forwardSms({
    required String from,
    required String message,
    String? gatewayUrl,
  }) async {
    final url = gatewayUrl ?? await _settingsStore.getGatewayUrl();
    if (url.isEmpty) {
      throw GatewayApiException('Gateway URL is not configured');
    }

    final uri = Uri.parse(url);
    final response = await http
        .post(
          uri,
          headers: const {'Content-Type': 'application/json'},
          body: jsonEncode({
            'from': from,
            'message': message,
          }),
        )
        .timeout(timeout);

    Map<String, dynamic> body;
    try {
      body = jsonDecode(response.body) as Map<String, dynamic>;
    } catch (_) {
      throw GatewayApiException(
        'Invalid JSON response (${response.statusCode})',
      );
    }

    if (response.statusCode >= 400) {
      final error = body['error'] as String? ?? 'HTTP ${response.statusCode}';
      throw GatewayApiException(error);
    }

    return GatewayResponse.fromJson(body);
  }
}
