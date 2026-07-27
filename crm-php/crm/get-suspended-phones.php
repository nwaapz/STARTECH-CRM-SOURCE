<?php
header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';

$sql = 'SELECT phone, suspended_at FROM lottery_suspended_phones ORDER BY suspended_at DESC';
$result = $conn->query($sql);

$suspended = [];
if ($result) {
    while ($row = $result->fetch_assoc()) {
        $suspended[] = [
            'phone' => $row['phone'],
            'suspendedAt' => $row['suspended_at']
        ];
    }
}

echo json_encode(['success' => true, 'data' => $suspended]);
$conn->close();
?>
