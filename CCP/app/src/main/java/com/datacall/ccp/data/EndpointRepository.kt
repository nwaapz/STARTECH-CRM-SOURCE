package com.datacall.ccp.data

import com.datacall.ccp.util.PhoneUtils
import kotlinx.coroutines.flow.Flow

class EndpointRepository(private val dao: EndpointDao) {
    fun observeAll(): Flow<List<EndpointEntity>> = dao.observeAll()

    suspend fun getAll(): List<EndpointEntity> = dao.getAll()

    suspend fun add(phone: String, label: String) {
        val normalized = PhoneUtils.normalizePhone(phone)
        require(normalized.isNotBlank()) { "Phone number required" }
        dao.insert(EndpointEntity(phone = normalized, label = label.ifBlank { normalized }))
    }

    suspend fun delete(entity: EndpointEntity) = dao.delete(entity)

    suspend fun isTrustedSender(phone: String?): Boolean {
        if (phone.isNullOrBlank()) return false
        return getAll().any { PhoneUtils.phonesMatch(it.phone, phone) }
    }
}
