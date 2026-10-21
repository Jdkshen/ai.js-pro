package org.aijsprobe.nodeplugin;
import android.content.Context;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
public final class FixturePlugin implements android.content.ServiceConnection {
    private final Context context;
    private final java.util.concurrent.CountDownLatch connected = new java.util.concurrent.CountDownLatch(1);
    private volatile Remote remote;
    public android.content.ComponentName getService() {return new android.content.ComponentName(context, ProbeService.class);}
    public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {remote=new Remote(binder);connected.countDown();}
    public void onServiceDisconnected(android.content.ComponentName name) {remote=null;}
    public Remote waitForConnection() throws Exception {
        if(!connected.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("real service connection timeout");
        return remote;
    }
    public static final class Remote {
        private final android.os.IBinder binder;
        Remote(android.os.IBinder value) {binder=value;}
        public java.util.Map call(String action, java.util.Map args, Object callback) throws Exception {
            android.os.Parcel data=android.os.Parcel.obtain(),reply=android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken("org.aijsprobe.nodeplugin");data.writeString(action);data.writeMap(args);
                if(!binder.transact(1,data,reply,0)) throw new IllegalStateException("service RPC rejected");
                reply.readException();return reply.readHashMap(getClass().getClassLoader());
            } finally {data.recycle();reply.recycle();}
        }
    }
    public FixturePlugin(Context context) {this.context = context;}
    public String getAssetsScriptDir() {return "plugin";}
    public int getVersion() {return 2;}
    public int add(int a, int b) {return a + b;}
    public String packageName() {return context.getPackageName();}
    public String resourceText() {return context.getString(context.getResources().getIdentifier("fixture_message", "string", context.getPackageName()));}
    public String assetText() throws Exception {
        try (InputStream input = context.getAssets().open("fixture.txt"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512]; int size;
            while ((size = input.read(buffer)) != -1) out.write(buffer, 0, size);
            return new String(out.toByteArray(), "UTF-8").trim();
        }
    }
    public int nativeSum(int a, int b) {System.loadLibrary("nodeprobe"); return nativeAdd(a, b);}
    private static native int nativeAdd(int a, int b);
    public void onUnload() {System.setProperty("org.aijsprobe.nodeplugin.unloaded", "true");}
}
