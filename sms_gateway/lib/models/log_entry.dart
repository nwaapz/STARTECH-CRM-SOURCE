class LogEntry {
  const LogEntry({
    required this.timestamp,
    required this.sender,
    required this.inbound,
    this.reply,
    required this.status,
    this.error,
  });

  final DateTime timestamp;
  final String sender;
  final String inbound;
  final String? reply;
  final String status;
  final String? error;

  Map<String, dynamic> toJson() => {
        'timestamp': timestamp.toIso8601String(),
        'sender': sender,
        'inbound': inbound,
        'reply': reply,
        'status': status,
        'error': error,
      };

  factory LogEntry.fromJson(Map<String, dynamic> json) {
    return LogEntry(
      timestamp: DateTime.parse(json['timestamp'] as String),
      sender: json['sender'] as String? ?? '',
      inbound: json['inbound'] as String? ?? '',
      reply: json['reply'] as String?,
      status: json['status'] as String? ?? 'unknown',
      error: json['error'] as String?,
    );
  }
}
