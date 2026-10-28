package com.hatchstudio.archive.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.LIGHT,
    val landingTreeUri: String? = null,
    val landingLabel: String? = null,
    val conflict: ConflictPolicy = ConflictPolicy.REPLACE,
    val createFolder: Boolean = true,
    val keepAwake: Boolean = true,
    val haptics: Boolean = true,
    val onboarded: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("hatch_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val landingTree: Uri?
        get() = _state.value.landingTreeUri?.let(Uri::parse)

    private fun load(): AppSettings = AppSettings(
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "LIGHT").orEmpty()) }
            .getOrDefault(ThemeMode.LIGHT),
        landingTreeUri = prefs.getString("landingTreeUri", null),
        landingLabel = prefs.getString("landingLabel", null),
        conflict = runCatching {
            ConflictPolicy.valueOf(prefs.getString("conflict", "REPLACE").orEmpty())
        }.getOrDefault(ConflictPolicy.REPLACE),
        createFolder = prefs.getBoolean("createFolder", true),
        keepAwake = prefs.getBoolean("keepAwake", true),
        haptics = prefs.getBoolean("haptics", true),
        onboarded = prefs.getBoolean("onboarded", false),
    )

    fun setTheme(mode: ThemeMode) = update { prefs.edit().putString("theme", mode.name).apply() }

    fun setLanding(uri: Uri, label: String) = update {
        prefs.edit()
            .putString("landingTreeUri", uri.toString())
            .putString("landingLabel", label)
            .apply()
    }

    fun clearLanding() = update {
        prefs.edit().remove("landingTreeUri").remove("landingLabel").apply()
    }

    fun setConflict(value: ConflictPolicy) = update {
        prefs.edit().putString("conflict", value.name).apply()
    }

    fun setCreateFolder(value: Boolean) = update {
        prefs.edit().putBoolean("createFolder", value).apply()
    }

    fun setKeepAwake(value: Boolean) = update {
        prefs.edit().putBoolean("keepAwake", value).apply()
    }

    fun setHaptics(value: Boolean) = update {
        prefs.edit().putBoolean("haptics", value).apply()
    }

    fun setOnboarded(value: Boolean) = update {
        prefs.edit().putBoolean("onboarded", value).apply()
    }

    private fun update(change: () -> Unit) {
        change()
        _state.value = load()
    }
}
