<?php
// easy_update_cities.php

// 1. Include your mysqli connection
include 'db.php'; // defines $conn

// 2. Remove PHP execution limits (for safety)
ini_set('max_execution_time', 0);
set_time_limit(0);
ignore_user_abort(true);
ini_set('memory_limit', '512M');

// 3. Select all Scode rows with a non-empty city
$selectSql = "
    SELECT serial, city
    FROM Scode
    WHERE city IS NOT NULL
      AND TRIM(city) != ''
";
$result = $conn->query($selectSql);
if (! $result) {
    die("❌ Select failed [{$conn->errno}]: {$conn->error}\n");
}

// 4. Prepare the UPDATE once
$updateStmt = $conn->prepare("
    UPDATE old_serials
       SET city = ?
     WHERE serial = ?
       AND (city IS NULL OR TRIM(city) = '')
");
if (! $updateStmt) {
    die("❌ Prepare failed [{$conn->errno}]: {$conn->error}\n");
}
$updateStmt->bind_param('ss', $cityParam, $serialParam);

// 5. Iterate and apply
echo "▶️  Starting updates at " . date('Y-m-d H:i:s') . "\n\n";
while ($row = $result->fetch_assoc()) {
    $serialParam = $row['serial'];
    $cityParam   = $row['city'];

    $updateStmt->execute();
    if ($updateStmt->errno) {
        echo "❌ Error (serial={$serialParam}): [{$updateStmt->errno}] {$updateStmt->error}\n";
    } elseif ($updateStmt->affected_rows > 0) {
        echo "✅ Updated serial={$serialParam}, city=\"{$cityParam}\"\n";
    }

    // Wait 100 ms before next iteration
    usleep(100000);
}

echo "\n🎉 All done at " . date('Y-m-d H:i:s') . "\n";

// 6. Cleanup
$updateStmt->close();
$conn->close();
