package com.example.ads

import android.content.Context
import android.util.Log
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AdManager {
    private const val TAG = "AdManager"
    
    // Start.io developer test App ID or standard dummy App ID "0"
    private const val APP_ID = "200355447" 

    // Keep track of successful downloads
    private var successDownloadCount = 0

    // Premium multithreaded downloading feature state (unlocked by rewarded video)
    private val _isPremiumUnlocked = MutableStateFlow(false)
    val isPremiumUnlocked: StateFlow<Boolean> = _isPremiumUnlocked

    fun init(context: Context) {
        try {
            // Initialize Start.io SDK
            // We set returnAdsEnabled (3rd parameter) to false to prevent ads on app launch or exit
            StartAppSDK.init(context, APP_ID, false)
            // Enable Test Ads during development
            StartAppSDK.setTestAdsEnabled(true)
            Log.d(TAG, "Start.io SDK Initialized with Test Ads enabled.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Start.io SDK: ${e.message}", e)
        }
    }

    /**
     * Call when a download completes successfully.
     * Shows an interstitial after every 3 successful downloads.
     */
    fun onDownloadSuccess(context: Context, onAdClosedOrFailed: () -> Unit) {
        successDownloadCount++
        Log.d(TAG, "Success download count: $successDownloadCount")
        if (successDownloadCount >= 3) {
            successDownloadCount = 0
            Log.d(TAG, "Triggering Interstitial Ad")
            showInterstitial(context, onAdClosedOrFailed)
        } else {
            onAdClosedOrFailed()
        }
    }

    /**
     * Show Interstitial Ad gracefully.
     */
    fun showInterstitial(context: Context, onAdClosedOrFailed: () -> Unit) {
        try {
            val startAppAd = StartAppAd(context)
            startAppAd.loadAd(StartAppAd.AdMode.AUTOMATIC, object : AdEventListener {
                override fun onReceiveAd(ad: Ad) {
                    startAppAd.showAd(object : AdDisplayListener {
                        override fun adDisplayed(ad: Ad?) {
                            Log.d(TAG, "Interstitial displayed")
                        }

                        override fun adNotDisplayed(ad: Ad?) {
                            Log.d(TAG, "Interstitial not displayed")
                            onAdClosedOrFailed()
                        }

                        override fun adClicked(ad: Ad?) {
                            Log.d(TAG, "Interstitial clicked")
                        }

                        override fun adHidden(ad: Ad?) {
                            Log.d(TAG, "Interstitial hidden/closed")
                            onAdClosedOrFailed()
                        }
                    })
                }

                override fun onFailedToReceiveAd(ad: Ad?) {
                    Log.e(TAG, "Failed to load interstitial ad: ${ad?.errorMessage}")
                    onAdClosedOrFailed()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error showing interstitial: ${e.message}", e)
            onAdClosedOrFailed()
        }
    }

    /**
     * Load and show a Rewarded Video Ad to unlock the Premium Downloader Speed for 24 hours.
     */
    fun showRewardedAd(context: Context, onRewardEarned: () -> Unit, onFailure: (String) -> Unit) {
        try {
            val rewardedAd = StartAppAd(context)
            rewardedAd.loadAd(StartAppAd.AdMode.REWARDED_VIDEO, object : AdEventListener {
                override fun onReceiveAd(ad: Ad) {
                    rewardedAd.showAd(object : AdDisplayListener {
                        override fun adDisplayed(ad: Ad?) {
                            Log.d(TAG, "Rewarded ad displayed")
                        }

                        override fun adNotDisplayed(ad: Ad?) {
                            Log.d(TAG, "Rewarded ad failed to show")
                            onFailure("Ad failed to display. Please try again.")
                        }

                        override fun adClicked(ad: Ad?) {}

                        override fun adHidden(ad: Ad?) {
                            // User finished watching or closed the ad
                            // Standard practice for Start.io is that adHidden on a successful loading
                            // of REWARDED_VIDEO represents completion/reward.
                            Log.d(TAG, "Rewarded ad closed. Granting reward!")
                            _isPremiumUnlocked.value = true
                            onRewardEarned()
                        }
                    })
                }

                override fun onFailedToReceiveAd(ad: Ad?) {
                    Log.e(TAG, "Failed to receive rewarded ad: ${ad?.errorMessage}")
                    onFailure(ad?.errorMessage ?: "Failed to load Rewarded Ad. Please try again later.")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error during rewarded ad display: ${e.message}", e)
            onFailure(e.message ?: "An unknown error occurred.")
        }
    }

    /**
     * Lock/Unlock premium state manually (e.g. for testing or UI)
     */
    fun setPremiumState(unlocked: Boolean) {
        _isPremiumUnlocked.value = unlocked
    }
}
