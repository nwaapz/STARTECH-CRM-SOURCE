<?php
/**
 * One-time setup: creates the gateway_db table.
 * Run once via browser or CLI, then delete or protect this file on the server.
 */

error_reporting(E_ALL);
ini_set('display_errors', 1);

header('Content-Type: text/html; charset=utf-8');

$servername = 'localhost';
$username = 'startech_sms';
$password = '101010';
$dbname = 'startech_sms';

$conn = mysqli_connect($servername, $username, $password, $dbname);

if (!$conn) {
    http_response_code(500);
    echo '<h1>Database connection failed</h1>';
    echo '<p>' . htmlspecialchars(mysqli_connect_error()) . '</p>';
    exit;
}

mysqli_set_charset($conn, 'utf8mb4');

$sql = <<<'SQL'
CREATE TABLE IF NOT EXISTS gateway_db (
    id INT UNSIGNED NOT NULL AUTO_INCREMENT,
    serial VARCHAR(32) NOT NULL,
    phone VARCHAR(20) DEFAULT NULL,
    date VARCHAR(10) DEFAULT NULL,
    city VARCHAR(100) DEFAULT NULL,
    km INT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_serial (serial),
    KEY idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
SQL;

if (!mysqli_query($conn, $sql)) {
    http_response_code(500);
    echo '<h1>Setup failed</h1>';
    echo '<p>' . htmlspecialchars(mysqli_error($conn)) . '</p>';
    mysqli_close($conn);
    exit;
}

$tableCheck = mysqli_query($conn, "SHOW TABLES LIKE 'gateway_db'");
$tableExists = $tableCheck && mysqli_num_rows($tableCheck) > 0;

echo '<h1>Gateway DB setup complete</h1>';
echo '<p>Table <strong>gateway_db</strong> is ready.</p>';
echo '<ul>';
echo '<li><strong>id</strong> — auto-increment primary key</li>';
echo '<li><strong>serial</strong> — VARCHAR(32), unique, letters and digits</li>';
echo '<li><strong>phone</strong> — VARCHAR(20), optional</li>';
echo '<li><strong>date</strong> — VARCHAR(10), optional (Persian/Jalali, e.g. 1404/03/30)</li>';
echo '<li><strong>city</strong> — VARCHAR(100), optional (Persian supported)</li>';
echo '<li><strong>km</strong> — INT, optional</li>';
echo '</ul>';

if ($tableExists) {
    echo '<p>You can now use <code>add_serials.php</code> to insert serial numbers.</p>';
    echo '<p><strong>Security:</strong> delete or password-protect this setup file after use.</p>';
}

mysqli_close($conn);
