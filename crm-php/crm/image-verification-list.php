<?php
header('Content-Type: application/json; charset=utf-8');
require_once 'db.php';
require_once __DIR__ . '/image-verification-helper.php';

requireImageVerificationSession(3);

ensureImageVerificationSchema($conn);

$filter = trim((string) ($_GET['filter'] ?? 'pending'));
$page = max(1, (int) ($_GET['page'] ?? 1));
$perPage = min(100, max(10, (int) ($_GET['per_page'] ?? 25)));
$offset = ($page - 1) * $perPage;

$where = '1=1';
if ($filter === 'pending') {
    $where = "status = 'pending_review'";
} elseif ($filter === 'verified') {
    $where = "status IN ('verified', 'auto_verified') AND image_path IS NOT NULL AND image_path != ''";
} elseif ($filter === 'with_image') {
    $where = "image_path IS NOT NULL AND image_path != ''";
}

$countSql = "SELECT COUNT(*) AS cnt FROM image_verifications WHERE {$where}";
$countResult = $conn->query($countSql);
$total = 0;
if ($countResult && ($countRow = $countResult->fetch_assoc())) {
    $total = (int) $countRow['cnt'];
}

$sql = "SELECT id, serial, phone, code_type, km, city, status,
        submitted_at, created_at, verified_at, last_reject_at, last_reject_reason, reject_count, image_path
        FROM image_verifications
        WHERE {$where}
        ORDER BY COALESCE(submitted_at, verified_at, created_at) DESC
        LIMIT ? OFFSET ?";
$stmt = $conn->prepare($sql);
if (!$stmt) {
    echo json_encode(['success' => false, 'message' => 'خطای سرور'], JSON_UNESCAPED_UNICODE);
    $conn->close();
    exit;
}
$stmt->bind_param('ii', $perPage, $offset);
$stmt->execute();
$result = $stmt->get_result();
$rows = [];
while ($row = $result->fetch_assoc()) {
    $rows[] = [
        'id' => (int) $row['id'],
        'serial' => $row['serial'],
        'phone' => $row['phone'],
        'code_type' => $row['code_type'],
        'km' => $row['km'],
        'city' => $row['city'],
        'status' => $row['status'],
        'submitted_at' => $row['submitted_at'],
        'created_at' => $row['created_at'],
        'verified_at' => $row['verified_at'],
        'last_reject_at' => $row['last_reject_at'],
        'last_reject_reason' => $row['last_reject_reason'] ?? null,
        'reject_count' => (int) $row['reject_count'],
        'has_image' => !empty($row['image_path']),
    ];
}
$stmt->close();

echo json_encode([
    'success' => true,
    'rows' => $rows,
    'pagination' => [
        'page' => $page,
        'per_page' => $perPage,
        'total' => $total,
        'total_pages' => $perPage > 0 ? (int) ceil($total / $perPage) : 0,
    ],
], JSON_UNESCAPED_UNICODE);

$conn->close();
