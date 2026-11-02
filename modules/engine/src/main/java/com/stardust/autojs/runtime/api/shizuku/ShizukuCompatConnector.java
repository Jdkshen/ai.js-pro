package com.stardust.autojs.runtime.api.shizuku;

import android.content.Context;
import android.net.Uri;
import android.os.*;
import com.stardust.autojs.runtime.api.ShizukuClient;
import com.stardust.autojs.runtime.api.ShizukuShell;
import java.io.*;
import java.lang.Process;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/** One reusable service per client process; commands retain the existing Binder ownership. */
final class ShizukuCompatConnector {
    private static final Object LOCK = new Object();
    private static Connection cached;
    private static boolean starting;
    private static final AtomicBoolean LISTENER = new AtomicBoolean();
    private static final ThreadPoolExecutor LAUNCHER = new ThreadPoolExecutor(0,2,20,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), task -> { Thread t=new Thread(task,"shizuku-compat-launch");t.setDaemon(true);return t; });
    private ShizukuCompatConnector() { }

    static IShizukuUserService connect(long timeout) throws IOException {
        if (Looper.myLooper()==Looper.getMainLooper()) throw new IOException("Shizuku命令必须在工作线程执行");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1,timeout));
        if (!ShizukuClient.ensureConnected(Math.min(3000,Math.max(1,timeout))) || !ShizukuShell.hasPermission())
            throw new IOException("Shizuku未运行或尚未授权");
        synchronized (LOCK) {
            while (starting) {
                try { LOCK.wait(left(deadline)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt();throw new IOException("Shizuku连接已取消",error); }
            }
            if (cached!=null && cached.service.asBinder().pingBinder()) return cached.service;
            starting=true;
        }
        try {
            if (LISTENER.compareAndSet(false,true)) try {
                Shizuku.addBinderDeadListener(() -> {
                    Connection old;
                    synchronized(LOCK) { old=cached;cached=null; }
                    if (old!=null) {
                        Thread cleanup=new Thread(() -> {
                            try { old.service.destroy(); } catch(RemoteException ignored) { }
                            finally { old.close(); }
                        },"shizuku-compat-disconnect");
                        cleanup.setDaemon(true);cleanup.start();
                    }
                });
            } catch (RuntimeException error) { LISTENER.set(false);throw error; }
            Connection value=start(deadline);
            if (!ShizukuClient.isBinderAlive() || !ShizukuShell.hasPermission()) {
                try { value.service.destroy(); } finally { value.close(); }
                throw new IOException("Shizuku在连接期间断开或授权已撤销");
            }
            synchronized (LOCK) { cached=value; }
            return value.service;
        } catch(IOException error) { throw error; }
        catch(Exception error) { throw new IOException("Shizuku兼容连接失败: "+error,error); }
        finally { synchronized(LOCK) { starting=false;LOCK.notifyAll(); } }
    }

    private static Connection start(long deadline) throws Exception {
        Context context=ShizukuClient.context();
        if(context==null)throw new IOException("Shizuku上下文未初始化");
        Uri uri=Uri.parse("content://"+context.getPackageName()+".shizuku.exec");
        String token=UUID.randomUUID().toString();
        IBinder owner=new Binder();
        CountDownLatch ready=new CountDownLatch(1);
        AtomicReference<IBinder> received=new AtomicReference<>();
        AtomicReference<Process> raw=new AtomicReference<>();
        AtomicBoolean aborted=new AtomicBoolean();
        ResultReceiver callback=new ResultReceiver(null) {
            @Override protected void onReceiveResult(int code,Bundle data) {
                IBinder service=data==null?null:data.getBinder("service");
                if (aborted.get()) {
                    if(service!=null)try { IShizukuUserService.Stub.asInterface(service).destroy(); } catch(RemoteException ignored) { }
                } else { received.set(service);ready.countDown(); }
            }
        };
        Bundle request=new Bundle();
        request.putString("token",token);request.putBinder("owner",owner);request.putInt("backendUid",Shizuku.getUid());
        // Marshal the framework base class instead of the anonymous subclass's class name.
        Parcel parcel=Parcel.obtain();
        try {
            callback.writeToParcel(parcel,0);parcel.setDataPosition(0);
            request.putParcelable("callback",ResultReceiver.CREATOR.createFromParcel(parcel));
        } finally { parcel.recycle(); }
        Future<Process> launch=null;
        boolean success=false;
        StringBuffer errors=new StringBuffer();
        try {
            Bundle registered=context.getContentResolver().call(uri,"register",null,request);
            if(registered==null||!registered.getBoolean("registered"))throw new IOException("无法登记Shizuku执行进程");
            String[] argv={"/system/bin/env","CLASSPATH="+context.getApplicationInfo().sourceDir,"/system/bin/app_process","/system/bin",
                    "--nice-name=aijs-shizuku-compat-"+android.os.Process.myPid(),ShizukuCompatMain.class.getName(),
                    uri.getAuthority(),String.valueOf(android.os.Process.myUid()/100000),token};
            launch=LAUNCHER.submit(() -> {
                Process process=Shizuku.newProcess(argv,null,null);raw.set(process);
                if(aborted.get())process.destroy();
                return process;
            });
            Process process=launch.get(left(deadline),TimeUnit.MILLISECONDS);
            OutputStream stdin=process.getOutputStream();stdin.close();
            InputStream stdout=process.getInputStream(),stderr=process.getErrorStream();
            drain(stdout,errors);drain(stderr,errors);
            while(!ready.await(Math.min(100,left(deadline)),TimeUnit.MILLISECONDS)) {
                // Binder does not preserve IllegalThreadStateException from exitValue().
                if (!((ShizukuRemoteProcess)process).alive())
                    throw new IOException("Shizuku兼容引导退出("+process.exitValue()+"): "+errors);
            }
            IBinder binder=received.get();
            if(binder==null||!binder.pingBinder())throw new IOException("Shizuku没有返回有效执行Binder: "+errors);
            Connection result=new Connection(IShizukuUserService.Stub.asInterface(binder),owner,process,stdin,stdout,stderr);
            binder.linkToDeath(() -> {
                synchronized(LOCK) { if(cached==result)cached=null; }
                result.close();
            },0);
            success=true;
            return result;
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();throw new IOException("Shizuku兼容引导已取消",error);
        } catch(TimeoutException error) { throw new IOException("timeout: Shizuku兼容引导超时: "+errors,error); }
        finally {
            if(!success) {
                aborted.set(true);
                Process process=raw.get();if(process!=null)process.destroy();
                if(launch!=null) { launch.cancel(true);if(launch instanceof Runnable)LAUNCHER.remove((Runnable)launch); }
            }
            Bundle cancel=new Bundle();cancel.putString("token",token);
            try { context.getContentResolver().call(uri,"cancel",null,cancel); } catch(RuntimeException ignored) { }
        }
    }
    private static long left(long deadline) throws IOException {
        long value=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
        if(value<=0)throw new IOException("timeout: 等待Shizuku执行Binder超时");
        return value;
    }
    private static void drain(InputStream input,StringBuffer target) {
        Thread thread=new Thread(() -> {
            try {
                byte[] bytes=new byte[1024];int count;
                while((count=input.read(bytes))!=-1) synchronized(target) {
                    if(target.length()<8192)target.append(new String(bytes,0,Math.min(count,8192-target.length()),java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch(IOException ignored) { }
            finally { try { input.close(); } catch(IOException ignored) { } }
        },"shizuku-bootstrap-output");
        thread.setDaemon(true);thread.start();
    }
    private static final class Connection {
        final IShizukuUserService service;
        final IBinder owner;
        final Process process;
        final Closeable[] streams;
        Connection(IShizukuUserService service,IBinder owner,Process process,Closeable... streams) {
            this.service=service;this.owner=owner;this.process=process;this.streams=streams;
        }
        void close() { for(Closeable stream:streams)try { stream.close(); } catch(IOException ignored) { } }
    }
}
