<?php
/**
 * GET /api/sync/pull?since=<ms> — changed serials + groups for the phone.
 * Always filters by sync_updated_ms (includes unused inventory).
 * Paginated: up to 2000 serials + 500 groups + deleted group tombstones.
 */
require_once dirname(__DIR__, 2) . '/config/database.php';
require_once dirname(__DIR__) . '/bootstrap.php';
require_once dirname(__DIR__) . '/serials_write.php';

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Authorization, Content-Type');
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    exit;
}

function require_sync_token_pull(): void {
    global $PAYAMESH_SYNC_TOKEN;
    $hdr = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if (!preg_match('/Bearer\s+(\S+)/i', $hdr, $m)) {
        http_response_code(401);
        echo json_encode(['error' => 'Unauthorized']);
        exit;
    }
    $token = $m[1];
    $expected = $PAYAMESH_SYNC_TOKEN;
    if ($expected === '' || !hash_equals($expected, $token)) {
        $conn = payamesh_mysqli();
        $row = $conn->query("SELECT meta_value FROM crm_sync_meta WHERE meta_key='device_token' LIMIT 1");
        $stored = $row ? ($row->fetch_assoc()['meta_value'] ?? '') : '';
        if ($stored === '' || !hash_equals($stored, $token)) {
            http_response_code(401);
            echo json_encode(['error' => 'Unauthorized']);
            exit;
        }
    }
}

require_sync_token_pull();
$conn = payamesh_mysqli();
$since = (int) ($_GET['since'] ?? 0);
$limitSerials = 2000;
$limitGroups = 500;
$now = (int) (microtime(true) * 1000);

sw_ensure_serial_groups_table($conn);

$serials = [];
$maxSerialMs = $since;
foreach (['old_serials', 'new_serials'] as $table) {
    $check = $conn->query("SHOW TABLES LIKE '$table'");
    if (!$check || $check->num_rows === 0) {
        continue;
    }
    sw_ensure_columns($conn, $table);
    if (!column_exists($conn, $table, 'sync_updated_ms')) {
        continue;
    }
    $hasJalali = column_exists($conn, $table, 'date_jalali');
    $hasCity = column_exists($conn, $table, 'city');
    $hasReg = column_exists($conn, $table, 'reg_source');
    $hasGroup = column_exists($conn, $table, 'lan_group_id');
    $hasCategory = column_exists($conn, $table, 'category');
    $hasScore = column_exists($conn, $table, 'score');

    $extra = ($hasCity ? ', city' : '')
        . ($hasJalali ? ', date_jalali' : '')
        . ($hasReg ? ', reg_source' : '')
        . ($hasGroup ? ', lan_group_id' : '')
        . ($hasCategory ? ', category' : '')
        . ($hasScore ? ', score' : '');

    $remaining = $limitSerials - count($serials);
    if ($remaining <= 0) {
        break;
    }

    $sql = "SELECT serial, phone, km, time$extra, sync_updated_ms FROM `$table`
            WHERE sync_updated_ms IS NOT NULL AND sync_updated_ms > ?
            ORDER BY sync_updated_ms ASC LIMIT $remaining";
    $stmt = $conn->prepare($sql);
    $stmt->bind_param('i', $since);
    $stmt->execute();
    $res = $stmt->get_result();
    while ($row = $res->fetch_assoc()) {
        $ms = isset($row['sync_updated_ms']) ? (int) $row['sync_updated_ms'] : $now;
        if ($ms > $maxSerialMs) {
            $maxSerialMs = $ms;
        }
        $serials[] = [
            'serial' => $row['serial'],
            'phone' => $row['phone'],
            'km' => isset($row['km']) && $row['km'] !== null ? (int) $row['km'] : null,
            'city' => $row['city'] ?? null,
            'dateJalali' => $row['date_jalali'] ?? null,
            'regSource' => $row['reg_source'] ?? null,
            'lanGroupId' => isset($row['lan_group_id']) && $row['lan_group_id'] !== null
                ? (int) $row['lan_group_id'] : null,
            'category' => $row['category'] ?? 'end_user_client',
            'score' => isset($row['score']) ? (int) $row['score'] : 0,
            'syncUpdatedMs' => $ms,
        ];
    }
    $stmt->close();
}

$groups = [];
$maxGroupMs = $since;
$sql = "SELECT * FROM serial_groups
        WHERE sync_updated_ms IS NOT NULL AND sync_updated_ms > ?
        ORDER BY sync_updated_ms ASC LIMIT $limitGroups";
$stmt = $conn->prepare($sql);
$stmt->bind_param('i', $since);
$stmt->execute();
$res = $stmt->get_result();
while ($row = $res->fetch_assoc()) {
    $ms = isset($row['sync_updated_ms']) ? (int) $row['sync_updated_ms'] : (int) ($row['created_at'] ?? $now);
    if ($ms > $maxGroupMs) {
        $maxGroupMs = $ms;
    }
    $groups[] = [
        'id' => (int) $row['id'],
        'localGroupId' => isset($row['sync_origin_lan_id']) && $row['sync_origin_lan_id'] !== null
            ? (int) $row['sync_origin_lan_id'] : null,
        'prefix' => (string) ($row['prefix'] ?? ''),
        'startNumber' => (int) ($row['start_number'] ?? 0),
        'endNumber' => (int) ($row['end_number'] ?? 0),
        'padWidth' => (int) ($row['pad_width'] ?? 0),
        'description' => isset($row['description']) && $row['description'] !== ''
            ? (string) $row['description'] : null,
        'score' => (int) ($row['score'] ?? 1),
        'category' => (string) ($row['category'] ?? 'end_user_client'),
        'generationMode' => (string) ($row['generation_mode'] ?? 'range'),
        'warrantyMonths' => isset($row['warranty_months']) && $row['warranty_months'] !== null
            ? (int) $row['warranty_months'] : null,
        'warrantyKm' => isset($row['warranty_km']) && $row['warranty_km'] !== null
            ? (int) $row['warranty_km'] : null,
        'imageVerificationRequired' => !empty($row['image_verification_required']),
        'createdAt' => (int) ($row['created_at'] ?? 0),
        'syncUpdatedMs' => $ms,
    ];
}
$stmt->close();

$deletedGroups = [];
$maxDeleteMs = $since;
$delCheck = $conn->query("SHOW TABLES LIKE 'sync_group_deletes'");
if ($delCheck && $delCheck->num_rows > 0) {
    $sql = 'SELECT remote_group_id, sync_origin_lan_id, sync_updated_ms FROM sync_group_deletes
            WHERE sync_updated_ms > ? ORDER BY sync_updated_ms ASC LIMIT 500';
    $stmt = $conn->prepare($sql);
    $stmt->bind_param('i', $since);
    $stmt->execute();
    $res = $stmt->get_result();
    while ($row = $res->fetch_assoc()) {
        $ms = (int) $row['sync_updated_ms'];
        if ($ms > $maxDeleteMs) {
            $maxDeleteMs = $ms;
        }
        $deletedGroups[] = [
            'remoteGroupId' => (int) $row['remote_group_id'],
            'localGroupId' => isset($row['sync_origin_lan_id']) && $row['sync_origin_lan_id'] !== null
                ? (int) $row['sync_origin_lan_id'] : null,
            'syncUpdatedMs' => $ms,
        ];
    }
    $stmt->close();
}

$cursor = max($maxSerialMs, $maxGroupMs, $maxDeleteMs, $since);
// If we hit page limits, keep cursor at last returned ms so phone can continue.
$hitLimit = count($serials) >= $limitSerials || count($groups) >= $limitGroups;
if (!$hitLimit && count($serials) === 0 && count($groups) === 0 && count($deletedGroups) === 0) {
    $cursor = max($cursor, $now);
}

echo json_encode([
    'cursor' => $cursor,
    'hasMore' => $hitLimit,
    'serials' => $serials,
    'groups' => $groups,
    'deletedGroups' => $deletedGroups,
], JSON_UNESCAPED_UNICODE);
