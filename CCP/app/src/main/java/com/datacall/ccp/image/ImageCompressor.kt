package com.datacall.ccp.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class CompressionResult(
    val file: File,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Int,
    val width: Int,
    val height: Int,
    val quality: Int
)

class ImageCompressor(private val context: Context) {
    companion object {
        const val MIN_SIZE_BYTES = 5 * 1024
        const val MAX_SIZE_BYTES = 20 * 1024
        const val MAX_DIMENSION = 160
        const val MIN_DIMENSION = 64
    }

    fun compressFromBytes(inputBytes: ByteArray, sourceLabel: String = "img"): CompressionResult {
        val original = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size)
            ?: throw IllegalArgumentException("Unable to decode image")
        return compressBitmap(original, inputBytes.size.toLong(), sourceLabel)
    }

    fun compressFromFile(inputFile: File): CompressionResult {
        val bytes = inputFile.readBytes()
        return compressFromBytes(bytes, inputFile.nameWithoutExtension)
    }

    private fun compressBitmap(original: Bitmap, originalSize: Long, sourceLabel: String): CompressionResult {
        val grayscale = toGrayscale(original)
        if (original != grayscale) original.recycle()

        var dimension = MAX_DIMENSION
        var lastResult: Triple<ByteArray, Int, Int>? = null

        while (dimension >= MIN_DIMENSION) {
            val scaled = scaleToMax(grayscale, dimension)
            var quality = 10
            while (quality >= 5) {
                val jpeg = encodeJpeg(scaled, quality)
                lastResult = Triple(jpeg, scaled.width, scaled.height)
                if (jpeg.size in MIN_SIZE_BYTES..MAX_SIZE_BYTES) {
                    if (scaled != grayscale) scaled.recycle()
                    grayscale.recycle()
                    return saveResult(jpeg, originalSize, scaled.width, scaled.height, quality, sourceLabel)
                }
                if (jpeg.size < MIN_SIZE_BYTES) {
                    if (scaled != grayscale) scaled.recycle()
                    grayscale.recycle()
                    return saveResult(jpeg, originalSize, scaled.width, scaled.height, quality, sourceLabel)
                }
                quality -= 2
            }
            if (scaled != grayscale) scaled.recycle()
            dimension -= 16
        }

        grayscale.recycle()
        val fallback = lastResult ?: throw IllegalStateException("Compression failed")
        if (fallback.first.size > MAX_SIZE_BYTES) {
            throw IllegalStateException(
                "Image too large after compression (${fallback.first.size} bytes). Try a simpler photo."
            )
        }
        return saveResult(fallback.first, originalSize, fallback.second, fallback.third, 5, sourceLabel)
    }

    private fun saveResult(
        jpeg: ByteArray,
        originalSize: Long,
        width: Int,
        height: Int,
        quality: Int,
        sourceLabel: String
    ): CompressionResult {
        val dir = File(context.filesDir, "compressed").apply { mkdirs() }
        val file = File(dir, "${sourceLabel}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { it.write(jpeg) }
        return CompressionResult(
            file = file,
            originalSizeBytes = originalSize,
            compressedSizeBytes = jpeg.size,
            width = width,
            height = height,
            quality = quality
        )
    }

    private fun toGrayscale(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val gray = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = source.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                gray.setPixel(x, y, Color.rgb(lum, lum, lum))
            }
        }
        return gray
    }

    private fun scaleToMax(source: Bitmap, maxDim: Int): Bitmap {
        val w = source.width
        val h = source.height
        val scale = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h, 1f)
        if (scale >= 1f) return source
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, nw, nh, true)
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
