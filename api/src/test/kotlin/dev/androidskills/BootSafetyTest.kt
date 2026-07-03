package dev.androidskills

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BootSafetyTest {

    @Test
    fun `module fails fast when data dir is not writable`() {
        val file = Files.createTempFile("as-boot-test", ".txt")
        val env = mapOf(
            "DATA_DIR" to file.toString(),
            "PUBLIC_BASE_URL" to "http://localhost:8080",
        )
        val ex = assertFailsWith<IllegalStateException> {
            AppConfig.fromMap(env).validate()
        }
        assertTrue(ex.message!!.contains("DATA_DIR"))
        Files.deleteIfExists(file)
    }

    @Test
    fun `module fails fast when public base url is invalid`() {
        val dir = Files.createTempDirectory("as-boot-test")
        val env = mapOf(
            "DATA_DIR" to dir.toString(),
            "PUBLIC_BASE_URL" to "not a url",
        )
        val ex = assertFailsWith<IllegalStateException> {
            AppConfig.fromMap(env).validate()
        }
        assertTrue(ex.message!!.contains("PUBLIC_BASE_URL"))
        Files.deleteIfExists(dir)
    }
}
