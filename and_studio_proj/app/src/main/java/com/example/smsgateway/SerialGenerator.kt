package com.example.smsgateway

import com.example.smsgateway.data.GatewayDatabase
import com.example.smsgateway.data.SerialEntity
import com.example.smsgateway.data.SerialGroupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SerialGenerator(
    private val database: GatewayDatabase,
) {
    data class GenerateRequest(
        val prefix: String,
        val start: Int,
        val end: Int,
        val padWidth: Int = 0,
        val city: String? = null,
        val km: Int? = null,
        val description: String? = null,
        val score: Int = 1,
    )

    data class GenerateResult(
        val inserted: Int,
        val skipped: Int,
        val total: Int,
        val groupId: Long? = null,
    )

    data class GroupSummary(
        val id: Long,
        val prefix: String,
        val startNumber: Int,
        val endNumber: Int,
        val padWidth: Int,
        val rangeLabel: String,
        val description: String?,
        val score: Int,
        val total: Int,
        val used: Int,
        val unused: Int,
        val salePercentage: Double,
        val createdAt: Long,
    )

    data class UpdateGroupRequest(
        val description: String? = null,
        val score: Int? = null,
    )

    data class ExtendRangeRequest(
        val addBelow: Int? = null,
        val addAbove: Int? = null,
        val newStart: Int? = null,
        val newEnd: Int? = null,
    )

    data class ExtendRangeResult(
        val inserted: Int,
        val skipped: Int,
        val startNumber: Int,
        val endNumber: Int,
    )

    data class DuplicateRange(
        val startNumber: Int,
        val endNumber: Int,
        val count: Int,
        val rangeLabel: String,
    )

    class ValidationException(message: String) : Exception(message)

    class DuplicateSerialException(
        message: String,
        val duplicateCount: Int,
        val duplicateRanges: List<DuplicateRange>,
    ) : Exception(message)

    fun validate(request: GenerateRequest): Int {
        val prefix = SerialNormalizer.normalizePrefix(request.prefix)
        if (prefix.isNotEmpty() && !PREFIX_REGEX.matches(prefix)) {
            throw ValidationException("Prefix may only contain letters and digits.")
        }
        if (request.start > request.end) {
            throw ValidationException("Start number must be less than or equal to end number.")
        }
        if (request.start < 0 || request.end < 0) {
            throw ValidationException("Start and end numbers must be non-negative.")
        }
        if (request.padWidth < 0) {
            throw ValidationException("Pad width must be a non-negative integer.")
        }
        if (request.score < 1) {
            throw ValidationException("Score must be at least 1.")
        }

        val count = request.end - request.start + 1L
        if (count <= 0) {
            throw ValidationException("Invalid range.")
        }

        val sampleStart = buildSerial(prefix, request.start, request.padWidth)
        val sampleEnd = buildSerial(prefix, request.end, request.padWidth)
        if (sampleStart.length > MAX_SERIAL_LENGTH || sampleEnd.length > MAX_SERIAL_LENGTH) {
            throw ValidationException("Serial exceeds $MAX_SERIAL_LENGTH characters.")
        }

        return count.toInt()
    }

    fun buildSerials(request: GenerateRequest): List<String> {
        validate(request)
        val prefix = SerialNormalizer.normalizePrefix(request.prefix)
        return (request.start..request.end).map { number ->
            buildSerial(prefix, number, request.padWidth)
        }
    }

    fun buildRangeLabel(prefix: String, start: Int, end: Int, padWidth: Int): String {
        val trimmedPrefix = SerialNormalizer.normalizePrefix(prefix)
        return "${buildSerial(trimmedPrefix, start, padWidth)} – ${buildSerial(trimmedPrefix, end, padWidth)}"
    }

    suspend fun generate(
        request: GenerateRequest,
        chunkSize: Int = CHUNK_SIZE,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): GenerateResult = withContext(Dispatchers.IO) {
        val total = validate(request)
        val prefix = SerialNormalizer.normalizePrefix(request.prefix)
        val dao = database.serialDao()
        val groupDao = database.serialGroupDao()

        assertNoDuplicates(
            dao = dao,
            prefix = prefix,
            padWidth = request.padWidth,
            segments = listOf(request.start..request.end),
        )

        var inserted = 0
        var processed = 0

        val city = request.city?.trim()?.takeIf { it.isNotEmpty() }
        val km = request.km
        val description = request.description?.trim()?.takeIf { it.isNotEmpty() }

        val groupId = groupDao.insert(
            SerialGroupEntity(
                prefix = prefix,
                startNumber = request.start,
                endNumber = request.end,
                padWidth = request.padWidth,
                description = description,
                score = request.score,
                createdAt = System.currentTimeMillis(),
            ),
        )

        var number = request.start
        while (number <= request.end) {
            val chunkEnd = minOf(number + chunkSize - 1, request.end)
            val chunk = (number..chunkEnd).map { current ->
                SerialEntity(
                    serial = buildSerial(prefix, current, request.padWidth),
                    city = city,
                    km = km,
                    groupId = groupId,
                )
            }
            val upperSerials = chunk.map { it.serial }
            val existing = dao.findExistingUpperSerials(upperSerials).toSet()
            val newChunk = chunk.filter { it.serial !in existing }
            val rowIds = if (newChunk.isEmpty()) {
                emptyList()
            } else {
                dao.insertAll(newChunk)
            }
            inserted += rowIds.count { it != -1L }
            processed += chunk.size
            onProgress(processed, total)
            number = chunkEnd + 1
        }

        val finalGroupId = if (inserted > 0) {
            groupId
        } else {
            groupDao.deleteById(groupId)
            null
        }

        val result = GenerateResult(
            inserted = inserted,
            skipped = total - inserted,
            total = total,
            groupId = finalGroupId,
        )
        if (inserted > 0) {
            SyncEventWriter.getInstance(database).recordSerialsAdded(
                inserted = result.inserted,
                skipped = result.skipped,
                total = result.total,
            )
        }
        result
    }

    suspend fun updateGroup(
        groupId: Long,
        request: UpdateGroupRequest,
    ): SerialGroupEntity = withContext(Dispatchers.IO) {
        val groupDao = database.serialGroupDao()
        val group = groupDao.findById(groupId)
            ?: throw ValidationException("Serial group not found.")
        val score = request.score ?: group.score
        if (score < 1) {
            throw ValidationException("Score must be at least 1.")
        }
        val description = when {
            request.description != null -> request.description.trim().takeIf { it.isNotEmpty() }
            else -> group.description
        }
        groupDao.updateMetadata(groupId, description, score)
        groupDao.findById(groupId) ?: throw ValidationException("Serial group not found.")
    }

    suspend fun extendGroupRange(
        groupId: Long,
        request: ExtendRangeRequest,
    ): ExtendRangeResult = withContext(Dispatchers.IO) {
        val groupDao = database.serialGroupDao()
        val dao = database.serialDao()
        val group = groupDao.findById(groupId)
            ?: throw ValidationException("Serial group not found.")

        var newStart = group.startNumber
        var newEnd = group.endNumber

        if (request.addBelow != null) {
            if (request.addBelow < 1) {
                throw ValidationException("addBelow must be at least 1.")
            }
            newStart = group.startNumber - request.addBelow
        }
        if (request.addAbove != null) {
            if (request.addAbove < 1) {
                throw ValidationException("addAbove must be at least 1.")
            }
            newEnd = group.endNumber + request.addAbove
        }
        if (request.newStart != null) {
            if (request.newStart > group.startNumber) {
                throw ValidationException("newStart can only extend the range downward.")
            }
            newStart = request.newStart
        }
        if (request.newEnd != null) {
            if (request.newEnd < group.endNumber) {
                throw ValidationException("newEnd can only extend the range upward.")
            }
            newEnd = request.newEnd
        }

        if (newStart < 0) {
            throw ValidationException("Start number must be non-negative.")
        }
        if (newStart > newEnd) {
            throw ValidationException("Invalid range.")
        }

        val extensionCount = (if (newStart < group.startNumber) group.startNumber - newStart else 0) +
            (if (newEnd > group.endNumber) newEnd - group.endNumber else 0)
        if (extensionCount == 0) {
            throw ValidationException("No range extension specified.")
        }
        if (extensionCount > LARGE_BATCH_THRESHOLD) {
            throw ValidationException("Extension exceeds maximum of $LARGE_BATCH_THRESHOLD serials.")
        }

        validate(
            GenerateRequest(
                prefix = group.prefix,
                start = newStart,
                end = newEnd,
                padWidth = group.padWidth,
                score = group.score,
            ),
        )

        val segments = buildList {
            if (newStart < group.startNumber) {
                add(newStart..group.startNumber - 1)
            }
            if (newEnd > group.endNumber) {
                add(group.endNumber + 1..newEnd)
            }
        }
        assertNoDuplicates(
            dao = dao,
            prefix = group.prefix,
            padWidth = group.padWidth,
            segments = segments,
        )

        var inserted = 0
        var skipped = 0

        if (newStart < group.startNumber) {
            val result = insertRangeForGroup(
                dao = dao,
                prefix = group.prefix,
                padWidth = group.padWidth,
                groupId = groupId,
                start = newStart,
                end = group.startNumber - 1,
            )
            inserted += result.first
            skipped += result.second
        }
        if (newEnd > group.endNumber) {
            val result = insertRangeForGroup(
                dao = dao,
                prefix = group.prefix,
                padWidth = group.padWidth,
                groupId = groupId,
                start = group.endNumber + 1,
                end = newEnd,
            )
            inserted += result.first
            skipped += result.second
        }

        groupDao.updateRange(groupId, newStart, newEnd)

        if (inserted > 0) {
            SyncEventWriter.getInstance(database).recordSerialsAdded(
                inserted = inserted,
                skipped = skipped,
                total = extensionCount,
            )
        }

        ExtendRangeResult(
            inserted = inserted,
            skipped = skipped,
            startNumber = newStart,
            endNumber = newEnd,
        )
    }

    private suspend fun insertRangeForGroup(
        dao: com.example.smsgateway.data.SerialDao,
        prefix: String,
        padWidth: Int,
        groupId: Long,
        start: Int,
        end: Int,
    ): Pair<Int, Int> {
        var inserted = 0
        var skipped = 0
        var number = start
        while (number <= end) {
            val chunkEnd = minOf(number + CHUNK_SIZE - 1, end)
            val chunk = (number..chunkEnd).map { current ->
                SerialEntity(
                    serial = buildSerial(prefix, current, padWidth),
                    groupId = groupId,
                )
            }
            val upperSerials = chunk.map { it.serial }
            val existing = dao.findExistingUpperSerials(upperSerials).toSet()
            val newChunk = chunk.filter { it.serial !in existing }
            skipped += chunk.size - newChunk.size
            if (newChunk.isNotEmpty()) {
                inserted += dao.insertAll(newChunk).count { it != -1L }
            }
            number = chunkEnd + 1
        }
        return inserted to skipped
    }

    suspend fun deleteGroup(groupId: Long): Boolean = withContext(Dispatchers.IO) {
        val groupDao = database.serialGroupDao()
        if (groupDao.findById(groupId) == null) {
            return@withContext false
        }
        database.serialDao().deleteByGroupId(groupId)
        groupDao.deleteById(groupId)
        true
    }

    suspend fun listGroups(): List<GroupSummary> = withContext(Dispatchers.IO) {
        val groupDao = database.serialGroupDao()
        groupDao.listAll().map { group ->
            val total = groupDao.countByGroupId(group.id)
            val used = groupDao.countUsedByGroupId(group.id)
            val unused = total - used
            val salePercentage = if (total > 0) {
                kotlin.math.round(used * 1000.0 / total) / 10.0
            } else {
                0.0
            }
            GroupSummary(
                id = group.id,
                prefix = group.prefix,
                startNumber = group.startNumber,
                endNumber = group.endNumber,
                padWidth = group.padWidth,
                rangeLabel = buildRangeLabel(group.prefix, group.startNumber, group.endNumber, group.padWidth),
                description = group.description,
                score = group.score,
                total = total,
                used = used,
                unused = unused,
                salePercentage = salePercentage,
                createdAt = group.createdAt,
            )
        }
    }

    suspend fun getStats(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val dao = database.serialDao()
        dao.countAll() to dao.countUnused()
    }

    fun buildSerial(prefix: String, number: Int, padWidth: Int): String {
        val normalizedPrefix = SerialNormalizer.normalizePrefix(prefix)
        val numberPart = if (padWidth > 0) {
            number.toString().padStart(padWidth, '0')
        } else {
            number.toString()
        }
        val serial = normalizedPrefix + numberPart
        if (serial.length > MAX_SERIAL_LENGTH) {
            throw ValidationException("Serial \"$serial\" exceeds $MAX_SERIAL_LENGTH characters.")
        }
        if (!SERIAL_REGEX.matches(serial)) {
            throw ValidationException("Serial \"$serial\" contains invalid characters.")
        }
        return serial
    }

    private suspend fun assertNoDuplicates(
        dao: com.example.smsgateway.data.SerialDao,
        prefix: String,
        padWidth: Int,
        segments: List<IntRange>,
    ) {
        val duplicateNumbers = segments.flatMap { segment ->
            findDuplicateNumbers(dao, prefix, padWidth, segment.first, segment.last)
        }
        if (duplicateNumbers.isEmpty()) return

        val ranges = buildDuplicateRanges(prefix, padWidth, duplicateNumbers)
        val duplicateCount = duplicateNumbers.size
        throw DuplicateSerialException(
            message = "تولید انجام نشد — $duplicateCount سریال تکراری یافت شد.",
            duplicateCount = duplicateCount,
            duplicateRanges = ranges,
        )
    }

    private suspend fun findDuplicateNumbers(
        dao: com.example.smsgateway.data.SerialDao,
        prefix: String,
        padWidth: Int,
        start: Int,
        end: Int,
    ): List<Int> {
        val duplicates = mutableListOf<Int>()
        var number = start
        while (number <= end) {
            val chunkEnd = minOf(number + CHUNK_SIZE - 1, end)
            val chunkSerials = (number..chunkEnd).map { current ->
                buildSerial(prefix, current, padWidth)
            }
            val existing = dao.findExistingUpperSerials(chunkSerials).toSet()
            chunkSerials.forEachIndexed { index, serial ->
                if (serial in existing) {
                    duplicates.add(number + index)
                }
            }
            number = chunkEnd + 1
        }
        return duplicates
    }

    private fun buildDuplicateRanges(
        prefix: String,
        padWidth: Int,
        duplicateNumbers: List<Int>,
    ): List<DuplicateRange> {
        return compressToRanges(duplicateNumbers).map { (start, end) ->
            DuplicateRange(
                startNumber = start,
                endNumber = end,
                count = end - start + 1,
                rangeLabel = buildRangeLabel(prefix, start, end, padWidth),
            )
        }
    }

    companion object {
        const val MAX_SERIAL_LENGTH = 32
        const val CHUNK_SIZE = 5000
        const val LARGE_BATCH_THRESHOLD = 10_000

        private val PREFIX_REGEX = Regex("^[A-Za-z0-9]+$")
        private val SERIAL_REGEX = Regex("^[A-Za-z0-9]+$")

        fun compressToRanges(numbers: List<Int>): List<Pair<Int, Int>> {
            if (numbers.isEmpty()) return emptyList()
            val sorted = numbers.distinct().sorted()
            val ranges = mutableListOf<Pair<Int, Int>>()
            var rangeStart = sorted.first()
            var rangeEnd = sorted.first()
            for (index in 1 until sorted.size) {
                val current = sorted[index]
                if (current == rangeEnd + 1) {
                    rangeEnd = current
                } else {
                    ranges.add(rangeStart to rangeEnd)
                    rangeStart = current
                    rangeEnd = current
                }
            }
            ranges.add(rangeStart to rangeEnd)
            return ranges
        }
    }
}
