package com.omariskandarani.livelatexapp

import android.app.Activity
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

/**
 * Google Play Billing for one in-app (non-consumable) Pro product.
 */
class PlayBillingHelper(
    private val activity: Activity,
    private val entitlement: EntitlementRepository,
    private val productId: String = BuildConfig.IAP_PRO_PRODUCT_ID,
    private val onPurchasesUpdated: () -> Unit = {}
) : PurchasesUpdatedListener {

    private var billingClient: BillingClient? = null

    var productDetails: ProductDetails? = null
        private set

    fun startConnection(onReady: (Boolean) -> Unit) {
        val client = BillingClient.newBuilder(activity)
            .setListener(this)
            .enablePendingPurchases()
            .build()
        billingClient = client
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    onReady(false)
                    return
                }
                queryExistingPurchases()
                queryProductDetails { onReady(true) }
            }

            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun queryProductDetails(done: () -> Unit) {
        val client = billingClient ?: return done()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && list.isNotEmpty()) {
                productDetails = list.first()
            }
            done()
        }
    }

    private fun queryExistingPurchases() {
        val client = billingClient ?: return
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            syncEntitlementFromPurchases(purchases)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            syncEntitlementFromPurchases(purchases)
            for (p in purchases) {
                if (!p.isAcknowledged && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    acknowledge(p)
                }
            }
        }
        onPurchasesUpdated()
    }

    private fun syncEntitlementFromPurchases(purchases: List<Purchase>) {
        val hasPro = purchases.any { purchase ->
            purchase.products.contains(productId) &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (hasPro) entitlement.setPurchasedPro(true)
    }

    private fun acknowledge(purchase: Purchase) {
        val client = billingClient ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { }
    }

    fun launchPurchaseFlow() {
        val client = billingClient ?: return
        val pd = productDetails ?: return
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(pd)
            .build()
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        client.launchBillingFlow(activity, flow)
    }

    fun endConnection() {
        billingClient?.endConnection()
        billingClient = null
    }

    companion object {
        fun refreshEntitlementFromStore(
            activity: Activity,
            entitlement: EntitlementRepository,
            productId: String = BuildConfig.IAP_PRO_PRODUCT_ID
        ) {
            val client = BillingClient.newBuilder(activity)
                .setListener { _, _ -> }
                .enablePendingPurchases()
                .build()
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        client.endConnection()
                        return
                    }
                    client.queryPurchasesAsync(
                        QueryPurchasesParams.newBuilder()
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    ) { r, list ->
                        if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                            val hasPro = list.any { p ->
                                p.products.contains(productId) &&
                                    p.purchaseState == Purchase.PurchaseState.PURCHASED
                            }
                            if (hasPro) entitlement.setPurchasedPro(true)
                        }
                        client.endConnection()
                    }
                }

                override fun onBillingServiceDisconnected() {}
            })
        }
    }
}
