package com.hatchstudio.archive.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.hatchstudio.archive.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

data class BillingState(
    val connected: Boolean = false,
    val available: Boolean = false,
    val busy: Boolean = false,
    val price: String = "$4.99",
    val productTitle: String = "Remove ads",
    val message: String? = null,
)

class BillingManager(
    context: Context,
    private val purchases: PurchaseStore,
    private val onMessage: (String) -> Unit,
) : PurchasesUpdatedListener {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private var details: ProductDetails? = null
    private var pendingActivity: WeakReference<Activity>? = null
    private var pendingLaunch = false
    private var starting = false

    init {
        connect()
    }

    fun connect() {
        if (client.isReady || starting) return
        starting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                starting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _state.value = _state.value.copy(connected = true, message = null)
                    queryProducts()
                    restore(silent = true)
                } else {
                    _state.value = _state.value.copy(
                        connected = false,
                        message = billingMessage(result),
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                starting = false
                _state.value = _state.value.copy(
                    connected = false,
                    message = "Billing disconnected. Tap to reconnect.",
                )
            }
        })
    }

    fun buy(activity: Activity) {
        if (purchases.state.value.removeAds) {
            onMessage("Remove Ads is already active.")
            return
        }
        pendingActivity = WeakReference(activity)
        pendingLaunch = true
        if (!client.isReady) {
            _state.value = _state.value.copy(message = "Connecting to Google Play…")
            connect()
            return
        }
        if (details != null) {
            launch(activity, details!!)
        } else {
            queryProducts()
        }
    }

    fun restore(silent: Boolean = false) {
        if (!client.isReady) {
            if (!silent) connect()
            return
        }
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        ) { result, list ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                if (!silent) onMessage(billingMessage(result))
                return@queryPurchasesAsync
            }
            val match = list.firstOrNull { purchase ->
                purchase.products.contains(BuildConfig.REMOVE_ADS_PRODUCT_ID) &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            if (match != null) {
                grant(match)
                if (!silent) onMessage("Remove Ads restored.")
            } else if (!silent) {
                onMessage("No Remove Ads purchase was found on this Google account.")
            }
        }
    }

    private fun queryProducts() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(BuildConfig.REMOVE_ADS_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        client.queryProductDetailsAsync(params) { result, products ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                _state.value = _state.value.copy(
                    available = false,
                    busy = false,
                    message = billingMessage(result),
                )
                return@queryProductDetailsAsync
            }
            details = products.firstOrNull()
            val price = details?.oneTimePurchaseOfferDetails?.formattedPrice
            _state.value = _state.value.copy(
                available = details != null,
                busy = false,
                price = price ?: _state.value.price,
                productTitle = details?.name ?: "Remove ads",
                message = if (details == null) {
                    "Remove Ads is not configured in Play Console yet."
                } else null,
            )
            if (pendingLaunch && details != null) {
                pendingLaunch = false
                pendingActivity?.get()?.let { launch(it, details!!) }
            }
        }
    }

    private fun launch(activity: Activity, product: ProductDetails) {
        _state.value = _state.value.copy(busy = true, message = null)
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build(),
                ),
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.value = _state.value.copy(busy = false, message = billingMessage(result))
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        _state.value = _state.value.copy(busy = false)
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases.orEmpty()
                    .filter { it.products.contains(BuildConfig.REMOVE_ADS_PRODUCT_ID) }
                    .forEach(::grant)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            else -> onMessage(billingMessage(result))
        }
    }

    private fun grant(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.isAcknowledged) {
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build(),
            ) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchases.setRemoveAds(true)
                    onMessage("Remove Ads unlocked. Thank you.")
                } else {
                    onMessage(billingMessage(result))
                }
            }
        } else {
            purchases.setRemoveAds(true)
            onMessage("Remove Ads is active.")
        }
    }

    private fun billingMessage(result: BillingResult): String {
        val detail = result.debugMessage.takeIf { it.isNotBlank() }
        return when (result.responseCode) {
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                "Google Play billing is unavailable on this device."
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
                "Remove Ads is not available in this country yet."
            BillingClient.BillingResponseCode.NETWORK_ERROR ->
                "Billing needs a network connection. Try again."
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
                "Google Play billing disconnected. Try again."
            else -> detail ?: "Google Play returned an error (${result.responseCode})."
        }
    }
}
