package com.hatchstudio.archive.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.hatchstudio.archive.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small ad coordinator. Ads are optional: entitlement checks live in the
 * ViewModel, so this object never decides who is allowed to see an ad.
 */
object AdsManager {
    private const val INTERSTITIAL_COOLDOWN_MS = 3 * 60 * 1000L

    private var interstitial: InterstitialAd? = null
    private var loadingInterstitial = false
    private var lastInterstitialAt = 0L
    private var rewarded: RewardedAd? = null
    private var loadingRewarded = false

    private val initStarted = AtomicBoolean(false)
    private val _ready = MutableStateFlow(false)

    /** True once consent allows ads and the Mobile Ads SDK is initialized. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * Gathers UMP consent (GDPR / US state privacy) and then initializes ads.
     * Call from the launcher Activity; skip entirely when remove_ads is owned.
     */
    fun gatherConsent(activity: Activity) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        runCatching {
            info.requestConsentInfoUpdate(
                activity,
                params,
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                        if (info.canRequestAds()) initialize(activity)
                    }
                },
                { if (info.canRequestAds()) initialize(activity) },
            )
        }
        // Consent from a previous session lets ads start without waiting.
        if (info.canRequestAds()) initialize(activity)
    }

    fun isPrivacyOptionsRequired(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { }
    }

    private fun initialize(activity: Activity) {
        if (!initStarted.compareAndSet(false, true)) return
        val app = activity.applicationContext
        runCatching {
            MobileAds.initialize(app) {
                _ready.value = true
                preloadInterstitial(app)
                preloadRewarded(app)
            }
        }.onFailure { initStarted.set(false) }
    }

    fun preloadInterstitial(context: Context) {
        if (!_ready.value) return
        if (loadingInterstitial || interstitial != null) return
        loadingInterstitial = true
        InterstitialAd.load(
            context,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadingInterstitial = false
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingInterstitial = false
                    interstitial = null
                }
            },
        )
    }

    /** Returns true when an interstitial was actually shown. */
    fun maybeShowInterstitial(activity: Activity): Boolean {
        val ad = interstitial ?: run {
            preloadInterstitial(activity)
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastInterstitialAt < INTERSTITIAL_COOLDOWN_MS) return false
        lastInterstitialAt = now
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preloadInterstitial(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                preloadInterstitial(activity)
            }
        }
        ad.show(activity)
        return true
    }

    fun preloadRewarded(context: Context) {
        if (!_ready.value) return
        if (loadingRewarded || rewarded != null) return
        loadingRewarded = true
        RewardedAd.load(
            context,
            BuildConfig.ADMOB_REWARDED_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loadingRewarded = false
                    rewarded = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingRewarded = false
                    rewarded = null
                }
            },
        )
    }

    fun showRewarded(
        activity: Activity,
        onReward: () -> Unit,
        onUnavailable: (String) -> Unit,
    ) {
        val ad = rewarded
        if (ad == null) {
            preloadRewarded(activity)
            onUnavailable("The video is still loading. Try again in a moment.")
            return
        }
        rewarded = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preloadRewarded(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                preloadRewarded(activity)
            }
        }
        ad.show(
            activity,
            OnUserEarnedRewardListener { onReward() },
        )
    }
}
