package com.samyak.repostore.util

/**
 * Utility for comparing version strings from GitHub releases with installed app versions.
 * Handles various version formats like "v1.2.3", "1.2.3", "2.0.0-beta", "1.0.0-rc1", etc.
 */
object VersionComparator {

    /**
     * Check if the latest version is newer than the current installed version.
     * 
     * @param installedVersion The version string of the installed app (e.g., "1.2.3")
     * @param latestVersion The version string from GitHub release tag (e.g., "v1.2.4")
     * @return true if latestVersion is newer than installedVersion
     */
    fun isNewerVersion(installedVersion: String, latestVersion: String): Boolean {
        // When the installed package has no usable versionName the comparator works on
        // versionCode only — but a release tag is a string, not a number, so without a
        // numeric tag there is nothing to compare and the caller must treat it as
        // "cannot confirm" rather than "up to date" (issue #46).
        if (isCodeOnly(installedVersion)) {
            val installedCode = extractVersionCode(installedVersion) ?: return false
            val tagCode = latestVersion.trim().toLongOrNull() ?: return false
            return tagCode > installedCode
        }

        val installedCore = normalizeVersion(installedVersion)
        val latestCore = normalizeVersion(latestVersion)
        
        if (installedCore.isEmpty() || latestCore.isEmpty()) {
            return false
        }
        
        // 1. Compare the numeric core (major.minor.patch...) using Long to tolerate
        //    large build numbers that would overflow Int.
        val installedParts = installedCore.split(".")
        val latestParts = latestCore.split(".")
        val maxLength = maxOf(installedParts.size, latestParts.size)
        
        for (i in 0 until maxLength) {
            val installedPart = installedParts.getOrNull(i)?.toLongOrNull() ?: 0L
            val latestPart = latestParts.getOrNull(i)?.toLongOrNull() ?: 0L
            
            when {
                latestPart > installedPart -> return true
                latestPart < installedPart -> return false
            }
        }
        
        // 2. Numeric cores are equal — fall back to pre-release ordering.
        //    A stable release (no pre-release tag) outranks any pre-release of the
        //    same core (e.g. 1.0.0 is newer than 1.0.0-rc1).
        val installedPre = extractPreRelease(installedVersion)
        val latestPre = extractPreRelease(latestVersion)
        
        return when {
            installedPre.isEmpty() && latestPre.isEmpty() -> false // truly equal
            installedPre.isNotEmpty() && latestPre.isEmpty() -> true  // stable > pre-release
            installedPre.isEmpty() && latestPre.isNotEmpty() -> false // pre-release < stable
            else -> comparePreRelease(latestPre, installedPre) > 0
        }
    }
    
    /**
     * Extract the pre-release identifier (the part after the first '-' or '_' in the
     * core-stripped version), e.g. "v1.2.3-rc2" -> "rc2". Returns "" when absent.
     */
    private fun extractPreRelease(version: String): String {
        var normalized = version.trim()
        val prefixes = listOf("v", "V", "version", "Version", "release-", "Release-", "ver", "Ver")
        for (prefix in prefixes) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.removePrefix(prefix)
                break
            }
        }
        val sepIndex = normalized.indexOfFirst { it == '-' || it == '_' }
        if (sepIndex < 0) return ""
        return normalized.substring(sepIndex + 1).lowercase().trim()
    }
    
    /**
     * Compare two non-empty pre-release strings. Splits on common separators and
     * compares segment by segment: numeric segments compare numerically, others
     * lexically. Returns >0 if [a] is newer, <0 if older, 0 if equal.
     */
    private fun comparePreRelease(a: String, b: String): Int {
        val aParts = a.split('.', '-', '_').filter { it.isNotEmpty() }
        val bParts = b.split('.', '-', '_').filter { it.isNotEmpty() }
        val max = maxOf(aParts.size, bParts.size)
        for (i in 0 until max) {
            val ap = aParts.getOrNull(i) ?: return -1 // shorter pre-release is older
            val bp = bParts.getOrNull(i) ?: return 1
            val an = ap.toLongOrNull()
            val bn = bp.toLongOrNull()
            val cmp = when {
                an != null && bn != null -> an.compareTo(bn)
                else -> ap.compareTo(bp)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
    
    /**
     * Normalize a version string by removing common prefixes and suffixes.
     * Examples:
     * - "v1.2.3" -> "1.2.3"
     * - "V1.2.3" -> "1.2.3"
     * - "1.2.3-beta" -> "1.2.3"
     * - "1.2.3-rc1" -> "1.2.3"
     * - "release-1.2.3" -> "1.2.3"
     */
    fun normalizeVersion(version: String): String {
        var normalized = version.trim()
        
        // Remove common prefixes
        val prefixes = listOf("v", "V", "version", "Version", "release-", "Release-", "ver", "Ver")
        for (prefix in prefixes) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.removePrefix(prefix)
                break
            }
        }
        
        // Remove suffixes after hyphen (e.g., "-beta", "-rc1", "-alpha")
        val hyphenIndex = normalized.indexOf('-')
        if (hyphenIndex > 0) {
            normalized = normalized.substring(0, hyphenIndex)
        }
        
        // Remove suffixes after underscore (e.g., "_beta", "_rc1")
        val underscoreIndex = normalized.indexOf('_')
        if (underscoreIndex > 0) {
            normalized = normalized.substring(0, underscoreIndex)
        }
        
        // Keep only digits and dots
        normalized = normalized.replace(Regex("[^0-9.]"), "")
        
        // Remove leading/trailing dots
        normalized = normalized.trim('.')

        return normalized
    }

    /**
     * True when the comparison has to fall back to versionCode-only matching: the
     * installed version carries a "code:<n>" marker (no usable versionName) or the
     * release tag is a bare build number.
     */
    fun isCodeOnly(version: String): Boolean = version.trim().startsWith("code:")

    /**
     * Extract the bare versionCode from a "code:<n>" marker, or null when absent.
     */
    fun extractVersionCode(version: String): Long? =
        version.trim().removePrefix("code:").toLongOrNull()
}
