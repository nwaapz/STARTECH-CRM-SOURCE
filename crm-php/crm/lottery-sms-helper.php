<?php

const WINNER_SMS_DEFAULT = 'لطفا با پشتیبانی استارتک تماس بگیرید. خبر خوش منتظر شماست.  09360590157';

function ensureWinnerSmsColumn($conn): bool
{
    $result = $conn->query("SHOW COLUMNS FROM main_sms LIKE 'winner_sms'");
    if ($result && $result->num_rows > 0) {
        $result->free();
        return true;
    }
    if ($result) {
        $result->free();
    }

    if (!$conn->query('ALTER TABLE main_sms ADD COLUMN winner_sms TEXT NULL')) {
        return false;
    }

    $default = $conn->real_escape_string(WINNER_SMS_DEFAULT);
    $conn->query(
        "UPDATE main_sms SET winner_sms = '{$default}' WHERE id = 1 AND (winner_sms IS NULL OR winner_sms = '')"
    );

    return true;
}

function getWinnerSmsMessage($conn): string
{
    if (!ensureWinnerSmsColumn($conn)) {
        return WINNER_SMS_DEFAULT;
    }

    $result = $conn->query('SELECT winner_sms FROM main_sms WHERE id = 1 LIMIT 1');
    if (!$result || $result->num_rows === 0) {
        if ($result) {
            $result->free();
        }
        return WINNER_SMS_DEFAULT;
    }

    $row = $result->fetch_assoc();
    $result->free();

    $message = trim((string) ($row['winner_sms'] ?? ''));
    return $message !== '' ? $message : WINNER_SMS_DEFAULT;
}

function saveWinnerSmsMessage($conn, string $message): bool
{
    if (!ensureWinnerSmsColumn($conn)) {
        return false;
    }

    $stmt = $conn->prepare('UPDATE main_sms SET winner_sms = ? WHERE id = 1');
    if (!$stmt) {
        return false;
    }

    $stmt->bind_param('s', $message);
    $ok = $stmt->execute();
    $stmt->close();

    return $ok;
}

function sendWinnerSms(string $phone, string $message): void
{
    ini_set('soap.wsdl_cache_enabled', '0');
    $sms = new SoapClient('http://api.payamak-panel.com/post/Send.asmx?wsdl', [
        'encoding' => 'UTF-8',
    ]);
    $data = [
        'username' => '09121777039',
        'password' => '4thvahdati@FB',
        'to' => [$phone],
        'from' => '20001390',
        'text' => '-' . $message . '-',
        'isflash' => false,
    ];
    $sms->SendSimpleSMS($data)->SendSimpleSMSResult;
}

function requireSmsSettingsSession(int $minRole = 3): void
{
    session_start();
    if (!isset($_SESSION['loggedin']) || $_SESSION['loggedin'] !== true) {
        http_response_code(403);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['success' => false, 'message' => 'دسترسی غیرمجاز — ابتدا وارد CRM شوید'], JSON_UNESCAPED_UNICODE);
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
