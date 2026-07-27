<?php
header('Content-Type: application/json');
include 'db.php';

$sql = "SELECT phone, time FROM winners ORDER BY time DESC LIMIT 50";
$result = $conn->query($sql);

$winners = [];
while ($row = $result->fetch_assoc()) {
    $winners[] = $row;
}

echo json_encode($winners);
?>
