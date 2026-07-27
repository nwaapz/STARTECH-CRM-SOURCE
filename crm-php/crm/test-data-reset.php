<?php

function normalizeIranPhone($phone)
{
    $digits = preg_replace('/\D/', '', trim((string) $phone));
    if ($digits === '') {
        return '';
    }
    if (strpos($digits, '98') === 0 && strlen($digits) >= 12) {
        $digits = '0' . substr($digits, 2);
    }
    if (strlen($digits) === 10 && $digits[0] === '9') {
        $digits = '0' . $digits;
    }
    if (strlen($digits) === 11 && $digits[0] === '0') {
        return $digits;
    }
    return '';
}

function phoneMatchVariants($phone)
{
    $normalized = normalizeIranPhone($phone);
    if ($normalized === '') {
        return [];
    }
    $core = substr($normalized, 1);
    $variants = [$normalized, $core, '98' . $core];
    return array_values(array_unique($variants));
}

/**
 * SQL condition: match phone column whether stored as 0912... or 912...
 * Binds one param: normalized 0XXXXXXXXXX
 */
function sqlPhoneEqualsNormalized($column)
{
    return "CONCAT('0', IF(LEFT(IFNULL($column,''), 1) = '0', SUBSTRING($column, 2), IFNULL($column,''))) = ?";
}

/**
 * Match column against normalized phone and common literal variants (912..., 0912..., 98912...)
 */
function sqlPhoneMatchesAny($column, $variantCount)
{
    $normalizedMatch = sqlPhoneEqualsNormalized($column);
    if ($variantCount <= 0) {
        return $normalizedMatch;
    }
    $placeholders = implode(',', array_fill(0, $variantCount, '?'));
    return "($normalizedMatch OR $column IN ($placeholders))";
}

function bindPhoneMatch($stmt, $normalizedPhone, $variants)
{
    $types = 's' . str_repeat('s', count($variants));
    $params = array_merge([$normalizedPhone], $variants);
    $bind = [$types];
    foreach ($params as $i => $value) {
        $bind[] = &$params[$i];
    }
    call_user_func_array([$stmt, 'bind_param'], $bind);
}

function clearRegistrationForPhone($conn, $phone)
{
    $variants = phoneMatchVariants($phone);
    if (empty($variants)) {
        return ['success' => false, 'message' => 'شماره تلفن نامعتبر است'];
    }

    $counts = [
        'old_serials' => 0,
        'new_serials' => 0,
        'Mcode' => 0,
        'winners' => 0,
        'lottery_suspended_phones' => 0,
        'seller' => 0,
    ];

    $normalizedPhone = $variants[0];
    $phoneWhere = sqlPhoneMatchesAny('phone', count($variants));

    $stmtOld = $conn->prepare(
        "UPDATE old_serials SET phone = NULL, city = NULL, km = NULL, time = NULL WHERE $phoneWhere"
    );
    if ($stmtOld) {
        bindPhoneMatch($stmtOld, $normalizedPhone, $variants);
        $stmtOld->execute();
        $counts['old_serials'] = $stmtOld->affected_rows;
        $stmtOld->close();
    }

    $stmtNew = $conn->prepare(
        "UPDATE new_serials SET phone = NULL, km = NULL, time = NULL WHERE $phoneWhere"
    );
    if ($stmtNew) {
        bindPhoneMatch($stmtNew, $normalizedPhone, $variants);
        $stmtNew->execute();
        $counts['new_serials'] = $stmtNew->affected_rows;
        $stmtNew->close();
    }

    $stmtMcode = $conn->prepare(
        "UPDATE Mcode
         SET phone = '0',
             time = '0000-00-00 00:00:00',
             city = NULL,
             car_plate = NULL,
             score = 0,
             description = ''
         WHERE $phoneWhere"
    );
    if ($stmtMcode) {
        bindPhoneMatch($stmtMcode, $normalizedPhone, $variants);
        $stmtMcode->execute();
        $counts['Mcode'] = $stmtMcode->affected_rows;
        $stmtMcode->close();
    }

    $stmtWin = $conn->prepare("DELETE FROM winners WHERE $phoneWhere");
    if ($stmtWin) {
        bindPhoneMatch($stmtWin, $normalizedPhone, $variants);
        $stmtWin->execute();
        $counts['winners'] = $stmtWin->affected_rows;
        $stmtWin->close();
    }

    $stmtSusp = $conn->prepare("DELETE FROM lottery_suspended_phones WHERE $phoneWhere");
    if ($stmtSusp) {
        bindPhoneMatch($stmtSusp, $normalizedPhone, $variants);
        $stmtSusp->execute();
        $counts['lottery_suspended_phones'] = $stmtSusp->affected_rows;
        $stmtSusp->close();
    }

    $tableCheck = $conn->query("SHOW TABLES LIKE 'seller'");
    if ($tableCheck && $tableCheck->num_rows > 0) {
        $stmtSeller = $conn->prepare("UPDATE seller SET phone = NULL WHERE $phoneWhere");
        if ($stmtSeller) {
            bindPhoneMatch($stmtSeller, $normalizedPhone, $variants);
            $stmtSeller->execute();
            $counts['seller'] = $stmtSeller->affected_rows;
            $stmtSeller->close();
        }
    }
    if ($tableCheck) {
        $tableCheck->free();
    }

    return [
        'success' => true,
        'phone' => $variants[0],
        'counts' => $counts,
    ];
}

function requireTestDataAdminSession()
{
    session_start();
    if (!isset($_SESSION['loggedin']) || $_SESSION['loggedin'] !== true) {
        http_response_code(403);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['success' => false, 'message' => 'دسترسی غیرمجاز — ابتدا وارد CRM شوید'], JSON_UNESCAPED_UNICODE);
        exit;
    }
    $role = (int) ($_SESSION['role'] ?? 0);
    if ($role < 5) {
        http_response_code(403);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['success' => false, 'message' => 'فقط مدیران سطح بالا به این بخش دسترسی دارند'], JSON_UNESCAPED_UNICODE);
        exit;
    }
}

function ensureTestPhonesTable($conn)
{
    $sql = "CREATE TABLE IF NOT EXISTS crm_test_phones (
        phone VARCHAR(20) NOT NULL PRIMARY KEY,
        label VARCHAR(100) DEFAULT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";
    return $conn->query($sql);
}
