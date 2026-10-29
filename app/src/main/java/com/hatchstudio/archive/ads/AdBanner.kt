package com.hatchstudio.archive.ads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.hatchstudio.archive.BuildConfig

@Composable
fun AdBanner(
    modifier: Modifier = Modifier,
    visible: Boolean,
) {
    if (!visible) return
    val context = LocalContext.current
    val widthDp = (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(280)
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.width(widthDp.dp).height(56.dp),
            factory = { view ->
                AdView(view).apply {
                    adUnitId = BuildConfig.ADMOB_BANNER_ID
                    val adaptive = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                        view,
                        widthDp,
                    )
                    setAdSize(adaptive ?: AdSize.BANNER)
                    loadAd(AdRequest.Builder().build())
                }
            },
            onRelease = { view -> view.destroy() },
        )
    }
}
