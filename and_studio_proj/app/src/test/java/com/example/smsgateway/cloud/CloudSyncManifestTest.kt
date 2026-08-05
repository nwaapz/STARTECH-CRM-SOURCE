package com.example.smsgateway.cloud

import com.example.smsgateway.data.SerialEntity
import com.example.smsgateway.data.SerialGroupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncManifestTest {
    @Test
    fun findMismatches_detectsShortLocalGroup() {
        val server = ManifestSnapshot(
            totalSerials = 10000,
            pageSize = 5000,
            groups = listOf(
                ManifestGroupCount(remoteId = 42L, total = 5000, used = 10, prefix = "S", category = "end_user_client"),
            ),
        )
        val localGroups = listOf(
            SerialGroupEntity(
                id = 1L,
                prefix = "S",
                startNumber = 1,
                endNumber = 5000,
                padWidth = 0,
                createdAt = 1L,
                syncOriginGroupId = 42L,
            ),
        )
        val mismatches = CloudSyncDiff.findMismatches(
            server,
            localGroups,
            mapOf(1L to 0),
        )
        assertEquals(1, mismatches.size)
        assertEquals(5000, mismatches[0].delta)
    }

    @Test
    fun findMismatches_noMismatchWhenCountsMatch() {
        val server = ManifestSnapshot(
            totalSerials = 100,
            pageSize = 5000,
            groups = listOf(
                ManifestGroupCount(remoteId = 7L, total = 100, used = 5, prefix = "M", category = "seller_to_end_user"),
            ),
        )
        val localGroups = listOf(
            SerialGroupEntity(
                id = 2L,
                prefix = "M",
                startNumber = 1,
                endNumber = 100,
                padWidth = 0,
                category = "seller_to_end_user",
                createdAt = 1L,
                syncOriginGroupId = 7L,
            ),
        )
        val mismatches = CloudSyncDiff.findMismatches(server, localGroups, mapOf(2L to 100))
        assertTrue(mismatches.isEmpty())
    }

    @Test
    fun incomingEqualsExisting_skipsNoOpUpdate() {
        val existing = SerialEntity(
            serial = "S100",
            phone = null,
            city = null,
            km = null,
            groupId = 5L,
            syncUpdatedMs = 12345L,
        )
        assertTrue(
            CloudSyncApplyLogic.incomingEqualsExisting(
                phone = null,
                city = null,
                km = null,
                regSource = null,
                groupId = 5L,
                syncMs = 12345L,
                existing = existing,
            ),
        )
        assertFalse(
            CloudSyncApplyLogic.incomingEqualsExisting(
                phone = "09121234567",
                city = null,
                km = null,
                regSource = null,
                groupId = 5L,
                syncMs = 12345L,
                existing = existing,
            ),
        )
    }
}
