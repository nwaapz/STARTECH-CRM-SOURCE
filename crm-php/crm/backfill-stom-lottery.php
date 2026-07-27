<?php
/**
 * One-time backfill: mirror registered S440000–S450000 rows from old_serials into Mcode.
 * Run from CRM lottery page (session) or CLI: php backfill-stom-lottery.php
 */
session_start();

if (php_sapi_name() !== 'cli') {
    if (!isset($_SESSION['loggedin']) || $_SESSION['loggedin'] !== true) {
        http_response_code(403);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['success' => false, 'message' => 'دسترسی غیرمجاز — ابتدا از CRM وارد شوید و دکمه همگام‌سازی را بزنید']);
        exit;
    }
}

header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';
require_once __DIR__ . '/serial-utils.php';
require_once __DIR__ . '/lottery-sync.php';

$sql = "SELECT serial, phone, city FROM old_serials
        WHERE phone IS NOT NULL AND phone != '' AND phone != '0'
        AND UPPER(serial) REGEXP '^S[0-9]+$'
        AND CAST(SUBSTRING(UPPER(serial), 2) AS UNSIGNED) BETWEEN 440000 AND 450000";

$result = $conn->query($sql);
if (!$result) {
    echo json_encode(['success' => false, 'message' => $conn->error]);
    exit;
}

$synced = 0;
$failed = 0;

while ($row = $result->fetch_assoc()) {
    $ok = syncSToMSerialToMcode($conn, $row['serial'], $row['phone'], $row['city'] ?? '');
    if ($ok) {
        $synced++;
    } else {
        $failed++;
    }
}

$result->free();
$conn->close();

echo json_encode([
    'success' => true,
    'synced' => $synced,
    'failed' => $failed,
    'message' => "همگام‌سازی انجام شد: {$synced} سریال به Mcode منتقل شد" . ($failed ? "، {$failed} ناموفق" : '')
], JSON_UNESCAPED_UNICODE);
