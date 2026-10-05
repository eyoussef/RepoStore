package com.samyak.repostore.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the cases behind issue #46 ("not detect new version"):
 * a manually installed pre-release APK must still resolve against a newer
 * stable release — including when the installed package has no usable
 * versionName and only a versionCode to compare with.
 */
class VersionComparatorTest {

    @Test
    fun `pre-release install against newer stable is detected`() {
        // Mullvad-style: installed "2026.8-beta1", latest stable tag "v2026.8".
        assertTrue(VersionComparator.isNewerVersion("2026.8-beta1", "v2026.8"))
        assertTrue(VersionComparator.isNewerVersion("2026.8.0-beta.1", "v2026.8"))
        assertTrue(VersionComparator.isNewerVersion("2026.8_beta1", "v2026.8"))
    }

    @Test
    fun `date-style core with hyphen separator is normalized`() {
        // "2026-08-beta.1" — the '-' splits pre-release, but the core kept is
        // "2026"; a stable "2026.8" must still win.
        assertTrue(VersionComparator.isNewerVersion("2026-08-beta.1", "v2026.8"))
    }

    @Test
    fun `plain version bumps are detected and downgrades rejected`() {
        assertTrue(VersionComparator.isNewerVersion("1.0.20", "v1.0.21"))
        assertFalse(VersionComparator.isNewerVersion("1.0.22", "v1.0.21"))
        assertFalse(VersionComparator.isNewerVersion("1.0.21", "v1.0.21"))
    }

    @Test
    fun `code-only installed version compares against numeric tag`() {
        // Installed APK shipped without a versionName; only the versionCode exists.
        assertTrue(VersionComparator.isNewerVersion("code:230", "231"))
        assertFalse(VersionComparator.isNewerVersion("code:231", "230"))
        assertFalse(VersionComparator.isNewerVersion("code:231", "231"))
    }

    @Test
    fun `code-only installed version cannot be compared against a text tag`() {
        // Without a numeric tag there is nothing to compare: the caller treats
        // this as "cannot confirm", never as "up to date" disguised as false.
        assertFalse(VersionComparator.isNewerVersion("code:231", "v1.2.3"))
    }

    @Test
    fun `extractVersionCode parses and rejects malformed markers`() {
        assertEquals(231L, VersionComparator.extractVersionCode("code:231"))
        assertNull(VersionComparator.extractVersionCode("code:"))
        assertNull(VersionComparator.extractVersionCode("1.2.3"))
    }

    @Test
    fun `stable outranks same-core pre-release both directions`() {
        assertTrue(VersionComparator.isNewerVersion("1.0.0-rc1", "v1.0.0"))
        assertFalse(VersionComparator.isNewerVersion("1.0.0", "v1.0.0-rc1"))
    }

    @Test
    fun `zero-padded components compare numerically`() {
        assertTrue(VersionComparator.isNewerVersion("2026.08-beta1", "v2026.9"))
        assertFalse(VersionComparator.isNewerVersion("2026.08-beta1", "v2026.8"))
    }
}