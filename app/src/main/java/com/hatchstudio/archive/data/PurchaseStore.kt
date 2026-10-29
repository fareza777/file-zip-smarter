package com.hatchstudio.archive.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Entitlements(
    val removeAds: Boolean = false,
    val adFreeUntil: Long = 0L,
) {
    fun adFree(now: Long = System.currentTimeMillis()): Boolean =
        removeAds || adFreeUntil > now
}

/**
 * Ad-free entitlement is kept on-device, matching ZipHatch's account-free model.
 * Play Billing is the source of truth for the one-time purchase; the rewarded
 * window is intentionally short and local.
 */
class PurchaseStore(context: Context) {
    private val prefs = context.getSharedPreferences("hatch_purchases", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Entitlements> = _state.asStateFlow()

    private fun load() = Entitlements(
        removeAds = prefs.getBoolean("remove_ads", false),
        adFreeUntil = prefs.getLong("ad_free_until", 0L),
    )

    fun setRemoveAds(value: Boolean) {
        prefs.edit().putBoolean("remove_ads", value).apply()
        _state.value = load()
    }

    fun grantAdFree(hours: Int) {
        val base = maxOf(System.currentTimeMillis(), _state.value.adFreeUntil)
        prefs.edit().putLong("ad_free_until", base + hours * 60L * 60L * 1000L).apply()
        _state.value = load()
    }

    fun clear() {
        prefs.edit().clear().apply()
        _state.value = load()
    }
}
