<?php
header("Content-Type: application/json; charset=UTF-8");
include "db.php";

$conn->set_charset("utf8");

if ($conn->connect_error) {
    die(json_encode(["error" => "خطا در اتصال به دیتابیس: " . $conn->connect_error]));
}

// Initialize default conditions
$whereConditions = "phone IS NOT NULL";

// Check if start_date and end_date are provided
if (isset($_GET['start_date']) && isset($_GET['end_date'])) {
    $start_date = $conn->real_escape_string($_GET['start_date']);
    $end_date   = $conn->real_escape_string($_GET['end_date']);
    
    // Add date range condition to the WHERE clause
    $whereConditions .= " AND DATE(time) BETWEEN '$start_date' AND '$end_date'";
}

// Get the date range between start_date and end_date
function getDateRange($start_date, $end_date) {
    $dates = [];
    $currentDate = strtotime($start_date);
    $endDate = strtotime($end_date);

    while ($currentDate <= $endDate) {
        $dates[] = date('Y-m-d', $currentDate);
        $currentDate = strtotime("+1 day", $currentDate);
    }

    return $dates;
}

// Fetch data from the database
$sql = "SELECT DATE(time) AS request_date, COUNT(*) AS count 
        FROM $OldSerialTable
        WHERE $whereConditions
        GROUP BY DATE(time) 
        ORDER BY request_date";

$result = $conn->query($sql);

$data = [];
if ($result) {
    while ($row = $result->fetch_assoc()) {
        $data[$row['request_date']] = $row['count'];
    }
}

// Generate the full date range
$dateRange = getDateRange($start_date, $end_date);

// Prepare the final result with all days in the range, including those with count = 0
$finalData = [];
foreach ($dateRange as $date) {
    // If the date is found in the fetched data, use its count, otherwise set count to 0
    $finalData[] = [
        'request_date' => $date,
        'count' => isset($data[$date]) ? $data[$date] : 0
    ];
}

// Return the result as JSON
echo json_encode($finalData);
$conn->close();
?>
