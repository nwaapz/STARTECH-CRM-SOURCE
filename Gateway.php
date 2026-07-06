<?php
/**
 * SMS Gateway API
 * Receives incoming SMS data from the gateway app and returns reply text.
 * The phone app sends the reply SMS back to the sender.
 */

error_reporting(E_ALL);
ini_set('display_errors', 0);

header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/db_config.php';
require_once __DIR__ . '/jalali_helper.php';

const REPLY_VALID = 'شماره سریال شما معتبر است و گارانتی شما آغاز شد.';
const REPLY_INVALID = 'شماره سریال معتبر نیست.';

$logFile = 'sms_log.txt';

function logMessage($message) {
    global $logFile;
    $timestamp = date('Y-m-d H:i:s');
    file_put_contents($logFile, "[$timestamp] $message\n", FILE_APPEND);
}

function isSerialUsed($phone): bool
{
    return $phone !== null && trim((string) $phone) !== '';
}

function normalizeSerial(string $serial): string
{
    return strtoupper(trim($serial));
}

function processSerialRegistration(string $sender, string $serial): string
{
    $serial = normalizeSerial($serial);
    logMessage("Looking up serial: $serial");

    $conn = gatewayDbConnect();

    $selectSql = 'SELECT id, phone FROM gateway_db WHERE UPPER(serial) = UPPER(?) LIMIT 1';
    $selectStmt = mysqli_prepare($conn, $selectSql);
    if (!$selectStmt) {
        mysqli_close($conn);
        throw new RuntimeException('Prepare failed: ' . mysqli_error($conn));
    }

    mysqli_stmt_bind_param($selectStmt, 's', $serial);
    if (!mysqli_stmt_execute($selectStmt)) {
        $error = mysqli_stmt_error($selectStmt);
        mysqli_stmt_close($selectStmt);
        mysqli_close($conn);
        throw new RuntimeException('Select failed: ' . $error);
    }

    $result = mysqli_stmt_get_result($selectStmt);
    $row = $result ? mysqli_fetch_assoc($result) : null;
    mysqli_stmt_close($selectStmt);

    if ($row === null) {
        logMessage("Serial not found: $serial");
        mysqli_close($conn);
        return REPLY_INVALID;
    }

    if (isSerialUsed($row['phone'])) {
        logMessage("Serial already used: $serial (phone={$row['phone']})");
        mysqli_close($conn);
        return REPLY_INVALID;
    }

    $normalizedPhone = trim($sender);
    $jalaliDate = todayJalaliDate();
    $updateSql = <<<'SQL'
UPDATE gateway_db
SET phone = ?, date = ?
WHERE UPPER(serial) = UPPER(?)
  AND (phone IS NULL OR phone = '')
SQL;
    $updateStmt = mysqli_prepare($conn, $updateSql);
    if (!$updateStmt) {
        mysqli_close($conn);
        throw new RuntimeException('Prepare failed: ' . mysqli_error($conn));
    }

    mysqli_stmt_bind_param($updateStmt, 'sss', $normalizedPhone, $jalaliDate, $serial);
    if (!mysqli_stmt_execute($updateStmt)) {
        $error = mysqli_stmt_error($updateStmt);
        mysqli_stmt_close($updateStmt);
        mysqli_close($conn);
        throw new RuntimeException('Update failed: ' . $error);
    }

    $affectedRows = mysqli_stmt_affected_rows($updateStmt);
    mysqli_stmt_close($updateStmt);
    mysqli_close($conn);

    if ($affectedRows === 1) {
        logMessage("Serial registered: $serial for phone $normalizedPhone (date=$jalaliDate)");
        return REPLY_VALID;
    }

    logMessage("Serial registration race lost or already used: $serial");
    return REPLY_INVALID;
}

logMessage("=== New incoming request ===");

$rawInput = file_get_contents('php://input');
logMessage("Raw input: " . $rawInput);

$data = json_decode($rawInput, true);
if ($data === null) {
    logMessage("JSON decode failed, treating as form data.");
    $data = $_POST;
}

if (empty($data)) {
    logMessage("Error: No data received");
    http_response_code(400);
    echo json_encode(['error' => 'No data received']);
    exit;
}

$sender = $data['from'] ?? $data['sender'] ?? $data['phone'] ?? '';
$message = $data['message'] ?? $data['text'] ?? $data['body'] ?? '';

if (empty($sender) && isset($data['data']['from'])) {
    $sender = $data['data']['from'];
}
if (empty($message) && isset($data['data']['message'])) {
    $message = $data['data']['message'];
}

logMessage("Received SMS from: $sender");
logMessage("Message: $message");

if (empty($sender) || empty($message)) {
    logMessage("Error: Missing required fields");
    http_response_code(400);
    echo json_encode(['error' => 'Missing required fields']);
    exit;
}

// --- BUSINESS LOGIC ---
$serial = normalizeSerial($message);
$sender = trim($sender);

if ($serial === '') {
    logMessage("Error: Empty serial in message");
    $reply = REPLY_INVALID;
} else {
    try {
        $reply = processSerialRegistration($sender, $serial);
    } catch (Throwable $e) {
        logMessage("Database error: " . $e->getMessage());
        http_response_code(500);
        echo json_encode([
            'status' => 'error',
            'error' => 'Database error',
        ]);
        logMessage("=== Request completed with error ===\n");
        exit;
    }
}

logMessage("Reply: $reply");

http_response_code(200);
echo json_encode([
    'status' => 'success',
    'reply' => $reply,
]);

logMessage("=== Request completed ===\n");
