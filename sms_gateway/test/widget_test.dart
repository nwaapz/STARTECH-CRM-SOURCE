import 'package:flutter_test/flutter_test.dart';
import 'package:sms_gateway/models/gateway_response.dart';

void main() {
  test('GatewayResponse parses reply field', () {
    final response = GatewayResponse.fromJson({
      'status': 'success',
      'reply': 'Hello back',
    });

    expect(response.status, 'success');
    expect(response.reply, 'Hello back');
  });

  test('GatewayResponse falls back to reply_content', () {
    final response = GatewayResponse.fromJson({
      'status': 'success',
      'reply_content': 'Legacy reply',
    });

    expect(response.reply, 'Legacy reply');
  });
}
