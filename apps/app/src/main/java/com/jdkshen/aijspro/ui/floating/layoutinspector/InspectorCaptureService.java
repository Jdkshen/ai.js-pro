package com.jdkshen.aijspro.ui.floating.layoutinspector;

import android.app.Service;
import android.content.*;
import android.os.*;
import com.stardust.view.accessibility.LayoutInspector;
import com.stardust.view.accessibility.NodeCaptureBudget;
import com.stardust.view.accessibility.NodeInfo;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Captures in the accessibility process; transport carries only bounded cache tokens. */
public final class InspectorCaptureService extends Service {
    private static final int CAPTURE=1, CANCEL=2, RESULT=3;
    private final Map<String,Transfer> transfers=new ConcurrentHashMap<>();
    private HandlerThread thread;
    private Handler worker;
    private Messenger endpoint;
    private volatile boolean closing;

    public static void installRemote(Context context) {
        Context app=context.getApplicationContext();
        LayoutInspector.setCaptureTransport((windows,budget) -> capture(app,windows,budget));
    }

    @Override public void onCreate() {
        super.onCreate(); thread=new HandlerThread("inspector-capture-ipc"); thread.start();
        worker=new Handler(thread.getLooper(),this::receive); endpoint=new Messenger(worker);
    }
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }

    private boolean receive(Message message) {
        if (closing || message.sendingUid!=android.os.Process.myUid()) return true;
        String id=message.getData().getString("id","");
        if (message.what==CANCEL) { drop(id); return true; }
        if (message.what!=CAPTURE || message.replyTo==null || id.isEmpty()) return true;
        if (transfers.size()>=16 || transfers.containsKey(id)) { reply(message.replyTo,id,null,"CAPTURE_BUSY"); return true; }
        Transfer transfer=new Transfer(id,message.replyTo); transfers.put(id,transfer);
        try { transfer.target.getBinder().linkToDeath(transfer.death,0); }
        catch (RemoteException dead) { drop(id); return true; }
        worker.postDelayed(() -> drop(id),30000L);
        LayoutInspector inspector=com.jdkshen.aijspro.autojs.AutoJs.getInstance().getLayoutInspector();
        if (message.getData().getBoolean("windows")) {
            transfer.request=inspector.captureVisibleWindowsRequest(windows ->
                    worker.post(() -> complete(transfer,null,windows)));
        } else {
            transfer.request=inspector.captureCurrentWindowRequest(root ->
                    worker.post(() -> complete(transfer,root,Collections.emptyList())));
        }
        if (transfer.request==null) {
            reply(transfer.target,id,null,"CAPTURE_BUSY"); drop(id);
        }
        return true;
    }

    private void complete(Transfer transfer,NodeInfo root,List<LayoutInspector.CapturedWindow> windows) {
        if (closing || transfers.get(transfer.id)!=transfer) return;
        try {
            if (root==null && windows.isEmpty()) throw new IllegalStateException("NO_READABLE_ROOT");
            InspectorSnapshotTransport.cleanupOrphans(this);
            transfer.snapshot=InspectorSnapshotTransport.write(this,root,windows,false);
            reply(transfer.target,transfer.id,transfer.snapshot,null);
        } catch (Exception error) {
            reply(transfer.target,transfer.id,null,error.toString()); drop(transfer.id);
        }
    }

    private void reply(Messenger target,String id,String snapshot,String error) {
        Message result=Message.obtain(null,RESULT); Bundle data=new Bundle();
        data.putString("id",id); data.putString("snapshot",snapshot); data.putString("error",error);
        result.setData(data);
        try { target.send(result); } catch (RemoteException dead) { drop(id); }
    }

    private void drop(String id) {
        Transfer transfer=transfers.remove(id); if (transfer==null) return;
        if (transfer.request!=null) transfer.request.cancel();
        if (transfer.snapshot!=null) InspectorSnapshotTransport.discard(this,transfer.snapshot);
        try { transfer.target.getBinder().unlinkToDeath(transfer.death,0); } catch (RuntimeException ignored) { }
    }

    private final class Transfer {
        final String id; final Messenger target; final IBinder.DeathRecipient death;
        LayoutInspector.CaptureRequest request; String snapshot;
        Transfer(String id,Messenger target) {
            this.id=id; this.target=target;
            death=() -> { Handler current=worker; if (current!=null) current.post(() -> drop(id)); };
        }
    }

    @Override public void onDestroy() {
        closing=true;
        worker.post(() -> {
            for (String id : new ArrayList<>(transfers.keySet())) drop(id);
            thread.quitSafely();
        });
        super.onDestroy();
    }

    private static LayoutInspector.RemoteCapture capture(Context context,boolean windows,NodeCaptureBudget budget) {
        String id=UUID.randomUUID().toString();
        CountDownLatch connected=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicReference<Messenger> host=new AtomicReference<>(); AtomicReference<Bundle> reply=new AtomicReference<>();
        Messenger callback=new Messenger(new Handler(Looper.getMainLooper(),message -> {
            if (message.what==RESULT && id.equals(message.getData().getString("id"))) {
                reply.set(message.getData()); done.countDown();
            }
            return true;
        }));
        ServiceConnection connection=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder binder) { host.set(new Messenger(binder)); connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { host.set(null); connected.countDown(); done.countDown(); }
            @Override public void onNullBinding(ComponentName name) { connected.countDown(); }
        };
        boolean bound=false;
        try {
            bound=context.bindService(new Intent(context,InspectorCaptureService.class),connection,Context.BIND_AUTO_CREATE);
            if (!bound) throw new IllegalStateException("CAPTURE_HOST_UNAVAILABLE");
            while (!connected.await(50L,TimeUnit.MILLISECONDS)) budget.check();
            budget.check(); Messenger remote=host.get();
            if (remote==null) throw new IllegalStateException("CAPTURE_HOST_DISCONNECTED");
            Message request=Message.obtain(null,CAPTURE); request.replyTo=callback;
            Bundle data=new Bundle(); data.putString("id",id); data.putBoolean("windows",windows); request.setData(data);
            remote.send(request);
            while (!done.await(50L,TimeUnit.MILLISECONDS)) budget.check();
            budget.check(); Bundle result=reply.get();
            if (result==null) throw new IllegalStateException("CAPTURE_HOST_DISCONNECTED");
            String error=result.getString("error"); if (error!=null) throw new IllegalStateException(error);
            InspectorSnapshotTransport.Payload payload=InspectorSnapshotTransport.read(context,result.getString("snapshot"));
            return new LayoutInspector.RemoteCapture(payload.root,payload.windows);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new CancellationException("Capture interrupted");
        } catch (Exception error) { throw new IllegalStateException("Remote capture failed",error); }
        finally {
            Messenger remote=host.get();
            if (remote!=null) {
                Message cancel=Message.obtain(null,CANCEL); Bundle data=new Bundle(); data.putString("id",id); cancel.setData(data);
                try { remote.send(cancel); } catch (RemoteException ignored) { }
            }
            if (bound) try { context.unbindService(connection); } catch (RuntimeException ignored) { }
        }
    }
}
