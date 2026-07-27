<?php
/**
 * Batched deduplication of old_serials by serial value.
 * Web UI with progress bar, or CLI: php deduplicate-old-serials.php [--dry-run]
 */
session_start();

define('DEDUP_BATCH_SIZE', 500);
define('DEDUP_DIR', __DIR__);
define('DEDUP_PROGRESS_FILE', DEDUP_DIR . '/deduplicate-old-serials-progress.json');
define('DEDUP_ACTIONS_FILE', DEDUP_DIR . '/deduplicate-old-serials-actions.jsonl');
define('DEDUP_REPORT_FILE', DEDUP_DIR . '/deduplicate-old-serials-report.json');

$isCli = php_sapi_name() === 'cli';

if (!$isCli) {
    if (!isset($_SESSION['loggedin']) || $_SESSION['loggedin'] !== true) {
        http_response_code(403);
        header('Content-Type: text/html; charset=utf-8');
        echo '<p>دسترسی غیرمجاز — ابتدا از CRM وارد شوید.</p>';
        exit;
    }
}

require_once __DIR__ . '/db.php';

function dedupIsRegisteredPhone($phone)
{
    return $phone !== null && $phone !== '' && $phone !== '0';
}

function dedupJsonResponse(array $data, $status = 200)
{
    if (php_sapi_name() !== 'cli') {
        http_response_code($status);
        header('Content-Type: application/json; charset=utf-8');
    }
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
}

function dedupWriteProgress(array $state)
{
    file_put_contents(DEDUP_PROGRESS_FILE, json_encode($state, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));
}

function dedupReadProgress()
{
    if (!is_file(DEDUP_PROGRESS_FILE)) {
        return null;
    }
    $raw = file_get_contents(DEDUP_PROGRESS_FILE);
    if ($raw === false || $raw === '') {
        return null;
    }
    return json_decode($raw, true);
}

function dedupAppendJsonl(array $action)
{
    $line = json_encode($action, JSON_UNESCAPED_UNICODE) . "\n";
    file_put_contents(DEDUP_ACTIONS_FILE, $line, FILE_APPEND | LOCK_EX);
}

function dedupCountDuplicateSerialGroups($conn)
{
    $sql = 'SELECT COUNT(*) AS total FROM (
        SELECT serial FROM old_serials GROUP BY serial HAVING COUNT(*) > 1
    ) t';
    $result = $conn->query($sql);
    if (!$result) {
        return ['ok' => false, 'error' => $conn->error];
    }
    $row = $result->fetch_assoc();
    $result->free();
    return ['ok' => true, 'total' => (int) $row['total']];
}

function dedupFetchDuplicateSerialBatch($conn, $offset, $limit)
{
    $sql = 'SELECT serial FROM old_serials
            GROUP BY serial
            HAVING COUNT(*) > 1
            ORDER BY serial ASC
            LIMIT ? OFFSET ?';
    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        return ['ok' => false, 'error' => $conn->error];
    }
    $stmt->bind_param('ii', $limit, $offset);
    if (!$stmt->execute()) {
        $err = $stmt->error;
        $stmt->close();
        return ['ok' => false, 'error' => $err];
    }
    $result = $stmt->get_result();
    $serials = [];
    while ($row = $result->fetch_assoc()) {
        $serials[] = $row['serial'];
    }
    $result->free();
    $stmt->close();
    return ['ok' => true, 'serials' => $serials];
}

function dedupFetchRowsForSerial($conn, $serial)
{
    $sql = 'SELECT * FROM old_serials WHERE serial = ? ORDER BY id ASC';
    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        return ['ok' => false, 'error' => $conn->error];
    }
    $stmt->bind_param('s', $serial);
    if (!$stmt->execute()) {
        $err = $stmt->error;
        $stmt->close();
        return ['ok' => false, 'error' => $err];
    }
    $result = $stmt->get_result();
    $rows = [];
    while ($row = $result->fetch_assoc()) {
        $rows[] = $row;
    }
    $result->free();
    $stmt->close();
    return ['ok' => true, 'rows' => $rows];
}

function dedupDecideAction(array $rows)
{
    if (count($rows) < 2) {
        return null;
    }

    $registered = [];
    foreach ($rows as $row) {
        if (dedupIsRegisteredPhone($row['phone'] ?? null)) {
            $registered[] = $row;
        }
    }

    if (count($registered) > 0) {
        usort($registered, function ($a, $b) {
            return (int) $a['id'] <=> (int) $b['id'];
        });
        $keeper = $registered[0];
        $reason = count($registered) > 1
            ? 'kept_lowest_id_among_registered'
            : 'kept_registered_over_unregistered';
    } else {
        $keeper = $rows[0];
        $reason = 'kept_lowest_id_all_unregistered';
    }

    $deleteIds = [];
    $deletedRows = [];
    $keeperId = (int) $keeper['id'];

    foreach ($rows as $row) {
        if ((int) $row['id'] !== $keeperId) {
            $deleteIds[] = (int) $row['id'];
            $deletedRows[] = $row;
        }
    }

    if (count($deleteIds) === 0) {
        return null;
    }

    return [
        'serial' => $keeper['serial'],
        'kept_id' => $keeperId,
        'deleted_ids' => $deleteIds,
        'deleted_rows' => $deletedRows,
        'reason' => $reason,
    ];
}

function dedupDeleteByIds($conn, array $ids, $dryRun)
{
    if ($dryRun || count($ids) === 0) {
        return ['ok' => true, 'deleted' => count($ids)];
    }

    $placeholders = implode(',', array_fill(0, count($ids), '?'));
    $sql = "DELETE FROM old_serials WHERE id IN ($placeholders)";
    $stmt = $conn->prepare($sql);
    if (!$stmt) {
        return ['ok' => false, 'error' => $conn->error];
    }

    $types = str_repeat('i', count($ids));
    $stmt->bind_param($types, ...$ids);
    if (!$stmt->execute()) {
        $err = $stmt->error;
        $stmt->close();
        return ['ok' => false, 'error' => $err];
    }
    $deleted = $stmt->affected_rows;
    $stmt->close();
    return ['ok' => true, 'deleted' => $deleted];
}

function dedupProcessBatch($conn, $offset, $limit, $dryRun)
{
    set_time_limit(120);
    ignore_user_abort(true);

    // Live deletes shrink the duplicate set — always take from the top.
    // Dry run leaves rows in place — paginate with offset.
    $queryOffset = $dryRun ? $offset : 0;

    $fetch = dedupFetchDuplicateSerialBatch($conn, $queryOffset, $limit);
    if (!$fetch['ok']) {
        return ['success' => false, 'message' => $fetch['error']];
    }

    $serials = $fetch['serials'];
    if (count($serials) === 0) {
        return [
            'success' => true,
            'offset' => $offset,
            'batch_serials' => 0,
            'deleted_this_batch' => 0,
            'actions' => [],
        ];
    }

    $allDeleteIds = [];
    $actions = [];

    foreach ($serials as $serial) {
        $rowsResult = dedupFetchRowsForSerial($conn, $serial);
        if (!$rowsResult['ok']) {
            return ['success' => false, 'message' => $rowsResult['error']];
        }

        $action = dedupDecideAction($rowsResult['rows']);
        if ($action === null) {
            continue;
        }

        $actions[] = $action;
        $allDeleteIds = array_merge($allDeleteIds, $action['deleted_ids']);
    }

    $conn->begin_transaction();
    try {
        $deleteResult = dedupDeleteByIds($conn, $allDeleteIds, $dryRun);
        if (!$deleteResult['ok']) {
            throw new Exception($deleteResult['error']);
        }

        if ($dryRun) {
            $conn->rollback();
        } else {
            $conn->commit();
        }
    } catch (Exception $e) {
        $conn->rollback();
        return ['success' => false, 'message' => $e->getMessage()];
    }

    foreach ($actions as $action) {
        dedupAppendJsonl($action);
    }

    return [
        'success' => true,
        'offset' => $offset,
        'batch_serials' => count($serials),
        'deleted_this_batch' => count($allDeleteIds),
        'actions' => $actions,
    ];
}

function dedupHandleInit($conn, $dryRun)
{
    $count = dedupCountDuplicateSerialGroups($conn);
    if (!$count['ok']) {
        dedupJsonResponse(['success' => false, 'message' => $count['error']], 500);
        return;
    }

    file_put_contents(DEDUP_ACTIONS_FILE, '');
    $state = [
        'status' => 'running',
        'total' => $count['total'],
        'processed' => 0,
        'deleted_total' => 0,
        'dry_run' => $dryRun,
        'last_offset' => 0,
        'started_at' => date('c'),
        'updated_at' => date('c'),
    ];
    dedupWriteProgress($state);

    dedupJsonResponse([
        'success' => true,
        'total' => $count['total'],
        'dry_run' => $dryRun,
        'batch_size' => DEDUP_BATCH_SIZE,
    ]);
}

function dedupHandleBatch($conn, $offset, $limit, $dryRun)
{
    $progress = dedupReadProgress();
    if ($progress === null) {
        dedupJsonResponse(['success' => false, 'message' => 'Run action=init first'], 400);
        return;
    }

    $result = dedupProcessBatch($conn, $offset, $limit, $dryRun);
    if (!$result['success']) {
        $progress['status'] = 'error';
        $progress['error'] = $result['message'];
        $progress['updated_at'] = date('c');
        dedupWriteProgress($progress);
        dedupJsonResponse(['success' => false, 'message' => $result['message']], 500);
        return;
    }

    $processed = (int) $progress['processed'] + (int) $result['batch_serials'];
    $deletedTotal = (int) $progress['deleted_total'] + (int) $result['deleted_this_batch'];

    $progress['processed'] = $processed;
    $progress['deleted_total'] = $deletedTotal;
    $progress['updated_at'] = date('c');
    if ($result['batch_serials'] === 0 || $processed >= (int) $progress['total']) {
        $progress['status'] = 'completed';
    }
    dedupWriteProgress($progress);

    dedupJsonResponse([
        'success' => true,
        'offset' => $offset,
        'batch_serials' => (int) $result['batch_serials'],
        'processed' => $processed,
        'total' => (int) $progress['total'],
        'deleted_this_batch' => (int) $result['deleted_this_batch'],
        'deleted_total' => $deletedTotal,
        'dry_run' => $dryRun,
        'message' => $dryRun
            ? 'Dry run batch completed'
            : 'Batch completed',
    ]);
}

function dedupCountJsonlLines()
{
    if (!is_file(DEDUP_ACTIONS_FILE)) {
        return 0;
    }
    $count = 0;
    $fh = fopen(DEDUP_ACTIONS_FILE, 'rb');
    if (!$fh) {
        return 0;
    }
    while (!feof($fh)) {
        $line = fgets($fh);
        if ($line !== false && trim($line) !== '') {
            $count++;
        }
    }
    fclose($fh);
    return $count;
}

function dedupHandleFinalize($conn, $dryRun)
{
    $progress = dedupReadProgress();
    $actionCount = dedupCountJsonlLines();

    $report = [
        'generated_at' => date('c'),
        'dry_run' => $dryRun,
        'duplicate_serial_count' => $actionCount,
        'rows_deleted_count' => $progress['deleted_total'] ?? 0,
        'progress_file' => basename(DEDUP_PROGRESS_FILE),
        'actions_log_file' => basename(DEDUP_ACTIONS_FILE),
        'report_file' => basename(DEDUP_REPORT_FILE),
        'started_at' => $progress['started_at'] ?? null,
        'finished_at' => date('c'),
    ];

    file_put_contents(
        DEDUP_REPORT_FILE,
        json_encode($report, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT)
    );

    if ($progress !== null) {
        $progress['status'] = 'done';
        $progress['finished_at'] = date('c');
        $progress['updated_at'] = date('c');
        dedupWriteProgress($progress);
    }

    dedupJsonResponse([
        'success' => true,
        'report_file' => basename(DEDUP_REPORT_FILE),
        'actions_log_file' => basename(DEDUP_ACTIONS_FILE),
        'duplicate_serial_count' => $actionCount,
        'rows_deleted_count' => $report['rows_deleted_count'],
        'dry_run' => $dryRun,
    ]);
}

function dedupRunCli($conn, $dryRun)
{
    echo ($dryRun ? "Dry run\n" : "Live run\n");

    $count = dedupCountDuplicateSerialGroups($conn);
    if (!$count['ok']) {
        fwrite(STDERR, "Count failed: {$count['error']}\n");
        exit(1);
    }

    $total = $count['total'];
    echo "Duplicate serial groups: {$total}\n";

    file_put_contents(DEDUP_ACTIONS_FILE, '');
    $deletedTotal = 0;
    $processed = 0;
    $offset = 0;

    while (true) {
        $result = dedupProcessBatch($conn, $offset, DEDUP_BATCH_SIZE, $dryRun);
        if (!$result['success']) {
            fwrite(STDERR, "Batch failed: {$result['message']}\n");
            exit(1);
        }

        if ($result['batch_serials'] === 0) {
            break;
        }

        $processed += (int) $result['batch_serials'];
        $deletedTotal += (int) $result['deleted_this_batch'];
        $pct = $total > 0 ? round(min(100, ($processed / $total) * 100), 1) : 100;
        echo "Progress: {$processed}/{$total} ({$pct}%) — deleted so far: {$deletedTotal}\n";

        if ($dryRun) {
            $offset += DEDUP_BATCH_SIZE;
        }
    }

    $report = [
        'generated_at' => date('c'),
        'dry_run' => $dryRun,
        'duplicate_serial_count' => dedupCountJsonlLines(),
        'rows_deleted_count' => $deletedTotal,
        'actions_log_file' => basename(DEDUP_ACTIONS_FILE),
        'report_file' => basename(DEDUP_REPORT_FILE),
    ];
    file_put_contents(DEDUP_REPORT_FILE, json_encode($report, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));

    echo "Done. Report: " . DEDUP_REPORT_FILE . "\n";
}

function dedupRenderUi()
{
    header('Content-Type: text/html; charset=utf-8');
    $batchSize = DEDUP_BATCH_SIZE;
    ?>
<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>حذف سریال‌های تکراری — old_serials</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body class="bg-light">
<div class="container py-4" style="max-width: 720px;">
    <h1 class="h4 mb-3">حذف سریال‌های تکراری از old_serials</h1>
    <p class="text-muted small">
        در هر گروه تکراری: اگر یک ردیف شماره دارد همان نگه داشته می‌شود؛
        اگر همه خالی باشند یا چند شماره داشته باشند، ردیف با کمترین id نگه داشته می‌شود.
        هر دسته <?= (int) $batchSize ?> سریال پردازش می‌شود.
    </p>

    <div class="form-check mb-3">
        <input class="form-check-input" type="checkbox" id="dry-run" checked>
        <label class="form-check-label" for="dry-run">اجرای آزمایشی (بدون حذف واقعی)</label>
    </div>

    <button type="button" class="btn btn-primary mb-3" id="start-btn">شروع</button>

    <div class="progress mb-2" style="height: 28px;">
        <div class="progress-bar progress-bar-striped progress-bar-animated"
             id="progress-bar"
             role="progressbar"
             style="width: 0%;"
             aria-valuenow="0"
             aria-valuemin="0"
             aria-valuemax="100">0%</div>
    </div>

    <p id="status-text" class="small mb-2">آماده</p>
    <div id="error-panel" class="alert alert-danger d-none" role="alert"></div>
    <div id="done-panel" class="alert alert-success d-none" role="alert"></div>
</div>

<script>
(function () {
    const batchSize = <?= (int) $batchSize ?>;
    const startBtn = document.getElementById('start-btn');
    const dryRunEl = document.getElementById('dry-run');
    const progressBar = document.getElementById('progress-bar');
    const statusText = document.getElementById('status-text');
    const errorPanel = document.getElementById('error-panel');
    const donePanel = document.getElementById('done-panel');
    let running = false;
    let startedAt = null;

    function setProgress(processed, total) {
        const pct = total > 0 ? Math.min(100, Math.round((processed / total) * 100)) : 0;
        progressBar.style.width = pct + '%';
        progressBar.textContent = pct + '%';
        progressBar.setAttribute('aria-valuenow', String(pct));
    }

    function formatElapsed() {
        if (!startedAt) return '';
        const sec = Math.floor((Date.now() - startedAt) / 1000);
        const m = Math.floor(sec / 60);
        const s = sec % 60;
        return m + ':' + String(s).padStart(2, '0');
    }

    async function api(action, params) {
        const qs = new URLSearchParams({ action, ...params });
        const res = await fetch('deduplicate-old-serials.php?' + qs.toString(), {
            credentials: 'same-origin'
        });
        const data = await res.json();
        if (!res.ok || !data.success) {
            throw new Error(data.message || ('HTTP ' + res.status));
        }
        return data;
    }

    startBtn.addEventListener('click', async function () {
        if (running) return;
        running = true;
        startedAt = Date.now();
        startBtn.disabled = true;
        errorPanel.classList.add('d-none');
        donePanel.classList.add('d-none');
        setProgress(0, 1);

        const dryRun = dryRunEl.checked ? '1' : '0';

        try {
            const init = await api('init', { dry_run: dryRun });
            const total = init.total || 0;
            statusText.textContent = 'شروع — ' + total + ' گروه تکراری';

            if (total === 0) {
                setProgress(1, 1);
                statusText.textContent = 'سریال تکراری یافت نشد.';
                await api('finalize', { dry_run: dryRun });
                donePanel.textContent = 'کار تمام شد — تکراری وجود ندارد.';
                donePanel.classList.remove('d-none');
                return;
            }

            let deletedTotal = 0;
            let offset = 0;

            while (true) {
                const batch = await api('batch', {
                    offset: String(offset),
                    limit: String(batchSize),
                    dry_run: dryRun
                });

                deletedTotal = batch.deleted_total || deletedTotal;
                setProgress(batch.processed, batch.total);
                statusText.textContent =
                    (batch.processed || 0) + ' / ' + (batch.total || total) +
                    ' — حذف شده: ' + deletedTotal +
                    ' — زمان: ' + formatElapsed();

                if ((batch.batch_serials || 0) === 0) {
                    break;
                }
                if (dryRun === '1') {
                    offset += batchSize;
                }
                await new Promise(function (r) { setTimeout(r, 50); });
            }

            progressBar.classList.remove('progress-bar-animated');
            const final = await api('finalize', { dry_run: dryRun });
            donePanel.innerHTML =
                'تمام شد. گروه‌های پردازش‌شده: ' + (final.duplicate_serial_count || 0) +
                ' — ردیف‌های حذف‌شده: ' + (final.rows_deleted_count || 0) +
                (dryRun === '1' ? ' (اجرای آزمایشی)' : '') +
                '<br>گزارش: <code>' + (final.report_file || '') + '</code>' +
                ' — جزئیات: <code>' + (final.actions_log_file || '') + '</code>';
            donePanel.classList.remove('d-none');
        } catch (err) {
            errorPanel.textContent = err.message || String(err);
            errorPanel.classList.remove('d-none');
            progressBar.classList.remove('progress-bar-animated');
        } finally {
            running = false;
            startBtn.disabled = false;
        }
    });
})();
</script>
</body>
</html>
    <?php
}

// --- Router ---

if ($isCli) {
    $dryRun = in_array('--dry-run', $argv ?? [], true);
    dedupRunCli($conn, $dryRun);
    $conn->close();
    exit;
}

$action = $_GET['action'] ?? null;

if ($action === null) {
    dedupRenderUi();
    $conn->close();
    exit;
}

$dryRun = isset($_GET['dry_run']) && $_GET['dry_run'] === '1';
$offset = isset($_GET['offset']) ? (int) $_GET['offset'] : 0;
$limit = isset($_GET['limit']) ? (int) $_GET['limit'] : DEDUP_BATCH_SIZE;

switch ($action) {
    case 'init':
        dedupHandleInit($conn, $dryRun);
        break;
    case 'batch':
        dedupHandleBatch($conn, $offset, $limit, $dryRun);
        break;
    case 'finalize':
        dedupHandleFinalize($conn, $dryRun);
        break;
    default:
        dedupJsonResponse(['success' => false, 'message' => 'Unknown action'], 400);
}

$conn->close();
