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

    // 1) Legacy gateway_db (historical stock)
    $row = gatewayLookupSerial($conn, 'gateway_db', $serial);
    $table = 'gateway_db';

    // 2) Panel-created serials live in old_serials (and sometimes new_serials)
    if ($row === null) {
        $row = gatewayLookupSerial($conn, 'old_serials', $serial);
        $table = 'old_serials';
    }
    if ($row === null) {
        $row = gatewayLookupSerial($conn, 'new_serials', $serial);
        $table = 'new_serials';
    }

    if ($row === null) {
        logMessage("Serial not found: $serial");
        mysqli_close($conn);
        return REPLY_INVALID;
    }

    $dbSerial = (string) $row['serial'];
    if (isSerialUsed($row['phone'] ?? null)) {
        logMessage("Serial already used: $dbSerial (phone={$row['phone']})");
        mysqli_close($conn);
        return REPLY_INVALID;
    }

    $normalizedPhone = trim($sender);
    $jalaliDate = todayJalaliDate();
    $gregorianDate = date('Y-m-d');

    if ($table === 'gateway_db') {
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
        mysqli_stmt_bind_param($updateStmt, 'sss', $normalizedPhone, $jalaliDate, $dbSerial);
    } else {
        // Panel CRM tables use `time` (+ optional sync_updated_ms)
        $hasSync = gatewayHasColumn($conn, $table, 'sync_updated_ms');
        $syncMs = (int) round(microtime(true) * 1000);
        if ($hasSync) {
            $updateSql = "UPDATE `$table` SET phone = ?, time = ?, sync_updated_ms = ?
                           WHERE serial = ? AND (phone IS NULL OR phone = '' OR phone = '0')";
            $updateStmt = mysqli_prepare($conn, $updateSql);
            if (!$updateStmt) {
                mysqli_close($conn);
                throw new RuntimeException('Prepare failed: ' . mysqli_error($conn));
            }
            mysqli_stmt_bind_param($updateStmt, 'ssis', $normalizedPhone, $gregorianDate, $syncMs, $dbSerial);
        } else {
            $updateSql = "UPDATE `$table` SET phone = ?, time = ?
                           WHERE serial = ? AND (phone IS NULL OR phone = '' OR phone = '0')";
            $updateStmt = mysqli_prepare($conn, $updateSql);
            if (!$updateStmt) {
                mysqli_close($conn);
                throw new RuntimeException('Prepare failed: ' . mysqli_error($conn));
            }
            mysqli_stmt_bind_param($updateStmt, 'sss', $normalizedPhone, $gregorianDate, $dbSerial);
        }
    }

    if (!mysqli_stmt_execute($updateStmt)) {
        $error = mysqli_stmt_error($updateStmt);
        mysqli_stmt_close($updateStmt);
        mysqli_close($conn);
        throw new RuntimeException('Update failed: ' . $error);
    }

    $affectedRows = mysqli_stmt_affected_rows($updateStmt);
    mysqli_stmt_close($updateStmt);

    if ($affectedRows === 1) {
        if ($table === 'old_serials' || $table === 'new_serials') {
            $bufHelper = __DIR__ . '/crm-php/api/sync/sync_buffer_helpers.php';
            if (is_file($bufHelper)) {
                require_once $bufHelper;
                if (function_exists('sync_buffer_enqueue_serial_lookup')) {
                    sync_buffer_enqueue_serial_lookup($conn, $dbSerial, $table, 'gateway');
                }
            }
        }
        logMessage("Serial registered ($table): $dbSerial for phone $normalizedPhone");
        mysqli_close($conn);
        return REPLY_VALID;
    }

    mysqli_close($conn);
    logMessage("Serial registration race lost or already used: $dbSerial ($table)");
    return REPLY_INVALID;
}

function gatewayHasColumn(mysqli $conn, string $table, string $col): bool
{
    $t = mysqli_real_escape_string($conn, $table);
    $c = mysqli_real_escape_string($conn, $col);
    $r = mysqli_query($conn, "SHOW COLUMNS FROM `$t` LIKE '$c'");
    return $r && mysqli_num_rows($r) > 0;
}

/**
 * @return array<string,mixed>|null
 */
function gatewayLookupSerial(mysqli $conn, string $table, string $serial): ?array
{
    $check = @mysqli_query($conn, "SHOW TABLES LIKE '" . mysqli_real_escape_string($conn, $table) . "'");
    if (!$check || mysqli_num_rows($check) === 0) {
        return null;
    }

    $candidates = [$serial];
    if (preg_match('/^([A-Z]+)(\d+)$/', $serial, $m)) {
        $prefix = $m[1];
        $num = (int) $m[2];
        $raw = (string) $num;
        $candidates[] = $prefix . $raw;
        for ($w = strlen($raw); $w <= 8; $w++) {
            $candidates[] = $prefix . str_pad($raw, $w, '0', STR_PAD_LEFT);
        }
        $candidates = array_values(array_unique($candidates));
    }

    $placeholders = implode(',', array_fill(0, count($candidates), '?'));
    $sql = "SELECT * FROM `$table` WHERE UPPER(serial) IN ($placeholders) LIMIT 1";
    $stmt = mysqli_prepare($conn, $sql);
    if (!$stmt) {
        return null;
    }
    $types = str_repeat('s', count($candidates));
    mysqli_stmt_bind_param($stmt, $types, ...$candidates);
    if (!mysqli_stmt_execute($stmt)) {
        mysqli_stmt_close($stmt);
        return null;
    }
    $result = mysqli_stmt_get_result($stmt);
    $row = $result ? mysqli_fetch_assoc($result) : null;
    mysqli_stmt_close($stmt);
    return $row ?: null;
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
