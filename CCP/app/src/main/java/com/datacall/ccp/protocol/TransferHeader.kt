package com.datacall.ccp.protocol

import org.json.JSONObject

data class TransferHeader(
    val type: Int,
    val size: Int,
    val packets: Int
) {
    fun toJson(): String = JSONObject()
        .put("type", type)
        .put("size", size)
        .put("packets", packets)
        .toString()

    companion object {
        fun fromJson(json: String): TransferHeader {
            val obj = JSONObject(json)
            return TransferHeader(
                type = obj.getInt("type"),
                size = obj.getInt("size"),
                packets = obj.getInt("packets")
            )
        }
    }
}
