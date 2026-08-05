<?php
/**
 * Database credentials — preserved from legacy crm/db.php for current production.
 * Future clients: install.php writes this file.
 */
$PAYAMESH_DB = [
    'host' => 'localhost',
    'username' => 'startech_sms',
    'password' => '101010',
    'database' => 'startech_sms',
    'charset' => 'utf8mb4',
];

/** Device sync bearer token (rotate in Admin Setup). Empty = sync disabled until set. */
$PAYAMESH_SYNC_TOKEN = getenv('PAYAMESH_SYNC_TOKEN') ?: '88888888';

function payamesh_mysqli(): mysqli {
    global $PAYAMESH_DB;
    $conn = new mysqli(
        $PAYAMESH_DB['host'],
        $PAYAMESH_DB['username'],
        $PAYAMESH_DB['password'],
        $PAYAMESH_DB['database']
    );
    if ($conn->connect_error) {
        http_response_code(500);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['error' => 'DB connection failed']);
        exit;
    }
    $conn->set_charset($PAYAMESH_DB['charset'] ?? 'utf8mb4');
    return $conn;
}
