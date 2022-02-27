package com.newsblur.subscription

import android.content.Context
import android.util.Log
import com.amazon.device.drm.LicensingListener
import com.amazon.device.drm.LicensingService
import com.amazon.device.drm.model.LicenseResponse
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.*

private const val AMZN_SKU = "nb.premium"
private const val TAG = "Amzn"

interface AmznManagerListener {

    fun onServiceListenerRegistered()
}

interface AmznManager {

    /**
     * Open connection to Play Store to retrieve
     * purchases and subscriptions.
     */
    fun registerService(context: Context)

    fun getProductData()
}

class AmznManagerImpl(private val listener: AmznManagerListener) : AmznManager {

    private val amznPurchasingListener = AmznPurchasingListener()

    private var currentUserId: String? = null
    private var currentMarketplace: String? = null

    // 1) register service/listener
    // 2) Retrieve information (User Id and marketplace) about the current user by implementing getUserData() in onResume()
    // 3) The getPurchaseUpdates method retrieves all purchase transactions by a user since the last time the method was called.
    // Call getPurchaseUpdates in the onResume method to ensure you are getting the latest updates.
    // false - Returns a paginated response of purchase history since the last call to getPurchaseUpdates(). Retrieves the receipts for the user's pending consumable, entitlement, and subscription purchases. Amazon recommends using this approach in most cases.
    // true - Retrieves a user's entire purchase history. You need to store the data somewhere, such as in a server-side data cache or to hold everything in memory.
    // 4) You will receive a response from getPurchaseUpdates in most scenarios. Responses are sent in the following cases:
    // Subscriptions and entitlements: You will always receive a receipt for subscription and entitlement purchases.

    override fun registerService(context: Context) {
//        LicensingService.verifyLicense(context, object : LicensingListener {
//            override fun onLicenseCommandResponse(p0: LicenseResponse?) {
//                Log.d(TAG, p0.toString())
//            }
//
//        })
        PurchasingService.registerListener(context, amznPurchasingListener)
        listener.onServiceListenerRegistered()
    }

    override fun getProductData() {
        val productSkus = mutableSetOf(AMZN_SKU)
        PurchasingService.getProductData(productSkus)
    }

    fun purchaseSubscription() {
        val requestId = PurchasingService.purchase(AMZN_SKU)
    }

    private inner class AmznPurchasingListener : PurchasingListener {

        override fun onUserDataResponse(response: UserDataResponse) {
            when (response.requestStatus) {
                UserDataResponse.RequestStatus.SUCCESSFUL -> {
                    currentUserId = response.userData.userId
                    currentMarketplace = response.userData.marketplace
                }
                UserDataResponse.RequestStatus.FAILED,
                UserDataResponse.RequestStatus.NOT_SUPPORTED -> {
                }
                null -> {
                }
            }
        }

        override fun onProductDataResponse(response: ProductDataResponse) {
            when (response.requestStatus) {
                ProductDataResponse.RequestStatus.SUCCESSFUL -> {
                    response.unavailableSkus.forEach {
                        Log.d(TAG, "Unavailable sku $it")
                    }

                    response.productData.forEach {
                        val product = it.value
                        Log.d(TAG, String.format("Product: %s\n Type: %s\n SKU: %s\n Price: %s\n Description: %s\n", product.title, product.productType, product.sku, product.price, product.description))
                    }
                }
                ProductDataResponse.RequestStatus.FAILED -> {
                    Log.d(TAG, "ProductDataRequestStatus: FAILED ${response.toJSON()}")
                }
                ProductDataResponse.RequestStatus.NOT_SUPPORTED -> {
                }
                null -> {
                }
            }
        }

        override fun onPurchaseResponse(response: PurchaseResponse) {
            when (response.requestStatus) {
                PurchaseResponse.RequestStatus.SUCCESSFUL -> {
                    val receipt: Receipt = response.receipt
                    currentUserId = response.userData.userId
                    currentMarketplace = response.userData.marketplace
                    Log.d(TAG, "onPurchaseResponse: receipt json:" + receipt.toJSON())
                    // TODO handle receipt
//                    handleReceipt(receipt, response.userData)
//                    refresh content
                }
                PurchaseResponse.RequestStatus.FAILED,
                PurchaseResponse.RequestStatus.INVALID_SKU,
                PurchaseResponse.RequestStatus.ALREADY_PURCHASED,
                PurchaseResponse.RequestStatus.NOT_SUPPORTED -> {
                }
                null -> {
                }
            }
        }

        override fun onModifySubscriptionResponse(p0: ModifySubscriptionResponse?) {

        }

        override fun onPurchaseUpdatesResponse(response: PurchaseUpdatesResponse) {
            // Process receipts
            when (response.requestStatus) {
                PurchaseUpdatesResponse.RequestStatus.SUCCESSFUL -> {
                    for (receipt in response.receipts) {
                        // Process receipts
                        // TODO save receipt
//                        iapManager.handleReceipt(receipt, response.getUserData());
                    }
                    if (response.hasMore()) {
                        PurchasingService.getPurchaseUpdates(false)
                    }
                }
                PurchaseUpdatesResponse.RequestStatus.FAILED -> {
                }
                PurchaseUpdatesResponse.RequestStatus.NOT_SUPPORTED -> {
                }
                null -> {
                }
            }
        }
    }
}