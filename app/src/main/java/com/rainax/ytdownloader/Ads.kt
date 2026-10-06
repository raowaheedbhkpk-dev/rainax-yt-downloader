package com.rainax.ytdownloader

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob: a banner above the bottom bar and an occasional full-screen ad after adding downloads.
 *
 * The ad unit IDs come from app/build.gradle (BuildConfig.ADMOB_*). They are Google's TEST IDs until you put
 * your own there (and your App ID in the manifest placeholder `admobAppId`). Test ads are safe to tap.
 *
 * Privacy: Google's consent form (UMP) is shown first where the law needs it (EU/UK); ads load only after.
 */
object Ads {

    private val started = AtomicBoolean(false)
    @Volatile private var ready = false
    private var interstitial: InterstitialAd? = null
    private var loadingInterstitial = false
    private var lastFullScreen = 0L
    private var downloadsSinceAd = 0

    /** True once a banner is showing (the screen keeps it visible only then). */
    var bannerLoaded = false
        private set

    /** Full-screen ads: at most one every 3 minutes, and only after every 3rd download added. */
    private const val MIN_GAP_MS = 3 * 60 * 1000L
    private const val EVERY_N_DOWNLOADS = 3

    /** Call from the main screen: asks for consent where needed, then starts the ads SDK. */
    fun start(activity: Activity, onReady: () -> Unit) {
        if (!BuildConfig.ADS_ENABLED) return
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        consent.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                if (error != null) Log.w("RAINAX", "consent form: ${error.message}")
                if (consent.canRequestAds()) init(activity, onReady)
            }
        }, { error ->
            Log.w("RAINAX", "consent info: ${error.message}")
            if (consent.canRequestAds()) init(activity, onReady)    // e.g. offline: use the earlier choice
        })
        // a choice from an earlier start: ads can begin right away while the check runs
        if (consent.canRequestAds()) init(activity, onReady)
    }

    private fun init(activity: Activity, onReady: () -> Unit) {
        if (!started.compareAndSet(false, true)) {
            if (ready) activity.runOnUiThread(onReady)
            return
        }
        val app = activity.applicationContext
        Thread {
            MobileAds.initialize(app) {
                ready = true
                activity.runOnUiThread {
                    preloadInterstitial(app)
                    onReady()
                }
            }
        }.start()
    }

    /** Puts an adaptive banner (full width, height chosen by Google) into [container]. */
    fun showBanner(activity: Activity, container: FrameLayout, onChange: () -> Unit = {}) {
        if (!ready || container.childCount > 0) return
        val widthDp = (activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density).toInt()
        val view = AdView(activity).apply {
            adUnitId = BuildConfig.ADMOB_BANNER
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp))
            adListener = object : AdListener() {
                override fun onAdLoaded() { bannerLoaded = true; container.visibility = View.VISIBLE; onChange() }
                override fun onAdFailedToLoad(error: LoadAdError) { bannerLoaded = false; container.visibility = View.GONE; onChange() }
            }
        }
        container.addView(view)
        view.loadAd(AdRequest.Builder().build())
    }

    fun pauseBanner(container: FrameLayout) { (container.getChildAt(0) as? AdView)?.pause() }
    fun resumeBanner(container: FrameLayout) { (container.getChildAt(0) as? AdView)?.resume() }
    fun destroyBanner(container: FrameLayout) {
        (container.getChildAt(0) as? AdView)?.destroy()
        container.removeAllViews()
        bannerLoaded = false
    }

    private fun preloadInterstitial(context: Context) {
        if (!ready || interstitial != null || loadingInterstitial) return
        loadingInterstitial = true
        InterstitialAd.load(context, BuildConfig.ADMOB_INTERSTITIAL, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad; loadingInterstitial = false }
                override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null; loadingInterstitial = false }
            })
    }

    /** After the user added a download: sometimes shows a full-screen ad (never more than the limits above). */
    fun onDownloadAdded(activity: Activity) {
        if (!ready) return
        downloadsSinceAd++
        val now = SystemClock.elapsedRealtime()
        val ad = interstitial
        if (ad == null || downloadsSinceAd < EVERY_N_DOWNLOADS || (lastFullScreen > 0 && now - lastFullScreen < MIN_GAP_MS)) {
            preloadInterstitial(activity.applicationContext)
            return
        }
        interstitial = null
        downloadsSinceAd = 0
        lastFullScreen = now
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { preloadInterstitial(activity.applicationContext) }
            override fun onAdFailedToShowFullScreenContent(e: com.google.android.gms.ads.AdError) {
                preloadInterstitial(activity.applicationContext)
            }
        }
        ad.show(activity)
    }

    /** "Privacy settings for ads" in Settings: shown only where the consent form applies. */
    fun privacyOptionsNeeded(context: Context): Boolean =
        BuildConfig.ADS_ENABLED && UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.w("RAINAX", "privacy options: ${error.message}")
        }
    }
}
