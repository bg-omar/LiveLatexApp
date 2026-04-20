package com.omariskandarani.livelatexapp

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

object RewardedProHelper {

    private const val REWARD_HOURS = 24L

    fun loadAndShow(
        activity: Activity,
        adUnitId: String,
        entitlement: EntitlementRepository,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit
    ) {
        RewardedAd.load(
            activity,
            adUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    onFailure(error.message)
                }

                override fun onAdLoaded(ad: RewardedAd) {
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {}
                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            onFailure(adError.message)
                        }
                    }
                    ad.show(activity) {
                        val now = System.currentTimeMillis()
                        val curUntil = entitlement.state.value.proUntilEpochMs
                        val base = maxOf(now, curUntil)
                        entitlement.extendProUntil(base + REWARD_HOURS * 3600_000L)
                        entitlement.setLastRewardedAdAt(now)
                        onSuccess()
                    }
                }
            }
        )
    }

    fun canWatchRewarded(entitlement: EntitlementRepository): Boolean {
        val last = entitlement.getLastRewardedAdAt()
        val cooldownMs = if (BuildConfig.DEBUG) 30_000L else 4L * 3600_000L
        return System.currentTimeMillis() - last >= cooldownMs
    }
}
