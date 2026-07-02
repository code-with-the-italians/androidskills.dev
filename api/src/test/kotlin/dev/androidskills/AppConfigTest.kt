package dev.androidskills

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigTest {

    private val base = mapOf(
        "DATA_DIR" to Files.createTempDirectory("as-config-test").toString(),
        "PUBLIC_BASE_URL" to "http://localhost:8080",
    )

    @Test
    fun `minimal valid config disables all features`() {
        val cfg = AppConfig.fromMap(base)
        cfg.validate()
        assertNull(cfg.auth.oauth)
        assertNull(cfg.llmBaseUrl)
        assertEquals(false, cfg.githubApp.configured)
    }

    @Test
    fun `all feature vars present enables features`() {
        val env = base + mapOf(
            "GITHUB_OAUTH_CLIENT_ID" to "client-id",
            "GITHUB_OAUTH_CLIENT_SECRET" to "client-secret",
            "GITHUB_APP_ID" to "123456",
            "GITHUB_APP_PRIVATE_KEY" to "-----BEGIN RSA PRIVATE KEY-----\nkey\n-----END RSA PRIVATE KEY-----",
            "GITHUB_WEBHOOK_SECRET" to "wh-secret",
            "LLM_BASE_URL" to "https://api.openai.com/v1",
            "LLM_API_KEY" to "sk-secret",
            "LLM_MODEL" to "gpt-4o-mini",
        )
        val cfg = AppConfig.fromMap(env)
        cfg.validate()
        assertTrue(cfg.auth.oauth != null)
        assertTrue(cfg.githubApp.configured)
        assertEquals("gpt-4o-mini", cfg.llmModel)
    }

    @Test
    fun `invalid public base url throws`() {
        val env = base + mapOf("PUBLIC_BASE_URL" to "not a url")
        val ex = assertFailsWith<IllegalStateException> { AppConfig.fromMap(env).validate() }
        assertTrue(ex.message!!.contains("PUBLIC_BASE_URL"))
    }

    @Test
    fun `data dir that is not a directory throws`() {
        val file = Files.createTempFile("as-config", ".txt")
        val env = base + mapOf("DATA_DIR" to file.toString())
        val ex = assertFailsWith<IllegalStateException> { AppConfig.fromMap(env).validate() }
        assertTrue(ex.message!!.contains("DATA_DIR"))
        Files.deleteIfExists(file)
    }

    @Test
    fun `oauth half configured throws`() {
        val env = base + mapOf("GITHUB_OAUTH_CLIENT_ID" to "only-id")
        val ex = assertFailsWith<IllegalStateException> { AppConfig.fromMap(env) }
        assertTrue(ex.message!!.contains("GitHub OAuth"))
        assertTrue(ex.message!!.contains("GITHUB_OAUTH_CLIENT_SECRET"))
    }

    @Test
    fun `llm half configured throws`() {
        val env = base + mapOf(
            "LLM_BASE_URL" to "https://api.example.com",
            "LLM_API_KEY" to "sk-secret",
        )
        val ex = assertFailsWith<IllegalStateException> { AppConfig.fromMap(env) }
        assertTrue(ex.message!!.contains("LLM"))
        assertTrue(ex.message!!.contains("LLM_MODEL"))
    }

    @Test
    fun `github app half configured throws`() {
        val env = base + mapOf(
            "GITHUB_APP_ID" to "123456",
            "GITHUB_APP_PRIVATE_KEY" to "-----BEGIN RSA PRIVATE KEY-----\nkey\n-----END RSA PRIVATE KEY-----",
        )
        val ex = assertFailsWith<IllegalStateException> { AppConfig.fromMap(env) }
        assertTrue(ex.message!!.contains("GitHub App"))
        assertTrue(ex.message!!.contains("GITHUB_WEBHOOK_SECRET"))
    }

    @Test
    fun `all features absent disables cleanly`() {
        val cfg = AppConfig.fromMap(base)
        cfg.validate()
        assertNull(cfg.auth.oauth)
        assertNull(cfg.llmBaseUrl)
        assertEquals(false, cfg.githubApp.configured)
    }

    @Test
    fun `toString does not leak secrets`() {
        val env = base + mapOf(
            "GITHUB_OAUTH_CLIENT_ID" to "client-id",
            "GITHUB_OAUTH_CLIENT_SECRET" to "client-secret",
            "GITHUB_APP_ID" to "123456",
            "GITHUB_APP_PRIVATE_KEY" to "-----BEGIN RSA PRIVATE KEY-----\nkey\n-----END RSA PRIVATE KEY-----",
            "GITHUB_WEBHOOK_SECRET" to "wh-secret",
            "LLM_BASE_URL" to "https://api.example.com",
            "LLM_API_KEY" to "sk-secret",
            "LLM_MODEL" to "model",
        )
        val cfg = AppConfig.fromMap(env)
        val text = cfg.toString()
        assertTrue(!text.contains("sk-secret"))
        assertTrue(!text.contains("client-secret"))
        assertTrue(!text.contains("BEGIN RSA PRIVATE KEY"))
    }
}
