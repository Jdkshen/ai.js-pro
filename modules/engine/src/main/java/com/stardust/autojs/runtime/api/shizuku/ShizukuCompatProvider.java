package com.stardust.autojs.runtime.api.shizuku;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Privileged handoff only; no commands or persistent credentials are exposed by this provider. */
public final class ShizukuCompatProvider extends ContentProvider {
    private static final Map<String,Pending> PENDING = new LinkedHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        Bundle result = new Bundle();
        if (extras == null) return result;
        extras.setClassLoader(ResultReceiver.class.getClassLoader());
        String token = extras.getString("token");
        if (!ShizukuCompatPolicy.validToken(token)) throw new SecurityException("无效的Shizuku交接令牌");
        int uid = Binder.getCallingUid();
        if ("register".equals(method)) {
            if (uid != android.os.Process.myUid()) throw new SecurityException("仅本应用可登记执行进程");
            IBinder owner = extras.getBinder("owner");
            ResultReceiver callback = extras.getParcelable("callback");
            int expectedUid = extras.getInt("backendUid",-1);
            if (owner == null || callback == null || !ShizukuCompatPolicy.acceptsUid(expectedUid,expectedUid))
                throw new SecurityException("Shizuku交接参数不完整");
            Pending pending = new Pending(token,owner,callback,expectedUid);
            synchronized (PENDING) {
                prune();
                if (PENDING.size() >= ShizukuCompatPolicy.MAX_PENDING || PENDING.containsKey(token))
                    throw new IllegalStateException("Shizuku待连接任务已满或重复");
                try { owner.linkToDeath(pending,0); } catch (RemoteException error) { return result; }
                PENDING.put(token,pending);
            }
            MAIN.postDelayed(pending,ShizukuCompatPolicy.LEASE_MILLIS);
            result.putBoolean("registered",true);
        } else if ("cancel".equals(method)) {
            if (uid != android.os.Process.myUid()) throw new SecurityException("仅本应用可取消交接");
            synchronized (PENDING) { Pending old = PENDING.remove(token); if (old != null) old.unlink(); }
        } else if ("attach".equals(method)) {
            IBinder service = extras.getBinder("service");
            synchronized (PENDING) {
                prune();
                Pending pending = PENDING.get(token);
                if (pending == null || !ShizukuCompatPolicy.acceptsUid(pending.expectedUid,uid) || service == null || !service.pingBinder()) return result;
                PENDING.remove(token); pending.unlink();
                Bundle value = new Bundle(); value.putBinder("service",service);
                pending.callback.send(0,value);
                result.putBinder("owner",pending.owner);
            }
        }
        return result;
    }
    private static void prune() {
        java.util.Iterator<Pending> iterator = PENDING.values().iterator();
        long now = SystemClock.elapsedRealtime();
        while (iterator.hasNext()) {
            Pending item = iterator.next();
            if (ShizukuCompatPolicy.expired(item.created,now) || !item.owner.pingBinder()) { iterator.remove(); item.unlink(); }
        }
    }
    private static final class Pending implements IBinder.DeathRecipient,Runnable {
        final String token; final IBinder owner; final ResultReceiver callback; final int expectedUid;
        final long created = SystemClock.elapsedRealtime();
        Pending(String token,IBinder owner,ResultReceiver callback,int expectedUid) {
            this.token=token;this.owner=owner;this.callback=callback;this.expectedUid=expectedUid;
        }
        public void binderDied() { run(); }
        public void run() { synchronized (PENDING) { if (PENDING.get(token)==this) { PENDING.remove(token); unlink(); } } }
        void unlink() { MAIN.removeCallbacks(this); try { owner.unlinkToDeath(this,0); } catch (RuntimeException ignored) { } }
    }
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) { return null; }
    public String getType(Uri uri) { return null; }
    public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
