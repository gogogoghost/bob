package cc.jaxy.bob.core.data.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stores provider credentials outside Android backup, encrypted with an Android Keystore AES-GCM key. */
interface ProviderSecretStore {
    suspend fun read(providerId: String): Map<String, String>
    suspend fun write(providerId: String, secrets: Map<String, String>)
}

@Singleton
class AndroidProviderSecretStore @Inject constructor(@ApplicationContext context: Context) : ProviderSecretStore {
    private val directory = File(context.noBackupFilesDir, "provider_secrets")
    private val json = Json
    private val mutex = Mutex()

    override suspend fun read(providerId: String): Map<String, String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = file(providerId)
            if (!path.exists() && !File(path.path + ".bak").exists()) return@withLock emptyMap()
            try {
                val bytes = AtomicFile(path).openRead().use { it.readBytes() }
                require(bytes.size > GCM_IV_BYTES) { "Stored provider credentials are damaged" }
                val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
                val ciphertext = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(providerId.toByteArray(Charsets.UTF_8))
                json.decodeFromString<Map<String, String>>(
                    String(cipher.doFinal(ciphertext), Charsets.UTF_8),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Saved credentials are unavailable or damaged. Re-enter the provider credentials.",
                    e,
                )
            }
        }
    }

    override suspend fun write(providerId: String, secrets: Map<String, String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val path = file(providerId)
                if (secrets.isEmpty()) {
                    AtomicFile(path).delete()
                    return@withLock
                }
                check(directory.isDirectory || directory.mkdirs()) {
                    "Unable to create provider credential storage"
                }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
                cipher.updateAAD(providerId.toByteArray(Charsets.UTF_8))
                val encrypted = cipher.doFinal(json.encodeToString(secrets).toByteArray(Charsets.UTF_8))
                val atomicFile = AtomicFile(path)
                val output = atomicFile.startWrite()
                try {
                    output.write(cipher.iv + encrypted)
                    atomicFile.finishWrite(output)
                } catch (e: Exception) {
                    try {
                        atomicFile.failWrite(output)
                    } catch (cleanup: Exception) {
                        e.addSuppressed(cleanup)
                    }
                    throw e
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Unable to securely save provider credentials on this device.",
                    e,
                )
            }
        }
    }

    private fun file(id: String): File {
        require(id.matches(PROVIDER_ID_PATTERN)) { "Invalid provider id" }
        return File(directory, "$id.bin")
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw IllegalStateException("Credential encryption key is missing")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "local_provider_credentials_aes_gcm"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val KEY_BITS = 256
        val PROVIDER_ID_PATTERN = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}
