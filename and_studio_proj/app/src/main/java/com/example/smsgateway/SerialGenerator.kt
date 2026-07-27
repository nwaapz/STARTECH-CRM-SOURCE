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
        val category: String = SerialGroupCategory.DEFAULT,
        val warrantyMonths: Int? = null,
        val warrantyKm: Int? = null,
        val imageVerificationRequired: Boolean = false,
    )

    data class GenerateResult(
        val inserted: Int,
        val skipped: Int,
        val total: Int,
        val groupId: Long? = null,
    )

    data class ComplexGenerateRequest(
        val count: Int,
        /** Exact number of letter characters in each serial (placed at random positions). */
        val letterCount: Int,
        /** Exact number of digit characters in each serial. */
        val digitCount: Int,
        /**
         * Optional fixed prefix prepended to every generated serial (e.g. "G").
         * Empty means no prefix — only the random letter/digit body.
         */
        val prefix: String = "",
        val city: String? = null,
        val km: Int? = null,
        val description: String? = null,
        val score: Int = 1,
        val category: String = SerialGroupCategory.DEFAULT,
        val warrantyMonths: Int? = null,
        val warrantyKm: Int? = null,
        val imageVerificationRequired: Boolean = false,
    ) {
        /** Length of the random letter/digit body (excludes fixed prefix). */
        val length: Int get() = letterCount + digitCount
    }

    data class ImportGenerateRequest(
        val serials: List<String>,
        val city: String? = null,
        val km: Int? = null,
        val description: String? = null,
        val score: Int = 1,
        val category: String = SerialGroupCategory.DEFAULT,
        val warrantyMonths: Int? = null,
        val warrantyKm: Int? = null,
        val imageVerificationRequired: Boolean = false,
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
        val category: String,
        val generationMode: String = SerialGroupGenerationMode.DEFAULT,
        val warrantyMonths: Int? = null,
        val warrantyKm: Int? = null,
        val imageVerificationRequired: Boolean = false,
        val total: Int,
        val used: Int,
        val unused: Int,
        val salePercentage: Double,
        val createdAt: Long,
    )

    data class UpdateGroupRequest(
        val description: String? = null,
        val score: Int? = null,
        val category: String? = null,
        val warrantyMonths: Int? = null,
        val warrantyKm: Int? = null,
        val imageVerificationRequired: Boolean? = null,
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
        SerialGroupCategory.requireValid(request.category)
        SerialGroupWarranty.requireValid(request.warrantyMonths, request.warrantyKm)

        val count = request.end - request.start + 1L
        if (count <= 0) {
            throw ValidationException("Invalid range.")
        }
        if (count > LARGE_BATCH_THRESHOLD) {
            throw ValidationException("Count exceeds maximum of $LARGE_BATCH_THRESHOLD serials.")
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

    fun buildGroupLabel(group: SerialGroupEntity): String {
        return when (SerialGroupGenerationMode.normalizeOrDefault(group.generationMode)) {
            SerialGroupGenerationMode.COMPLEX -> {
                val userPrefix = group.prefix
                    .takeIf { it.isNotEmpty() && !it.equals(COMPLEX_PREFIX, ignoreCase = true) }
                if (userPrefix != null) {
                    "تصادفی پیشوند $userPrefix • ${group.padWidth} کاراکتر × ${group.endNumber}"
                } else {
                    "تصادفی ${group.padWidth} کاراکتر × ${group.endNumber}"
                }
            }
            SerialGroupGenerationMode.IMPORT ->
                "وارداتی × ${group.endNumber}"
            else ->
                buildRangeLabel(group.prefix, group.startNumber, group.endNumber, group.padWidth)
        }
    }

    fun validateImport(request: ImportGenerateRequest): List<String> {
        if (request.serials.isEmpty()) {
            throw ValidationException("At least one serial is required.")
        }
        if (request.serials.size > LARGE_BATCH_THRESHOLD) {
            throw ValidationException("Import exceeds maximum of $LARGE_BATCH_THRESHOLD serials.")
        }
        if (request.score < 1) {
            throw ValidationException("Score must be at least 1.")
        }
        SerialGroupCategory.requireValid(request.category)
        SerialGroupWarranty.requireValid(request.warrantyMonths, request.warrantyKm)

        val normalized = ArrayList<String>(request.serials.size)
        val invalid = mutableListOf<String>()
        for (raw in request.serials) {
            val serial = SerialNormalizer.normalizeSerial(raw)
            if (serial.isEmpty()) continue
            if (serial.length > MAX_SERIAL_LENGTH || !SERIAL_REGEX.matches(serial)) {
                invalid.add(raw.trim().ifEmpty { serial })
                if (invalid.size >= 5) break
            } else {
                normalized.add(serial)
            }
        }
        if (invalid.isNotEmpty()) {
            throw ValidationException(
                "Invalid serial format (letters/digits only, max $MAX_SERIAL_LENGTH): " +
                    invalid.joinToString(", "),
            )
        }
        if (normalized.isEmpty()) {
            throw ValidationException("No valid serials found in the import list.")
        }
        return normalized
    }

    suspend fun generateImport(
        request: ImportGenerateRequest,
    ): GenerateResult = withContext(Dispatchers.IO) {
        val normalized = validateImport(request)
        val dao = database.serialDao()
        val groupDao = database.serialGroupDao()
        val city = request.city?.trim()?.takeIf { it.isNotEmpty() }
        val km = request.km
        val description = request.description?.trim()?.takeIf { it.isNotEmpty() }
        val category = SerialGroupCategory.requireValid(request.category)
        val (warrantyMonths, warrantyKm) = SerialGroupWarranty.requireValid(
            request.warrantyMonths,
            request.warrantyKm,
        )

        val uniqueOrdered = LinkedHashSet<String>()
        var skippedInFile = 0
        for (serial in normalized) {
            if (!uniqueOrdered.add(serial)) {
                skippedInFile++
            }
        }

        val existing = mutableSetOf<String>()
        uniqueOrdered.toList().chunked(CHUNK_SIZE).forEach { chunk ->
            existing.addAll(dao.findExistingUpperSerials(chunk))
        }

        val toInsert = uniqueOrdered.filter { it !in existing }
        val skippedExisting = uniqueOrdered.size - toInsert.size
        val skipped = skippedInFile + skippedExisting

        if (toInsert.isEmpty()) {
            throw ValidationException(
                "No new serials to import — all ${uniqueOrdered.size} were duplicates.",
            )
        }

        val now = System.currentTimeMillis()
        val groupId = groupDao.insert(
            SerialGroupEntity(
                prefix = IMPORT_PREFIX,
                startNumber = 0,
                endNumber = toInsert.size,
                padWidth = toInsert.maxOf { it.length },
                description = description,
                score = request.score,
                category = category,
                generationMode = SerialGroupGenerationMode.IMPORT,
                warrantyMonths = warrantyMonths,
                warrantyKm = warrantyKm,
                imageVerificationRequired = request.imageVerificationRequired,
                createdAt = now,
                syncUpdatedMs = now,
            ),
        )

        var inserted = 0
        toInsert.chunked(CHUNK_SIZE).forEach { chunk ->
            val entities = chunk.map { serial ->
                SerialEntity(
                    serial = serial,
                    city = city,
                    km = km,
                    groupId = groupId,
                    syncUpdatedMs = now,
                )
            }
            inserted += dao.insertAll(entities).count { it != -1L }
        }

        if (inserted <= 0) {
            groupDao.deleteById(groupId)
            return@withContext GenerateResult(
                inserted = 0,
                skipped = skipped,
                total = normalized.size,
                groupId = null,
            )
        }

        groupDao.updateRange(groupId, 0, inserted, now)

        val syncWriter = SyncEventWriter.getInstance(database)
        syncWriter.recordSerialsAdded(
            inserted = inserted,
            skipped = skipped,
            total = normalized.size,
        )
        syncWriter.enqueueInventoryForGroup(groupId)

        GenerateResult(
            inserted = inserted,
            skipped = skipped,
            total = normalized.size,
            groupId = groupId,
        )
    }

    fun validateComplex(request: ComplexGenerateRequest): String {
        if (request.count < 1) {
            throw ValidationException("Count must be at least 1.")
        }
        if (request.count > LARGE_BATCH_THRESHOLD) {
            throw ValidationException("Count exceeds maximum of $LARGE_BATCH_THRESHOLD serials.")
        }
        if (request.letterCount < 0 || request.digitCount < 0) {
            throw ValidationException("Letter and digit counts must be non-negative.")
        }
        if (request.letterCount == 0 && request.digitCount == 0) {
            throw ValidationException("At least one letter or digit is required.")
        }
        val prefix = SerialNormalizer.normalizePrefix(request.prefix)
        if (prefix.isNotEmpty() && !PREFIX_REGEX.matches(prefix)) {
            throw ValidationException("Prefix may only contain letters and digits.")
        }
        val bodyLength = request.length
        if (bodyLength < MIN_COMPLEX_LENGTH || bodyLength > MAX_SERIAL_LENGTH) {
            throw ValidationException(
                "Random body length (letters + digits) must be between $MIN_COMPLEX_LENGTH and $MAX_SERIAL_LENGTH.",
            )
        }
        val totalLength = prefix.length + bodyLength
        if (totalLength > MAX_SERIAL_LENGTH) {
            throw ValidationException(
                "Prefix plus random body exceeds $MAX_SERIAL_LENGTH characters " +
                    "(prefix ${prefix.length} + body $bodyLength = $totalLength).",
            )
        }
        if (request.score < 1) {
            throw ValidationException("Score must be at least 1.")
        }
        SerialGroupCategory.requireValid(request.category)
        SerialGroupWarranty.requireValid(request.warrantyMonths, request.warrantyKm)
        val maxDistinct = estimateMaxDistinctComposition(request.letterCount, request.digitCount)
        if (request.count > maxDistinct) {
            throw ValidationException(
                "Requested count exceeds maximum unique serials for this composition ($maxDistinct).",
            )
        }
        return prefix
    }

    suspend fun generateComplex(
        request: ComplexGenerateRequest,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): GenerateResult = withContext(Dispatchers.IO) {
        val prefix = validateComplex(request)
        val dao = database.serialDao()
        val groupDao = database.serialGroupDao()
        val length = request.length
        val city = request.city?.trim()?.takeIf { it.isNotEmpty() }
        val km = request.km
        val description = request.description?.trim()?.takeIf { it.isNotEmpty() }
        val category = SerialGroupCategory.requireValid(request.category)
        val (warrantyMonths, warrantyKm) = SerialGroupWarranty.requireValid(
            request.warrantyMonths,
            request.warrantyKm,
        )

        val now = System.currentTimeMillis()
        val groupId = groupDao.insert(
            SerialGroupEntity(
                prefix = prefix.ifEmpty { COMPLEX_PREFIX },
                startNumber = 0,
                endNumber = request.count,
                padWidth = length,
                description = description,
                score = request.score,
                category = category,
                generationMode = SerialGroupGenerationMode.COMPLEX,
                warrantyMonths = warrantyMonths,
                warrantyKm = warrantyKm,
                imageVerificationRequired = request.imageVerificationRequired,
                createdAt = now,
                syncUpdatedMs = now,
            ),
        )

        val accepted = HashSet<String>((request.count * 4 / 3) + 16)
        val pending = ArrayList<SerialEntity>(CHUNK_SIZE)
        var acceptedCount = 0
        var inserted = 0
        var skipped = 0
        var attempts = 0
        val maxAttempts = (request.count.toLong() * COMPLEX_ATTEMPT_MULTIPLIER)
            .coerceAtLeast(request.count.toLong() + 100L)
            .coerceAtMost(COMPLEX_MAX_ATTEMPTS)

        suspend fun flushPending() {
            if (pending.isEmpty()) return
            inserted += dao.insertAll(pending).count { it != -1L }
            pending.clear()
        }

        suspend fun abortGroup() {
            pending.clear()
            dao.deleteByGroupId(groupId)
            groupDao.deleteById(groupId)
        }

        while (acceptedCount < request.count && attempts < maxAttempts) {
            val remaining = request.count - acceptedCount
            // Cap candidate batch size so large runs (e.g. 500k) stream instead of allocating all at once.
            val batchSize = minOf(remaining * 3, COMPLEX_BATCH_SIZE).coerceAtLeast(1)
            val candidates = LinkedHashSet<String>()
            var guard = 0
            while (candidates.size < batchSize && guard < batchSize * 20) {
                candidates.add(
                    randomComplexSerial(prefix, request.letterCount, request.digitCount),
                )
                guard++
                attempts++
            }
            val existing = dao.findExistingUpperSerials(candidates.toList()).toSet()
            for (candidate in candidates) {
                if (candidate in existing || candidate in accepted) {
                    skipped++
                    continue
                }
                accepted.add(candidate)
                acceptedCount++
                pending.add(
                    SerialEntity(
                        serial = candidate,
                        city = city,
                        km = km,
                        groupId = groupId,
                        syncUpdatedMs = now,
                    ),
                )
                if (pending.size >= CHUNK_SIZE) {
                    flushPending()
                }
                if (acceptedCount >= request.count) break
            }
            onProgress(acceptedCount, request.count)
        }

        flushPending()

        if (acceptedCount < request.count) {
            abortGroup()
            throw ValidationException(
                "Could not generate ${request.count} unique serials after skipping duplicates. " +
                    "Generated $acceptedCount; try a longer length or different character mix.",
            )
        }

        if (inserted <= 0) {
            abortGroup()
            return@withContext GenerateResult(inserted = 0, skipped = skipped, total = request.count, groupId = null)
        }

        // Persist the actual inserted count on the group metadata.
        groupDao.updateRange(groupId, 0, inserted, now)

        val syncWriter = SyncEventWriter.getInstance(database)
        syncWriter.recordSerialsAdded(
            inserted = inserted,
            skipped = skipped,
            total = request.count,
        )
        syncWriter.enqueueInventoryForGroup(groupId)

        GenerateResult(
            inserted = inserted,
            skipped = skipped,
            total = request.count,
            groupId = groupId,
        )
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
        val category = SerialGroupCategory.requireValid(request.category)
        val (warrantyMonths, warrantyKm) = SerialGroupWarranty.requireValid(
            request.warrantyMonths,
            request.warrantyKm,
        )

        val now = System.currentTimeMillis()
        val groupId = groupDao.insert(
            SerialGroupEntity(
                prefix = prefix,
                startNumber = request.start,
                endNumber = request.end,
                padWidth = request.padWidth,
                description = description,
                score = request.score,
                category = category,
                generationMode = SerialGroupGenerationMode.RANGE,
                warrantyMonths = warrantyMonths,
                warrantyKm = warrantyKm,
                imageVerificationRequired = request.imageVerificationRequired,
                createdAt = now,
                syncUpdatedMs = now,
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
                    syncUpdatedMs = now,
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
        if (inserted > 0 && finalGroupId != null) {
            val syncWriter = SyncEventWriter.getInstance(database)
            syncWriter.recordSerialsAdded(
                inserted = result.inserted,
                skipped = result.skipped,
                total = result.total,
            )
            syncWriter.enqueueInventoryForGroup(finalGroupId)
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
        val category = if (request.category != null) {
            SerialGroupCategory.requireValid(request.category)
        } else {
            SerialGroupCategory.normalizeOrDefault(group.category)
        }
        val (normalizedMonths, normalizedKm) =
            if (request.warrantyMonths != null || request.warrantyKm != null) {
                SerialGroupWarranty.requireValid(request.warrantyMonths, request.warrantyKm)
            } else {
                SerialGroupWarranty.normalizeOptional(group.warrantyMonths, group.warrantyKm)
            }
        if (normalizedMonths == null && normalizedKm == null) {
            throw ValidationException(
                "حداقل یکی از مدت گارانتی (ماه) یا کیلومتر گارانتی باید وارد شود.",
            )
        }
        val imageVerificationRequired =
            request.imageVerificationRequired ?: group.imageVerificationRequired
        val now = System.currentTimeMillis()
        groupDao.updateMetadata(
            groupId,
            description,
            score,
            category,
            normalizedMonths,
            normalizedKm,
            imageVerificationRequired,
            now,
        )
        val updated = groupDao.findById(groupId) ?: throw ValidationException("Serial group not found.")
        SyncEventWriter.getInstance(database).enqueueGroupUpsert(updated)
        updated
    }

    suspend fun extendGroupRange(
        groupId: Long,
        request: ExtendRangeRequest,
    ): ExtendRangeResult = withContext(Dispatchers.IO) {
        val groupDao = database.serialGroupDao()
        val dao = database.serialDao()
        val group = groupDao.findById(groupId)
            ?: throw ValidationException("Serial group not found.")

        val mode = SerialGroupGenerationMode.normalizeOrDefault(group.generationMode)
        if (mode == SerialGroupGenerationMode.COMPLEX || mode == SerialGroupGenerationMode.IMPORT) {
            throw ValidationException("Only range serial groups can be extended by numeric range.")
        }

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

        groupDao.updateRange(groupId, newStart, newEnd, System.currentTimeMillis())

        if (inserted > 0) {
            val syncWriter = SyncEventWriter.getInstance(database)
            syncWriter.recordSerialsAdded(
                inserted = inserted,
                skipped = skipped,
                total = extensionCount,
            )
            syncWriter.enqueueInventoryForGroup(groupId)
        } else {
            groupDao.findById(groupId)?.let {
                SyncEventWriter.getInstance(database).enqueueGroupUpsert(it)
            }
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
        val now = System.currentTimeMillis()
        while (number <= end) {
            val chunkEnd = minOf(number + CHUNK_SIZE - 1, end)
            val chunk = (number..chunkEnd).map { current ->
                SerialEntity(
                    serial = buildSerial(prefix, current, padWidth),
                    groupId = groupId,
                    syncUpdatedMs = now,
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
        val group = groupDao.findById(groupId) ?: return@withContext false
        SyncEventWriter.getInstance(database).enqueueGroupDelete(group)
        database.serialDao().deleteByGroupId(groupId)
        groupDao.deleteById(groupId)
        true
    }

    suspend fun listSerialsForGroup(groupId: Long): List<String> = withContext(Dispatchers.IO) {
        val groupDao = database.serialGroupDao()
        if (groupDao.findById(groupId) == null) {
            throw ValidationException("Serial group not found.")
        }
        database.serialDao().listSerialValuesByGroupId(groupId)
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
                rangeLabel = buildGroupLabel(group),
                description = group.description,
                score = group.score,
                category = SerialGroupCategory.normalizeOrDefault(group.category),
                generationMode = SerialGroupGenerationMode.normalizeOrDefault(group.generationMode),
                warrantyMonths = group.warrantyMonths,
                warrantyKm = group.warrantyKm,
                imageVerificationRequired = group.imageVerificationRequired,
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

    /**
     * Builds a serial with an optional fixed [prefix] followed by exactly [letterCount] letters
     * and [digitCount] digits. Letter positions in the random body are chosen randomly.
     */
    private fun randomComplexSerial(prefix: String, letterCount: Int, digitCount: Int): String {
        val random = java.security.SecureRandom()
        val letters = COMPLEX_LETTERS.toCharArray()
        val digits = COMPLEX_DIGITS.toCharArray()
        val length = letterCount + digitCount
        val positions = (0 until length).toMutableList()
        // Fisher–Yates shuffle so letter slots are random.
        for (i in positions.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = positions[i]
            positions[i] = positions[j]
            positions[j] = tmp
        }
        val letterSlots = positions.take(letterCount).toSet()
        val chars = CharArray(length)
        for (index in 0 until length) {
            chars[index] = if (index in letterSlots) {
                letters[random.nextInt(letters.size)]
            } else {
                digits[random.nextInt(digits.size)]
            }
        }
        return if (prefix.isEmpty()) String(chars) else prefix + String(chars)
    }

    private fun estimateMaxDistinctComposition(letterCount: Int, digitCount: Int): Long {
        val length = letterCount + digitCount
        if (length <= 0) return 0L
        val letterAlphabet = COMPLEX_LETTERS.length.toLong()
        val digitAlphabet = COMPLEX_DIGITS.length.toLong()
        // C(length, letterCount) * letterAlphabet^letterCount * digitAlphabet^digitCount
        var combinations = 1L
        val choose = minOf(letterCount, length - letterCount)
        for (i in 0 until choose) {
            if (combinations > LARGE_BATCH_THRESHOLD) return Long.MAX_VALUE
            val next = combinations * (length - i) / (i + 1)
            if (next < combinations && combinations > 1) return Long.MAX_VALUE
            combinations = next
        }
        var total = combinations
        repeat(letterCount) {
            if (total > LARGE_BATCH_THRESHOLD) return Long.MAX_VALUE
            val next = total * letterAlphabet
            if (letterAlphabet > 0 && next / letterAlphabet != total) return Long.MAX_VALUE
            total = next
        }
        repeat(digitCount) {
            if (total > LARGE_BATCH_THRESHOLD) return Long.MAX_VALUE
            val next = total * digitAlphabet
            if (digitAlphabet > 0 && next / digitAlphabet != total) return Long.MAX_VALUE
            total = next
        }
        return total
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
        const val MIN_COMPLEX_LENGTH = 6
        const val CHUNK_SIZE = 5000
        /** Hard maximum serials allowed in one generate / import / extend run. */
        const val LARGE_BATCH_THRESHOLD = 500_000
        /** UI confirmation prompt when a range job exceeds this size. */
        const val CONFIRM_BATCH_THRESHOLD = 10_000
        const val COMPLEX_PREFIX = "RND"
        const val IMPORT_PREFIX = "IMP"
        const val COMPLEX_BATCH_SIZE = 500
        const val COMPLEX_ATTEMPT_MULTIPLIER = 40L
        /** Enough headroom for LARGE_BATCH_THRESHOLD * COMPLEX_ATTEMPT_MULTIPLIER. */
        const val COMPLEX_MAX_ATTEMPTS = 25_000_000L
        private const val COMPLEX_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val COMPLEX_DIGITS = "23456789"

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
