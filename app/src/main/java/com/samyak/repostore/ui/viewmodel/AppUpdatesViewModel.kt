package com.samyak.repostore.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.samyak.repostore.data.db.InstalledAppMappingDao
import com.samyak.repostore.data.model.ReleaseAsset
import com.samyak.repostore.data.repository.GitHubRepository
import com.samyak.repostore.util.AppInstaller
import com.samyak.repostore.util.ApkArchitectureHelper
import com.samyak.repostore.util.ApkSelectionResult
import com.samyak.repostore.util.VersionComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A single app that has an update available.
 */
data class AppUpdate(
    val owner: String,
    val repo: String,
    val packageName: String,
    val appName: String,
    val installedVersion: String,
    val latestVersion: String,
    val asset: ReleaseAsset
) {
    val sizeBytes: Long get() = asset.size
}

sealed class AppUpdatesUiState {
    data object Loading : AppUpdatesUiState()
    data class Success(val updates: List<AppUpdate>) : AppUpdatesUiState()
    data object Empty : AppUpdatesUiState()
    data class Error(val message: String) : AppUpdatesUiState()
}

class AppUpdatesViewModel(
    private val repository: GitHubRepository,
    private val installedAppMappingDao: InstalledAppMappingDao,
    private val appInstaller: AppInstaller
) : ViewModel() {

    private val _uiState = MutableStateFlow<AppUpdatesUiState>(AppUpdatesUiState.Loading)
    val uiState: StateFlow<AppUpdatesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = AppUpdatesUiState.Loading
            try {
                val updates = loadUpdates()
                _uiState.value = if (updates.isEmpty()) {
                    AppUpdatesUiState.Empty
                } else {
                    AppUpdatesUiState.Success(updates)
                }
            } catch (e: Exception) {
                _uiState.value = AppUpdatesUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun loadUpdates(): List<AppUpdate> = withContext(Dispatchers.IO) {
        val mappings = installedAppMappingDao.getAllMappingsFlow().first()

        mappings.map { mapping ->
            async {
                try {
                    val packageName = mapping.packageName
                    if (!appInstaller.isInstalled(packageName)) return@async null

                    val installedVersion = appInstaller.getInstalledVersion(packageName)
                        ?: return@async null

                    val release = repository.getLatestRelease(
                        mapping.ownerName,
                        mapping.repoName,
                        forceRefresh = true
                    ).getOrNull() ?: return@async null

                    if (release.draft) return@async null
                    val latestTag = release.tagName.takeIf { it.isNotBlank() } ?: return@async null

                    if (!VersionComparator.isNewerVersion(installedVersion, latestTag)) return@async null
                    // isNewerVersion already handles "code:<n>" installed versions; a tag
                    // that parses as pure number is compared against the code — anything
                    // else is treated as not comparable and yields false upstream.

                    val asset = when (val selection = ApkArchitectureHelper.selectBestApk(release.assets)) {
                        is ApkSelectionResult.Single -> selection.asset
                        is ApkSelectionResult.ExactMatch -> selection.asset
                        is ApkSelectionResult.Universal -> selection.asset
                        is ApkSelectionResult.Fallback -> selection.asset
                        is ApkSelectionResult.NoApkFound -> return@async null
                    }

                    val appName = appInstaller.getAppLabel(packageName) ?: mapping.repoName

                    AppUpdate(
                        owner = mapping.ownerName,
                        repo = mapping.repoName,
                        packageName = packageName,
                        appName = appName,
                        installedVersion = installedVersion,
                        latestVersion = latestTag,
                        asset = asset
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.appName.lowercase() }
    }
}

class AppUpdatesViewModelFactory(
    private val repository: GitHubRepository,
    private val installedAppMappingDao: InstalledAppMappingDao,
    private val appInstaller: AppInstaller
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppUpdatesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppUpdatesViewModel(repository, installedAppMappingDao, appInstaller) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
