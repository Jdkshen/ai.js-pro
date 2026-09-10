package com.stardust.autojs.project;

import com.google.gson.annotations.SerializedName;

/**
 * Created by Stardust on 2018/1/25.
 */

public class LaunchConfig {

    @SerializedName("hideLogs")
    private boolean mHideLogs = false;

    @SerializedName("showSplash")
    private boolean mShowSplash = true;

    @SerializedName("splashText")
    private String mSplashText;

    public boolean shouldHideLogs() {
        return mHideLogs;
    }

    public void setHideLogs(boolean hideLogs) {
        mHideLogs = hideLogs;
    }

    /**
     * Whether the packaged app shows its splash screen while the runtime starts up.
     * Defaults to true; absent JSON fields keep the default so older packages are unaffected.
     */
    public boolean shouldShowSplash() {
        return mShowSplash;
    }

    public void setShowSplash(boolean showSplash) {
        mShowSplash = showSplash;
    }

    /**
     * Replaces the "Powered by" line on the splash screen; null/empty keeps the default.
     */
    public String getSplashText() {
        return mSplashText;
    }

    public void setSplashText(String splashText) {
        mSplashText = splashText;
    }

}
