package com.newsblur.subscription

import android.content.Context
import android.util.Log
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.Receipt
import com.amazon.device.iap.model.UserDataResponse


interface AmznManager {

    /**
     * Open connection to Play Store to retrieve
     * purchases and subscriptions.
     */
    fun registerService(context: Context)
}

class AmznManagerImpl : AmznManager {

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
        PurchasingService.registerListener(context, amznPurchasingListener)
    }

    fun getProductData() {
        val productSkus: MutableSet<String> = mutableSetOf()
//        for (mySku in MySku.values()) {
//            productSkus.add(mySku.getSku())
//        }
        PurchasingService.getProductData(productSkus)


//        val productSkus: MutableSet<string> = HashSet()
//        productSkus.add("com.amazon.example.iap.consumable")
//        productSkus.add("com.amazon.example.iap.entitlement")
//        productSkus.add("com.amazon.example.iap.subscription")
//        PurchasingService.getProductData(productSkus)
    }

    fun purchaseSubscription() {
        val requestId = PurchasingService.purchase("sku")
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
                        Log.d("Amzn", "Unavailable sku $it")
                    }

                    response.productData.forEach {
                        val product = it.value
                        Log.d("Amzn", String.format("Product: %s\n Type: %s\n SKU: %s\n Price: %s\n Description: %s\n", product.title, product.productType, product.sku, product.price, product.description))
                    }
                }
                ProductDataResponse.RequestStatus.FAILED -> {
                    Log.d("Amzn", "ProductDataRequestStatus: FAILED")
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
                    Log.d("Amzn", "onPurchaseResponse: receipt json:" + receipt.toJSON())
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