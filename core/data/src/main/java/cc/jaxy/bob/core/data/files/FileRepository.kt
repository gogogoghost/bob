package cc.jaxy.bob.core.data.files

import android.content.Context
import cc.jaxy.bob.core.common.result.AnError
import cc.jaxy.bob.core.common.result.AnResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class UploadedFile(val id: String = "", val url: String? = null, val name: String = "", val mime: String = "application/octet-stream")
interface FileRepository {
    suspend fun uploadFile(name: String, mime: String, bytes: ByteArray): AnResult<UploadedFile>
    suspend fun readFile(id: String): AnResult<ByteArray>
}

@Singleton
class FileRepositoryImpl @Inject constructor(@ApplicationContext private val context: Context) : FileRepository {
    private val directory get() = File(context.filesDir, "local_files")
    override suspend fun uploadFile(name: String, mime: String, bytes: ByteArray): AnResult<UploadedFile> = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext AnResult.Err(AnError("INVALID_INPUT", "empty file content"))
        try {
            directory.mkdirs()
            val safeName = name.substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotBlank) ?: "file"
            val id = UUID.randomUUID().toString()
            File(directory, id).writeBytes(bytes)
            AnResult.Ok(UploadedFile(id, File(directory, id).toURI().toString(), safeName, mime.ifBlank { "application/octet-stream" }))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { AnResult.Err(AnError("LOCAL_FILE", e.message ?: "Unable to save file", e)) }
    }
    override suspend fun readFile(id: String): AnResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val file = File(directory, id)
            require(file.parentFile?.canonicalFile == directory.canonicalFile && file.isFile) { "File not found" }
            AnResult.Ok(file.readBytes())
        } catch (e: CancellationException) { throw e } catch (e: Exception) { AnResult.Err(AnError("LOCAL_FILE", e.message ?: "Unable to read file", e)) }
    }
}
