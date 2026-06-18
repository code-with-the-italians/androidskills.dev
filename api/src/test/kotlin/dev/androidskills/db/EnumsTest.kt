package dev.androidskills.db

import dev.androidskills.api.ApiValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnumsTest {

    @Test
    fun `parses every valid value`() {
        assertEquals(Role.admin, Role.parse("admin"))
        assertEquals(Role.contributor, Role.parse("contributor"))
        assertEquals(Role.member, Role.parse("member"))
        assertEquals(UserStatus.active, UserStatus.parse("active"))
        assertEquals(UserStatus.suspended, UserStatus.parse("suspended"))
        assertEquals(SkillStatus.flagged, SkillStatus.parse("flagged"))
        assertEquals(BundleKind.repo, BundleKind.parse("repo"))
        assertEquals(VersionSource.git_head, VersionSource.parse("git_head"))
        assertEquals(TokenBand.`100s`, TokenBand.parse("100s"))
        assertEquals(TokenBand.`100k`, TokenBand.parse("100k"))
    }

    @Test
    fun `rejects impossible states with a 422 validation error`() {
        for (raw in listOf("superuser", "ROOT", "", "member ", "frozen")) {
            val ex = assertFailsWith<ApiValidationException> { Role.parse(raw) }
            assertTrue(ex.fields.containsKey("role"))
        }
        assertFailsWith<ApiValidationException> { SkillStatus.parse("quarantined") }
        assertFailsWith<ApiValidationException> { TokenBand.parse("50k") }
        assertFailsWith<ApiValidationException> { VersionSource.parse("magic") }
        assertFailsWith<ApiValidationException> { BundleKind.parse("hg") }
    }
}
