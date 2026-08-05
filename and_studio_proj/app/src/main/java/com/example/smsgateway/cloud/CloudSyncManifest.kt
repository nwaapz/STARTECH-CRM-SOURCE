package com.example.smsgateway.cloud

import com.example.smsgateway.data.SerialGroupEntity
import org.json.JSONArray
import org.json.JSONObject

data class ManifestGroupCount(
    val remoteId: Long,
    val total: Int,
    val used: Int,
    val prefix: String,
    val category: String,
)

data class GroupSyncMismatch(
    val remoteGroupId: Long,
    val localGroupId: Long?,
    val label: String,
    val localCount: Int,
    val serverCount: Int,
) {
    val delta: Int = serverCount - localCount
}

data class ManifestSnapshot(
    val totalSerials: Int,
    val pageSize: Int,
    val groups: List<ManifestGroupCount>,
) {
    fun pullPagesEstimate(): Int {
        if (pageSize <= 0 || totalSerials <= 0) return 1
        return ((totalSerials + pageSize - 1) / pageSize).coerceAtLeast(1)
    }
}

object CloudSyncManifestParser {
    fun parse(json: JSONObject): ManifestSnapshot {
        val groupsArr = json.optJSONArray("groups") ?: JSONArray()
        val groups = ArrayList<ManifestGroupCount>(groupsArr.length())
        for (i in 0 until groupsArr.length()) {
            val g = groupsArr.getJSONObject(i)
            groups.add(
                ManifestGroupCount(
                    remoteId = g.optLong("id", 0L),
                    total = g.optInt("total", 0),
                    used = g.optInt("used", 0),
                    prefix = g.optString("prefix", ""),
                    category = g.optString("category", "end_user_client"),
                ),
            )
        }
        return ManifestSnapshot(
            totalSerials = json.optInt("totalSerials", 0),
            pageSize = json.optInt("pageSize", 5000).coerceAtLeast(1),
            groups = groups,
        )
    }
}

object CloudSyncDiff {
    fun findMismatches(
        server: ManifestSnapshot,
        localGroups: List<SerialGroupEntity>,
        localCountByGroupId: Map<Long, Int>,
    ): List<GroupSyncMismatch> {
        val serverById = server.groups.associateBy { it.remoteId }
        val mismatches = ArrayList<GroupSyncMismatch>()
        for (local in localGroups) {
            val remoteId = local.syncOriginGroupId ?: continue
            val serverGroup = serverById[remoteId]
            if (serverGroup == null) continue
            val localCount = localCountByGroupId[local.id] ?: 0
            if (localCount < serverGroup.total) {
                mismatches.add(
                    GroupSyncMismatch(
                        remoteGroupId = remoteId,
                        localGroupId = local.id,
                        label = buildLabel(local, serverGroup),
                        localCount = localCount,
                        serverCount = serverGroup.total,
                    ),
                )
            }
        }
        for (serverGroup in server.groups) {
            if (serverGroup.total <= 0) continue
            val linked = localGroups.any { it.syncOriginGroupId == serverGroup.remoteId }
            if (!linked) {
                mismatches.add(
                    GroupSyncMismatch(
                        remoteGroupId = serverGroup.remoteId,
                        localGroupId = null,
                        label = serverGroup.prefix.ifBlank { "group $serverGroup.remoteId" },
                        localCount = 0,
                        serverCount = serverGroup.total,
                    ),
                )
            }
        }
        return mismatches.sortedByDescending { it.delta }
    }

    fun mismatchesToJson(mismatches: List<GroupSyncMismatch>): String {
        val arr = JSONArray()
        for (m in mismatches) {
            arr.put(
                JSONObject()
                    .put("remoteGroupId", m.remoteGroupId)
                    .put("localGroupId", m.localGroupId)
                    .put("label", m.label)
                    .put("localCount", m.localCount)
                    .put("serverCount", m.serverCount)
                    .put("delta", m.delta),
            )
        }
        return arr.toString()
    }

    private fun buildLabel(local: SerialGroupEntity, server: ManifestGroupCount): String {
        val prefix = local.prefix.ifBlank { server.prefix }
        if (prefix.isNotBlank()) return prefix
        return "group ${local.syncOriginGroupId}"
    }
}

object CloudSyncApplyLogic {
    fun incomingEqualsExisting(
        phone: String?,
        city: String?,
        km: Int?,
        regSource: String?,
        groupId: Long?,
        syncMs: Long,
        existing: com.example.smsgateway.data.SerialEntity,
    ): Boolean {
        return syncMs == existing.syncUpdatedMs &&
            phone == existing.phone &&
            city == existing.city &&
            km == existing.km &&
            regSource == existing.registrationSource &&
            groupId == existing.groupId
    }
}
