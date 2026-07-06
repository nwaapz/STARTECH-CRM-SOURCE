class GatewayResponse {
  const GatewayResponse({
    required this.status,
    required this.reply,
  });

  final String status;
  final String reply;

  factory GatewayResponse.fromJson(Map<String, dynamic> json) {
    final reply = json['reply'] as String? ?? json['reply_content'] as String?;
    if (reply == null || reply.isEmpty) {
      throw GatewayApiException('Response missing reply field');
    }

    return GatewayResponse(
      status: json['status'] as String? ?? 'success',
      reply: reply,
    );
  }
}

class GatewayApiException implements Exception {
  GatewayApiException(this.message);

  final String message;

  @override
  String toString() => message;
}
