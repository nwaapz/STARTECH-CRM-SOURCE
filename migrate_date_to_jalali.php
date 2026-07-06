<?php
/**
 * One-time migration: change gateway_db.date from DATE to VARCHAR(10) for Jalali storage.
 * Run once if the table was already created with DATE type, then delete this file.
 */

error_reporting(E_ALL);
ini_set('display_errors', 1);

header('Content-Type: text/html; charset=utf-8');

require_once __DIR__ . '/db_config.php';

try {
    $conn = gatewayDbConnect();

    $sql = <<<'SQL'
ALTER TABLE gateway_db
MODIFY date VARCHAR(10) DEFAULT NULL
SQL;

    if (!mysqli_query($conn, $sql)) {
        throw new RuntimeException('Migration failed: ' . mysqli_error($conn));
    }

    mysqli_close($conn);

    echo '<h1>Migration complete</h1>';
    echo '<p>Column <strong>date</strong> is now VARCHAR(10) for Persian dates (e.g. 1404/03/30).</p>';
    echo '<p>New registrations will store Jalali dates automatically.</p>';
    echo '<p><strong>Note:</strong> Existing rows may still show old Gregorian values until updated.</p>';
    echo '<p>Delete this file after running.</p>';
} catch (Throwable $e) {
    http_response_code(500);
    echo '<h1>Migration failed</h1>';
    echo '<p>' . htmlspecialchars($e->getMessage()) . '</p>';
}
