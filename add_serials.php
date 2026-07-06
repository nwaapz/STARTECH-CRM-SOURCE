<?php
/**
 * Bulk-add serial numbers to gateway_db.
 * Supports optional prefix + numeric range, or numeric range only.
 * Protect this file on the server (HTTP auth / IP allowlist).
 */

error_reporting(E_ALL);
ini_set('display_errors', 1);

header('Content-Type: text/html; charset=utf-8');

require_once __DIR__ . '/jalali_helper.php';

const MAX_BATCH_SIZE = 10000;
const MAX_SERIAL_LENGTH = 32;

$servername = 'localhost';
$username = 'startech_sms';
$password = '101010';
$dbname = 'startech_sms';

$messages = [];
$errors = [];
$result = null;

function h(string $value): string
{
    return htmlspecialchars($value, ENT_QUOTES, 'UTF-8');
}

function connectDb(): mysqli
{
    global $servername, $username, $password, $dbname;

    $conn = mysqli_connect($servername, $username, $password, $dbname);
    if (!$conn) {
        throw new RuntimeException('Database connection failed: ' . mysqli_connect_error());
    }
    mysqli_set_charset($conn, 'utf8mb4');
    return $conn;
}

function generateSerials(string $prefix, int $start, int $end, int $padWidth): array
{
    $serials = [];
    $prefix = strtoupper(trim($prefix));

    if ($prefix !== '' && !preg_match('/^[A-Za-z0-9]+$/', $prefix)) {
        throw new InvalidArgumentException('Prefix may only contain letters and digits.');
    }

    for ($n = $start; $n <= $end; $n++) {
        $numberPart = $padWidth > 0 ? str_pad((string) $n, $padWidth, '0', STR_PAD_LEFT) : (string) $n;
        $serial = strtoupper($prefix . $numberPart);

        if (strlen($serial) > MAX_SERIAL_LENGTH) {
            throw new InvalidArgumentException(
                'Serial "' . $serial . '" exceeds ' . MAX_SERIAL_LENGTH . ' characters.'
            );
        }

        if (!preg_match('/^[A-Za-z0-9]+$/', $serial)) {
            throw new InvalidArgumentException('Serial "' . $serial . '" contains invalid characters.');
        }

        $serials[] = $serial;
    }

    return $serials;
}

function validateOptionalFields(?string $date, ?string $km): array
{
    $validatedDate = null;
    $validatedKm = null;

    if ($date !== null && $date !== '') {
        $dt = DateTime::createFromFormat('Y-m-d', $date);
        $dateErrors = DateTime::getLastErrors();
        if (!$dt || ($dateErrors['warning_count'] ?? 0) > 0 || ($dateErrors['error_count'] ?? 0) > 0) {
            throw new InvalidArgumentException('Invalid date. Use YYYY-MM-DD format.');
        }
        $validatedDate = gregorianStringToJalali($dt->format('Y-m-d'));
    }

    if ($km !== null && $km !== '') {
        if (!preg_match('/^-?\d+$/', $km)) {
            throw new InvalidArgumentException('KM must be an integer.');
        }
        $validatedKm = (int) $km;
    }

    return [$validatedDate, $validatedKm];
}

function insertSerials(
    mysqli $conn,
    array $serials,
    ?string $phone,
    ?string $date,
    ?string $city,
    ?int $km
): array {
    $inserted = 0;
    $skipped = 0;

    $sql = 'INSERT IGNORE INTO gateway_db (serial, phone, date, city, km) VALUES (?, ?, ?, ?, ?)';
    $stmt = mysqli_prepare($conn, $sql);
    if (!$stmt) {
        throw new RuntimeException('Prepare failed: ' . mysqli_error($conn));
    }

    $phoneValue = ($phone !== null && $phone !== '') ? $phone : null;
    $cityValue = ($city !== null && $city !== '') ? $city : null;

    mysqli_begin_transaction($conn);

    try {
        foreach ($serials as $serialValue) {
            $bindSerial = $serialValue;
            $bindPhone = $phoneValue;
            $bindDate = $date;
            $bindCity = $cityValue;
            $bindKm = $km;

            mysqli_stmt_bind_param(
                $stmt,
                'ssssi',
                $bindSerial,
                $bindPhone,
                $bindDate,
                $bindCity,
                $bindKm
            );

            if (!mysqli_stmt_execute($stmt)) {
                throw new RuntimeException('Insert failed: ' . mysqli_stmt_error($stmt));
            }

            if (mysqli_stmt_affected_rows($stmt) > 0) {
                $inserted++;
            } else {
                $skipped++;
            }
        }

        mysqli_commit($conn);
    } catch (Throwable $e) {
        mysqli_rollback($conn);
        mysqli_stmt_close($stmt);
        throw $e;
    }

    mysqli_stmt_close($stmt);

    return [
        'inserted' => $inserted,
        'skipped' => $skipped,
        'total' => count($serials),
    ];
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $prefix = trim($_POST['prefix'] ?? '');
    $startRaw = trim($_POST['start_number'] ?? '');
    $endRaw = trim($_POST['end_number'] ?? '');
    $padRaw = trim($_POST['pad_width'] ?? '0');
    $phone = trim($_POST['phone'] ?? '');
    $date = trim($_POST['date'] ?? '');
    $city = trim($_POST['city'] ?? '');
    $km = trim($_POST['km'] ?? '');

    try {
        if ($startRaw === '' || $endRaw === '') {
            throw new InvalidArgumentException('Start number and end number are required.');
        }

        if (!preg_match('/^\d+$/', $startRaw) || !preg_match('/^\d+$/', $endRaw)) {
            throw new InvalidArgumentException('Start and end numbers must be non-negative integers.');
        }

        if (!preg_match('/^\d+$/', $padRaw)) {
            throw new InvalidArgumentException('Pad width must be a non-negative integer.');
        }

        $start = (int) $startRaw;
        $end = (int) $endRaw;
        $padWidth = (int) $padRaw;

        if ($start > $end) {
            throw new InvalidArgumentException('Start number must be less than or equal to end number.');
        }

        $count = $end - $start + 1;
        if ($count > MAX_BATCH_SIZE) {
            throw new InvalidArgumentException('Maximum ' . MAX_BATCH_SIZE . ' serials per batch.');
        }

        [$validatedDate, $validatedKm] = validateOptionalFields($date, $km);
        $serials = generateSerials($prefix, $start, $end, $padWidth);

        $conn = connectDb();
        $result = insertSerials($conn, $serials, $phone, $validatedDate, $city, $validatedKm);
        mysqli_close($conn);

        $messages[] = 'Batch completed successfully.';
    } catch (Throwable $e) {
        $errors[] = $e->getMessage();
    }
}

$formPrefix = $_POST['prefix'] ?? '';
$formStart = $_POST['start_number'] ?? '';
$formEnd = $_POST['end_number'] ?? '';
$formPad = $_POST['pad_width'] ?? '0';
$formPhone = $_POST['phone'] ?? '';
$formDate = $_POST['date'] ?? '';
$formCity = $_POST['city'] ?? '';
$formKm = $_POST['km'] ?? '';
?>
<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>افزودن سریال‌ها</title>
    <style>
        body { font-family: Tahoma, Arial, sans-serif; margin: 2rem; background: #f7f7f7; color: #222; }
        .card { max-width: 640px; margin: 0 auto; background: #fff; padding: 1.5rem; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,.08); }
        h1 { margin-top: 0; font-size: 1.4rem; }
        label { display: block; margin-top: 1rem; font-weight: bold; }
        input[type="text"], input[type="number"], input[type="date"] {
            width: 100%; box-sizing: border-box; padding: .6rem; margin-top: .35rem; border: 1px solid #ccc; border-radius: 4px;
        }
        .hint { color: #666; font-size: .9rem; margin-top: .25rem; }
        button { margin-top: 1.25rem; padding: .7rem 1.2rem; background: #1565c0; color: #fff; border: 0; border-radius: 4px; cursor: pointer; }
        button:hover { background: #0d47a1; }
        .msg { padding: .75rem; border-radius: 4px; margin-bottom: 1rem; }
        .ok { background: #e8f5e9; color: #2e7d32; }
        .err { background: #ffebee; color: #c62828; }
        .stats { background: #e3f2fd; padding: .75rem; border-radius: 4px; margin-bottom: 1rem; }
        .stats p { margin: .25rem 0; }
    </style>
</head>
<body>
    <div class="card">
        <h1>افزودن سریال‌ها به gateway_db</h1>

        <?php foreach ($messages as $message): ?>
            <div class="msg ok"><?= h($message) ?></div>
        <?php endforeach; ?>

        <?php foreach ($errors as $error): ?>
            <div class="msg err"><?= h($error) ?></div>
        <?php endforeach; ?>

        <?php if ($result !== null): ?>
            <div class="stats">
                <p><strong>کل:</strong> <?= (int) $result['total'] ?></p>
                <p><strong>اضافه‌شده:</strong> <?= (int) $result['inserted'] ?></p>
                <p><strong>رد شده (تکراری):</strong> <?= (int) $result['skipped'] ?></p>
            </div>
        <?php endif; ?>

        <form method="post">
            <label for="prefix">پیشوند (اختیاری)</label>
            <input type="text" id="prefix" name="prefix" value="<?= h($formPrefix) ?>" maxlength="20">
            <div class="hint">مثال: SN یا ABC — فقط حروف و عدد انگلیسی</div>

            <label for="start_number">شماره شروع *</label>
            <input type="number" id="start_number" name="start_number" value="<?= h($formStart) ?>" min="0" required>

            <label for="end_number">شماره پایان *</label>
            <input type="number" id="end_number" name="end_number" value="<?= h($formEnd) ?>" min="0" required>

            <label for="pad_width">تعداد صفر پیش‌رو (اختیاری)</label>
            <input type="number" id="pad_width" name="pad_width" value="<?= h($formPad) ?>" min="0" max="20">
            <div class="hint">مثال: ۳ → SN001 تا SN100</div>

            <label for="phone">تلفن (اختیاری — برای همه ردیف‌ها)</label>
            <input type="text" id="phone" name="phone" value="<?= h($formPhone) ?>" maxlength="20">

            <label for="date">تاریخ (اختیاری — برای همه ردیف‌ها)</label>
            <input type="date" id="date" name="date" value="<?= h($formDate) ?>">
            <div class="hint">در پایگاه داده به صورت شمسی ذخیره می‌شود (مثال: 1404/03/30)</div>

            <label for="city">شهر (اختیاری — برای همه ردیف‌ها)</label>
            <input type="text" id="city" name="city" value="<?= h($formCity) ?>" maxlength="100">

            <label for="km">کیلومتر (اختیاری — برای همه ردیف‌ها)</label>
            <input type="number" id="km" name="km" value="<?= h($formKm) ?>">

            <button type="submit">افزودن سریال‌ها</button>
        </form>
    </div>
</body>
</html>
