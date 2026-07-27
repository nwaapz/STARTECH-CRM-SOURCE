<?php
/**
 * Lists all rows in old_serials where the same serial appears more than once.
 */
require_once __DIR__ . '/db.php';

header('Content-Type: text/html; charset=utf-8');

$sql = "
    SELECT o.*
    FROM old_serials o
    INNER JOIN (
        SELECT serial
        FROM old_serials
        GROUP BY serial
        HAVING COUNT(*) > 1
    ) dup ON o.serial = dup.serial
    ORDER BY o.serial ASC, o.id ASC
";

$result = $conn->query($sql);

if ($result === false) {
    http_response_code(500);
    die('Query failed: ' . htmlspecialchars($conn->error, ENT_QUOTES, 'UTF-8'));
}

$rows = [];
while ($row = $result->fetch_assoc()) {
    $rows[] = $row;
}
$result->free();

$duplicateSerialCount = count(array_unique(array_column($rows, 'serial')));
$duplicateRowCount = count($rows);

$conn->close();
?>
<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
    <meta charset="UTF-8">
    <title>سریال‌های تکراری در old_serials</title>
    <style>
        body { font-family: Tahoma, sans-serif; margin: 24px; }
        table { border-collapse: collapse; width: 100%; margin-top: 16px; }
        th, td { border: 1px solid #ccc; padding: 8px; text-align: center; }
        th { background: #f5f5f5; }
        tr:nth-child(even) { background: #fafafa; }
        .summary { margin-bottom: 12px; }
        .empty { color: #666; }
    </style>
</head>
<body>
    <h1>سریال‌های تکراری در جدول old_serials</h1>

    <div class="summary">
        <?php if ($duplicateRowCount === 0): ?>
            <p class="empty">هیچ سریال تکراری یافت نشد.</p>
        <?php else: ?>
            <p>
                <strong><?= (int) $duplicateSerialCount ?></strong> سریال تکراری —
                <strong><?= (int) $duplicateRowCount ?></strong> ردیف
            </p>
        <?php endif; ?>
    </div>

    <?php if ($duplicateRowCount > 0): ?>
        <table>
            <thead>
                <tr>
                    <?php foreach (array_keys($rows[0]) as $col): ?>
                        <th><?= htmlspecialchars($col, ENT_QUOTES, 'UTF-8') ?></th>
                    <?php endforeach; ?>
                </tr>
            </thead>
            <tbody>
                <?php foreach ($rows as $row): ?>
                    <tr>
                        <?php foreach ($row as $value): ?>
                            <td><?= htmlspecialchars((string) ($value ?? ''), ENT_QUOTES, 'UTF-8') ?></td>
                        <?php endforeach; ?>
                    </tr>
                <?php endforeach; ?>
            </tbody>
        </table>
    <?php endif; ?>
</body>
</html>