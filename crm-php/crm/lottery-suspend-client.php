<?php
header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';

$data = json_decode(file_get_contents('php://input'), true);
$phone = trim($data['phone'] ?? '');
$suspend = !empty($data['suspend']);

if ($phone === '') {
    echo json_encode(['success' => false, 'message' => 'شماره تلفن ارسال نشده است']);
    exit;
}

try {
    if ($suspend) {
        $stmt = $conn->prepare('INSERT IGNORE INTO lottery_suspended_phones (phone) VALUES (?)');
        if (!$stmt) {
            throw new Exception($conn->error);
        }
        $stmt->bind_param('s', $phone);
        if (!$stmt->execute()) {
            throw new Exception($stmt->error);
        }
        $stmt->close();
        $message = 'این شماره از قرعه‌کشی تعلیق شد.';
    } else {
        $stmt = $conn->prepare('DELETE FROM lottery_suspended_phones WHERE phone = ?');
        if (!$stmt) {
            throw new Exception($conn->error);
        }
        $stmt->bind_param('s', $phone);
        if (!$stmt->execute()) {
            throw new Exception($stmt->error);
        }
        $stmt->close();
        $message = 'تعلیق این شماره برداشته شد.';
    }

    echo json_encode([
        'success' => true,
        'phone' => $phone,
        'suspended' => $suspend,
        'message' => $message
    ]);
} catch (Exception $e) {
    echo json_encode(['success' => false, 'message' => $e->getMessage()]);
}

$conn->close();
?>
