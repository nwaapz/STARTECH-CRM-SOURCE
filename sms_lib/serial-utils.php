<?php

function isMprefixSerial($serial)
{
    $serial = strtoupper(trim($serial));
    return $serial !== '' && $serial[0] === 'M';
}

function isSToMRange($serial)
{
    $serial = strtoupper(trim($serial));
    if (!preg_match('/^S(\d+)$/', $serial, $m)) {
        return false;
    }
    $num = (int) $m[1];
    return $num >= 440000 && $num <= 450000;
}

/** Seller / فروشنده pipeline: city only, never km. */
function smsIsSellerCategory($category): bool
{
    return trim((string) $category) === 'seller_to_end_user';
}

/**
 * Seller SMS pipeline for old_serials: category seller_to_end_user or legacy S440000–S450000.
 * @param array|null $row optional DB row (needs category when not SToM range)
 */
function smsOldSerialIsSellerPipeline($serial, $row = null): bool
{
    if (isSToMRange($serial)) {
        return true;
    }
    if (is_array($row) && smsIsSellerCategory($row['category'] ?? '')) {
        return true;
    }
    return false;
}
