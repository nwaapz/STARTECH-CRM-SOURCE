<?php

require_once __DIR__ . '/test-data-reset.php';

const LOTTERY_PRESET_APCU_KEY = 'lottery_preset_winner';

function lotteryPresetStoragePath(): string
{
    return sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'crm_lottery_preset_winner.txt';
}

function lotteryPresetReadShared(): ?string
{
    if (function_exists('apcu_fetch')) {
        $value = apcu_fetch(LOTTERY_PRESET_APCU_KEY);
        if ($value !== false && $value !== '') {
            return (string) $value;
        }
        return null;
    }

    $path = lotteryPresetStoragePath();
    if (!is_readable($path)) {
        return null;
    }

    $value = trim((string) file_get_contents($path));
    return $value !== '' ? $value : null;
}

function lotteryPresetWriteShared(?string $phone): void
{
    if (function_exists('apcu_store')) {
        if ($phone === null) {
            if (function_exists('apcu_delete')) {
                apcu_delete(LOTTERY_PRESET_APCU_KEY);
            }
        } else {
            apcu_store(LOTTERY_PRESET_APCU_KEY, $phone);
        }
        return;
    }

    $path = lotteryPresetStoragePath();
    if ($phone === null) {
        if (is_file($path)) {
            @unlink($path);
        }
        return;
    }

    file_put_contents($path, $phone, LOCK_EX);
}

function lotteryPresetGet(): ?string
{
    return lotteryPresetReadShared();
}

function lotteryPresetSet(?string $phone): void
{
    if ($phone === null || trim($phone) === '') {
        lotteryPresetWriteShared(null);
        return;
    }

    $normalized = normalizeIranPhone($phone);
    if ($normalized === '') {
        lotteryPresetWriteShared(null);
        return;
    }

    lotteryPresetWriteShared($normalized);
}

function lotteryPresetClear(): void
{
    lotteryPresetWriteShared(null);
}
