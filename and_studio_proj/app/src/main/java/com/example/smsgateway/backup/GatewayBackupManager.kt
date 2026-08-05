package com.example.smsgateway.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import com.example.smsgateway.JalaliHelper
import com.example.smsgateway.PhoneUtils
import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.license.LicenseStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class BackupReason { MANUAL, DAILY }

private const val BACKUP_FOLDER_NAME = "PayameshBackups"

data class BackupStorageTarget(
    val id: String,
    val labelKey: String,
    /** Absolute path to the PayameshBackups directory root parent (app files dir on that volume). */
    val rootDir: File,
    val isRemovable: Boolean,
    val freeBytes: Long,
) {
    val backupDir: File get() = File(rootDir, BACKUP_FOLDER_NAME)
}

data class BackupFileInfo(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val isDaily: Boolean,
)

data class BackupProgress(
    val phase: String,
    val percent: Int,
    val detail: String? = null,
)

data class BackupResult(
    val ok: Boolean,
    val message: String? = null,
    val file: File? = null,
)

object GatewayBackupManager {
    private const val TAG = "GatewayBackup"
    private const val KEEP_DAILY = 7
    private const val PREFS_NAME = "gateway_settings.xml"
    private const val PHOTO_DIR = "serial_photo_submissions"
    private const val BOT_MEDIA_DIR = "bot_media"

    fun listStorageTargets(context: Context): List<BackupStorageTarget> {
        val app = context.applicationContext
        val out = ArrayList<BackupStorageTarget>()
        val seen = HashSet<String>()

        fun add(root: File?, labelKey: String, removable: Boolean) {
            if (root == null || !root.exists()) return
            val abs = root.absolutePath
            if (!seen.add(abs)) return
            out.add(
                BackupStorageTarget(
                    id = abs,
                    labelKey = labelKey,
                    rootDir = root,
                    isRemovable = removable,
                    freeBytes = freeBytes(root),
                ),
            )
        }

        add(app.filesDir, "settings_backup_storage_internal", removable = false)

        val externalDirs = app.getExternalFilesDirs(null) ?: emptyArray()
        externalDirs.forEachIndexed { index, dir ->
            if (dir == null) return@forEachIndexed
            val removable = isRemovableVolume(app, dir)
            val label = when {
                removable -> "settings_backup_storage_sd"
                index == 0 -> "settings_backup_storage_shared"
                else -> "settings_backup_storage_external"
            }
            add(dir, label, removable)
        }
        return out
    }

    fun preferredAutoTarget(context: Context, stickyId: String?): BackupStorageTarget? {
        val targets = listStorageTargets(context)
        if (targets.isEmpty()) return null
        if (!stickyId.isNullOrBlank()) {
            targets.firstOrNull { it.id == stickyId }?.let { return it }
        }
        // Default: internal app storage (filesDir), not SD / shared external.
        return targets.firstOrNull { it.labelKey == "settings_backup_storage_internal" }
            ?: targets.first()
    }

    fun listBackups(target: BackupStorageTarget): List<BackupFileInfo> {
        val dir = target.backupDir
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            ?.map { f ->
                BackupFileInfo(
                    file = f,
                    name = f.name,
                    sizeBytes = f.length(),
                    modifiedMs = f.lastModified(),
                    isDaily = f.name.contains("_daily_", ignoreCase = true),
                )
            }
            ?.sortedByDescending { it.modifiedMs }
            .orEmpty()
    }

    fun deleteBackup(file: File): Boolean {
        return runCatching { file.delete() }.getOrDefault(false)
    }

    fun pruneOldDailyBackups(target: BackupStorageTarget, keep: Int = KEEP_DAILY) {
        val daily = listBackups(target).filter { it.isDaily }
        daily.drop(keep).forEach { deleteBackup(it.file) }
    }

    fun defaultBackupFileName(
        context: Context,
        reason: BackupReason = BackupReason.MANUAL,
    ): String {
        val phone = licensedPhoneForFileName(context)
        val stamp = JalaliHelper.nowJalaliDateTime()
            .replace('/', '-')
            .replace(' ', '_')
            .replace(':', '-')
        val kind = if (reason == BackupReason.DAILY) "daily" else "manual"
        return "payamesh_backup_${phone}_${kind}_$stamp.zip"
    }

    private fun licensedPhoneForFileName(context: Context): String {
        val raw = LicenseStore.getInstance(context).getLicensedMsisdn().orEmpty()
        val normalized = PhoneUtils.formatForDisplay(raw)
            ?.let { PhoneUtils.normalizeToAsciiDigits(it) }
            ?.filter { it.isDigit() }
            .orEmpty()
        return normalized.ifBlank { "unknown" }
    }

    suspend fun createBackup(
        context: Context,
        target: BackupStorageTarget,
        reason: BackupReason = BackupReason.MANUAL,
        onProgress: ((BackupProgress) -> Unit)? = null,
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val backupDir = target.backupDir
            if (!backupDir.exists() && !backupDir.mkdirs()) {
                return@withContext BackupResult(false, "Cannot create backup folder")
            }

            val outFile = File(backupDir, defaultBackupFileName(context, reason))
            val tmpFile = File(backupDir, "${outFile.name}.tmp")

            writeBackupZip(context, BufferedOutputStream(FileOutputStream(tmpFile)), reason, onProgress)

            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }

            if (reason == BackupReason.DAILY) {
                pruneOldDailyBackups(target)
            }

            onProgress?.invoke(BackupProgress("done", 100, outFile.name))
            BackupResult(true, "OK", outFile)
        } catch (e: Exception) {
            Log.w(TAG, "createBackup failed", e)
            BackupResult(false, e.message ?: "Backup failed")
        }
    }

    suspend fun createBackupToUri(
        context: Context,
        uri: Uri,
        reason: BackupReason = BackupReason.MANUAL,
        onProgress: ((BackupProgress) -> Unit)? = null,
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val output = context.contentResolver.openOutputStream(uri)
                ?: return@withContext BackupResult(false, "Cannot open selected location")
            output.use { stream ->
                writeBackupZip(context, BufferedOutputStream(stream), reason, onProgress)
            }
            onProgress?.invoke(BackupProgress("done", 100, null))
            BackupResult(true, "OK")
        } catch (e: Exception) {
            Log.w(TAG, "createBackupToUri failed", e)
            BackupResult(false, e.message ?: "Backup failed")
        }
    }

    suspend fun restoreBackupFromUri(
        context: Context,
        uri: Uri,
        onProgress: ((BackupProgress) -> Unit)? = null,
    ): BackupResult = withContext(Dispatchers.IO) {
        val tmpFile = File(context.cacheDir, "backup_restore_pick_${System.currentTimeMillis()}.zip")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext BackupResult(false, "Cannot open backup file")
            input.use { stream ->
                FileOutputStream(tmpFile).use { out -> stream.copyTo(out) }
            }
            return@withContext restoreBackup(context, tmpFile, onProgress)
        } catch (e: Exception) {
            Log.w(TAG, "restoreBackupFromUri failed", e)
            BackupResult(false, e.message ?: "Restore failed")
        } finally {
            tmpFile.delete()
        }
    }

    private fun writeBackupZip(
        context: Context,
        output: OutputStream,
        reason: BackupReason,
        onProgress: ((BackupProgress) -> Unit)?,
    ) {
        onProgress?.invoke(BackupProgress("checkpoint", 5, "Checkpoint…"))
        checkpointDatabase(context)

        onProgress?.invoke(BackupProgress("zip", 15, "Packing…"))
        ZipOutputStream(output).use { zos ->
            writeManifest(context, zos, reason)

            val dbBase = context.getDatabasePath(GatewayDatabase.DB_NAME)
            listOf(dbBase, File(dbBase.path + "-wal"), File(dbBase.path + "-shm"))
                .filter { it.exists() && it.length() > 0L }
                .forEachIndexed { i, f ->
                    onProgress?.invoke(
                        BackupProgress("zip", 20 + i * 10, "DB ${f.name}"),
                    )
                    putFile(zos, "databases/${f.name}", f)
                }

            val prefsFile = File(File(context.applicationInfo.dataDir, "shared_prefs"), PREFS_NAME)
            if (prefsFile.exists()) {
                onProgress?.invoke(BackupProgress("zip", 55, "Settings…"))
                putFile(zos, "shared_prefs/$PREFS_NAME", prefsFile)
            }

            onProgress?.invoke(BackupProgress("zip", 65, "Media…"))
            putDir(zos, "files/$PHOTO_DIR/", File(context.filesDir, PHOTO_DIR))
            putDir(zos, "files/$BOT_MEDIA_DIR/", File(context.filesDir, BOT_MEDIA_DIR))
        }
    }

    suspend fun restoreBackup(
        context: Context,
        zipFile: File,
        onProgress: ((BackupProgress) -> Unit)? = null,
    ): BackupResult = withContext(Dispatchers.IO) {
        if (!zipFile.isFile) {
            return@withContext BackupResult(false, "Backup file missing")
        }
        try {
            onProgress?.invoke(BackupProgress("prepare", 5, "Stopping…"))
            com.example.smsgateway.GatewayForegroundService.stop(context)
            GatewayDatabase.closeInstance()

            val staging = File(context.cacheDir, "backup_restore_${System.currentTimeMillis()}")
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()

            onProgress?.invoke(BackupProgress("extract", 20, "Extracting…"))
            unzipSafe(zipFile, staging)

            onProgress?.invoke(BackupProgress("apply", 55, "Applying database…"))
            val dbDir = context.getDatabasePath(GatewayDatabase.DB_NAME).parentFile
                ?: return@withContext BackupResult(false, "DB path missing")
            dbDir.mkdirs()
            // Remove live DB files before replace
            listOf(
                GatewayDatabase.DB_NAME,
                GatewayDatabase.DB_NAME + "-wal",
                GatewayDatabase.DB_NAME + "-shm",
                GatewayDatabase.DB_NAME + "-journal",
            ).forEach { name ->
                File(dbDir, name).delete()
            }
            val stagedDb = File(staging, "databases")
            stagedDb.listFiles()?.forEach { f ->
                f.copyTo(File(dbDir, f.name), overwrite = true)
            }

            onProgress?.invoke(BackupProgress("apply", 75, "Applying settings…"))
            val stagedPrefs = File(staging, "shared_prefs/$PREFS_NAME")
            if (stagedPrefs.isFile) {
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                prefsDir.mkdirs()
                stagedPrefs.copyTo(File(prefsDir, PREFS_NAME), overwrite = true)
            }

            onProgress?.invoke(BackupProgress("apply", 85, "Applying media…"))
            restoreDir(File(staging, "files/$PHOTO_DIR"), File(context.filesDir, PHOTO_DIR))
            restoreDir(File(staging, "files/$BOT_MEDIA_DIR"), File(context.filesDir, BOT_MEDIA_DIR))

            staging.deleteRecursively()
            onProgress?.invoke(BackupProgress("done", 100, "Restart required"))
            BackupResult(true, "OK")
        } catch (e: Exception) {
            Log.w(TAG, "restoreBackup failed", e)
            // Try to reopen DB even on failure
            runCatching { GatewayDatabase.getInstance(context) }
            BackupResult(false, e.message ?: "Restore failed")
        }
    }

    private fun checkpointDatabase(context: Context) {
        val db = GatewayDatabase.getInstance(context)
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { /* drain */ }
    }

    private fun writeManifest(context: Context, zos: ZipOutputStream, reason: BackupReason) {
        val json = JSONObject()
            .put("app", "payamesh-gateway")
            .put("package", context.packageName)
            .put("versionName", runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "")
            .put("dbName", GatewayDatabase.DB_NAME)
            .put("createdAt", System.currentTimeMillis())
            .put("reason", reason.name)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        zos.putNextEntry(ZipEntry("manifest.json"))
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun putFile(zos: ZipOutputStream, entryName: String, file: File) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input ->
            input.copyTo(zos)
        }
        zos.closeEntry()
    }

    private fun putDir(zos: ZipOutputStream, entryPrefix: String, dir: File) {
        if (!dir.isDirectory) return
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(dir).path.replace('\\', '/')
            putFile(zos, entryPrefix + rel, file)
        }
    }

    private fun restoreDir(from: File, to: File) {
        if (!from.isDirectory) return
        if (to.exists()) to.deleteRecursively()
        to.mkdirs()
        from.copyRecursively(to, overwrite = true)
    }

    private fun unzipSafe(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (name.contains("..") || name.startsWith("/")) {
                    throw IllegalStateException("Invalid backup entry: $name")
                }
                if (!isAllowedEntry(name)) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                val outFile = File(destDir, name)
                val canonicalDest = destDir.canonicalFile
                val canonicalOut = outFile.canonicalFile
                if (!canonicalOut.path.startsWith(canonicalDest.path + File.separator) &&
                    canonicalOut != canonicalDest
                ) {
                    throw IllegalStateException("Zip slip blocked: $name")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun isAllowedEntry(name: String): Boolean {
        return name == "manifest.json" ||
            name.startsWith("databases/") ||
            name.startsWith("shared_prefs/") ||
            name.startsWith("files/$PHOTO_DIR/") ||
            name.startsWith("files/$BOT_MEDIA_DIR/")
    }

    private fun isRemovableVolume(context: Context, dir: File): Boolean {
        return try {
            val sm = context.getSystemService(StorageManager::class.java) ?: return false
            val volume = sm.getStorageVolume(dir) ?: return false
            volume.isRemovable
        } catch (_: Exception) {
            !Environment.isExternalStorageEmulated(dir)
        }
    }

    private fun freeBytes(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            0L
        }
    }
}
