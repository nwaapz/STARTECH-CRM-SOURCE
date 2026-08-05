<?php
$SMS_DEBUG = isset($_GET['debug']) && $_GET['debug'] === '1';
$smsDebugTrace = [];

function smsDebug($message)
{
    global $SMS_DEBUG, $smsDebugTrace;
    if ($SMS_DEBUG) {
        $smsDebugTrace[] = $message;
    }
}

register_shutdown_function(function () {
    global $SMS_DEBUG, $smsDebugTrace;
    $err = error_get_last();
    if ($err && in_array($err['type'], [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR], true)) {
        error_log('[SMS.php fatal] ' . $err['message'] . ' in ' . $err['file'] . ':' . $err['line']);
        if ($SMS_DEBUG) {
            $smsDebugTrace[] = 'FATAL: ' . $err['message'] . ' in ' . $err['file'] . ':' . $err['line'];
        }
    }
    if ($SMS_DEBUG) {
        if (!headers_sent()) {
            header('Content-Type: text/plain; charset=utf-8');
        }
        echo implode("\n", $smsDebugTrace);
    }
});

// jdf.php is unused here; keep optional so a missing file cannot kill inbound SMS.
if (is_file(__DIR__ . '/jdf.php')) {
    require_once __DIR__ . '/jdf.php';
}

/**
 * On production, public_html/crm/ is the Vue SPA — NOT the PHP helpers.
 * Load helpers from sms_lib/ (upload next to SMS.php) or crm-php/crm/ in this repo.
 */
$smsLibCandidates = [
    __DIR__ . '/sms_lib',
    __DIR__ . '/crm-php/crm',
];
$crmDir = null;
foreach ($smsLibCandidates as $candidate) {
    if (is_file($candidate . '/serial-utils.php') && is_file($candidate . '/lottery-sync.php')) {
        $crmDir = $candidate;
        break;
    }
}
smsDebug('crmDir=' . ($crmDir ?: 'NOT FOUND'));

if ($crmDir === null) {
    error_log('[SMS.php] missing sms_lib (serial-utils.php / lottery-sync.php). Upload sms_lib/ next to SMS.php');
    smsDebug('FATAL: upload sms_lib/ folder next to SMS.php (contains serial-utils.php, lottery-sync.php)');
    if (!$SMS_DEBUG) {
        http_response_code(500);
        echo 'SMS bootstrap failed: missing sms_lib';
    }
    exit;
}

require_once $crmDir . '/serial-utils.php';
require_once $crmDir . '/lottery-sync.php';

// Older live sms_lib may predate seller helpers — define stubs so SMS never fatals.
if (!function_exists('smsIsSellerCategory')) {
    function smsIsSellerCategory($category): bool
    {
        return trim((string) $category) === 'seller_to_end_user';
    }
}
if (!function_exists('smsOldSerialIsSellerPipeline')) {
    function smsOldSerialIsSellerPipeline($serial, $row = null): bool
    {
        if (function_exists('isSToMRange') && isSToMRange($serial)) {
            return true;
        }
        if (is_array($row) && smsIsSellerCategory($row['category'] ?? '')) {
            return true;
        }
        return false;
    }
}

$ivHelper = $crmDir . '/image-verification-helper.php';
if (is_file($ivHelper)) {
    require_once $ivHelper;
    smsDebug('image-verification: loaded');
} else {
    // Soft stubs so warranty SMS still works if IV helper is not deployed next to SMS.php
    if (!function_exists('isImageVerificationEnabledForSerial')) {
        function isImageVerificationEnabledForSerial($conn, string $serial): bool
        {
            return false;
        }
    }
    if (!function_exists('ivRegistrationLotteryScore')) {
        function ivRegistrationLotteryScore($conn, string $serial): int
        {
            return defined('STOM_MCODE_SCORE') ? (int) STOM_MCODE_SCORE : 10;
        }
    }
    if (!function_exists('ivGrantMcodeLotteryScore')) {
        function ivGrantMcodeLotteryScore($conn, string $serial, string $phone): bool
        {
            return false;
        }
    }
    if (!function_exists('maybeDeferRegistrationForImageVerify')) {
        function maybeDeferRegistrationForImageVerify($conn, string $serial, string $phone, array $meta = []): bool
        {
            return false;
        }
    }
    smsDebug('image-verification: stubs (helper not found)');
}
date_default_timezone_set("Asia/Tehran");

/** Portal URL appended to error SMS so users can register/report via web instead of SMS */
define("PORTAL_SMS_SUFFIX", " ثبت و استعلام آنلاین: startechgroup.ir/warrantyDT/");
define("MCODE_SCORE_PER_SERIAL", 10);

function smsRequestParam(array $keys, $default = '')
{
    foreach ($keys as $key) {
        if (isset($_GET[$key]) && $_GET[$key] !== '') {
            return $_GET[$key];
        }
        if (isset($_POST[$key]) && $_POST[$key] !== '') {
            return $_POST[$key];
        }
    }
    return $default;
}

function smsNormalizePhone($phone)
{
    $digits = preg_replace('/\D/', '', trim((string) $phone));
    if ($digits === '') {
        return '';
    }
    if (strpos($digits, '98') === 0 && strlen($digits) >= 12) {
        $digits = '0' . substr($digits, 2);
    }
    if (strlen($digits) === 10 && isset($digits[0]) && $digits[0] === '9') {
        $digits = '0' . $digits;
    }
    return $digits;
}

function smsInboundLog($message)
{
    $line = '[' . date('Y-m-d H:i:s') . '] ' . $message . "\n";
    @file_put_contents(__DIR__ . '/sms_inbound_log.txt', $line, FILE_APPEND);
}

$servername = "localhost";
$username = "startech_sms";
$password = "101010";
$dbname = "startech_sms"; // ← Replace with your actual DB name

// Create connection
$conn = mysqli_connect($servername, $username, $password, $dbname);

if (!$conn) {
    $err = mysqli_connect_error();
    error_log('[SMS.php] DB connect failed: ' . $err);
    smsDebug('FATAL db: ' . $err);
    smsInboundLog('DB connect failed: ' . $err);
    if (!$SMS_DEBUG) {
        http_response_code(500);
        echo 'DB connection failed';
    }
    exit;
}
$conn->set_charset("utf8");

// Melipayamak URL forward may use body/from, or message/text + sender depending on panel config.
$body = smsRequestParam(['body', 'message', 'text', 'msg', 'MessageBody']);
$body = strtr((string) $body, ['%20' => ' ', '+' => ' ']);
$body = trim(urldecode($body));

$rawFrom = smsRequestParam(['from', 'sender', 'phone', 'From', 'Sender']);
$user = smsNormalizePhone($rawFrom);

smsInboundLog('hit method=' . ($_SERVER['REQUEST_METHOD'] ?? '') . ' query=' . ($_SERVER['QUERY_STRING'] ?? '') . ' body=' . $body . ' from_raw=' . $rawFrom . ' from=' . $user);
smsDebug('ok: script started');
smsDebug('body=' . $body);
smsDebug('phone_raw=' . $rawFrom);
smsDebug('phone=' . $user);

if ($user !== '' && $body !== '') {
    smsLogCommunication($conn, $user, 'incoming', $body, 'received');
}

if ($user === '') {
    smsDebug('FATAL: empty/invalid from');
    smsInboundLog('invalid from');
    if (!$SMS_DEBUG) {
        http_response_code(400);
        echo 'invalid from';
    }
    exit;
}

if (empty($body)) {
    SendMessage($user, smsResolveMessage($conn, 'empty_serial', smsPortalValues()));
    exit;
}

// Check for Persian letters and digits (Persian or English)
// Convert Persian digits to English digits in the body
$body = convertPersianToEnglishDigits($body);

function convertPersianToEnglishDigits($string)
{
    $persianDigits = ['۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'];
    $englishDigits = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];
    return str_replace($persianDigits, $englishDigits, $string);
}

function logSToMRouting($serial, $phone, $event, $context = [])
{
    $serial = strtoupper(trim($serial));
    if (!preg_match('/^S(\d+)$/', $serial, $m)) {
        return;
    }
    $num = (int) $m[1];
    if ($num < 440000 || $num > 450000) {
        return;
    }
    $ctx = $context ? ' ' . json_encode($context, JSON_UNESCAPED_UNICODE) : '';
    error_log("[S-to-M] {$event} serial={$serial} phone={$phone}{$ctx}");
}

function smsHasColumn($conn, $table, $col)
{
    static $cache = [];
    $key = $table . '.' . $col;
    if (array_key_exists($key, $cache)) {
        return $cache[$key];
    }
    $t = $conn->real_escape_string($table);
    $c = $conn->real_escape_string($col);
    $r = $conn->query("SHOW COLUMNS FROM `$t` LIKE '$c'");
    $cache[$key] = $r && $r->num_rows > 0;
    return $cache[$key];
}

/**
 * Candidate forms for panel serials (J100 vs J0100 pad, case).
 * @return string[]
 */
function smsSerialLookupCandidates($serial)
{
    $serial = strtoupper(trim((string) $serial));
    if ($serial === '') {
        return [];
    }
    $out = [$serial];
    if (preg_match('/^([A-Z]+)(\d+)$/', $serial, $m)) {
        $prefix = $m[1];
        $num = (int) $m[2];
        $raw = (string) $num;
        $out[] = $prefix . $raw;
        for ($w = strlen($raw); $w <= 8; $w++) {
            $out[] = $prefix . str_pad($raw, $w, '0', STR_PAD_LEFT);
        }
    }
    return array_values(array_unique($out));
}

/**
 * Warranty tables that exist on this DB (old_serials / new_serials).
 * @return string[]
 */
function smsWarrantyTables($conn)
{
    static $tables = null;
    if ($tables !== null) {
        return $tables;
    }
    $tables = [];
    foreach (['old_serials', 'new_serials'] as $t) {
        $check = @$conn->query("SHOW TABLES LIKE '" . $conn->real_escape_string($t) . "'");
        if ($check && $check->num_rows > 0) {
            $tables[] = $t;
        }
    }
    return $tables;
}

/**
 * Find unused/used warranty serial row created by panel (old_serials / new_serials).
 * Always returns the DB-stored serial string for later UPDATEs.
 */
function smsFindWarrantySerialRow($conn, $serial)
{
    $candidates = smsSerialLookupCandidates($serial);
    if ($candidates === []) {
        return null;
    }
    foreach (smsWarrantyTables($conn) as $table) {
        $placeholders = implode(',', array_fill(0, count($candidates), '?'));
        $sql = "SELECT * FROM `$table` WHERE UPPER(serial) IN ($placeholders) LIMIT 1";
        $stmt = $conn->prepare($sql);
        if (!$stmt) {
            continue;
        }
        $types = str_repeat('s', count($candidates));
        $stmt->bind_param($types, ...$candidates);
        $stmt->execute();
        $res = $stmt->get_result();
        $row = $res ? $res->fetch_assoc() : null;
        $stmt->close();
        if (is_array($row)) {
            $row['_table'] = $table;
            return $row;
        }
    }
    return null;
}

/**
 * Whether a warranty row still needs city/km (or city-only for seller pipeline).
 * Seller rows never store km — treating km=0 as "pending" forever would steal
 * later client city+km SMS and send success without saving the client fields.
 */
function smsRegistrationFieldsIncomplete(array $row): bool
{
    $serial = (string) ($row['serial'] ?? '');
    $city = trim((string) ($row['city'] ?? ''));
    if (smsOldSerialIsSellerPipeline($serial, $row)) {
        return $city === '';
    }
    $km = $row['km'] ?? null;
    $kmMissing = ($km === null || $km === '' || (int) $km === 0);
    return $city === '' || $kmMissing;
}

/**
 * Find a pending (incomplete) registration for this phone across warranty tables.
 * Modes:
 *   - client_km: end-user missing city and/or km, or seller still missing city
 *   - seller_city: seller pipeline, city still empty
 * Returns newest match with _table set, or null.
 */
function smsFindPendingRegistrationByPhone($conn, $phone, $mode = 'client_km')
{
    $phone = smsNormalizePhone($phone);
    if ($phone === '') {
        return null;
    }
    $best = null;
    $bestRank = null;
    foreach (smsWarrantyTables($conn) as $table) {
        // `time` in these tables is date-only (Y-m-d): several test registrations on
        // the same day cannot be ordered by it. sync_updated_ms is bumped at the exact
        // phone-bind moment, so prefer it — otherwise a city+km reply can be saved onto
        // an older incomplete serial bound to the same phone.
        $orderBy = smsHasColumn($conn, $table, 'sync_updated_ms')
            ? 'ORDER BY COALESCE(NULLIF(sync_updated_ms, 0), 0) DESC, time DESC, id DESC'
            : 'ORDER BY time DESC, id DESC';
        // Fetch recent bound rows and filter incompleteness in PHP so completed
        // seller registrations (city set, km always 0) are not treated as pending.
        $sql = "SELECT * FROM `$table` WHERE phone = ? AND phone != '' AND phone != '0'
                $orderBy LIMIT 20";
        $stmt = $conn->prepare($sql);
        if (!$stmt) {
            continue;
        }
        $stmt->bind_param('s', $phone);
        if (!$stmt->execute()) {
            $stmt->close();
            continue;
        }
        $res = $stmt->get_result();
        $rowMatch = null;
        while ($res && ($row = $res->fetch_assoc())) {
            if ($mode === 'seller_city') {
                if (!smsOldSerialIsSellerPipeline($row['serial'] ?? '', $row)) {
                    continue;
                }
                if (trim((string) ($row['city'] ?? '')) !== '') {
                    continue;
                }
            } elseif (!smsRegistrationFieldsIncomplete($row)) {
                continue;
            }
            $row['_table'] = $table;
            $rowMatch = $row;
            break;
        }
        $stmt->close();
        if (!is_array($rowMatch)) {
            continue;
        }
        $syncMs = isset($rowMatch['sync_updated_ms']) ? (int) $rowMatch['sync_updated_ms'] : 0;
        if ($syncMs <= 0) {
            $ts = strtotime((string) ($rowMatch['time'] ?? ''));
            $syncMs = $ts ? $ts * 1000 : 0;
        }
        $rank = [$syncMs, (int) ($rowMatch['id'] ?? 0)];
        if ($best === null || $rank > $bestRank) {
            $best = $rowMatch;
            $bestRank = $rank;
        }
    }
    return $best;
}

/**
 * Persist city (and optional km) and bump sync_updated_ms so phone cloud pull sees the change.
 * Verifies the write by re-reading the row; logs outcome to sms_inbound_log.txt.
 * @param int|null $km null = city-only update
 */
function smsApplyCityKmUpdate($conn, string $table, string $serial, string $city, $km = null): bool
{
    $syncMs = (int) round(microtime(true) * 1000);
    $hasSync = smsHasColumn($conn, $table, 'sync_updated_ms');
    if ($km === null) {
        if ($hasSync) {
            $stmt = $conn->prepare("UPDATE `$table` SET city = ?, sync_updated_ms = ? WHERE serial = ?");
            if (!$stmt) {
                smsInboundLog("city_km prepare failed table=$table serial=$serial err=" . $conn->error);
                return false;
            }
            $stmt->bind_param('sis', $city, $syncMs, $serial);
        } else {
            $stmt = $conn->prepare("UPDATE `$table` SET city = ? WHERE serial = ?");
            if (!$stmt) {
                smsInboundLog("city_km prepare failed table=$table serial=$serial err=" . $conn->error);
                return false;
            }
            $stmt->bind_param('ss', $city, $serial);
        }
    } else {
        $kmInt = (int) $km;
        if ($hasSync) {
            $stmt = $conn->prepare("UPDATE `$table` SET city = ?, km = ?, sync_updated_ms = ? WHERE serial = ?");
            if (!$stmt) {
                smsInboundLog("city_km prepare failed table=$table serial=$serial err=" . $conn->error);
                return false;
            }
            $stmt->bind_param('siis', $city, $kmInt, $syncMs, $serial);
        } else {
            $stmt = $conn->prepare("UPDATE `$table` SET city = ?, km = ? WHERE serial = ?");
            if (!$stmt) {
                smsInboundLog("city_km prepare failed table=$table serial=$serial err=" . $conn->error);
                return false;
            }
            $stmt->bind_param('sis', $city, $kmInt, $serial);
        }
    }
    $ok = $stmt->execute();
    $affected = $stmt->affected_rows;
    $err = $ok ? '' : $stmt->error;
    $stmt->close();
    if (!$ok) {
        smsInboundLog("city_km UPDATE failed table=$table serial=$serial err=$err");
        return false;
    }

    // Verify the row actually holds the new values before we report success.
    $check = $conn->prepare("SELECT city, km FROM `$table` WHERE serial = ? LIMIT 1");
    $savedCity = null;
    $savedKm = null;
    if ($check) {
        $check->bind_param('s', $serial);
        if ($check->execute()) {
            $res = $check->get_result();
            if ($res && ($row = $res->fetch_assoc())) {
                $savedCity = (string) ($row['city'] ?? '');
                $savedKm = $row['km'];
            }
        }
        $check->close();
    }
    $cityOk = $savedCity !== null && trim($savedCity) !== '';
    $kmOk = $km === null || (int) $savedKm === (int) $km;
    smsInboundLog(
        "city_km save table=$table serial=$serial city=$city km=" . var_export($km, true) .
        " affected=$affected saved_city=" . var_export($savedCity, true) .
        " saved_km=" . var_export($savedKm, true)
    );
    if (!$cityOk || !$kmOk) {
        smsInboundLog("city_km VERIFY FAILED table=$table serial=$serial");
        return false;
    }
    return true;
}

/**
 * Built-in fallbacks when crm_settings.sms_messages is empty / missing an id.
 * Keep in sync with crm_default_sms_messages() in settings_store.php.
 */
function smsDefaultMessageTemplates(): array
{
    return [
        'empty_serial' => 'شماره سریال وارد نشده است.@1',
        'serial_not_found' => 'کد نامعتبر است. لطفا مجددا کد گارانتی را بصورت صحیح وارد کنید. در صورت نیاز با واحد پشتیبانی تماس بگیرید ۱۵۷-۰۵۹۰-۰۹۳۶@1',
        'serial_already_used' => 'این سریال قبلاً با شماره تماس ثبت شده است.@1',
        'processing_error' => 'خطا در پردازش درخواست. لطفا دوباره تلاش کنید.@1',
        'registration_session_missing' => 'سابقه ثبت نام یافت نشد. لطفا ابتدا کد سریال خود را ارسال کنید.@1',
        'digit_serial_hint' => 'برای فعالسازی گارانتی تسمه تایم کد را بصورت زیر ثبت کنید. مثال S123456 در صورت نیاز با ما تماس بگیرید ۱۵۷-۰۵۹۰-۰۹۳۶@1',
        'invalid_serial_format' => 'فرمت سریال وارد شده معتبر نیست.@1',
        'seller_prompt_city' => 'لطفا نام شهر خود را ارسال کنید.',
        'seller_reply_valid' => 'ثبت سریال فروشنده با موفقیت انجام شد.',
        'client_prompt_after_serial' => 'لطفا شهر و کیلومتر را ارسال کنید. مثال: تبریز 3350',
        'client_reply_valid' => 'شماره سریال شما معتبر است و گارانتی شما آغاز شد.',
    ];
}

/** Load editable SMS templates from crm_settings (same store as remote web Messages page). */
function smsLoadMessageTemplates($conn): array
{
    static $loaded = null;
    if ($loaded !== null) {
        return $loaded;
    }
    $loaded = smsDefaultMessageTemplates();
    $res = @$conn->query("SELECT setting_value FROM crm_settings WHERE setting_key = 'sms_messages' LIMIT 1");
    if ($res && ($row = $res->fetch_assoc())) {
        $decoded = json_decode((string) ($row['setting_value'] ?? ''), true);
        if (is_array($decoded) && isset($decoded['messages']) && is_array($decoded['messages'])) {
            foreach ($decoded['messages'] as $msg) {
                if (!is_array($msg)) {
                    continue;
                }
                $id = trim((string) ($msg['id'] ?? ''));
                $tpl = trim((string) ($msg['template'] ?? ''));
                if ($id !== '' && $tpl !== '') {
                    $loaded[$id] = $tpl;
                }
            }
        }
    }
    // Legacy main_sms fallbacks only when catalog still has defaults (not customized).
    $main = @$conn->query('SELECT a, c, m FROM main_sms LIMIT 1');
    if ($main && ($mrow = $main->fetch_assoc())) {
        $defaults = smsDefaultMessageTemplates();
        if (!empty($mrow['a']) && ($loaded['client_prompt_after_serial'] ?? '') === ($defaults['client_prompt_after_serial'] ?? '')) {
            $loaded['client_prompt_after_serial'] = (string) $mrow['a'];
        }
        if (!empty($mrow['c']) && ($loaded['client_reply_valid'] ?? '') === ($defaults['client_reply_valid'] ?? '')) {
            $loaded['client_reply_valid'] = (string) $mrow['c'];
        }
        if (!empty($mrow['m']) && ($loaded['seller_reply_valid'] ?? '') === ($defaults['seller_reply_valid'] ?? '')) {
            $loaded['seller_reply_valid'] = (string) $mrow['m'];
        }
    }
    return $loaded;
}

/**
 * @param array<string,string> $values placeholder replacements e.g. ['@1' => PORTAL_SMS_SUFFIX]
 */
function smsResolveMessage($conn, string $messageId, array $values = [], ?string $fallback = null): string
{
    $templates = smsLoadMessageTemplates($conn);
    $text = isset($templates[$messageId]) ? (string) $templates[$messageId] : '';
    if ($text === '') {
        $defaults = smsDefaultMessageTemplates();
        $text = $defaults[$messageId] ?? '';
    }
    if ($text === '' && $fallback !== null) {
        $text = $fallback;
    }
    foreach ($values as $token => $value) {
        $text = str_replace((string) $token, (string) $value, $text);
    }
    return $text;
}

function smsPortalValues(): array
{
    return ['@1' => defined('PORTAL_SMS_SUFFIX') ? PORTAL_SMS_SUFFIX : ''];
}

function smsRecordRegistrationActivity($conn, $phone, $serial, $extra = [])
{
    smsEnqueueCloudSyncBuffer($conn, $serial, is_array($extra) ? ($extra['_table'] ?? null) : null);
    $store = null;
    foreach ([
        __DIR__ . '/crm-php/api/activity_store.php',
        __DIR__ . '/crm/api/activity_store.php',
        __DIR__ . '/api/activity_store.php',
    ] as $candidate) {
        if (is_file($candidate)) {
            $store = $candidate;
            break;
        }
    }
    if ($store === null) {
        // Inline minimal write if activity_store.php is not beside SMS.php.
        $conn->query(
            "CREATE TABLE IF NOT EXISTS crm_activity_events (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                event_type VARCHAR(64) NOT NULL,
                payload_json TEXT NOT NULL,
                at_ms BIGINT NOT NULL,
                username VARCHAR(100) DEFAULT NULL,
                INDEX idx_crm_activity_at (at_ms)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );
        $at = (int) round(microtime(true) * 1000);
        $payload = array_merge([
            'serial' => strtoupper(trim((string) $serial)),
            'phone' => smsNormalizePhone((string) $phone),
            'date' => date('Y-m-d'),
            'at' => $at,
        ], is_array($extra) ? $extra : []);
        $json = json_encode($payload, JSON_UNESCAPED_UNICODE);
        if ($json === false) {
            return;
        }
        $type = 'registered';
        $user = '';
        $stmt = $conn->prepare(
            'INSERT INTO crm_activity_events (event_type, payload_json, at_ms, username) VALUES (?, ?, ?, ?)'
        );
        if (!$stmt) {
            return;
        }
        $stmt->bind_param('ssis', $type, $json, $at, $user);
        $stmt->execute();
        $stmt->close();
        return;
    }
    require_once $store;
    $payload = array_merge([
        'serial' => strtoupper(trim((string) $serial)),
        'phone' => smsNormalizePhone((string) $phone),
        'date' => date('Y-m-d'),
    ], is_array($extra) ? $extra : []);
    crm_activity_record($conn, 'registered', $payload);
}

/**
 * Push remote SMS registration changes into crm_sync_buffer for phone pull.
 */
function smsEnqueueCloudSyncBuffer($conn, $serial, $table = null)
{
    $helpers = null;
    foreach ([
        __DIR__ . '/crm-php/api/sync/sync_buffer_helpers.php',
        __DIR__ . '/crm/api/sync/sync_buffer_helpers.php',
        __DIR__ . '/api/sync/sync_buffer_helpers.php',
    ] as $candidate) {
        if (is_file($candidate)) {
            $helpers = $candidate;
            break;
        }
    }
    if ($helpers === null) {
        return;
    }
    require_once $helpers;
    if (!function_exists('sync_buffer_enqueue_serial_lookup')) {
        return;
    }
    $tableName = is_string($table) && $table !== '' ? $table : null;
    sync_buffer_enqueue_serial_lookup($conn, (string) $serial, $tableName, 'sms');
}

function smsRecordRegistrationError($conn, $phone, $serial, $reason)
{
    foreach ([
        __DIR__ . '/crm-php/api/serial_errors_store.php',
        __DIR__ . '/crm/api/serial_errors_store.php',
        __DIR__ . '/api/serial_errors_store.php',
    ] as $store) {
        if (is_file($store)) {
            require_once $store;
            sre_insert($conn, (string) $phone, (string) $serial, (string) $reason);
            return;
        }
    }
    $conn->query(
        "CREATE TABLE IF NOT EXISTS serial_registration_errors (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            phone VARCHAR(32) NOT NULL,
            serial VARCHAR(64) NOT NULL,
            reason VARCHAR(32) NOT NULL,
            at_ms BIGINT NOT NULL,
            INDEX idx_sre_phone_at (phone, at_ms),
            INDEX idx_sre_at (at_ms)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );
    $phoneNorm = smsNormalizePhone($phone);
    $serialNorm = strtoupper(trim((string) $serial));
    $reasonNorm = strtolower(trim((string) $reason));
    if ($reasonNorm === 'already_registered') {
        $reasonNorm = 'already_used';
    }
    if ($reasonNorm === 'serial_not_found') {
        $reasonNorm = 'not_found';
    }
    if ($phoneNorm === '' || $serialNorm === '') {
        return;
    }
    $at = (int) round(microtime(true) * 1000);
    $stmt = $conn->prepare(
        'INSERT INTO serial_registration_errors (phone, serial, reason, at_ms) VALUES (?, ?, ?, ?)'
    );
    if (!$stmt) {
        return;
    }
    $stmt->bind_param('sssi', $phoneNorm, $serialNorm, $reasonNorm, $at);
    $stmt->execute();
    $stmt->close();
}


// Check if there is Persian text (for city) and digits (for km) - for old_serials / new_serials
if (preg_match('/[\x{0600}-\x{06FF}]/u', $body) && preg_match('/[0-9\x{06F0}-\x{06F9}]/u', $body)) {
    // Extract Persian text (city) and digits (km)
    preg_match_all('/[\x{0600}-\x{06FF}]+/u', $body, $matchesCity);
    preg_match_all('/[0-9\x{06F0}-\x{06F9}]+/u', $body, $matchesKm);

    // Join all Persian tokens so multi-word cities work (e.g. شاهین شهر)
    $city = isset($matchesCity[0]) ? trim(implode(' ', $matchesCity[0])) : "";
    $km = isset($matchesKm[0][0]) ? $matchesKm[0][0] : "";

    // Check if both city and km are non-empty
    if (!empty($city) && !empty($km)) {
        // Call the function to process km and city
        Client_Km_City_confirm($km, $city, $user, $conn);
    }
    exit;
} elseif (preg_match('/^[\x{0600}-\x{06FF}\s]+$/u', $body)) {
    // Persian text only (city for M-codes) - no digits
    $city = trim($body);
    if (!empty($city)) {
        if (!SToM_City_confirm($city, $user, $conn)) {
            Mcode_City_confirm($city, $user, $conn);
        }
    }
    exit;
}


$firstChar = substr($body, 0, 1);
$serial = strtoupper(trim($body));

if (isMprefixSerial($serial)) {
    smsDebug('route: mcode_table');

    $sql = "SELECT * FROM Mcode WHERE serial = ?";

    if ($stmt = $conn->prepare($sql)) {
        $stmt->bind_param("s", $serial);
        $stmt->execute();
        $result = $stmt->get_result();

        if ($result->num_rows > 0) {
            smsDebug('mcode: found in database');
            Old_seller_register_existing_serial($serial, $user, $conn);
            smsDebug('mcode: registration handler finished');
        } else {
            smsDebug('mcode: not found in database');
            smsRecordRegistrationError($conn, $user, $serial, 'not_found');
            SendMessage($user, smsResolveMessage($conn, 'serial_not_found', smsPortalValues()));
        }

        $stmt->close();
    } else {
        smsDebug('mcode: SQL prepare failed');
        SendMessage($user, smsResolveMessage($conn, 'processing_error', smsPortalValues()));
    }
} elseif (isSToMRange($serial)) {
    smsDebug('route: old_serials_as_mcode');
    logSToMRouting($serial, $user, 'route_old_serials_as_mcode');

    $sql = "SELECT * FROM old_serials WHERE UPPER(serial) = ?";
    $stmt = $conn->prepare($sql);
    $stmt->bind_param("s", $serial);
    $stmt->execute();
    $result = $stmt->get_result();

    if ($result->num_rows > 0) {
        $row = $result->fetch_assoc();
        $dbSerial = $row['serial'];
        if ($row['phone'] !== null && $row['phone'] !== '' && $row['phone'] !== '0') {
            smsDebug('old_serials_as_mcode: already registered');
            logSToMRouting($serial, $user, 'already_registered');
            smsRecordRegistrationError($conn, $user, $dbSerial, 'already_used');
            Client_Serial_has_been_registered($dbSerial, $row['phone']);
            SendMessage($user, smsResolveMessage($conn, 'serial_already_used', smsPortalValues()));
        } else {
            smsDebug('old_serials_as_mcode: starting registration');
            logSToMRouting($serial, $user, 'serial_found');
            client_calling_for_unused_serial_as_mcode($dbSerial, $user, $conn);
            smsDebug('old_serials_as_mcode: registration handler finished');
        }
    } else {
        smsDebug('old_serials_as_mcode: not found in database');
        logSToMRouting($serial, $user, 'serial_not_found');
        smsRecordRegistrationError($conn, $user, $serial, 'not_found');
        SendMessage($user, smsResolveMessage($conn, 'serial_not_found', smsPortalValues()));
    }

    $stmt->close();
} elseif (ctype_alpha($firstChar) || preg_match('/^[A-Za-z]/', $serial)) {
    smsDebug('route: old_serials');
    $row = smsFindWarrantySerialRow($conn, $serial);
    if ($row !== null) {
        $dbSerial = (string) $row['serial'];
        if ($row['phone'] !== null && $row['phone'] !== '' && $row['phone'] !== '0') {
            smsRecordRegistrationError($conn, $user, $dbSerial, 'already_used');
            Client_Serial_has_been_registered($dbSerial, $row['phone']);
            SendMessage($user, smsResolveMessage($conn, 'serial_already_used', smsPortalValues()));
        } else {
            client_calling_for_unused_serial($dbSerial, $user, $conn);
        }
    } else {
        smsDebug('old_serials: not found serial=' . $serial);
        smsRecordRegistrationError($conn, $user, $serial, 'not_found');
        SendMessage($user, smsResolveMessage($conn, 'serial_not_found', smsPortalValues()));
    }

} elseif (ctype_digit($firstChar)) {
    smsDebug('route: digit_hint');
    SendMessage($user, smsResolveMessage($conn, 'digit_serial_hint', smsPortalValues()));

} else {
    smsDebug('route: invalid_format');
    SendMessage($user, smsResolveMessage($conn, 'invalid_serial_format', smsPortalValues()));
}


function SendMessage($phone, $message)
{
    global $conn;
    $phone = smsNormalizePhone($phone);
    if ($phone === '' || $message === null || $message === '') {
        smsDebug('SendMessage skipped: empty phone/message');
        smsInboundLog('SendMessage skipped empty phone/message');
        return false;
    }

    ini_set("soap.wsdl_cache_enabled", 0);
    try {
        $sms = new SoapClient("http://api.payamak-panel.com/post/Send.asmx?wsdl", [
            "encoding" => "UTF-8",
            "connection_timeout" => 15,
            "default_socket_timeout" => 20,
        ]);
        $data = [
            "username" => "09121777039",
            "password" => "4thvahdati@FB",
            "to" => [$phone],
            "from" => "20001390",
            "text" => $message,
            "isflash" => false,
        ];
        $result = $sms->SendSimpleSMS($data)->SendSimpleSMSResult;
        $resultText = is_array($result) ? json_encode($result, JSON_UNESCAPED_UNICODE) : (string) $result;
        smsDebug('SendMessage ok to=' . $phone . ' result=' . $resultText);
        smsInboundLog('SendMessage ok to=' . $phone . ' result=' . $resultText);
        smsLogCommunication($conn ?? null, $phone, 'outgoing', (string) $message, 'sent');
        return true;
    } catch (Throwable $e) {
        smsDebug('SendMessage fail: ' . $e->getMessage());
        smsInboundLog('SendMessage fail: ' . $e->getMessage());
        smsLogCommunication($conn ?? null, $phone, 'outgoing', (string) $message, 'failed');
        return false;
    }
}

function smsLogCommunication($conn, $phone, $direction, $body, $status = 'sent')
{
    if (!$conn) {
        return;
    }
    static $loaded = false;
    if (!$loaded) {
        foreach ([
            __DIR__ . '/crm-php/api/comms_store.php',
            __DIR__ . '/crm/api/comms_store.php',
            __DIR__ . '/api/comms_store.php',
        ] as $store) {
            if (is_file($store)) {
                require_once $store;
                break;
            }
        }
        $loaded = true;
    }
    if (function_exists('comms_insert')) {
        comms_insert($conn, (string) $phone, (string) $direction, (string) $body, 'sms', $status);
    }
}

function getKnownCityForPhone($phone, $conn)
{
    $sql = "SELECT city FROM Mcode WHERE phone = ? AND city IS NOT NULL AND city != '' ORDER BY time DESC LIMIT 1";
    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        return null;
    }
    $stmt->bind_param("s", $phone);
    $stmt->execute();
    $result = $stmt->get_result();
    $city = null;
    if ($result && $row = $result->fetch_assoc()) {
        $city = $row['city'];
    }
    $stmt->close();
    return $city;
}

function getMcodeTotalScore($phone, $conn)
{
    $sql = "SELECT COALESCE(SUM(score), 0) AS total FROM Mcode WHERE phone = ? AND phone != '0' AND score > 0";
    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        return 0;
    }
    $stmt->bind_param("s", $phone);
    if (!$stmt->execute()) {
        $stmt->close();
        return 0;
    }
    $result = $stmt->get_result();
    $total = 0;
    if ($result && $row = $result->fetch_assoc()) {
        $total = (int) $row['total'];
    }
    $stmt->close();
    return $total;
}

function toPersianDigits($value)
{
    $englishDigits = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];
    $persianDigits = ['۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'];
    return str_replace($englishDigits, $persianDigits, (string) $value);
}

function formatMcodeScorePhrase($total)
{
    if ($total <= 0) {
        return '';
    }
    return ' شما تا این لحظه ' . toPersianDigits($total) . ' امتیاز دارید.';
}

function appendScoreSuffix($message, $phone, $conn)
{
    $total = getMcodeTotalScore($phone, $conn);
    $phrase = formatMcodeScorePhrase($total);
    if ($phrase === '') {
        return $message;
    }
    return rtrim($message) . $phrase;
}

function sendMcodeSms($phone, $message, $conn)
{
    SendMessage($phone, appendScoreSuffix($message, $phone, $conn));
}

function Client_Serial_has_been_registered($serial, $phone)
{
}

function seller_register_existing_serial($serial, $user, $conn)
{


    // Prepare the query to update the phone number for the seller with the given serial
    $sql = "UPDATE seller SET phone = ? WHERE serial = ?";

    // Prepare the statement
    if ($stmt = $conn->prepare($sql)) {
        // Bind the parameters (phone and serial) to the query
        $stmt->bind_param("ss", $user, $serial);

        // Execute the query
        if ($stmt->execute() && $stmt->affected_rows > 0) {
            SendConfirmSms($user, $conn);
        }

        $stmt->close();
    }
}



function Old_seller_register_existing_serial($serial, $phone, $conn)
{
    if (empty($serial) || empty($phone)) {
        return "سریال یا شماره تلفن معتبر نیست.";
    }

    // Check if phone is already registered for this serial
    $checkSql = "SELECT phone FROM Mcode WHERE serial = ?";
    if ($checkStmt = $conn->prepare($checkSql)) {
        $checkStmt->bind_param("s", $serial);
        $checkStmt->execute();
        $checkResult = $checkStmt->get_result();
        if ($checkResult && $row = $checkResult->fetch_assoc()) {
            if (!empty($row['phone']) && $row['phone'] !== '0') {
                // Already registered
                $message = smsResolveMessage($conn, 'serial_already_used', smsPortalValues());
                SendMessage($phone, $message);
                $checkStmt->close();
                return $message;
            }
        }
        $checkStmt->close();
    }

    // Send advertisement messages first (same flow as old_serials)
    $sqlAd = "SELECT message FROM ad_sms";
    $resultAd = mysqli_query($conn, $sqlAd);
    if ($resultAd && mysqli_num_rows($resultAd) > 0) {
        while ($row = mysqli_fetch_assoc($resultAd)) {
            $message = $row['message'];
            SendMessage($phone, $message);
            sleep(4);
        }
    }

    $knownCity = getKnownCityForPhone($phone, $conn);
    $deferImage = isImageVerificationEnabledForSerial($conn, $serial);

    if ($knownCity) {
        $sql = "UPDATE Mcode SET phone = ?, time = NOW(), city = ?, score = ? WHERE serial = ?";
        if ($stmt = $conn->prepare($sql)) {
            $score = ivRegistrationLotteryScore($conn, $serial);
            $stmt->bind_param("ssis", $phone, $knownCity, $score, $serial);
            if ($stmt->execute() && $stmt->affected_rows > 0) {
                $stmt->close();
                if ($deferImage && maybeDeferRegistrationForImageVerify($conn, $serial, $phone, [
                    'city' => $knownCity,
                    'confirm_column' => 'm',
                ])) {
                    return "سریال ثبت شد. لینک تأیید تصویر ارسال شد.";
                }
                if ($deferImage) {
                    ivGrantMcodeLotteryScore($conn, $serial, $phone);
                }
                SendConfirmSms($phone, $conn, 'm', true);
                return "سریال با موفقیت ثبت شد، شهر از سابقه شناسایی شد.";
            }
            $stmt->close();
            return "هیچ تغییری در اطلاعات فروشنده ایجاد نشد.";
        }
        return "خطا در برقراری ارتباط با پایگاه داده.";
    }

    $sql = "UPDATE Mcode SET phone = ?, time = NOW(), score = ? WHERE serial = ?";
    if ($stmt = $conn->prepare($sql)) {
        $score = ivRegistrationLotteryScore($conn, $serial);
        $stmt->bind_param("sis", $phone, $score, $serial);

        if ($stmt->execute()) {
            if ($stmt->affected_rows > 0) {
                $stmt->close();
                sendMcodeSms($phone, smsResolveMessage($conn, 'seller_prompt_city'), $conn);
                return "سریال با موفقیت ثبت شد، شماره تلفن و زمان به‌روز شدند.";
            }
            $stmt->close();
            return "هیچ تغییری در اطلاعات فروشنده ایجاد نشد.";
        }
        $stmt->close();
        return "خطا در بروزرسانی اطلاعات فروشنده.";
    }

    return "خطا در برقراری ارتباط با پایگاه داده.";
}






function client_calling_for_unused_serial($serial, $phone, $conn)
{
    $serial = strtoupper(trim((string) $serial));
    $row = smsFindWarrantySerialRow($conn, $serial);
    if ($row === null) {
        smsDebug('client_calling_for_unused_serial: row missing after route serial=' . $serial);
        smsRecordRegistrationError($conn, $phone, $serial, 'not_found');
        SendMessage($phone, smsResolveMessage($conn, 'serial_not_found', smsPortalValues()));
        return;
    }
    $serial = (string) $row['serial'];
    $table = (string) ($row['_table'] ?? 'old_serials');

    // Seller serials (category seller_to_end_user): city-only pipeline — never ask for km.
    if (smsOldSerialIsSellerPipeline($serial, $row)) {
        register_seller_unused_serial($serial, $phone, $conn, $table);
        return;
    }

    // Bind phone BEFORE prompts so city/km reply can find this session.
    $currentTime = date("Y-m-d");
    $syncMs = (int) round(microtime(true) * 1000);
    if (smsHasColumn($conn, $table, 'sync_updated_ms')) {
        $sqlUpdate = "UPDATE `$table` SET phone = ?, time = ?, sync_updated_ms = ? WHERE serial = ?";
        $stmtUpdate = $conn->prepare($sqlUpdate);
        if ($stmtUpdate === false) {
            exit;
        }
        $stmtUpdate->bind_param("ssis", $phone, $currentTime, $syncMs, $serial);
    } else {
        $sqlUpdate = "UPDATE `$table` SET phone = ?, time = ? WHERE serial = ?";
        $stmtUpdate = $conn->prepare($sqlUpdate);
        if ($stmtUpdate === false) {
            exit;
        }
        $stmtUpdate->bind_param("sss", $phone, $currentTime, $serial);
    }

    if (!$stmtUpdate->execute()) {
        $stmtUpdate->close();
        exit;
    }
    $stmtUpdate->close();
    smsRecordRegistrationActivity($conn, $phone, $serial);

    $sqlAd = "SELECT message FROM ad_sms";
    $resultAd = mysqli_query($conn, $sqlAd);

    if ($resultAd && mysqli_num_rows($resultAd) > 0) {
        while ($adRow = mysqli_fetch_assoc($resultAd)) {
            $message = $adRow['message'];
            SendMessage($phone, $message);
            sleep(4); // delay between messages
        }
    }

    $sqlMainTpl = smsResolveMessage($conn, 'client_prompt_after_serial', [
        '@1' => 'تبریز',
        '@2' => '3350',
    ]);
    if ($sqlMainTpl !== '') {
        SendMessage($phone, $sqlMainTpl);
    }
}

/**
 * Seller registration: ads → inherit known city or ask city only (no km).
 * Legacy S440000–S450000 also mirrors into Mcode; category sellers stay in warranty tables.
 */
function register_seller_unused_serial($serial, $phone, $conn, $table = 'old_serials')
{
    $knownCity = getKnownCityFromOldSerials($phone, $conn);
    if (!$knownCity) {
        $knownCity = getKnownCityForPhone($phone, $conn);
    }

    $currentTime = date("Y-m-d");
    $syncMs = (int) round(microtime(true) * 1000);
    $hasSync = smsHasColumn($conn, $table, 'sync_updated_ms');
    $syncToMcode = isSToMRange($serial);

    // Bind phone first so a city reply can find this session while ads are sending.
    if ($knownCity) {
        if ($hasSync) {
            $sqlUpdate = "UPDATE `$table` SET phone = ?, time = ?, city = ?, sync_updated_ms = ? WHERE serial = ?";
            $stmtUpdate = $conn->prepare($sqlUpdate);
            if ($stmtUpdate === false) {
                exit;
            }
            $stmtUpdate->bind_param("sssis", $phone, $currentTime, $knownCity, $syncMs, $serial);
        } else {
            $sqlUpdate = "UPDATE `$table` SET phone = ?, time = ?, city = ? WHERE serial = ?";
            $stmtUpdate = $conn->prepare($sqlUpdate);
            if ($stmtUpdate === false) {
                exit;
            }
            $stmtUpdate->bind_param("ssss", $phone, $currentTime, $knownCity, $serial);
        }
        if ($stmtUpdate->execute() && $stmtUpdate->affected_rows > 0) {
            $stmtUpdate->close();
            if (maybeDeferRegistrationForImageVerify($conn, $serial, $phone, [
                'city' => $knownCity,
                'confirm_column' => 'm',
            ])) {
                smsRecordRegistrationActivity($conn, $phone, $serial, ['city' => $knownCity]);
                return;
            }
            smsRecordRegistrationActivity($conn, $phone, $serial, ['city' => $knownCity]);
            if ($syncToMcode) {
                syncSToMSerialToMcode($conn, $serial, $phone, $knownCity);
            }
            $sqlAd = "SELECT message FROM ad_sms";
            $resultAd = mysqli_query($conn, $sqlAd);
            if ($resultAd && mysqli_num_rows($resultAd) > 0) {
                while ($row = mysqli_fetch_assoc($resultAd)) {
                    SendMessage($phone, $row['message']);
                    sleep(4);
                }
            }
            SendConfirmSms($phone, $conn, 'm');
            return;
        }
        $stmtUpdate->close();
    }

    if ($hasSync) {
        $sqlUpdate = "UPDATE `$table` SET phone = ?, time = ?, sync_updated_ms = ? WHERE serial = ?";
        $stmtUpdate = $conn->prepare($sqlUpdate);
        if ($stmtUpdate === false) {
            exit;
        }
        $stmtUpdate->bind_param("ssis", $phone, $currentTime, $syncMs, $serial);
    } else {
        $sqlUpdate = "UPDATE `$table` SET phone = ?, time = ? WHERE serial = ?";
        $stmtUpdate = $conn->prepare($sqlUpdate);
        if ($stmtUpdate === false) {
            exit;
        }
        $stmtUpdate->bind_param("sss", $phone, $currentTime, $serial);
    }
    if (!$stmtUpdate->execute()) {
        $stmtUpdate->close();
        exit;
    }
    $stmtUpdate->close();

    smsRecordRegistrationActivity($conn, $phone, $serial);
    if ($syncToMcode && !isImageVerificationEnabledForSerial($conn, $serial)) {
        syncSToMSerialToMcode($conn, $serial, $phone);
    }

    $sqlAd = "SELECT message FROM ad_sms";
    $resultAd = mysqli_query($conn, $sqlAd);
    if ($resultAd && mysqli_num_rows($resultAd) > 0) {
        while ($row = mysqli_fetch_assoc($resultAd)) {
            SendMessage($phone, $row['message']);
            sleep(4);
        }
    }
    SendMessage($phone, smsResolveMessage($conn, 'seller_prompt_city'));
}

function getKnownCityFromOldSerials($phone, $conn)
{
    $phone = smsNormalizePhone($phone);
    foreach (smsWarrantyTables($conn) as $table) {
        $sql = "SELECT city FROM `$table` WHERE phone = ? AND city IS NOT NULL AND city != '' ORDER BY time DESC, id DESC LIMIT 1";
        $stmt = $conn->prepare($sql);
        if (!$stmt) {
            continue;
        }
        $stmt->bind_param("s", $phone);
        $stmt->execute();
        $result = $stmt->get_result();
        $city = null;
        if ($result && $row = $result->fetch_assoc()) {
            $city = $row['city'];
        }
        $stmt->close();
        if ($city !== null && $city !== '') {
            return $city;
        }
    }
    return null;
}

function client_calling_for_unused_serial_as_mcode($serial, $phone, $conn)
{
    register_seller_unused_serial($serial, $phone, $conn);
}

function SToM_City_confirm($city, $phone, $conn)
{
    $row = smsFindPendingRegistrationByPhone($conn, $phone, 'seller_city');
    if ($row === null) {
        return false;
    }

    $serial = $row['serial'];
    $table = (string) ($row['_table'] ?? 'old_serials');

    if (!smsOldSerialIsSellerPipeline($serial, $row)) {
        return false;
    }

    logSToMRouting($serial, $phone, 'city_confirm');

    if (!smsApplyCityKmUpdate($conn, $table, $serial, $city, null)) {
        return false;
    }
    smsEnqueueCloudSyncBuffer($conn, $serial, $table);
    if (maybeDeferRegistrationForImageVerify($conn, $serial, $phone, [
        'city' => $city,
        'confirm_column' => 'm',
    ])) {
        return true;
    }
    if (isSToMRange($serial)) {
        syncSToMSerialToMcode($conn, $serial, $phone, $city);
    }
    SendConfirmSms($phone, $conn, 'm');
    return true;
}

function Client_Km_City_confirm($km, $city, $phone, $conn)
{
    $row = smsFindPendingRegistrationByPhone($conn, $phone, 'client_km');
    if ($row === null) {
        // Seller awaiting city may send "شهر 1234"; apply city-only rather than fail.
        if (SToM_City_confirm($city, $phone, $conn)) {
            return;
        }
        smsDebug('Client_Km_City_confirm: no pending warranty row for phone=' . smsNormalizePhone($phone));
        SendMessage($phone, smsResolveMessage($conn, 'registration_session_missing', smsPortalValues()));
        return;
    }

    $serial = $row['serial'];
    $table = (string) ($row['_table'] ?? 'old_serials');
    smsInboundLog(
        'city_km session phone=' . smsNormalizePhone($phone) .
        " -> serial=$serial table=$table city=$city km=$km"
    );

    // Seller pipeline: accept city (ignore km), complete with seller confirm SMS.
    if (smsOldSerialIsSellerPipeline($serial, $row)) {
        if (!smsApplyCityKmUpdate($conn, $table, $serial, $city, null)) {
            SendMessage($phone, smsResolveMessage($conn, 'processing_error', smsPortalValues()));
            return;
        }
        smsEnqueueCloudSyncBuffer($conn, $serial, $table);
        smsDebug('Client_Km_City_confirm: seller city saved serial=' . $serial . ' city=' . $city);
        if (maybeDeferRegistrationForImageVerify($conn, $serial, $phone, [
            'city' => $city,
            'confirm_column' => 'm',
        ])) {
            return;
        }
        if (isSToMRange($serial)) {
            syncSToMSerialToMcode($conn, $serial, $phone, $city);
        }
        SendConfirmSms($phone, $conn, 'm');
        return;
    }

    $kmInt = (int) $km;
    if (!smsApplyCityKmUpdate($conn, $table, $serial, $city, $kmInt)) {
        SendMessage($phone, smsResolveMessage($conn, 'processing_error', smsPortalValues()));
        return;
    }
    smsEnqueueCloudSyncBuffer($conn, $serial, $table);
    smsDebug('Client_Km_City_confirm: saved serial=' . $serial . ' city=' . $city . ' km=' . $kmInt);
    if (maybeDeferRegistrationForImageVerify($conn, $serial, $phone, [
        'km' => $kmInt,
        'city' => $city,
        'confirm_column' => 'c',
    ])) {
        return;
    }
    SendConfirmSms($phone, $conn);
}

function SendConfirmSms($phone, $conn, $column = 'c', $appendScore = null)
{
    $messageId = ($column === 'm') ? 'seller_reply_valid' : 'client_reply_valid';
    $message = smsResolveMessage($conn, $messageId);

    // Legacy main_sms fallback if catalog somehow empty
    if ($message === '') {
        $sqlMessage = "SELECT $column FROM main_sms LIMIT 1";
        $resultMessage = mysqli_query($conn, $sqlMessage);
        if ($resultMessage && mysqli_num_rows($resultMessage) > 0) {
            $messageRow = mysqli_fetch_assoc($resultMessage);
            $message = (string) ($messageRow[$column] ?? '');
        }
    }

    if ($message === '') {
        return;
    }

    if ($appendScore === null) {
        $appendScore = ($column === 'm');
    }
    if ($appendScore) {
        $message = appendScoreSuffix($message, $phone, $conn);
    }

    SendMessage($phone, $message);
}

function Mcode_City_confirm($city, $phone, $conn)
{
    $sql = "SELECT * FROM Mcode WHERE phone = ? AND (city IS NULL OR city = '') ORDER BY time DESC LIMIT 1";
    $stmt = $conn->prepare($sql);
    if ($stmt === false) {
        exit;
    }

    $stmt->bind_param("s", $phone);

    if (!$stmt->execute()) {
        $stmt->close();
        exit;
    }

    $result = $stmt->get_result();
    if ($result && $row = $result->fetch_assoc()) {
        $serial = $row['serial'];
        $updateSql = "UPDATE Mcode SET city = ? WHERE serial = ?";
        $stmtUpdate = $conn->prepare($updateSql);

        if ($stmtUpdate === false) {
            $stmt->close();
            exit;
        }

        $stmtUpdate->bind_param("ss", $city, $serial);

        if (!$stmtUpdate->execute()) {
            $stmtUpdate->close();
            $stmt->close();
            exit;
        }

        $stmtUpdate->close();
        $stmt->close();
        $deferImage = isImageVerificationEnabledForSerial($conn, $serial);
        if ($deferImage && maybeDeferRegistrationForImageVerify($conn, $serial, $phone, [
            'city' => $city,
            'confirm_column' => 'm',
        ])) {
            return;
        }
        if ($deferImage) {
            ivGrantMcodeLotteryScore($conn, $serial, $phone);
        }
        SendConfirmSms($phone, $conn, 'm');
    } else {
        $stmt->close();
        SendMessage($phone, smsResolveMessage($conn, 'registration_session_missing', smsPortalValues()));
    }
}



?>