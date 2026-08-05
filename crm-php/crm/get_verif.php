<?php
// index.php
// Ensure no whitespace or BOM before opening tag

// Include the existing DB connection
try {
    require_once 'db.php';  // assumes db.php defines $conn (mysqli)
} catch (Exception $e) {
    http_response_code(500);
    echo json_encode(['error' => 'مشکل اتصال به پایگاه داده']);
    exit;
}

// CORS and JSON response headers
header("Access-Control-Allow-Origin: *");
header("Content-Type: application/json; charset=UTF-8");
header("Access-Control-Allow-Methods: GET, POST, OPTIONS");
header("Access-Control-Allow-Headers: Content-Type, Authorization");

// Handle CORS preflight
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

// Gather input data: prefer JSON/POST body, fallback to GET
$raw = file_get_contents('php://input');
$data = json_decode($raw, true);
if (json_last_error() !== JSON_ERROR_NONE || empty($data)) {
    if (!empty($_POST)) {
        $data = $_POST;
    } else {
        $data = $_GET;
    }
}

// Map fields to Persian labels
$fieldLabels = [
    'serial' => 'سریال',
    'city'   => 'شهر',
    'phone'  => 'شماره تلفن',
    'km'     => 'کیلومتر'
];

// Validate required fields and collect missing labels
$missingLabels = [];
foreach (array_keys($fieldLabels) as $field) {
    if (!isset($data[$field]) || trim((string)$data[$field]) === '') {
        $missingLabels[] = $fieldLabels[$field];
    }
}
if (!empty($missingLabels)) {
    http_response_code(400);
    echo json_encode([
        'error' => 'اطلاعات ناقص است: ' . implode('، ', $missingLabels)
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// Sanitize inputs
$serial = trim((string)$data['serial']);
$city   = trim((string)$data['city']);
$phone  = trim((string)$data['phone']);
$km     = trim((string)$data['km']);
$now    = date('Y-m-d');  // current date in YYYY-MM-DD

// Prepare SELECT
$stmt = $conn->prepare("SELECT phone FROM old_serials WHERE serial = ?");
$stmt->bind_param('s', $serial);
$stmt->execute();
$stmt->bind_result($existingPhone);
if (!$stmt->fetch()) {
    http_response_code(404);
    echo json_encode(['error' => 'شماره سریال یافت نشد'], JSON_UNESCAPED_UNICODE);
    $stmt->close();
    exit;
}
$stmt->close();

// Check already registered: if phone is not 0 and not null
if ($existingPhone !== null && $existingPhone !== '0' && $existingPhone !== 0) {
    http_response_code(409);
    echo json_encode(['error' => 'این سریال قبلاً ثبت شده است'], JSON_UNESCAPED_UNICODE);
    exit;
}

// Prepare UPDATE with time column
$upd = $conn->prepare(
    "UPDATE old_serials SET city = ?, phone = ?, km = ?, time = ? WHERE serial = ?"
);
$upd->bind_param('sisss', $city, $phone, $km, $now, $serial);
if ($upd->execute()) {
    $bufHelper = dirname(__DIR__) . '/api/sync/sync_buffer_helpers.php';
    if (is_file($bufHelper)) {
        require_once $bufHelper;
        if (function_exists('sync_buffer_enqueue_serial_lookup')) {
            sync_buffer_enqueue_serial_lookup($conn, $serial, 'old_serials', 'portal');
        }
    }
    http_response_code(200);
    echo json_encode(['message' => 'ثبت با موفقیت انجام شد'], JSON_UNESCAPED_UNICODE);
} else {
    http_response_code(500);
    echo json_encode(['error' => 'خطای سرور، لطفاً دوباره تلاش کنید'], JSON_UNESCAPED_UNICODE);
}
$upd->close();
$conn->close();
?>
