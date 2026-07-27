<?php

require_once __DIR__ . '/serial-utils.php';
require_once __DIR__ . '/lottery-sync.php';
require_once __DIR__ . '/test-data-reset.php';

const IV_SMS_LINK_DEFAULT = 'لطفاً تصویر سریال {serial} را ثبت کنید: {link}';
const IV_SMS_VERIFIED_DEFAULT = 'اپراتور تصویر سریال {serial} شما را تأیید کرد. ثبت‌نام تکمیل شد.';
const IV_SMS_REJECTED_DEFAULT = 'تصویر سریال {serial} قابل قبول نیست. لطفاً مجدداً ثبت کنید: {link}';
const IV_SMS_REJECTED_UNREADABLE_DEFAULT = 'تصویر سریال {serial} خوانا نیست. لطفاً مجدداً با وضوح بیشتر ثبت کنید: {link}';
const IV_SMS_REJECTED_WRONG_SERIAL_DEFAULT = 'شماره سریال در تصویر با سریال ثبت‌شده ({serial}) مطابقت ندارد. لطفاً مجدداً ثبت کنید: {link}';
const IV_CAPTURE_BASE_DEFAULT = 'https://startechgroup.ir/warrantyDT/capture/';
const IV_TOKEN_TTL_DAYS = 7;
const IV_UPLOAD_MAX_BYTES = 5242880;
/** When true, S/M toggles are off and image verification is disabled regardless of DB. */
const IV_SERIAL_TOGGLES_LOCKED = false;

function ivAreSerialTogglesLocked(): bool
{
    return IV_SERIAL_TOGGLES_LOCKED;
}

function ivSendSms(string $phone, string $message): void
{
    ini_set('soap.wsdl_cache_enabled', '0');
    try {
        $sms = new SoapClient('http://api.payamak-panel.com/post/Send.asmx?wsdl', ['encoding' => 'UTF-8']);
        $data = [
            'username' => '09121777039',
            'password' => '4thvahdati@FB',
            'to' => [$phone],
            'from' => '20001390',
            'text' => $message,
            'isflash' => false,
        ];
        $sms->SendSimpleSMS($data);
    } catch (Exception $e) {
        error_log('[image-verification] SMS failed: ' . $e->getMessage());
    }
}

function ivEnsureColumn($conn, string $table, string $column, string $definition): void
{
    $tableEsc = $conn->real_escape_string($table);
    $columnEsc = $conn->real_escape_string($column);
    $result = $conn->query("SHOW COLUMNS FROM `{$tableEsc}` LIKE '{$columnEsc}'");
    if ($result && $result->num_rows > 0) {
        $result->free();
        return;
    }
    $conn->query("ALTER TABLE `{$tableEsc}` ADD COLUMN {$definition}");
}

function ivNormalizeRejectReason(string $reason): string
{
    return $reason === 'wrong_serial' ? 'wrong_serial' : 'unreadable';
}

function ivIsCrmTestPhone($conn, string $phone): bool
{
    if (!ensureTestPhonesTable($conn)) {
        return false;
    }
    $normalized = normalizeIranPhone($phone);
    if ($normalized === '') {
        return false;
    }
    $stmt = $conn->prepare('SELECT 1 FROM crm_test_phones WHERE phone = ? LIMIT 1');
    if (!$stmt) {
        return false;
    }
    $stmt->bind_param('s', $normalized);
    $stmt->execute();
    $result = $stmt->get_result();
    $found = $result && $result->num_rows > 0;
    $stmt->close();
    return $found;
}

function ensureImageVerificationSchema($conn): bool
{
    $ok = $conn->query("CREATE TABLE IF NOT EXISTS image_verification_settings (
        id INT NOT NULL PRIMARY KEY DEFAULT 1,
        enable_s TINYINT(1) NOT NULL DEFAULT 0,
        enable_m TINYINT(1) NOT NULL DEFAULT 0,
        auto_verify_threshold INT NOT NULL DEFAULT 85,
        sms_link_request TEXT NULL,
        sms_verified TEXT NULL,
        sms_rejected TEXT NULL,
        capture_base_url VARCHAR(255) NOT NULL DEFAULT '" . IV_CAPTURE_BASE_DEFAULT . "'
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");

    if (!$ok) {
        return false;
    }

    $conn->query("INSERT INTO image_verification_settings (id) VALUES (1) ON DUPLICATE KEY UPDATE id = id");

    $defaults = [
        'sms_link_request' => IV_SMS_LINK_DEFAULT,
        'sms_verified' => IV_SMS_VERIFIED_DEFAULT,
        'sms_rejected' => IV_SMS_REJECTED_DEFAULT,
    ];
    foreach ($defaults as $col => $val) {
        $esc = $conn->real_escape_string($val);
        $conn->query("UPDATE image_verification_settings SET {$col} = '{$esc}' WHERE id = 1 AND ({$col} IS NULL OR {$col} = '')");
    }

    ivEnsureColumn($conn, 'image_verification_settings', 'testers_only', 'testers_only TINYINT(1) NOT NULL DEFAULT 0');
    ivEnsureColumn($conn, 'image_verification_settings', 'sms_rejected_unreadable', 'sms_rejected_unreadable TEXT NULL');
    ivEnsureColumn($conn, 'image_verification_settings', 'sms_rejected_wrong_serial', 'sms_rejected_wrong_serial TEXT NULL');

    $rejectDefaults = [
        'sms_rejected_unreadable' => IV_SMS_REJECTED_UNREADABLE_DEFAULT,
        'sms_rejected_wrong_serial' => IV_SMS_REJECTED_WRONG_SERIAL_DEFAULT,
    ];
    foreach ($rejectDefaults as $col => $val) {
        $esc = $conn->real_escape_string($val);
        $conn->query("UPDATE image_verification_settings SET {$col} = '{$esc}' WHERE id = 1 AND ({$col} IS NULL OR {$col} = '')");
    }

    $verificationsOk = (bool) $conn->query("CREATE TABLE IF NOT EXISTS image_verifications (
        id INT AUTO_INCREMENT PRIMARY KEY,
        serial VARCHAR(50) NOT NULL,
        phone VARCHAR(20) NOT NULL,
        code_type ENUM('S','M') NOT NULL,
        source_table ENUM('old_serials','Mcode') NOT NULL,
        confirm_column CHAR(1) NOT NULL DEFAULT 'c',
        km INT NULL,
        city VARCHAR(100) NULL,
        image_path VARCHAR(500) NULL,
        image_mime VARCHAR(50) NULL,
        status ENUM('pending_capture','pending_review','auto_verified','verified','rejected') NOT NULL DEFAULT 'pending_capture',
        token VARCHAR(64) NOT NULL,
        token_expires_at DATETIME NOT NULL,
        ocr_confidence DECIMAL(5,2) NULL,
        ocr_detected_text VARCHAR(255) NULL,
        last_reject_at DATETIME NULL,
        reject_count INT NOT NULL DEFAULT 0,
        submitted_at DATETIME NULL,
        verified_at DATETIME NULL,
        verified_by VARCHAR(100) NULL,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        UNIQUE KEY uq_token (token),
        KEY idx_status (status),
        KEY idx_serial (serial),
        KEY idx_phone (phone)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");

    if (!$verificationsOk) {
        return false;
    }

    ivEnsureColumn($conn, 'image_verifications', 'last_reject_reason', "last_reject_reason VARCHAR(32) NULL");

    return true;
}

function getImageVerificationSettings($conn): array
{
    ensureImageVerificationSchema($conn);
    $result = $conn->query('SELECT * FROM image_verification_settings WHERE id = 1 LIMIT 1');
    if (!$result || $result->num_rows === 0) {
        return [
            'enable_s' => 0,
            'enable_m' => 0,
            'testers_only' => 0,
            'auto_verify_threshold' => 85,
            'sms_link_request' => IV_SMS_LINK_DEFAULT,
            'sms_verified' => IV_SMS_VERIFIED_DEFAULT,
            'sms_rejected' => IV_SMS_REJECTED_DEFAULT,
            'sms_rejected_unreadable' => IV_SMS_REJECTED_UNREADABLE_DEFAULT,
            'sms_rejected_wrong_serial' => IV_SMS_REJECTED_WRONG_SERIAL_DEFAULT,
            'capture_base_url' => IV_CAPTURE_BASE_DEFAULT,
            'toggles_locked' => ivAreSerialTogglesLocked(),
        ];
    }
    $row = $result->fetch_assoc();
    $result->free();
    $settings = [
        'enable_s' => (int) ($row['enable_s'] ?? 0),
        'enable_m' => (int) ($row['enable_m'] ?? 0),
        'testers_only' => (int) ($row['testers_only'] ?? 0),
        'auto_verify_threshold' => (int) ($row['auto_verify_threshold'] ?? 85),
        'sms_link_request' => (string) ($row['sms_link_request'] ?? IV_SMS_LINK_DEFAULT),
        'sms_verified' => (string) ($row['sms_verified'] ?? IV_SMS_VERIFIED_DEFAULT),
        'sms_rejected' => (string) ($row['sms_rejected'] ?? IV_SMS_REJECTED_DEFAULT),
        'sms_rejected_unreadable' => (string) ($row['sms_rejected_unreadable'] ?? IV_SMS_REJECTED_UNREADABLE_DEFAULT),
        'sms_rejected_wrong_serial' => (string) ($row['sms_rejected_wrong_serial'] ?? IV_SMS_REJECTED_WRONG_SERIAL_DEFAULT),
        'capture_base_url' => (string) ($row['capture_base_url'] ?? IV_CAPTURE_BASE_DEFAULT),
        'toggles_locked' => ivAreSerialTogglesLocked(),
    ];
    if (ivAreSerialTogglesLocked()) {
        $settings['enable_s'] = 0;
        $settings['enable_m'] = 0;
    }
    return $settings;
}

function saveImageVerificationSettings($conn, array $data): bool
{
    ensureImageVerificationSchema($conn);
    $stmt = $conn->prepare('UPDATE image_verification_settings SET
        enable_s = ?,
        enable_m = ?,
        testers_only = ?,
        auto_verify_threshold = ?,
        sms_link_request = ?,
        sms_verified = ?,
        sms_rejected = ?,
        sms_rejected_unreadable = ?,
        sms_rejected_wrong_serial = ?,
        capture_base_url = ?
        WHERE id = 1');
    if (!$stmt) {
        return false;
    }
    $enableS = (int) ($data['enable_s'] ?? 0);
    $enableM = (int) ($data['enable_m'] ?? 0);
    $testersOnly = (int) ($data['testers_only'] ?? 0);
    if (ivAreSerialTogglesLocked()) {
        $enableS = 0;
        $enableM = 0;
    }
    $threshold = max(0, min(100, (int) ($data['auto_verify_threshold'] ?? 85)));
    $linkReq = (string) ($data['sms_link_request'] ?? IV_SMS_LINK_DEFAULT);
    $verified = (string) ($data['sms_verified'] ?? IV_SMS_VERIFIED_DEFAULT);
    $rejected = (string) ($data['sms_rejected'] ?? IV_SMS_REJECTED_DEFAULT);
    $rejectedUnreadable = (string) ($data['sms_rejected_unreadable'] ?? IV_SMS_REJECTED_UNREADABLE_DEFAULT);
    $rejectedWrongSerial = (string) ($data['sms_rejected_wrong_serial'] ?? IV_SMS_REJECTED_WRONG_SERIAL_DEFAULT);
    $baseUrl = (string) ($data['capture_base_url'] ?? IV_CAPTURE_BASE_DEFAULT);
    $stmt->bind_param(
        'iiiissssss',
        $enableS,
        $enableM,
        $testersOnly,
        $threshold,
        $linkReq,
        $verified,
        $rejected,
        $rejectedUnreadable,
        $rejectedWrongSerial,
        $baseUrl
    );
    $ok = $stmt->execute();
    $stmt->close();
    return $ok;
}

function ivResolveCodeMeta(string $serial): array
{
    $serial = strtoupper(trim($serial));
    if (isMprefixSerial($serial) || isSToMRange($serial)) {
        return [
            'code_type' => 'M',
            'source_table' => isSToMRange($serial) ? 'old_serials' : 'Mcode',
            'confirm_column' => 'm',
        ];
    }
    return [
        'code_type' => 'S',
        'source_table' => 'old_serials',
        'confirm_column' => 'c',
    ];
}

function isImageVerificationEnabledForSerial($conn, string $serial): bool
{
    if (ivAreSerialTogglesLocked()) {
        return false;
    }
    $settings = getImageVerificationSettings($conn);
    $serial = strtoupper(trim($serial));
    if (isMprefixSerial($serial) || isSToMRange($serial)) {
        return (bool) $settings['enable_m'];
    }
    return (bool) $settings['enable_s'];
}

function ivGenerateToken(): string
{
    return bin2hex(random_bytes(32));
}

function ivBuildCaptureUrl(string $baseUrl, string $token): string
{
    $base = rtrim($baseUrl, '/') . '/';
    return $base . '?t=' . urlencode($token);
}

function ivApplyTemplate(string $template, string $serial, string $link): string
{
    return str_replace(['{serial}', '{link}'], [$serial, $link], $template);
}

/** Project root (public_html): parent of crm/ */
function ivGetProjectRoot(): string
{
    return dirname(__DIR__);
}

/** warrantyDT app root: public_html/warrantyDT/ (same path locally and on server). */
function ivGetWarrantyRoot(): string
{
    return ivGetProjectRoot() . DIRECTORY_SEPARATOR . 'warrantyDT';
}

function ivGetUploadDir(): string
{
    return ivGetWarrantyRoot() . '/uploads/verifications';
}

function ivEnsureUploadDir(): bool
{
    $dir = ivGetUploadDir();
    if (!is_dir($dir)) {
        if (!mkdir($dir, 0755, true)) {
            return false;
        }
    }
    $htaccess = $dir . '/.htaccess';
    if (!file_exists($htaccess)) {
        file_put_contents($htaccess, "Deny from all\n");
    }
    return true;
}

function createVerificationRequest($conn, string $serial, string $phone, array $meta = []): ?array
{
    ensureImageVerificationSchema($conn);
    $settings = getImageVerificationSettings($conn);
    $codeMeta = ivResolveCodeMeta($serial);
    $token = ivGenerateToken();
    $expires = date('Y-m-d H:i:s', strtotime('+' . IV_TOKEN_TTL_DAYS . ' days'));

    $stmtCancel = $conn->prepare("UPDATE image_verifications SET status = 'rejected'
        WHERE serial = ? AND status IN ('pending_capture','pending_review')");
    if ($stmtCancel) {
        $stmtCancel->bind_param('s', $serial);
        $stmtCancel->execute();
        $stmtCancel->close();
    }

    $km = isset($meta['km']) ? (int) $meta['km'] : 0;
    $city = isset($meta['city']) ? (string) $meta['city'] : '';
    $confirmColumn = $meta['confirm_column'] ?? $codeMeta['confirm_column'];

    $sql = "INSERT INTO image_verifications
        (serial, phone, code_type, source_table, confirm_column, km, city, status, token, token_expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'pending_capture', ?, ?)";
    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        return null;
    }
    $stmt->bind_param(
        'sssssisss',
        $serial,
        $phone,
        $codeMeta['code_type'],
        $codeMeta['source_table'],
        $confirmColumn,
        $km,
        $city,
        $token,
        $expires
    );
    if (!$stmt->execute()) {
        $stmt->close();
        return null;
    }
    $id = (int) $stmt->insert_id;
    $stmt->close();

    $url = ivBuildCaptureUrl($settings['capture_base_url'], $token);
    return ['id' => $id, 'token' => $token, 'url' => $url];
}

function sendCaptureLinkSms($conn, string $phone, string $serial, string $url): void
{
    $settings = getImageVerificationSettings($conn);
    $message = ivApplyTemplate($settings['sms_link_request'], $serial, $url);
    ivSendSms($phone, $message);
}

function sendVerifiedSms($conn, string $phone, string $serial): void
{
    $settings = getImageVerificationSettings($conn);
    $message = ivApplyTemplate($settings['sms_verified'], $serial, '');
    ivSendSms($phone, $message);
}

function sendRejectSms($conn, string $phone, string $serial, string $url, string $reason = 'unreadable'): void
{
    $settings = getImageVerificationSettings($conn);
    $reason = ivNormalizeRejectReason($reason);
    if ($reason === 'wrong_serial') {
        $template = $settings['sms_rejected_wrong_serial'] ?: $settings['sms_rejected'] ?: IV_SMS_REJECTED_WRONG_SERIAL_DEFAULT;
    } else {
        $template = $settings['sms_rejected_unreadable'] ?: $settings['sms_rejected'] ?: IV_SMS_REJECTED_UNREADABLE_DEFAULT;
    }
    $message = ivApplyTemplate($template, $serial, $url);
    ivSendSms($phone, $message);
}

function getVerificationByToken($conn, string $token): ?array
{
    ensureImageVerificationSchema($conn);
    $stmt = $conn->prepare('SELECT * FROM image_verifications WHERE token = ? LIMIT 1');
    if (!$stmt) {
        return null;
    }
    $stmt->bind_param('s', $token);
    $stmt->execute();
    $result = $stmt->get_result();
    $row = $result ? $result->fetch_assoc() : null;
    $stmt->close();
    return $row ?: null;
}

function getVerificationById($conn, int $id): ?array
{
    ensureImageVerificationSchema($conn);
    $stmt = $conn->prepare('SELECT * FROM image_verifications WHERE id = ? LIMIT 1');
    if (!$stmt) {
        return null;
    }
    $stmt->bind_param('i', $id);
    $stmt->execute();
    $result = $stmt->get_result();
    $row = $result ? $result->fetch_assoc() : null;
    $stmt->close();
    return $row ?: null;
}

function ivTokenIsValid(array $row): bool
{
    if (in_array($row['status'], ['auto_verified', 'verified'], true)) {
        return false;
    }
    if (empty($row['token_expires_at'])) {
        return false;
    }
    return strtotime($row['token_expires_at']) >= time();
}

function ivNormalizeSerialText(string $text): string
{
    $text = strtoupper(trim($text));
    $text = preg_replace('/[^A-Z0-9]/', '', $text);
    return $text;
}

function ivLevenshtein(string $a, string $b): int
{
    $lenA = strlen($a);
    $lenB = strlen($b);
    if ($lenA === 0) {
        return $lenB;
    }
    if ($lenB === 0) {
        return $lenA;
    }
    $matrix = [];
    for ($i = 0; $i <= $lenB; $i++) {
        $matrix[$i] = [$i];
    }
    for ($j = 0; $j <= $lenA; $j++) {
        $matrix[0][$j] = $j;
    }
    for ($i = 1; $i <= $lenB; $i++) {
        for ($j = 1; $j <= $lenA; $j++) {
            $cost = ($b[$i - 1] === $a[$j - 1]) ? 0 : 1;
            $matrix[$i][$j] = min(
                $matrix[$i - 1][$j] + 1,
                $matrix[$i][$j - 1] + 1,
                $matrix[$i - 1][$j - 1] + $cost
            );
        }
    }
    return $matrix[$lenB][$lenA];
}

function computeOcrMatchScore(string $expectedSerial, string $detectedText): float
{
    $expected = ivNormalizeSerialText($expectedSerial);
    $detected = ivNormalizeSerialText($detectedText);
    if ($expected === '' || $detected === '') {
        return 0.0;
    }
    if ($detected === $expected) {
        return 100.0;
    }
    if (strpos($detected, $expected) !== false || strpos($expected, $detected) !== false) {
        return 95.0;
    }
    $dist = ivLevenshtein($expected, $detected);
    if ($dist <= 1) {
        return 90.0;
    }
    if ($dist <= 2) {
        return 75.0;
    }
    return max(0.0, 100.0 - ($dist * 15));
}

function ivGetMcodeTotalScore($conn, string $phone): int
{
    $stmt = $conn->prepare("SELECT COALESCE(SUM(score), 0) AS total FROM Mcode WHERE phone = ? AND phone != '0' AND score > 0");
    if (!$stmt) {
        return 0;
    }
    $stmt->bind_param('s', $phone);
    $stmt->execute();
    $result = $stmt->get_result();
    $total = 0;
    if ($result && $row = $result->fetch_assoc()) {
        $total = (int) $row['total'];
    }
    $stmt->close();
    return $total;
}

function ivToPersianDigits($value): string
{
    $englishDigits = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];
    $persianDigits = ['۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'];
    return str_replace($englishDigits, $persianDigits, (string) $value);
}

function ivAppendScoreSuffix($conn, string $message, string $phone): string
{
    $total = ivGetMcodeTotalScore($conn, $phone);
    if ($total <= 0) {
        return $message;
    }
    return rtrim($message) . ' شما تا این لحظه ' . ivToPersianDigits($total) . ' امتیاز دارید.';
}

function ivSendConfirmSms($conn, string $phone, string $column = 'c', bool $appendScore = false): void
{
    $allowed = ['a', 'b', 'c', 'm'];
    if (!in_array($column, $allowed, true)) {
        $column = 'c';
    }
    $result = $conn->query("SELECT {$column} FROM main_sms LIMIT 1");
    if (!$result || $result->num_rows === 0) {
        return;
    }
    $row = $result->fetch_assoc();
    $message = (string) ($row[$column] ?? '');
    if ($message === '') {
        return;
    }
    if ($appendScore || $column === 'm') {
        $message = ivAppendScoreSuffix($conn, $message, $phone);
    }
    ivSendSms($phone, $message);
}

function ivRegistrationLotteryScore($conn, string $serial): int
{
    return isImageVerificationEnabledForSerial($conn, $serial) ? 0 : STOM_MCODE_SCORE;
}

function ivGrantMcodeLotteryScore($conn, string $serial, string $phone): bool
{
    if (!isMprefixSerial($serial) || isSToMRange($serial)) {
        return false;
    }
    if ($phone === '' || $phone === '0') {
        return false;
    }

    $score = STOM_MCODE_SCORE;
    $stmt = $conn->prepare('UPDATE Mcode SET score = ? WHERE serial = ? AND phone = ?');
    if (!$stmt) {
        return false;
    }
    $stmt->bind_param('iss', $score, $serial, $phone);
    $ok = $stmt->execute() && $stmt->affected_rows > 0;
    $stmt->close();

    return $ok;
}

function completeRegistrationAfterVerify($conn, array $row, string $verifiedBy): bool
{
    $serial = $row['serial'];
    $phone = $row['phone'];
    $confirmColumn = $row['confirm_column'] ?? 'c';

    if (isSToMRange($serial)) {
        $city = $row['city'] ?? '';
        syncSToMSerialToMcode($conn, $serial, $phone, $city);
    } elseif (isMprefixSerial($serial)) {
        ivGrantMcodeLotteryScore($conn, $serial, $phone);
    }

    $status = 'verified';
    $now = date('Y-m-d H:i:s');
    $stmt = $conn->prepare('UPDATE image_verifications SET status = ?, verified_at = ?, verified_by = ? WHERE id = ?');
    if (!$stmt) {
        return false;
    }
    $stmt->bind_param('sssi', $status, $now, $verifiedBy, $row['id']);
    $stmt->execute();
    $stmt->close();

    sendVerifiedSms($conn, $phone, $serial);

    return true;
}

function maybeDeferRegistrationForImageVerify($conn, string $serial, string $phone, array $meta = []): bool
{
    if (!isImageVerificationEnabledForSerial($conn, $serial)) {
        return false;
    }
    $settings = getImageVerificationSettings($conn);
    if (!empty($settings['testers_only']) && !ivIsCrmTestPhone($conn, $phone)) {
        return false;
    }
    $req = createVerificationRequest($conn, $serial, $phone, $meta);
    if (!$req) {
        return false;
    }
    sendCaptureLinkSms($conn, $phone, $serial, $req['url']);
    return true;
}

function rotateTokenForResubmit($conn, array $row, string $reason = 'unreadable'): ?array
{
    $settings = getImageVerificationSettings($conn);
    $token = ivGenerateToken();
    $expires = date('Y-m-d H:i:s', strtotime('+' . IV_TOKEN_TTL_DAYS . ' days'));
    $now = date('Y-m-d H:i:s');
    $rejectCount = ((int) ($row['reject_count'] ?? 0)) + 1;
    $reason = ivNormalizeRejectReason($reason);

    $stmt = $conn->prepare("UPDATE image_verifications SET
        token = ?,
        token_expires_at = ?,
        status = 'pending_capture',
        last_reject_at = ?,
        last_reject_reason = ?,
        reject_count = ?
        WHERE id = ?");
    if (!$stmt) {
        return null;
    }
    $stmt->bind_param('ssssii', $token, $expires, $now, $reason, $rejectCount, $row['id']);
    if (!$stmt->execute()) {
        $stmt->close();
        return null;
    }
    $stmt->close();

    $url = ivBuildCaptureUrl($settings['capture_base_url'], $token);
    return ['token' => $token, 'url' => $url];
}

function requireImageVerificationSession(int $minRole = 3): void
{
    session_start();
    if (!isset($_SESSION['loggedin']) || $_SESSION['loggedin'] !== true) {
        http_response_code(403);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['success' => false, 'message' => 'دسترسی غیرمجاز'], JSON_UNESCAPED_UNICODE);
        exit;
    }
    $role = (int) ($_SESSION['role'] ?? 0);
    if ($role < $minRole) {
        http_response_code(403);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['success' => false, 'message' => 'دسترسی غیرمجاز'], JSON_UNESCAPED_UNICODE);
        exit;
    }
}
