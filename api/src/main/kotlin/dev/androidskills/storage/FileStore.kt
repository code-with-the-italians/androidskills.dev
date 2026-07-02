package dev.androidskills.storage

import java.nio.file.Files
import java.nio.file.Path

/**
 * Object storage abstraction. [LocalFsStore] backs local dev; a Cloudflare R2
 * (S3 API) implementation slots in behind the same interface for prod.
 */
interface FileStore {
    val kind: String
    fun put(key: String, bytes: ByteArray)
    fun get(key: String): ByteArray?
    fun exists(key: String): Boolean
    fun check(): Boolean
}

class LocalFsStore(private val root: Path) : FileStore {
    override val kind = "local-fs"

    init {
        Files.createDirectories(root)
    }

    override fun check(): Boolean = Files.isDirectory(root) && Files.isWritable(root)

    private fun resolve(key: String): Path =
        root.resolve(key).normalize().also {
            require(it.startsWith(root)) { "key escapes store root: $key" }
        }

    override fun put(key: String, bytes: ByteArray) {
        val p = resolve(key)
        Files.createDirectories(p.parent)
        Files.write(p, bytes)
    }

    override fun get(key: String): ByteArray? =
        resolve(key).let { if (Files.exists(it)) Files.readAllBytes(it) else null }

    override fun exists(key: String): Boolean = Files.exists(resolve(key))
}
