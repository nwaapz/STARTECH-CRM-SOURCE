package com.example.smsgateway

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object SerialGroupExcelExporter {
    fun buildWorkbookXml(serials: List<String>): String {
        val rows = serials.joinToString(separator = "") { serial ->
            val value = escapeXml(SerialNormalizer.normalizeSerial(serial))
            "<Row><Cell><Data ss:Type=\"String\">$value</Data></Cell></Row>"
        }
        return """
            |<?xml version="1.0"?>
            |<?mso-application progid="Excel.Sheet"?>
            |<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"
            | xmlns:o="urn:schemas-microsoft-com:office:office"
            | xmlns:x="urn:schemas-microsoft-com:office:excel"
            | xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet"
            | xmlns:html="http://www.w3.org/TR/REC-html40">
            | <Worksheet ss:Name="Serials">
            |  <Table>
            |$rows
            |  </Table>
            | </Worksheet>
            |</Workbook>
        """.trimMargin()
    }

    fun sanitizeFilenamePart(value: String): String {
        val cleaned = value.replace(Regex("""[\\/:*?"<>|]+"""), "_").trim()
        return cleaned.ifEmpty { "serials" }
    }

    fun shareExcel(context: Context, serials: List<String>, filenameBase: String) {
        val filename = "${sanitizeFilenamePart(filenameBase)}.xls"
        val file = File(context.cacheDir, filename)
        file.writeText(buildWorkbookXml(serials), Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.ms-excel"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, filename)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.serial_group_export_chooser)),
        )
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
