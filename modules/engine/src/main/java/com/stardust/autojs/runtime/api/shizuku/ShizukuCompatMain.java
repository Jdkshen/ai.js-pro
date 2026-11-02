package com.stardust.autojs.runtime.api.shizuku;

import android.content.AttributionSource;
import android.os.*;
import androidx.annotation.Keep;
import java.lang.reflect.Method;

/** A real shell/root Java process without ActivityThread.makeApplication. */
@Keep public final class ShizukuCompatMain {
    private ShizukuCompatMain() { }
    @Keep public static void main(String[] args) {
        ShizukuUserService service = null;
        try {
            if (args.length != 3 || !ShizukuCompatPolicy.validToken(args[2])) throw new IllegalArgumentException("Shizuku兼容引导参数无效");
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            service = new ShizukuUserService();
            Bundle request = new Bundle(); request.putString("token",args[2]); request.putBinder("service",service);
            Bundle result = handoff(args[0],Integer.parseInt(args[1]),request);
            IBinder owner = result == null ? null : result.getBinder("owner");
            if (owner == null) throw new IllegalStateException("Shizuku执行进程交接被拒绝或已取消");
            ShizukuUserService owned = service;
            owner.linkToDeath(owned::destroy,0);
            sOwner = owner;
            Looper.loop();
        } catch (Throwable failure) {
            System.err.println("Shizuku兼容引导失败: " + failure);
            failure.printStackTrace(System.err);
            if (service != null) service.destroy(); else System.exit(1);
        }
    }
    private static IBinder sOwner;
    private static Bundle handoff(String authority,int user,Bundle extras) throws Exception {
        Object manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
        Class<?> managerType = Class.forName("android.app.IActivityManager");
        IBinder lease = new Binder();
        Object holder = managerType.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
                .invoke(manager,authority,user,lease,authority);
        if (holder == null) throw new IllegalStateException("无法取得Shizuku交接Provider");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            Method call = Class.forName("android.content.IContentProvider").getMethod("call",AttributionSource.class,String.class,String.class,String.class,Bundle.class);
            AttributionSource source = new AttributionSource.Builder(android.os.Process.myUid())
                    .setPackageName(android.os.Process.myUid()==2000 ? "com.android.shell" : null).build();
            return (Bundle)call.invoke(provider,source,authority,"attach",null,extras);
        } finally {
            managerType.getMethod("removeContentProviderExternal",String.class,IBinder.class).invoke(manager,authority,lease);
        }
    }
}
