<?php
// get-sellers.php — paginated lottery participants aggregated by phone
header('Content-Type: application/json');
require_once 'db.php';
require_once __DIR__ . '/image-verification-helper.php';

ensureImageVerificationSchema($conn);

$forDraw = isset($_GET['for_draw']) && $_GET['for_draw'] === '1';
$page = max(1, (int) ($_GET['page'] ?? 1));
$perPage = min(100, max(5, (int) ($_GET['per_page'] ?? 20)));
$phoneFilter = trim($_GET['phone'] ?? '');
$cityFilter = trim($_GET['city'] ?? '');
$citiesParam = trim($_GET['cities'] ?? '');

$where = [
    "phone IS NOT NULL",
    "phone != ''",
    "phone != '0'",
    "score > 0",
    "NOT EXISTS (
        SELECT 1 FROM image_verifications iv
        WHERE iv.serial = Mcode.serial
        AND iv.status IN ('pending_capture', 'pending_review')
    )",
];
$params = [];
$types = '';

if ($phoneFilter !== '') {
    $where[] = "phone LIKE ?";
    $params[] = $phoneFilter . '%';
    $types .= 's';
}

if ($cityFilter !== '') {
    $where[] = "city LIKE ?";
    $params[] = $cityFilter . '%';
    $types .= 's';
}

if ($citiesParam !== '') {
    $cities = array_filter(array_map('trim', explode(',', $citiesParam)));
    if (count($cities) > 0) {
        $placeholders = implode(',', array_fill(0, count($cities), '?'));
        $where[] = "city IN ($placeholders)";
        foreach ($cities as $c) {
            $params[] = $c;
            $types .= 's';
        }
    }
}

$whereSql = implode(' AND ', $where);

$aggregateSql = "
    SELECT phone,
           SUM(score) AS total_score,
           COUNT(*) AS serial_count,
           MAX(CASE WHEN city IS NOT NULL AND TRIM(city) != '' THEN city END) AS city
    FROM Mcode
    WHERE $whereSql
    GROUP BY phone
";

// Count total participants in filtered pool
$countSql = "SELECT COUNT(*) AS cnt FROM ($aggregateSql) AS agg";
$countStmt = $conn->prepare($countSql);
if (!$countStmt) {
    echo json_encode(['success' => false, 'message' => 'خطا در آماده‌سازی پرس‌وجو']);
    exit;
}
if ($types !== '') {
    $countStmt->bind_param($types, ...$params);
}
$countStmt->execute();
$countResult = $countStmt->get_result();
$totalParticipants = (int) ($countResult->fetch_assoc()['cnt'] ?? 0);
$countStmt->close();

// Total score for weighted draw (entire filtered pool)
$scoreSql = "SELECT COALESCE(SUM(total_score), 0) AS pool_score FROM ($aggregateSql) AS agg";
$scoreStmt = $conn->prepare($scoreSql);
if (!$scoreStmt) {
    echo json_encode(['success' => false, 'message' => 'خطا در آماده‌سازی پرس‌وجو']);
    exit;
}
if ($types !== '') {
    $scoreStmt->bind_param($types, ...$params);
}
$scoreStmt->execute();
$scoreResult = $scoreStmt->get_result();
$poolTotalScore = (int) ($scoreResult->fetch_assoc()['pool_score'] ?? 0);
$scoreStmt->close();

if ($forDraw) {
    $drawSql = "
        SELECT phone, total_score AS score, serial_count, city
        FROM ($aggregateSql) AS agg
        ORDER BY total_score DESC
    ";
    $drawStmt = $conn->prepare($drawSql);
    if (!$drawStmt) {
        echo json_encode(['success' => false, 'message' => 'خطا در آماده‌سازی پرس‌وجو']);
        exit;
    }
    if ($types !== '') {
        $drawStmt->bind_param($types, ...$params);
    }
    $drawStmt->execute();
    $drawResult = $drawStmt->get_result();
    $pool = [];
    while ($row = $drawResult->fetch_assoc()) {
        $pool[] = [
            'phone' => $row['phone'],
            'score' => (int) $row['score'],
            'serialCount' => (int) $row['serial_count'],
            'city' => $row['city'] ?? ''
        ];
    }
    $drawStmt->close();

    echo json_encode([
        'success' => true,
        'pool' => $pool,
        'meta' => [
            'totalParticipants' => $totalParticipants,
            'poolTotalScore' => $poolTotalScore
        ]
    ]);
    $conn->close();
    exit;
}

$offset = ($page - 1) * $perPage;
$listSql = "
    SELECT phone, total_score AS score, serial_count, city
    FROM ($aggregateSql) AS agg
    ORDER BY total_score DESC
    LIMIT ? OFFSET ?
";

$listStmt = $conn->prepare($listSql);
if (!$listStmt) {
    echo json_encode(['success' => false, 'message' => 'خطا در آماده‌سازی پرس‌وجو']);
    exit;
}

$listTypes = $types . 'ii';
$listParams = $params;
$listParams[] = $perPage;
$listParams[] = $offset;

if ($listTypes !== 'ii' || count($listParams) > 2) {
    $listStmt->bind_param($listTypes, ...$listParams);
} else {
    $listStmt->bind_param('ii', $perPage, $offset);
}

$listStmt->execute();
$listResult = $listStmt->get_result();

$data = [];
while ($row = $listResult->fetch_assoc()) {
    $data[] = [
        'phone' => $row['phone'],
        'score' => (int) $row['score'],
        'serialCount' => (int) $row['serial_count'],
        'city' => $row['city'] ?? ''
    ];
}
$listStmt->close();

$totalPages = $perPage > 0 ? (int) ceil($totalParticipants / $perPage) : 1;

echo json_encode([
    'success' => true,
    'data' => $data,
    'pagination' => [
        'page' => $page,
        'perPage' => $perPage,
        'total' => $totalParticipants,
        'totalPages' => max(1, $totalPages)
    ],
    'meta' => [
        'poolTotalScore' => $poolTotalScore
    ]
]);

$conn->close();
?>
