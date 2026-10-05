package com.samyak.repostore.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.samyak.repostore.RepoStoreApp
import com.samyak.repostore.data.db.InstalledAppMappingDao
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for issue #46: an app installed manually (outside RepoStore)
 * must still be detected so the detail page can offer "Update" instead of only
 * "Open"/"Install". Covers the last-resort acceptance branch: owner tokens that
 * never appear in the package name keep the fuzzy score below the 0.75
 * threshold, but an exact label match or an agreeing installed version is
 * enough to accept the match.
 */
class ManualInstallDetectionTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var installedAppMappingDao: InstalledAppMappingDao
    private lateinit var appInstaller: AppInstaller

    /** packageName -> (label, versionName, versionCode) */
    private data class Info(val label: String, val versionName: String?, val versionCode: Long)

    private val installedPackages = mutableMapOf<String, Info>()

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        val repoStoreApp = mockk<RepoStoreApp>(relaxed = true)
        packageManager = mockk(relaxed = true)
        installedAppMappingDao = mockk(relaxed = true)

        every { context.applicationContext } returns repoStoreApp
        every { repoStoreApp.installedAppMappingDao } returns installedAppMappingDao
        every { context.packageManager } returns packageManager

        every { installedAppMappingDao.getPackageNameSync(any(), any()) } returns null

        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } answers {
            val pkg = firstArg<String>()
            val info = installedPackages[pkg] ?: throw PackageManager.NameNotFoundException(pkg)
            PackageInfo().apply {
                packageName = pkg
                versionName = info.versionName
                val field = PackageInfo::class.java.getDeclaredField("longVersionCode")
                field.isAccessible = true
                field.set(this, info.versionCode)
            }
        }

        every { packageManager.getInstalledApplications(any<Int>()) } answers {
            installedPackages.keys.map { pkg ->
                ApplicationInfo().apply { packageName = pkg }
            }
        }

        every { packageManager.getApplicationLabel(any()) } answers {
            val appInfo = firstArg<ApplicationInfo>()
            val label = installedPackages[appInfo.packageName]?.label ?: ""
            label as CharSequence
        }

        val constructor = AppInstaller::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        appInstaller = constructor.newInstance(context)

        // findPackage step 2 performs a network lookup; force it to fall through
        // to the fuzzy scan so these tests exercise detection logic only.
        mockkObject(PackageIdFetcher)
        every {
            PackageIdFetcher.fetchPackageId(any(), any(), any(), any())
        } throws RuntimeException("offline in tests")
    }

    @After
    fun tearDown() {
        installedPackages.clear()
        unmockkAll()
    }

    private fun addInstalledApp(
        packageName: String,
        label: String,
        versionName: String? = null,
        versionCode: Long = 1L,
    ) {
        installedPackages[packageName] = Info(label, versionName, versionCode)
    }

    @Test
    fun manuallyInstalledMullvad_isAcceptedByExactLabel() {
        // Repo "Mullvad"/"mullvadvpn-android"-class case: the owner token never
        // appears in the package name, so the score stays under 0.75, but the
        // label equals the repository name exactly.
        addInstalledApp("net.mullvad.mullvadvpn", "Mullvad", versionName = "2026.8", versionCode = 30001)
        val result = appInstaller.findPackage("Mullvad", "mullvadvpn-android", "v2026.8")
        assertEquals("net.mullvad.mullvadvpn", result)
    }

    @Test
    fun manuallyInstalledPreRelease_isAcceptedByVersionAgreement() {
        // Partial overlap ("mullvad" appears in the package name but the
        // owner label never does → score lands in the 0.4–0.75 band) and no
        // label match ("VPN Client"); the installed version matching the
        // expected release version is the confirmation.
        addInstalledApp("org.mullvad.vpnclient", "VPN Client", versionName = "2026.8")
        val result = appInstaller.findPackage("Mullvad", "mullvadvpn-android", "2026.8")
        assertEquals("org.mullvad.vpnclient", result)
    }

    @Test
    fun lowerBandMatch_isRejectedWithoutAnyConfirmation() {
        // Partial repo overlap, but the label differs and the installed version is
        // newer than the expected release version — neither confirmation holds.
        addInstalledApp("org.mullvad.otherthing", "Something Else", versionName = "9.9.9")
        val result = appInstaller.findPackage("Mullvad", "mullvadvpn-android", "2026.8")
        assertNull(result)
    }

    @Test
    fun codeOnlyInstalledVersion_stillDetectedForNumericTag() {
        // Pre-release APKs sometimes ship without a versionName; the versionCode
        // fallback keeps detection working against numeric build-number tags.
        addInstalledApp("net.mullvad.mullvadvpn", "Mullvad", versionName = null, versionCode = 30001)
        val result = appInstaller.findPackage("Mullvad", "mullvadvpn-android", "30001")
        assertEquals("net.mullvad.mullvadvpn", result)
    }

    @Test
    fun strongFuzzyMatch_aboveThreshold_unaffected() {
        // Existing behaviour must be preserved: full token match clears 0.75.
        addInstalledApp("org.fossify.calculator", "Fossify Calculator", versionName = "1.0")
        val result = appInstaller.findPackage("Calculator", "FossifyOrg")
        assertEquals("org.fossify.calculator", result)
    }
}