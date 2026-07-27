<?php
header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';
require_once __DIR__ . '/test-data-reset.php';

function requireLotterySession(int $minRole): void
{
    session_start();
    if (!isset($_SESSION['loggedin']) || $_SESSION['loggedin'] !== true) {
        http_response_code(403);
        echo json_encode(['success' => false, 'message' => 'دسترسی غیرمجاز — ابتدا وارد CRM شوید'], JSON_UNESCAPED_UNICODE);
        exit;
    }
    $role = (int) ($_SESSION['role'] ?? 0);
    if ($role < $minRole) {
        http_response_code(403);
        echo json_encode(['success' => false, 'message' => 'دسترسی غیرمجاز'], JSON_UNESCAPED_UNICODE);
        exit;
    }
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['success' => false, 'message' => 'Method not allowed'], JSON_UNESCAPED_UNICODE);
    exit;
}

requireLotterySession(3);

$data = json_decode(file_get_contents('php://input'), true);
$phone = trim($data['phone'] ?? '');
$time = trim($data['time'] ?? '');

if ($phone === '' || $time === '') {
    echo json_encode(['success' => false, 'message' => 'شماره تلفن یا زمان ارسال نشده است'], JSON_UNESCAPED_UNICODE);
    exit;
}

if (normalizeIranPhone($phone) === '') {
    echo json_encode(['success' => false, 'message' => 'شماره تلفن نامعتبر است'], JSON_UNESCAPED_UNICODE);
    exit;
}

try {
    $stmt = $conn->prepare('DELETE FROM winners WHERE phone = ? AND time = ?');
    if (!$stmt) {
        throw new Exception($conn->error);
    }
    $stmt->bind_param('ss', $phone, $time);
    if (!$stmt->execute()) {
        throw new Exception($stmt->error);
    }

    if ($stmt->affected_rows === 0) {
        $stmt->close();
        echo json_encode(['success' => false, 'message' => 'رکورد یافت نشد'], JSON_UNESCAPED_UNICODE);
        exit;
    }

    $stmt->close();
    echo json_encode(['success' => true, 'phone' => $phone, 'time' => $time], JSON_UNESCAPED_UNICODE);
} catch (Exception $e) {
    echo json_encode(['success' => false, 'message' => $e->getMessage()], JSON_UNESCAPED_UNICODE);
}

$conn->close();
