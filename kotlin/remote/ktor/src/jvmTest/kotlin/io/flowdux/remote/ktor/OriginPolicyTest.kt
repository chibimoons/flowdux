package io.flowdux.remote.ktor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginPolicyTest {

    // ── AllowAll ────────────────────────────────────────────────

    @Test
    fun `AllowAll permits any origin`() {
        val policy = OriginPolicy.AllowAll
        assertTrue(policy.isAllowed("https://example.com"))
        assertTrue(policy.isAllowed("http://evil.com"))
    }

    @Test
    fun `AllowAll permits null origin`() {
        assertTrue(OriginPolicy.AllowAll.isAllowed(null))
    }

    // ── AllowList — basic matching ──────────────────────────────

    @Test
    fun `AllowList permits listed origin`() {
        val policy = OriginPolicy.AllowList(origins = setOf("https://example.com"))
        assertTrue(policy.isAllowed("https://example.com"))
    }

    @Test
    fun `AllowList rejects unlisted origin`() {
        val policy = OriginPolicy.AllowList(origins = setOf("https://example.com"))
        assertFalse(policy.isAllowed("https://evil.com"))
    }

    @Test
    fun `AllowList rejects null origin by default`() {
        val policy = OriginPolicy.AllowList(origins = setOf("https://example.com"))
        assertFalse(policy.isAllowed(null))
    }

    @Test
    fun `AllowList permits null origin when allowNullOrigin is true`() {
        val policy = OriginPolicy.AllowList(
            origins = setOf("https://example.com"),
            allowNullOrigin = true,
        )
        assertTrue(policy.isAllowed(null))
    }

    // ── AllowList — normalization ───────────────────────────────

    @Test
    fun `AllowList matching is case insensitive`() {
        val policy = OriginPolicy.AllowList(origins = setOf("https://Example.COM"))
        assertTrue(policy.isAllowed("https://example.com"))
        assertTrue(policy.isAllowed("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun `AllowList ignores trailing slash`() {
        val policy = OriginPolicy.AllowList(origins = setOf("https://example.com/"))
        assertTrue(policy.isAllowed("https://example.com"))
        assertTrue(policy.isAllowed("https://example.com/"))
    }

    // ── AllowList — multiple origins ────────────────────────────

    @Test
    fun `AllowList permits any of multiple origins`() {
        val policy = OriginPolicy.AllowList(
            origins = setOf("https://app.example.com", "http://localhost:3000"),
        )
        assertTrue(policy.isAllowed("https://app.example.com"))
        assertTrue(policy.isAllowed("http://localhost:3000"))
        assertFalse(policy.isAllowed("https://other.com"))
    }

    // ── AllowList — empty set ───────────────────────────────────

    @Test
    fun `AllowList with empty set rejects everything`() {
        val policy = OriginPolicy.AllowList(origins = emptySet())
        assertFalse(policy.isAllowed("https://example.com"))
        assertFalse(policy.isAllowed(null))
    }
}
