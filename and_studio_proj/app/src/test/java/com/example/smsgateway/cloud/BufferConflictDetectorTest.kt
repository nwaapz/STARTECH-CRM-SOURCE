package com.example.smsgateway.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferConflictDetectorTest {
    @Test
    fun findConflicts_sameSerialDifferentFields() {
        val local = listOf(
            SyncBufferEntry(
                entryId = "l1",
                type = "serial_upsert",
                serial = "ABC1",
                payload = JSONObject()
                    .put("serial", "ABC1")
                    .put("phone", "09120000001")
                    .put("city", "Tehran")
                    .put("km", 10),
                createdAt = 1L,
                localOutboxId = 1L,
            ),
        )
        val remote = listOf(
            SyncBufferEntry(
                entryId = "r1",
                type = "serial_upsert",
                serial = "ABC1",
                payload = JSONObject()
                    .put("serial", "ABC1")
                    .put("phone", "09120000001")
                    .put("city", "Isfahan")
                    .put("km", 10),
                createdAt = 2L,
            ),
        )
        val conflicts = BufferConflictDetector.findConflicts(local, remote)
        assertEquals(1, conflicts.size)
        assertEquals("ABC1", conflicts[0].serial)
    }

    @Test
    fun findConflicts_identicalPayloads_notConflict() {
        val payload = JSONObject()
            .put("serial", "XYZ")
            .put("phone", "09121111111")
            .put("city", "Shiraz")
            .put("km", 5)
            .put("dateJalali", "1403/01/01")
            .put("regSource", "sms")
        val local = listOf(
            SyncBufferEntry("l1", "serial_upsert", "XYZ", payload, 1L, 1L),
        )
        val remote = listOf(
            SyncBufferEntry("r1", "serial_upsert", "XYZ", JSONObject(payload.toString()), 2L),
        )
        assertTrue(BufferConflictDetector.findConflicts(local, remote).isEmpty())
    }

    @Test
    fun findConflicts_onlyOneSide_notConflict() {
        val local = listOf(
            SyncBufferEntry(
                entryId = "l1",
                type = "serial_upsert",
                serial = "ONLY",
                payload = JSONObject().put("serial", "ONLY").put("phone", "0912"),
                createdAt = 1L,
            ),
        )
        assertTrue(BufferConflictDetector.findConflicts(local, emptyList()).isEmpty())
        assertTrue(BufferConflictDetector.findConflicts(emptyList(), local).isEmpty())
    }

    @Test
    fun expandBatch_detectsConflict() {
        val batch = JSONObject().put(
            "serials",
            org.json.JSONArray().put(
                JSONObject().put("serial", "B1").put("phone", "1").put("city", "A"),
            ),
        )
        val local = listOf(
            SyncBufferEntry("l1", "serial_batch_upsert", null, batch, 1L, 1L),
        )
        val remote = listOf(
            SyncBufferEntry(
                entryId = "r1",
                type = "serial_upsert",
                serial = "B1",
                payload = JSONObject().put("serial", "B1").put("phone", "1").put("city", "B"),
                createdAt = 2L,
            ),
        )
        val conflicts = BufferConflictDetector.findConflicts(local, remote)
        assertEquals(1, conflicts.size)
        assertEquals("B1", conflicts[0].serial)
    }

    @Test
    fun registrationFieldsDiffer_ignoresBlankVsZeroPhone() {
        val a = JSONObject().put("phone", "0").put("city", "")
        val b = JSONObject().put("phone", "").put("city", "")
        assertFalse(BufferConflictDetector.registrationFieldsDiffer(a, b))
    }
}
