package com.example.smsgateway.lan

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private const val TAG = "LanQrCode"

fun encodeUrlToBitmap(url: String, sizePx: Int = 512): Bitmap? {
    if (url.isBlank()) return null
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    } catch (e: Exception) {
        LanTraceLog.e(TAG, "QR encode failed for url=$url", e)
        null
    }
}

@Composable
fun LanQrCodeImage(
    url: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(url) { encodeUrlToBitmap(url) }
    if (bitmap != null) {
        Box(
            modifier = modifier
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = url,
                modifier = Modifier.size(200.dp),
            )
        }
    }
}
