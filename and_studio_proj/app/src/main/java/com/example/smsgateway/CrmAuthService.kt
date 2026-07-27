package com.example.smsgateway

import android.content.Context
import android.util.Base64
import com.example.smsgateway.data.CrmSessionEntity
import com.example.smsgateway.data.CrmUserEntity
import com.example.smsgateway.data.GatewayDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object CrmRoles {
    const val REPORTER = 1
    const val MANAGER = 4
    const val SUPER_ADMIN = 5

    fun canManageUsers(role: Int): Boolean = role >= SUPER_ADMIN
    fun canRunLottery(role: Int): Boolean = role >= MANAGER
    fun canEditOps(role: Int): Boolean = role >= MANAGER
}

data class CrmAuthUser(
    val id: Long,
    val username: String,
    val role: Int,
)

class CrmAuthService(context: Context) {
    private val db = GatewayDatabase.getInstance(context)
    private val userDao = db.crmUserDao()
    private val sessionDao = db.crmSessionDao()

    suspend fun ensureBootstrapAdmin(defaultPassword: String = "admin123"): Unit = withContext(Dispatchers.IO) {
        if (userDao.count() > 0) return@withContext
        val now = System.currentTimeMillis()
        userDao.insert(
            CrmUserEntity(
                username = "admin",
                passwordHash = hashPassword(defaultPassword),
                role = CrmRoles.SUPER_ADMIN,
                createdAt = now,
                updatedAt = now,
                syncUpdatedMs = now,
            ),
        )
    }

    suspend fun login(username: String, password: String): Pair<String, CrmAuthUser>? = withContext(Dispatchers.IO) {
        ensureBootstrapAdmin()
        sessionDao.deleteExpired(System.currentTimeMillis())
        val user = userDao.findByUsername(username.trim()) ?: return@withContext null
        if (!verifyPassword(password, user.passwordHash)) return@withContext null
        val token = newToken()
        val now = System.currentTimeMillis()
        sessionDao.insert(
            CrmSessionEntity(
                userId = user.id,
                token = token,
                createdAt = now,
                expiresAt = now + SESSION_TTL_MS,
            ),
        )
        token to CrmAuthUser(user.id, user.username, user.role)
    }

    /** Accept CRM session token or legacy LAN PIN (treated as SUPER_ADMIN for backward compat). */
    suspend fun resolveBearer(
        bearer: String?,
        lanPin: String,
    ): CrmAuthUser? = withContext(Dispatchers.IO) {
        if (bearer.isNullOrBlank()) return@withContext null
        if (bearer == lanPin) {
            return@withContext CrmAuthUser(id = 0, username = "lan-pin", role = CrmRoles.SUPER_ADMIN)
        }
        sessionDao.deleteExpired(System.currentTimeMillis())
        val session = sessionDao.findByToken(bearer) ?: return@withContext null
        if (session.expiresAt < System.currentTimeMillis()) {
            sessionDao.deleteByToken(bearer)
            return@withContext null
        }
        val user = userDao.findById(session.userId) ?: return@withContext null
        CrmAuthUser(user.id, user.username, user.role)
    }

    suspend fun listUsers(): List<CrmUserEntity> = withContext(Dispatchers.IO) {
        ensureBootstrapAdmin()
        userDao.listAll()
    }

    suspend fun createUser(username: String, password: String, role: Int): CrmUserEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = userDao.insert(
            CrmUserEntity(
                username = username.trim(),
                passwordHash = hashPassword(password),
                role = role,
                createdAt = now,
                updatedAt = now,
                syncUpdatedMs = now,
            ),
        )
        userDao.findById(id)!!
    }

    suspend fun deleteUser(id: Long) = withContext(Dispatchers.IO) {
        sessionDao.deleteByUserId(id)
        userDao.deleteById(id)
    }

    suspend fun changePassword(id: Long, newPassword: String): Boolean = withContext(Dispatchers.IO) {
        val user = userDao.findById(id) ?: return@withContext false
        val now = System.currentTimeMillis()
        userDao.update(
            user.copy(
                passwordHash = hashPassword(newPassword),
                updatedAt = now,
                syncUpdatedMs = now,
            ),
        )
        true
    }

    companion object {
        private const val SESSION_TTL_MS = 8L * 60L * 60L * 1000L
        private const val PBKDF2_ITERATIONS = 120_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256

        fun hashPassword(password: String): String {
            val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val hash = pbkdf2(password.toCharArray(), salt)
            val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
            val hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP)
            return "pbkdf2$$PBKDF2_ITERATIONS$$saltB64$$hashB64"
        }

        fun verifyPassword(password: String, stored: String): Boolean {
            val parts = stored.split('$')
            if (parts.size != 4 || parts[0] != "pbkdf2") {
                // Legacy plaintext fallback (migrate on next change-password)
                return stored == password
            }
            val iterations = parts[1].toIntOrNull() ?: return false
            val salt = Base64.decode(parts[2], Base64.NO_WRAP)
            val expected = Base64.decode(parts[3], Base64.NO_WRAP)
            val actual = pbkdf2(password.toCharArray(), salt, iterations)
            return MessageDigest.isEqual(expected, actual)
        }

        private fun pbkdf2(
            password: CharArray,
            salt: ByteArray,
            iterations: Int = PBKDF2_ITERATIONS,
        ): ByteArray {
            val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        }

        private fun newToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        @Volatile
        private var instance: CrmAuthService? = null

        fun getInstance(context: Context): CrmAuthService {
            return instance ?: synchronized(this) {
                instance ?: CrmAuthService(context.applicationContext).also { instance = it }
            }
        }
    }
}
