<?php
// Show all PHP errors during debugging
ini_set('display_errors', 1);
ini_set('display_startup_errors', 1);
error_reporting(E_ALL);

// Include DB connection
include 'db.php';

// Prepare query
$sql = "
    SELECT 
        LEFT(serial, 1) AS group_prefix,
        MIN(serial) AS start_serial,
        MAX(serial) AS end_serial,
        COUNT(*) AS count
    FROM old_serials
    GROUP BY group_prefix
    ORDER BY group_prefix ASC
";

// Execute query
$result = $conn->query($sql);

// Check for query errors
if (!$result) {
    http_response_code(500);
    echo json_encode(['success' => false, 'message' => 'Query failed', 'error' => $conn->error]);
    exit;
}

// Build result array
$groups = [];
while ($row = $result->fetch_assoc()) {
    $groups[] = [
        'prefix' => $row['group_prefix'],
        'start' => $row['start_serial'],
        'end' => $row['end_serial'],
        'count' => $row['count']
    ];
}

// Output as JSON
header('Content-Type: application/json');
echo json_encode(['success' => true, 'groups' => $groups]);
