package com.newsblur.activity

import android.net.Uri
import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.ProductDetails
import com.newsblur.R
import com.newsblur.databinding.ActivitySubscriptionBinding
import com.newsblur.databinding.ViewArchiveSubscriptionBinding
import com.newsblur.databinding.ViewPremiumSubscriptionBinding
import com.newsblur.di.IconLoader
import com.newsblur.subscription.SubscriptionManager
import com.newsblur.subscription.SubscriptionManagerImpl
import com.newsblur.subscription.SubscriptionsListener
import com.newsblur.util.AppConstants
import com.newsblur.util.BetterLinkMovementMethod
import com.newsblur.util.ImageLoader
import com.newsblur.util.UIUtils
import dagger.hilt.android.AndroidEntryPoint
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class SubscriptionActivity : NbActivity() {

    @IconLoader
    @Inject
    lateinit var iconLoader: ImageLoader

    private lateinit var binding: ActivitySubscriptionBinding
    private lateinit var bindingPremium: ViewPremiumSubscriptionBinding
    private lateinit var bindingArchive: ViewArchiveSubscriptionBinding
    private lateinit var subscriptionManager: SubscriptionManager

    private val subscriptionManagerListener = object : SubscriptionsListener {

        override fun onActiveSubscription(renewalMessage: String?, isPremium: Boolean, isArchive: Boolean) {
            showActiveSubscriptionDetails(renewalMessage)
        }

        override fun onAvailableSubscriptions(productDetails: List<ProductDetails>) {
            showAvailableSubscriptionDetails(productDetails)
        }

        override fun onBillingConnectionReady() {
            subscriptionManager.syncSubscriptionState()
        }

        override fun onBillingConnectionError(message: String?) {
            showSubscriptionDetailsError(message)
        }
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        bindingPremium = ViewPremiumSubscriptionBinding.bind(binding.containerPremiumSubscription.root)
        bindingArchive = ViewArchiveSubscriptionBinding.bind(binding.containerArchiveSubscription.root)
        setContentView(binding.root)
        setupUI()
        setupBilling()
    }

    private fun setupUI() {
        UIUtils.setupToolbar(this, R.drawable.logo, getString(R.string.subscription_toolbar_title), true)

        // linkify before setting the string resource
        BetterLinkMovementMethod.linkify(Linkify.WEB_URLS, binding.textPolicies)
                .setOnLinkClickListener { _: TextView?, url: String? ->
                    UIUtils.handleUri(this@SubscriptionActivity, Uri.parse(url))
                    true
                }
        binding.textPolicies.text = UIUtils.fromHtml(getString(R.string.premium_policies))
//        binding.textSubTitle.paintFlags = binding.textSubTitle.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        iconLoader.displayImage(AppConstants.LYRIC_PHOTO_URL, bindingPremium.imgShiloh)
    }

    private fun setupBilling() {
        subscriptionManager = SubscriptionManagerImpl(this, lifecycleScope)
        subscriptionManager.startBillingConnection(subscriptionManagerListener)
    }

    private fun showSubscriptionDetailsError(message: String?) {
//        binding.textLoading.text = message ?: getString(R.string.premium_subscription_details_error)
//        binding.textLoading.visibility = View.VISIBLE
//        binding.containerSub.visibility = View.GONE
    }

    private fun showAvailableSubscriptionDetails(productDetails: List<ProductDetails>) {
        productDetails.find { it.productId == AppConstants.PREMIUM_SUB_ID }?.let {
            showPremiumSubscription(it)
        } ?: hidePremiumSubscription()
        productDetails.find { it.productId == AppConstants.PREMIUM_ARCHIVE_SUB_ID }?.let {
            showArchiveSubscription(it)
        }
        // TODO check if any sub is visible
    }

    private fun showPremiumSubscription(productDetails: ProductDetails) {
        val productOffer = productDetails.subscriptionOfferDetails?.firstOrNull()
        productOffer?.let { offerDetails ->
            val pricingPhase = offerDetails.pricingPhases.pricingPhaseList.firstOrNull()
            pricingPhase?.let { pricing ->
                bindingPremium.textSubPrice.text = extractProductPricing(pricing)
                bindingPremium.textLoading.visibility = View.GONE
                bindingPremium.containerSub.visibility = View.VISIBLE
                bindingPremium.containerSub.setOnClickListener {
                    subscriptionManager.purchaseSubscription(this, productDetails, offerDetails)
                }
            }
        } ?: hidePremiumSubscription()
    }

    private fun showArchiveSubscription(productDetails: ProductDetails) {
        val productOffer = productDetails.subscriptionOfferDetails?.firstOrNull()
        productOffer?.let { offerDetails ->
            val pricingPhase = offerDetails.pricingPhases.pricingPhaseList.firstOrNull()
            pricingPhase?.let { pricing ->
                bindingArchive.textSubPrice.text = extractProductPricing(pricing)
                bindingArchive.textLoading.visibility = View.GONE
                bindingArchive.containerSub.visibility = View.VISIBLE
                bindingArchive.containerSub.setOnClickListener {
                    subscriptionManager.purchaseSubscription(this, productDetails, offerDetails)
                }
            }
        } ?: hideArchiveSubscription()
    }

    private fun hidePremiumSubscription() {
        bindingPremium.containerSub.visibility = View.GONE
    }

    private fun hideArchiveSubscription() {
        bindingArchive.containerSub.visibility = View.GONE
    }

    private fun showActiveSubscriptionDetails(renewalMessage: String?) {
//        binding.containerGoingPremium.visibility = View.GONE
//        binding.containerSubscribed.visibility = View.VISIBLE

        if (!renewalMessage.isNullOrEmpty()) {
            binding.textSubscriptionRenewal.text = renewalMessage
            binding.textSubscriptionRenewal.visibility = View.VISIBLE
        }
    }

    private fun extractProductPricing(pricing: ProductDetails.PricingPhase) : String {
        val price = (pricing.priceAmountMicros / 1000f / 1000f).toDouble()
        val currency = Currency.getInstance(pricing.priceCurrencyCode)
        val currencySymbol = currency.getSymbol(Locale.getDefault())
        return StringBuilder().apply {
            append(String.format(Locale.getDefault(), "%.2f", price))
            append(" per year (")
            append(currencySymbol)
            append(String.format(Locale.getDefault(), "%.2f", price / 12))
            append("/month)")
        }.toString()
    }
}