package com.jdkshen.aijspro.automation;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import com.stardust.autojs.host.IAutomationHost;

/** Same-UID status query independent of ServiceConnection callbacks on the UI thread. */
public final class AutomationStateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method,String argument,Bundle extras) {
        if (Binder.getCallingUid()!=Process.myUid()) throw new SecurityException("Same UID required");
        if (!"status".equals(method)) throw new IllegalArgumentException("Unknown automation status method");
        Bundle result=new Bundle();
        try {
            IAutomationHost backend=AutomationHostService.localBackend(getContext());
            result.putBoolean("ready",backend.isReady());
            result.putBoolean("enabled",backend.isAccessibilityServiceEnabled());
        } catch (android.os.RemoteException error) { result.putString("error",error.toString()); }
        return result;
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
