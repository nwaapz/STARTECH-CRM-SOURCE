<?php
header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';
require_once __DIR__ . '/test-data-reset.php';

requireTestDataAdminSession();

if (!ensureTestPhonesTable($conn)) {
    echo json_encode(['success' => false, 'message' => $conn->error], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $result = $conn->query('SELECT phone, label, created_at FROM crm_test_phones ORDER BY created_at DESC');
    if (!$result) {
        echo json_encode(['success' => false, 'message' => $conn->error], JSON_UNESCAPED_UNICODE);
        exit;
    }
    $phones = [];
    while ($row = $result->fetch_assoc()) {
        $phones[] = $row;
    }
    $result->free();
    echo json_encode(['success' => true, 'phones' => $phones], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['success' => false, 'message' => 'Method not allowed'], JSON_UNESCAPED_UNICODE);
    exit;
}

$data = json_decode(file_get_contents('php://input'), true);
$action = $data['action'] ?? '';

if ($action === 'add') {
    $phone = normalizeIranPhone($data['phone'] ?? '');
    $label = trim((string) ($data['label'] ?? ''));

    if ($phone === '') {
        echo json_encode(['success' => false, 'message' => 'شماره تلفن نامعتبر است'], JSON_UNESCAPED_UNICODE);
        exit;
    }

    $stmt = $conn->prepare('INSERT INTO crm_test_phones (phone, label) VALUES (?, ?) ON DUPLICATE KEY UPDATE label = VALUES(label)');
    if (!$stmt) {
        echo json_encode(['success' => false, 'message' => $conn->error], JSON_UNESCAPED_UNICODE);
        exit;
    }
    $labelVal = $label !== '' ? $label : null;
    $stmt->bind_param('ss', $phone, $labelVal);
    $ok = $stmt->execute();
    $stmt->close();

    if (!$ok) {
        echo json_encode(['success' => false, 'message' => $conn->error], JSON_UNESCAPED_UNICODE);
        exit;
    }

    echo json_encode(['success' => true, 'phone' => $phone, 'message' => 'شماره تست ذخیره شد'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

if ($action === 'remove') {
    $phone = normalizeIranPhone($data['phone'] ?? '');
    if ($phone === '') {
        echo json_encode(['success' => false, 'message' => 'شماره تلفن نامعتبر است'], JSON_UNESCAPED_UNICODE);
        exit;
    }

    $stmt = $conn->prepare('DELETE FROM crm_test_phones WHERE phone = ?');
    if (!$stmt) {
        echo json_encode(['success' => false, 'message' => $conn->error], JSON_UNESCAPED_UNICODE);
        exit;
    }
    $stmt->bind_param('s', $phone);
    $stmt->execute();
    $removed = $stmt->affected_rows;
    $stmt->close();

    echo json_encode([
        'success' => true,
        'removed' => $removed,
        'message' => $removed ? 'شماره تست حذف شد' : 'شماره یافت نشد'
    ], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}

echo json_encode(['success' => false, 'message' => 'عملیات نامعتبر است'], JSON_UNESCAPED_UNICODE);
$conn->close();
