<?php
header('Content-Type: application/json; charset=utf-8');
require_once __DIR__ . '/lottery-preset-store.php';

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

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    requireLotterySession(3);
    echo json_encode([
        'success' => true,
        'preset' => lotteryPresetGet(),
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['success' => false, 'message' => 'Method not allowed'], JSON_UNESCAPED_UNICODE);
    exit;
}

requireLotterySession(5);

$data = json_decode(file_get_contents('php://input'), true);
$action = $data['action'] ?? '';

if ($action === 'set') {
    $phone = normalizeIranPhone($data['phone'] ?? '');
    if ($phone === '') {
        echo json_encode(['success' => false, 'message' => 'شماره تلفن نامعتبر است'], JSON_UNESCAPED_UNICODE);
        exit;
    }
    lotteryPresetSet($phone);
    echo json_encode([
        'success' => true,
        'preset' => $phone,
        'message' => 'برنده از پیش تعیین‌شده ذخیره شد',
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($action === 'clear') {
    lotteryPresetClear();
    echo json_encode([
        'success' => true,
        'preset' => null,
        'message' => 'برنده از پیش تعیین‌شده پاک شد',
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

echo json_encode(['success' => false, 'message' => 'عملیات نامعتبر'], JSON_UNESCAPED_UNICODE);
