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
require_once __DIR__ . "/serial-utils.php";
require_once __DIR__ . "/lottery-sync.php";
if (is_file(__DIR__ . '/image-verification-helper.php')) {
    require_once __DIR__ . '/image-verification-helper.php';
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
    $message = "شماره سریال وارد نشده است." . PORTAL_SMS_SUFFIX;
    SendMessage($user, $message);
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

function smsRecordRegistrationActivity($conn, $phone, $serial, $extra = [])
{
    $store = null;
    foreach ([
        dirname(__DIR__) . '/api/activity_store.php',
        __DIR__ . '/../api/activity_store.php',
        __DIR__ . '/api/activity_store.php',
    ] as $candidate) {
        if (is_file($candidate)) {
            $store = $candidate;
            break;
        }
    }
    if ($store === null) {
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

function smsRecordRegistrationError($conn, $phone, $serial, $reason)
{
    foreach ([
        dirname(__DIR__) . '/api/serial_errors_store.php',
        __DIR__ . '/../api/serial_errors_store.php',
        __DIR__ . '/api/serial_errors_store.php',
    ] as $store) {
        if (is_file($store)) {
            require_once $store;
            sre_insert($conn, (string) $phone, (string) $serial, (string) $reason);
            return;
        }
    }
    // Fallback when SMS.php is not deployed beside /crm/api (same MySQL DB).
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

    // Ensure that we correctly extract the city and km values
    $city = isset($matchesCity[0][0]) ? $matchesCity[0][0] : "";
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
            $message = "کد نامعتبر است. لطفا مجددا کد گارانتی را بصورت صحیح وارد کنید. در صورت نیاز با واحد پشتیبانی تماس بگیرید ۱۵۷-۰۵۹۰-۰۹۳۶" . PORTAL_SMS_SUFFIX;
            SendMessage($user, $message);
        }

        $stmt->close();
    } else {
        smsDebug('mcode: SQL prepare failed');
        $message = "خطا در پردازش درخواست. لطفا دوباره تلاش کنید." . PORTAL_SMS_SUFFIX;
        SendMessage($user, $message);
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
            $message = "این سریال قبلاً با شماره تماس ثبت شده است." . PORTAL_SMS_SUFFIX;
            SendMessage($user, $message);
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
        $message = "کد نامعتبر است. لطفا مجددا کد گارانتی را بصورت صحیح وارد کنید. در صورت نیاز با واحد پشتیبانی تماس بگیرید ۱۵۷-۰۵۹۰-۰۹۳۶" . PORTAL_SMS_SUFFIX;
        SendMessage($user, $message);
    }

    $stmt->close();
} elseif (ctype_alpha($firstChar)) {
    smsDebug('route: old_serials');
    $sql = "SELECT * FROM old_serials WHERE serial = ?";
    $stmt = $conn->prepare($sql);
    $stmt->bind_param("s", $body);
    $stmt->execute();
    $result = $stmt->get_result();

    if ($result->num_rows > 0) {
        $row = $result->fetch_assoc();
        if ($row['phone'] !== null && $row['phone'] !== '' && $row['phone'] !== '0') {
            smsRecordRegistrationError($conn, $user, $body, 'already_used');
            Client_Serial_has_been_registered($body, $row['phone']);
            $message = "این سریال قبلاً با شماره تماس ثبت شده است." . PORTAL_SMS_SUFFIX;
            SendMessage($user, $message);
        } else {
            client_calling_for_unused_serial($body, $user, $conn);
        }
    } else {
        smsRecordRegistrationError($conn, $user, $serial, 'not_found');
        $message = "کد نامعتبر است. لطفا مجددا کد گارانتی را بصورت صحیح وارد کنید. در صورت نیاز با واحد پشتیبانی تماس بگیرید ۱۵۷-۰۵۹۰-۰۹۳۶" . PORTAL_SMS_SUFFIX;
        SendMessage($user, $message);
    }

} elseif (ctype_digit($firstChar)) {
    smsDebug('route: digit_hint');
    $message = "برای فعالسازی گارانتی تسمه تایم کد را بصورت زیر ثبت کنید. مثال S123456 در صورت نیاز با ما تماس بگیرید ۱۵۷-۰۵۹۰-۰۹۳۶" . PORTAL_SMS_SUFFIX;
    SendMessage($user, $message);

} else {
    smsDebug('route: invalid_format');
    $message = "فرمت سریال وارد شده معتبر نیست." . PORTAL_SMS_SUFFIX;
    SendMessage($user, $message);
}


function SendMessage($phone, $message)
{
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
        return true;
    } catch (Throwable $e) {
        error_log('[SMS.php] SendMessage failed: ' . $e->getMessage());
        smsDebug('SendMessage FAIL: ' . $e->getMessage());
        smsInboundLog('SendMessage FAIL to=' . $phone . ' err=' . $e->getMessage());
        return false;
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
                $message = "این سریال قبلاً با شماره تماس ثبت شده است." . PORTAL_SMS_SUFFIX;
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

    if ($knownCity) {
        $sql = "UPDATE Mcode SET phone = ?, time = NOW(), city = ?, score = ? WHERE serial = ?";
        if ($stmt = $conn->prepare($sql)) {
            $score = MCODE_SCORE_PER_SERIAL;
            $stmt->bind_param("ssis", $phone, $knownCity, $score, $serial);
            if ($stmt->execute() && $stmt->affected_rows > 0) {
                $stmt->close();
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
        $score = MCODE_SCORE_PER_SERIAL;
        $stmt->bind_param("sis", $phone, $score, $serial);

        if ($stmt->execute()) {
            if ($stmt->affected_rows > 0) {
                $stmt->close();
                sendMcodeSms($phone, "لطفا نام شهر خود را ارسال کنید.", $conn);
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
    $row = null;
    $stmt = $conn->prepare('SELECT * FROM old_serials WHERE serial = ? LIMIT 1');
    if ($stmt) {
        $stmt->bind_param('s', $serial);
        $stmt->execute();
        $res = $stmt->get_result();
        if ($res) {
            $row = $res->fetch_assoc() ?: null;
        }
        $stmt->close();
    }

    // Seller serials (category seller_to_end_user): city-only pipeline — never ask for km.
    if (function_exists('smsOldSerialIsSellerPipeline') && smsOldSerialIsSellerPipeline($serial, $row)) {
        register_seller_unused_serial($serial, $phone, $conn);
        return;
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

    $sqlMain = "SELECT a FROM main_sms LIMIT 1";
    $resultMain = mysqli_query($conn, $sqlMain);
    if ($resultMain && mysqli_num_rows($resultMain) > 0) {
        $rowMain = mysqli_fetch_assoc($resultMain);
        $message = $rowMain['a'];
        SendMessage($phone, $message);
    }

    $currentTime = date("Y-m-d");
    $syncMs = (int) round(microtime(true) * 1000);
    if (smsHasColumn($conn, 'old_serials', 'sync_updated_ms')) {
        $sqlUpdate = "UPDATE old_serials SET phone = ?, time = ?, sync_updated_ms = ? WHERE serial = ?";
        $stmtUpdate = $conn->prepare($sqlUpdate);
        if ($stmtUpdate === false) {
            exit;
        }
        $stmtUpdate->bind_param("ssis", $phone, $currentTime, $syncMs, $serial);
    } else {
        $sqlUpdate = "UPDATE old_serials SET phone = ?, time = ? WHERE serial = ?";
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
}

/**
 * Seller registration on old_serials: ads → inherit known city or ask city only (no km).
 * Legacy S440000–S450000 also mirrors into Mcode; category sellers stay in old_serials only.
 */
function register_seller_unused_serial($serial, $phone, $conn)
{
    $sqlAd = "SELECT message FROM ad_sms";
    $resultAd = mysqli_query($conn, $sqlAd);

    if ($resultAd && mysqli_num_rows($resultAd) > 0) {
        while ($row = mysqli_fetch_assoc($resultAd)) {
            SendMessage($phone, $row['message']);
            sleep(4);
        }
    }

    $knownCity = getKnownCityFromOldSerials($phone, $conn);
    if (!$knownCity) {
        $knownCity = getKnownCityForPhone($phone, $conn);
    }

    $currentTime = date("Y-m-d");
    $syncMs = (int) round(microtime(true) * 1000);
    $hasSync = smsHasColumn($conn, 'old_serials', 'sync_updated_ms');
    $syncToMcode = isSToMRange($serial);

    if ($knownCity) {
        if ($hasSync) {
            $sqlUpdate = "UPDATE old_serials SET phone = ?, time = ?, city = ?, sync_updated_ms = ? WHERE serial = ?";
            $stmtUpdate = $conn->prepare($sqlUpdate);
            if ($stmtUpdate === false) {
                exit;
            }
            $stmtUpdate->bind_param("sssis", $phone, $currentTime, $knownCity, $syncMs, $serial);
        } else {
            $sqlUpdate = "UPDATE old_serials SET phone = ?, time = ?, city = ? WHERE serial = ?";
            $stmtUpdate = $conn->prepare($sqlUpdate);
            if ($stmtUpdate === false) {
                exit;
            }
            $stmtUpdate->bind_param("ssss", $phone, $currentTime, $knownCity, $serial);
        }
        if ($stmtUpdate->execute() && $stmtUpdate->affected_rows > 0) {
            $stmtUpdate->close();
            smsRecordRegistrationActivity($conn, $phone, $serial, ['city' => $knownCity]);
            if ($syncToMcode) {
                syncSToMSerialToMcode($conn, $serial, $phone, $knownCity);
            }
            SendConfirmSms($phone, $conn, 'm');
            return;
        }
        $stmtUpdate->close();
    }

    if ($hasSync) {
        $sqlUpdate = "UPDATE old_serials SET phone = ?, time = ?, sync_updated_ms = ? WHERE serial = ?";
        $stmtUpdate = $conn->prepare($sqlUpdate);
        if ($stmtUpdate === false) {
            exit;
        }
        $stmtUpdate->bind_param("ssis", $phone, $currentTime, $syncMs, $serial);
    } else {
        $sqlUpdate = "UPDATE old_serials SET phone = ?, time = ? WHERE serial = ?";
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
    if ($syncToMcode) {
        syncSToMSerialToMcode($conn, $serial, $phone);
    }
    SendMessage($phone, "لطفا نام شهر خود را ارسال کنید.");
}

function getKnownCityFromOldSerials($phone, $conn)
{
    $sql = "SELECT city FROM old_serials WHERE phone = ? AND city IS NOT NULL AND city != '' ORDER BY time DESC LIMIT 1";
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

function client_calling_for_unused_serial_as_mcode($serial, $phone, $conn)
{
    register_seller_unused_serial($serial, $phone, $conn);
}

function SToM_City_confirm($city, $phone, $conn)
{
    $hasCategory = smsHasColumn($conn, 'old_serials', 'category');
    $sql = $hasCategory
        ? "SELECT serial, category FROM old_serials WHERE phone = ? AND (city IS NULL OR city = '') ORDER BY time DESC LIMIT 1"
        : "SELECT serial FROM old_serials WHERE phone = ? AND (city IS NULL OR city = '') ORDER BY time DESC LIMIT 1";
    $stmt = $conn->prepare($sql);
    if ($stmt === false) {
        return false;
    }

    $stmt->bind_param("s", $phone);
    if (!$stmt->execute()) {
        $stmt->close();
        return false;
    }

    $result = $stmt->get_result();
    if (!$result || !($row = $result->fetch_assoc())) {
        $stmt->close();
        return false;
    }

    $serial = $row['serial'];
    $stmt->close();

    if (!smsOldSerialIsSellerPipeline($serial, $row)) {
        return false;
    }

    logSToMRouting($serial, $phone, 'city_confirm');

    $updateSql = "UPDATE old_serials SET city = ? WHERE serial = ?";
    $stmtUpdate = $conn->prepare($updateSql);
    if ($stmtUpdate === false) {
        return false;
    }

    $stmtUpdate->bind_param("ss", $city, $serial);
    if (!$stmtUpdate->execute()) {
        $stmtUpdate->close();
        return false;
    }

    $stmtUpdate->close();
    if (isSToMRange($serial)) {
        syncSToMSerialToMcode($conn, $serial, $phone, $city);
    }
    SendConfirmSms($phone, $conn, 'm');
    return true;
}

function Client_Km_City_confirm($km, $city, $phone, $conn)
{
    $sql = "SELECT * FROM old_serials WHERE phone = ? AND (km IS NULL OR km = 0) ORDER BY time DESC LIMIT 1";
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

        if (smsOldSerialIsSellerPipeline($serial, $row)) {
            $updateSql = "UPDATE old_serials SET city = ? WHERE serial = ?";
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
            if (isSToMRange($serial)) {
                syncSToMSerialToMcode($conn, $serial, $phone, $city);
            }
            SendConfirmSms($phone, $conn, 'm');
            $stmt->close();
            return;
        }

        $updateSql = "UPDATE old_serials SET km = ?, city = ? WHERE serial = ?";
        $stmtUpdate = $conn->prepare($updateSql);

        if ($stmtUpdate === false) {
            $stmt->close();
            exit;
        }

        $stmtUpdate->bind_param("sss", $km, $city, $serial);

        if (!$stmtUpdate->execute()) {
            $stmtUpdate->close();
            $stmt->close();
            exit;
        }

        $stmtUpdate->close();
        SendConfirmSms($phone, $conn);
        $stmt->close();
    } else {
        $stmt->close();
        Mcode_City_confirm($city, $phone, $conn);
    }
}

function SendConfirmSms($phone, $conn, $column = 'c', $appendScore = null)
{
    $sqlMessage = "SELECT $column FROM main_sms LIMIT 1";
    $resultMessage = mysqli_query($conn, $sqlMessage);

    if ($resultMessage && mysqli_num_rows($resultMessage) > 0) {
        $messageRow = mysqli_fetch_assoc($resultMessage);
        $message = $messageRow[$column];

        if ($appendScore === null) {
            $appendScore = ($column === 'm');
        }
        if ($appendScore) {
            $message = appendScoreSuffix($message, $phone, $conn);
        }

        SendMessage($phone, $message);
    }
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
        SendConfirmSms($phone, $conn, 'm');
    } else {
        $stmt->close();
        $message = "سابقه ثبت نام یافت نشد. لطفا ابتدا کد سریال خود را ارسال کنید." . PORTAL_SMS_SUFFIX;
        SendMessage($phone, $message);
    }
}



?>