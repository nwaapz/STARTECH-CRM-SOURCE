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

/** Answer the webhook immediately, then keep running long enough to send the reply SMS. */
function smsDetachInboundRequest(): void
{
    ignore_user_abort(true);
    @set_time_limit(90);
    if (function_exists('fastcgi_finish_request')) {
        fastcgi_finish_request();
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
 * public_html/crm/ is the Vue SPA, not these helpers.
 * serial-utils.php already lives next to SMS.php on the server.
 * lottery-sync.php is optional: a missing file must not kill inbound SMS.
 */
$smsHelperDirs = [
    __DIR__,
    __DIR__ . '/sms_lib',
    __DIR__ . '/crm-php/crm',
];
$serialUtils = null;
$lotterySync = null;
$ivHelper = null;
foreach ($smsHelperDirs as $candidate) {
    if ($serialUtils === null && is_file($candidate . '/serial-utils.php')) {
        $serialUtils = $candidate . '/serial-utils.php';
    }
    if ($lotterySync === null && is_file($candidate . '/lottery-sync.php') && is_file($candidate . '/serial-utils.php')) {
        $lotterySync = $candidate . '/lottery-sync.php';
    }
    if ($ivHelper === null && is_file($candidate . '/image-verification-helper.php')) {
        $ivHelper = $candidate . '/image-verification-helper.php';
    }
}
smsDebug('serialUtils=' . ($serialUtils ?: 'NOT FOUND'));
smsDebug('lotterySync=' . ($lotterySync ?: 'NOT FOUND'));

if ($serialUtils !== null) {
    require_once $serialUtils;
}
if ($lotterySync !== null) {
    require_once $lotterySync;
} else {
    smsDebug('lottery-sync: skipped (file not found)');
}

if (!function_exists('isMprefixSerial')) {
    function isMprefixSerial($serial)
    {
        $serial = strtoupper(trim((string) $serial));
        return $serial !== '' && $serial[0] === 'M';
    }
}
if (!function_exists('isSToMRange')) {
    function isSToMRange($serial)
    {
        $serial = strtoupper(trim((string) $serial));
        if (!preg_match('/^S(\d+)$/', $serial, $m)) {
            return false;
        }
        $num = (int) $m[1];
        return $num >= 440000 && $num <= 450000;
    }
}

// Older live sms_lib may predate seller helpers — define stubs so SMS never fatals.
if (!function_exists('smsIsSellerCategory')) {
    function smsIsSellerCategory($category): bool
    {
        return trim((string) $category) === 'seller_to_end_user';
    }
}
if (!function_exists('syncMcodeRegistrationToOldSerials')) {
    function syncMcodeRegistrationToOldSerials($conn, $serial, $phone, $city = null, $score = null)
    {
        return false;
    }
}
if (!function_exists('smsOldSerialIsSellerPipeline')) {
    function smsOldSerialIsSellerPipeline($serial, $row = null): bool
    {
        if (function_exists('isMprefixSerial') && isMprefixSerial($serial)) {
            return true;
        }
        if (function_exists('isSToMRange') && isSToMRange($serial)) {
            return true;
        }
        if (is_array($row) && smsIsSellerCategory($row['category'] ?? '')) {
            return true;
        }
        return false;
    }
}

if ($ivHelper !== null && is_file($ivHelper)) {
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

// Melipayamak closes the HTTP call as soon as it has a response. Without this,
// PHP is aborted after the DB write and before the reply SOAP call finishes.
// debug=1 stays attached so the browser can still show the trace.
if (!$SMS_DEBUG) {
    smsDetachInboundRequest();
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
 * Inbound SMS registers only against old_serials.
 * new_serials is a separate stock and must not satisfy a lookup.
 * @return string[]
 */
function smsWarrantyTables($conn)
{
    static $tables = null;
    if ($tables !== null) {
        return $tables;
    }
    $tables = [];
    $check = @$conn->query("SHOW TABLES LIKE 'old_serials'");
    if ($check && $check->num_rows > 0) {
        $tables[] = 'old_serials';
    }
    return $tables;
}

/**
 * Find unused/used warranty serial row in old_serials.
 * Always returns the DB-stored serial string for later UPDATEs.
 */
/**
 * One SELECT ... WHERE <expr> IN (candidates) LIMIT 1.
 * @param string[] $candidates
 */
function smsSelectSerialRow($conn, string $table, string $whereExpr, array $candidates)
{
    $placeholders = implode(',', array_fill(0, count($candidates), '?'));
    $sql = "SELECT * FROM `$table` WHERE $whereExpr IN ($placeholders) LIMIT 1";
    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        return null;
    }
    $types = str_repeat('s', count($candidates));
    $stmt->bind_param($types, ...$candidates);
    if (!$stmt->execute()) {
        $stmt->close();
        return null;
    }
    $res = $stmt->get_result();
    $row = $res ? $res->fetch_assoc() : null;
    $stmt->close();
    return is_array($row) ? $row : null;
}

function smsFindWarrantySerialRow($conn, $serial)
{
    $candidates = smsSerialLookupCandidates($serial);
    if ($candidates === []) {
        return null;
    }
    foreach (smsWarrantyTables($conn) as $table) {
        // Indexed lookup first: serials are stored uppercase and the column collation is
        // case-insensitive, so this hits idx_serials_serial. UPPER(serial) can't use the
        // index and scans the whole table (45 s for rows near the end) — only as a fallback.
        $row = smsSelectSerialRow($conn, $table, 'serial', $candidates);
        if ($row === null) {
            $row = smsSelectSerialRow($conn, $table, 'UPPER(serial)', $candidates);
        }
        if ($row !== null) {
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


// Check if there is Persian text (for city) and digits (for km) - for old_serials
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
    // Persian text only (city for seller / M-codes) - no digits
    $city = trim($body);
    if (!empty($city)) {
        if (!SToM_City_confirm($city, $user, $conn)) {
            SendMessage($user, smsResolveMessage($conn, 'registration_session_missing', smsPortalValues()));
        }
    }
    exit;
}


$firstChar = substr($body, 0, 1);
$serial = strtoupper(trim($body));

// All letter serials (M-prefix sellers, S440–S450, warranty) live in old_serials.
if (ctype_alpha($firstChar) || preg_match('/^[A-Za-z]/', $serial) || isMprefixSerial($serial) || isSToMRange($serial)) {
    $routeLabel = isMprefixSerial($serial) || isSToMRange($serial) ? 'seller_old_serials' : 'old_serials';
    smsDebug('route: ' . $routeLabel);
    if (isSToMRange($serial)) {
        logSToMRouting($serial, $user, 'route_old_serials_as_mcode');
    }
    $row = smsFindWarrantySerialRow($conn, $serial);
    if ($row !== null) {
        $dbSerial = (string) $row['serial'];
        if ($row['phone'] !== null && $row['phone'] !== '' && $row['phone'] !== '0') {
            if (isSToMRange($serial)) {
                logSToMRouting($serial, $user, 'already_registered');
            }
            smsRecordRegistrationError($conn, $user, $dbSerial, 'already_used');
            Client_Serial_has_been_registered($dbSerial, $row['phone']);
            SendMessage($user, smsResolveMessage($conn, 'serial_already_used', smsPortalValues()));
        } else {
            if (isSToMRange($serial)) {
                logSToMRouting($serial, $user, 'serial_found');
            }
            client_calling_for_unused_serial($dbSerial, $user, $conn);
        }
    } else {
        smsDebug($routeLabel . ': not found serial=' . $serial);
        if (isSToMRange($serial)) {
            logSToMRouting($serial, $user, 'serial_not_found');
        }
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


function smsValueToText($value): string
{
    if (is_string($value)) {
        return $value;
    }
    if (is_int($value) || is_float($value)) {
        return (string) $value;
    }
    if (is_bool($value)) {
        return $value ? '1' : '0';
    }
    if ($value === null) {
        return '';
    }
    $json = json_encode($value, JSON_UNESCAPED_UNICODE);
    return $json === false ? '' : $json;
}

function SendMessage($phone, $message)
{
    global $conn;
    $phone = smsNormalizePhone($phone);
    $message = smsValueToText($message);
    if ($phone === '' || $message === '') {
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
        $resultText = smsValueToText($result);
        smsDebug('SendMessage ok to=' . $phone . ' result=' . $resultText);
        smsInboundLog('SendMessage ok to=' . $phone . ' result=' . $resultText);
        smsLogCommunication($conn ?? null, $phone, 'outgoing', $message, 'sent');
        return true;
    } catch (Throwable $e) {
        smsDebug('SendMessage fail: ' . $e->getMessage());
        smsInboundLog('SendMessage fail: ' . $e->getMessage());
        smsLogCommunication($conn ?? null, $phone, 'outgoing', $message, 'failed');
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
    // City history lives on warranty tables (old_serials); Mcode is no longer used.
    return getKnownCityFromOldSerials($phone, $conn);
}

function getMcodeTotalScore($phone, $conn)
{
    $phone = smsNormalizePhone($phone);
    if ($phone === '') {
        return 0;
    }
    $hasScore = smsHasColumn($conn, 'old_serials', 'score');
    if (!$hasScore) {
        return 0;
    }
    if (smsHasColumn($conn, 'old_serials', 'category')) {
        $sql = "SELECT COALESCE(SUM(score), 0) AS total FROM old_serials
                WHERE phone = ? AND phone != '0' AND score > 0
                  AND category = 'seller_to_end_user'";
    } else {
        $sql = "SELECT COALESCE(SUM(score), 0) AS total FROM old_serials
                WHERE phone = ? AND phone != '0' AND score > 0";
    }
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
    // Legacy name: M-prefix sellers now register only via old_serials.
    register_seller_unused_serial($serial, $phone, $conn, 'old_serials');
    return '';
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
    smsMaybeSendClubWelcome($conn, $phone, $serial);

    $sqlMainTpl = smsResolveMessage($conn, 'client_prompt_after_serial', [
        '@1' => 'تبریز',
        '@2' => '3350',
    ]);
    if ($sqlMainTpl !== '') {
        SendMessage($phone, $sqlMainTpl);
    }

    $sqlAd = "SELECT message FROM ad_sms";
    $resultAd = mysqli_query($conn, $sqlAd);

    if ($resultAd && mysqli_num_rows($resultAd) > 0) {
        while ($adRow = mysqli_fetch_assoc($resultAd)) {
            $message = $adRow['message'];
            SendMessage($phone, $message);
            sleep(4); // delay between messages
        }
    }
}

/**
 * Seller registration: ads → inherit known city or ask city only (no km).
 * Writes only to old_serials / warranty tables (no Mcode).
 */
function register_seller_unused_serial($serial, $phone, $conn, $table = 'old_serials')
{
    $knownCity = getKnownCityFromOldSerials($phone, $conn);

    $currentTime = date("Y-m-d");
    $syncMs = (int) round(microtime(true) * 1000);
    $hasSync = smsHasColumn($conn, $table, 'sync_updated_ms');
    $hasScore = smsHasColumn($conn, $table, 'score');
    $hasCategory = smsHasColumn($conn, $table, 'category');
    $score = ivRegistrationLotteryScore($conn, $serial);
    $category = 'seller_to_end_user';

    // Bind phone first so a city reply can find this session while ads are sending.
    if ($knownCity) {
        $sets = ['phone = ?', 'time = ?', 'city = ?'];
        $types = 'sss';
        $params = [$phone, $currentTime, $knownCity];
        if ($hasScore) {
            $sets[] = 'score = GREATEST(IFNULL(score, 0), ?)';
            $types .= 'i';
            $params[] = $score;
        }
        if ($hasCategory) {
            $sets[] = 'category = ?';
            $types .= 's';
            $params[] = $category;
        }
        if ($hasSync) {
            $sets[] = 'sync_updated_ms = ?';
            $types .= 'i';
            $params[] = $syncMs;
        }
        $types .= 's';
        $params[] = $serial;
        $sqlUpdate = "UPDATE `$table` SET " . implode(', ', $sets) . " WHERE serial = ?";
        $stmtUpdate = $conn->prepare($sqlUpdate);
        if ($stmtUpdate === false) {
            exit;
        }
        // bind_param needs references
        $bind = [$types];
        foreach ($params as $k => $v) {
            $bind[] = &$params[$k];
        }
        call_user_func_array([$stmtUpdate, 'bind_param'], $bind);
        if ($stmtUpdate->execute() && $stmtUpdate->affected_rows > 0) {
            $stmtUpdate->close();
            if (maybeDeferRegistrationForImageVerify($conn, $serial, $phone, [
                'city' => $knownCity,
                'confirm_column' => 'm',
            ])) {
                smsRecordRegistrationActivity($conn, $phone, $serial, [
                    'city' => $knownCity,
                    '_table' => $table,
                ]);
                smsMaybeSendClubWelcome($conn, $phone, $serial);
                return;
            }
            smsRecordRegistrationActivity($conn, $phone, $serial, [
                'city' => $knownCity,
                '_table' => $table,
            ]);
            smsMaybeSendClubWelcome($conn, $phone, $serial);
            SendConfirmSms($phone, $conn, 'm');
            $sqlAd = "SELECT message FROM ad_sms";
            $resultAd = mysqli_query($conn, $sqlAd);
            if ($resultAd && mysqli_num_rows($resultAd) > 0) {
                while ($row = mysqli_fetch_assoc($resultAd)) {
                    SendMessage($phone, $row['message']);
                    sleep(4);
                }
            }
            return;
        }
        $stmtUpdate->close();
    }

    $sets = ['phone = ?', 'time = ?'];
    $types = 'ss';
    $params = [$phone, $currentTime];
    if ($hasScore) {
        $sets[] = 'score = GREATEST(IFNULL(score, 0), ?)';
        $types .= 'i';
        $params[] = $score;
    }
    if ($hasCategory) {
        $sets[] = 'category = ?';
        $types .= 's';
        $params[] = $category;
    }
    if ($hasSync) {
        $sets[] = 'sync_updated_ms = ?';
        $types .= 'i';
        $params[] = $syncMs;
    }
    $types .= 's';
    $params[] = $serial;
    $sqlUpdate = "UPDATE `$table` SET " . implode(', ', $sets) . " WHERE serial = ?";
    $stmtUpdate = $conn->prepare($sqlUpdate);
    if ($stmtUpdate === false) {
        exit;
    }
    $bind = [$types];
    foreach ($params as $k => $v) {
        $bind[] = &$params[$k];
    }
    call_user_func_array([$stmtUpdate, 'bind_param'], $bind);
    if (!$stmtUpdate->execute()) {
        $stmtUpdate->close();
        exit;
    }
    $stmtUpdate->close();

    smsRecordRegistrationActivity($conn, $phone, $serial, ['_table' => $table]);
    smsMaybeSendClubWelcome($conn, $phone, $serial);

    SendMessage($phone, smsResolveMessage($conn, 'seller_prompt_city'));
    $sqlAd = "SELECT message FROM ad_sms";
    $resultAd = mysqli_query($conn, $sqlAd);
    if ($resultAd && mysqli_num_rows($resultAd) > 0) {
        while ($row = mysqli_fetch_assoc($resultAd)) {
            SendMessage($phone, $row['message']);
            sleep(4);
        }
    }
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
        smsInboundLog('SendConfirmSms skipped: empty template column=' . $column . ' phone=' . smsNormalizePhone($phone));
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

/**
 * Second SMS after warranty confirm (باشگاه مشتریان → club_welcome_settings).
 * Self-contained: must not include crm-php/api/bootstrap.php (that file is a JSON API
 * bootstrap and would break this Melipayamak GET webhook).
 */
function smsMaybeSendClubWelcome($conn, $phone, $serial = null)
{
    if (!$conn) {
        return;
    }
    try {
        $phoneNorm = smsNormalizePhone($phone);
        if ($phoneNorm === '') {
            smsDebug('club welcome skipped: empty phone');
            return;
        }
        $res = @$conn->query(
            "SELECT setting_value FROM crm_settings WHERE setting_key = 'club_welcome_settings' LIMIT 1"
        );
        if (!$res) {
            smsDebug('club welcome skipped: crm_settings missing or unreadable');
            smsInboundLog('club welcome: no crm_settings row');
            return;
        }
        $settingRow = $res->fetch_assoc();
        if (!$settingRow) {
            smsDebug('club welcome skipped: settings not saved yet');
            return;
        }
        $decoded = json_decode((string) ($settingRow['setting_value'] ?? ''), true);
        if (!is_array($decoded) || empty($decoded['enabled'])) {
            smsDebug('club welcome skipped: disabled');
            return;
        }
        $template = trim((string) ($decoded['message'] ?? ''));
        if ($template === '') {
            smsDebug('club welcome skipped: empty message');
            return;
        }
        $row = smsFindClubWelcomeSerialRow($conn, $phoneNorm, $serial);
        if (!$row) {
            smsDebug('club welcome skipped: no serial row for ' . $phoneNorm);
            smsInboundLog('club welcome: no serial row for ' . $phoneNorm);
            return;
        }
        $serialVal = (string) ($row['serial'] ?? '');
        $kmVal = '';
        if (isset($row['km']) && $row['km'] !== null && $row['km'] !== '') {
            $kmVal = (string) (int) $row['km'];
        }
        $dateVal = trim((string) ($row['date_jalali'] ?? ''));
        if ($dateVal === '') {
            $dateVal = trim((string) ($row['time'] ?? $row['date'] ?? ''));
        }
        $body = str_replace(
            ['{سریال}', '{serial}', '{کیلومتر}', '{km}', '{تاریخ}', '{date}'],
            [$serialVal, $serialVal, $kmVal, $kmVal, $dateVal, $dateVal],
            $template
        );
        if (trim($body) === '') {
            return;
        }
        smsDebug('club welcome send to=' . $phoneNorm . ' serial=' . $serialVal);
        smsInboundLog('club welcome send to=' . $phoneNorm . ' serial=' . $serialVal);
        SendMessage($phoneNorm, $body);
    } catch (Throwable $e) {
        smsDebug('club welcome error: ' . $e->getMessage());
        smsInboundLog('club welcome error: ' . $e->getMessage());
    }
}

function smsFindClubWelcomeSerialRow($conn, $phoneNorm, $serial = null)
{
    $serialKey = $serial !== null ? strtoupper(trim((string) $serial)) : '';
    foreach (['old_serials', 'new_serials'] as $table) {
        if ($serialKey !== '') {
            $stmt = $conn->prepare("SELECT * FROM `$table` WHERE UPPER(serial) = ? LIMIT 1");
            if ($stmt) {
                $stmt->bind_param('s', $serialKey);
                $stmt->execute();
                $result = $stmt->get_result();
                $row = $result ? $result->fetch_assoc() : null;
                $stmt->close();
                if ($row) {
                    return $row;
                }
            }
        }
        $stmt = $conn->prepare(
            "SELECT * FROM `$table` WHERE phone = ? ORDER BY id DESC LIMIT 1"
        );
        if (!$stmt) {
            continue;
        }
        $stmt->bind_param('s', $phoneNorm);
        $stmt->execute();
        $result = $stmt->get_result();
        $row = $result ? $result->fetch_assoc() : null;
        $stmt->close();
        if ($row) {
            return $row;
        }
    }
    return null;
}

/** @deprecated Mcode table no longer used; seller city confirm goes through SToM_City_confirm / old_serials. */
function Mcode_City_confirm($city, $phone, $conn)
{
    if (!SToM_City_confirm($city, $phone, $conn)) {
        SendMessage($phone, smsResolveMessage($conn, 'registration_session_missing', smsPortalValues()));
    }
}



?>