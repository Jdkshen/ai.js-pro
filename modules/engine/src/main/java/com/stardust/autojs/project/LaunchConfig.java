package com.stardust.autojs.project;

import com.google.gson.annotations.SerializedName;

import java.util.List;

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

    @SerializedName("requestPermissions")
    private List<String> mRequestPermissions;

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

    /**
     * Runtime permissions the packaged app asks for on start-up. Null/empty keeps the
     * runtime default (storage + phone state), so packages made before this field existed
     * behave exactly as before.
     */
    public List<String> getRequestPermissions() {
        return mRequestPermissions;
    }

    public void setRequestPermissions(List<String> requestPermissions) {
        mRequestPermissions = requestPermissions;
    }

}
