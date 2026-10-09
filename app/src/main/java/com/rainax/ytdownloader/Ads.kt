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
 * Formats: banner above the bottom bar, full-screen ad after adding downloads, an ad when the app opens
 * (app open), and ad cards in the Home feed (native). Each one has frequency limits (below) so the app stays
 * pleasant to use; AdMob also limits accounts whose ads annoy users.
 *
 * The ad unit IDs come from app/build.gradle (BuildConfig.ADMOB_*). An empty ID turns that format off.
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

    /** Full-screen ads (after downloads and on app open together): never two within 2 minutes. */
    private const val MIN_GAP_MS = 2 * 60 * 1000L
    /** After every 2nd download added. */
    private const val EVERY_N_DOWNLOADS = 2
    /** App open ad: at most once every 30 minutes. */
    private const val APP_OPEN_GAP_MS = 30 * 60 * 1000L
    /** A loaded app open ad stays usable for 4 hours (Google's rule). */
    private const val APP_OPEN_VALID_MS = 4 * 60 * 60 * 1000L

    private var appOpen: com.google.android.gms.ads.appopen.AppOpenAd? = null
    private var appOpenLoadedAt = 0L
    private var loadingAppOpen = false
    private var lastAppOpen = 0L
    @Volatile private var showingFullScreen = false

    /** Native ads for the Home feed, by slot number (each slot keeps its ad while it exists). */
    private val nativeBySlot = LinkedHashMap<Int, com.google.android.gms.ads.nativead.NativeAd>()
    private val nativeSpare = ArrayDeque<com.google.android.gms.ads.nativead.NativeAd>()
    private var loadingNative = false
    private var nativeFailedAt = 0L
    /** Called when new feed ads arrive, so the list can show them. */
    var onNativeReady: (() -> Unit)? = null

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
                    loadAppOpen(app)
                    loadNative(app)
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
        if (ad == null || showingFullScreen || downloadsSinceAd < EVERY_N_DOWNLOADS ||
            (lastFullScreen > 0 && now - lastFullScreen < MIN_GAP_MS)
        ) {
            preloadInterstitial(activity.applicationContext)
            return
        }
        interstitial = null
        downloadsSinceAd = 0
        lastFullScreen = now
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() { showingFullScreen = true }
            override fun onAdDismissedFullScreenContent() {
                showingFullScreen = false
                preloadInterstitial(activity.applicationContext)
            }
            override fun onAdFailedToShowFullScreenContent(e: com.google.android.gms.ads.AdError) {
                showingFullScreen = false
                preloadInterstitial(activity.applicationContext)
            }
        }
        ad.show(activity)
    }

    // ---------- app open ----------

    private fun loadAppOpen(context: Context) {
        if (!ready || BuildConfig.ADMOB_APP_OPEN.isBlank() || loadingAppOpen) return
        if (appOpen != null && SystemClock.elapsedRealtime() - appOpenLoadedAt < APP_OPEN_VALID_MS) return
        loadingAppOpen = true
        com.google.android.gms.ads.appopen.AppOpenAd.load(
            context, BuildConfig.ADMOB_APP_OPEN, AdRequest.Builder().build(),
            object : com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: com.google.android.gms.ads.appopen.AppOpenAd) {
                    appOpen = ad; appOpenLoadedAt = SystemClock.elapsedRealtime(); loadingAppOpen = false
                    pendingAppOpen?.let { (act, until) ->
                        pendingAppOpen = null
                        if (SystemClock.elapsedRealtime() < until && !act.isFinishing && screenCanShow()) showAppOpen(act)
                    }
                }
                override fun onAdFailedToLoad(error: LoadAdError) { appOpen = null; loadingAppOpen = false; pendingAppOpen = null }
            })
    }

    // ---- when to show the app open ad: the app comes back after 30+ seconds in the background ----
    private var screen: java.lang.ref.WeakReference<Activity>? = null
    private var screenCanShow: () -> Boolean = { false }
    private var backgroundAt = 0L
    private var watching = false

    /** The main screen is in front (and can say whether an ad would interrupt something right now). */
    fun onScreenResumed(activity: Activity, canShow: () -> Boolean) {
        screen = java.lang.ref.WeakReference(activity)
        screenCanShow = canShow
        if (!watching) {
            watching = true
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { backgroundAt = SystemClock.elapsedRealtime() }
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    val away = if (backgroundAt > 0) SystemClock.elapsedRealtime() - backgroundAt else 0L
                    backgroundAt = 0
                    if (away < 30_000) return
                    val act = screen?.get() ?: return
                    act.window?.decorView?.postDelayed({
                        if (screen?.get() === act && !act.isFinishing && screenCanShow()) showAppOpen(act)
                    }, 400)
                }
            })
        }
    }

    fun onScreenPaused(activity: Activity) {
        if (screen?.get() === activity) screen = null
    }

    /** Start of the app: show the app open ad as soon as it arrives, but only within a few seconds. */
    private var pendingAppOpen: Pair<Activity, Long>? = null

    fun showAppOpenSoon(activity: Activity) {
        if (!ready || BuildConfig.ADMOB_APP_OPEN.isBlank()) return
        if (appOpen != null) { showAppOpen(activity); return }
        pendingAppOpen = activity to SystemClock.elapsedRealtime() + 4000
        loadAppOpen(activity.applicationContext)
    }

    /** Back in the app after a while: shows the app open ad if the limits allow it. */
    fun showAppOpen(activity: Activity) {
        val ad = appOpen ?: run { loadAppOpen(activity.applicationContext); return }
        val now = SystemClock.elapsedRealtime()
        if (showingFullScreen || now - appOpenLoadedAt > APP_OPEN_VALID_MS) {
            if (now - appOpenLoadedAt > APP_OPEN_VALID_MS) { appOpen = null; loadAppOpen(activity.applicationContext) }
            return
        }
        if (lastAppOpen > 0 && now - lastAppOpen < APP_OPEN_GAP_MS) return
        if (lastFullScreen > 0 && now - lastFullScreen < MIN_GAP_MS) return
        appOpen = null
        lastAppOpen = now
        lastFullScreen = now
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() { showingFullScreen = true }
            override fun onAdDismissedFullScreenContent() { showingFullScreen = false; loadAppOpen(activity.applicationContext) }
            override fun onAdFailedToShowFullScreenContent(e: com.google.android.gms.ads.AdError) {
                showingFullScreen = false; loadAppOpen(activity.applicationContext)
            }
        }
        ad.show(activity)
    }

    // ---------- native ads in the Home feed ----------

    private fun loadNative(context: Context) {
        if (!ready || BuildConfig.ADMOB_NATIVE.isBlank() || loadingNative || nativeSpare.size >= 3) return
        if (nativeFailedAt > 0 && SystemClock.elapsedRealtime() - nativeFailedAt < 60_000) return   // wait after "no fill"
        loadingNative = true
        val loader = com.google.android.gms.ads.AdLoader.Builder(context, BuildConfig.ADMOB_NATIVE)
            .forNativeAd { ad ->
                nativeSpare.addLast(ad)
                loadingNative = false
                onNativeReady?.invoke()
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingNative = false
                    nativeFailedAt = SystemClock.elapsedRealtime()
                }
            })
            .withNativeAdOptions(
                com.google.android.gms.ads.nativead.NativeAdOptions.Builder()
                    .setAdChoicesPlacement(com.google.android.gms.ads.nativead.NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .setMediaAspectRatio(com.google.android.gms.ads.nativead.NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
                    .build()
            )
            .build()
        loader.loadAds(AdRequest.Builder().build(), 3)
    }

    /** The ad for feed slot [slot], or null (the slot stays empty) while none is loaded. */
    fun nativeFor(context: Context, slot: Int): com.google.android.gms.ads.nativead.NativeAd? {
        if (!ready || BuildConfig.ADMOB_NATIVE.isBlank()) return null
        nativeBySlot[slot]?.let { return it }
        val ad = nativeSpare.removeFirstOrNull()
        if (ad != null) {
            nativeBySlot[slot] = ad
            while (nativeBySlot.size > 12) {                // keep memory small: forget the oldest slots
                val first = nativeBySlot.keys.first()
                nativeBySlot.remove(first)?.destroy()
            }
        }
        if (nativeSpare.size < 2) loadNative(context.applicationContext)
        return ad
    }

    /** New feed (refresh, other tab): old ads are released, fresh ones are used. */
    fun resetNative() {
        nativeBySlot.values.forEach { it.destroy() }
        nativeBySlot.clear()
    }

    val feedAdsEnabled get() = BuildConfig.ADS_ENABLED && BuildConfig.ADMOB_NATIVE.isNotBlank()

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
