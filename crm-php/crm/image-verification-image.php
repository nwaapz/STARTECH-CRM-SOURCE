<?php
require_once 'db.php';
require_once __DIR__ . '/image-verification-helper.php';

requireImageVerificationSession(3);

$id = (int) ($_GET['id'] ?? 0);
if ($id <= 0) {
    http_response_code(400);
    exit('Invalid id');
}

$row = getVerificationById($conn, $id);
if (!$row || empty($row['image_path'])) {
    http_response_code(404);
    exit('Not found');
}

$base = ivGetWarrantyRoot() . '/';
$path = $base . ltrim(str_replace(['../', '..\\'], '', $row['image_path']), '/\\');

if (!is_file($path)) {
    http_response_code(404);
    exit('File not found');
}

$mime = $row['image_mime'] ?: mime_content_type($path) ?: 'image/jpeg';
header('Content-Type: ' . $mime);
header('Content-Length: ' . filesize($path));
header('Cache-Control: private, max-age=3600');
readfile($path);
$conn->close();
