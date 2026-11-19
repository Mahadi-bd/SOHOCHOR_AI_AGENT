package com.sahachor.app.billing

import android.app.Activity
import android.app.Application
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.sahachor.app.docs.Entitlements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BillingUi(
    val loading: Boolean = true,
    val available: Boolean = false,    // Google Play connection works
    val productFound: Boolean = false, // the subscription exists in Play Console
    val price: String? = null,
    val message: String? = null,
)

/**
 * The paid "remove LIFE EASY branding" subscription.
 * Works only for an app installed from Google Play with the product created in Play Console (see docs/BILLING_SETUP.md);
 * elsewhere it reports "unavailable" and the app keeps running normally.
 */
class BillingManager(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(BillingUi())
    val state: StateFlow<BillingUi> = _state

    private var details: ProductDetails? = null
    private var offerToken: String? = null

    private val client: BillingClient = BillingClient.newBuilder(app)
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                scope.launch { handle(purchases) }
            } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                say("কেনা বাতিল করা হয়েছে।")
            } else {
                say("কেনা সম্পন্ন হয়নি। (${result.responseCode})")
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun start() {
        if (client.isReady) { refresh(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) refresh()
                else _state.update { it.copy(loading = false, available = false) }
            }

            override fun onBillingServiceDisconnected() { _state.update { it.copy(available = false) } }
        })
    }

    /** Re-reads the product price and the user's purchases (also the "restore purchase" button). */
    fun refresh() {
        if (!client.isReady) { start(); return }
        scope.launch {
            loadDetails()
            val res = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
            if (res.billingResult.responseCode == BillingClient.BillingResponseCode.OK) handle(res.purchasesList)
        }
    }

    private suspend fun loadDetails() {
        val product = QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.SUBS).build()
        val res = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build())
        val pd = res.productDetailsList?.firstOrNull()
        details = pd
        val offer = pd?.subscriptionOfferDetails?.firstOrNull()
        offerToken = offer?.offerToken
        val price = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
        _state.update { it.copy(loading = false, available = true, productFound = pd != null && offer != null, price = price) }
    }

    private suspend fun handle(purchases: List<Purchase>) {
        val owned = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.contains(PRODUCT_ID) }
        for (p in owned) {
            if (!p.isAcknowledged) client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
        }
        Entitlements.set(owned.isNotEmpty())
        if (owned.isNotEmpty()) say("LIFE EASY Pro চালু হয়েছে। এখন থেকে ডকুমেন্টে লোগো থাকবে না।")
    }

    fun buy(activity: Activity) {
        val pd = details
        val token = offerToken
        if (pd == null || token == null) { say("সাবস্ক্রিপশন প্ল্যান Google Play-তে পাওয়া যায়নি।"); return }
        val p = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).setOfferToken(token).build()
        val r = client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(p)).build())
        if (r.responseCode != BillingClient.BillingResponseCode.OK) say("কেনার পর্দা খোলা যায়নি। (${r.responseCode})")
    }

    private fun say(m: String) { _state.update { it.copy(message = m) } }

    companion object {
        /** Create a subscription with exactly this product ID in Play Console. */
        const val PRODUCT_ID = "remove_branding"
    }
}
