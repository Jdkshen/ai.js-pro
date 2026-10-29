package com.jdkshen.aijspro.log;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.*;
import android.util.Log;
import com.stardust.app.GlobalAppContext;
import java.util.*;

/** Host owns the log ring; isolated screens receive bounded snapshots and live batches. */
public final class ConsoleLogService extends Service {
    private static final int SUBSCRIBE=1, UNSUBSCRIBE=2, CLEAR=3, BEGIN=4, ROWS=5, END=6, CLEARED=7;
    private static final int MAX_LINES=2000, MAX_CHARS=512*1024, PARCEL_CHARS=24*1024;
    private final Map<IBinder, Subscriber> clients = new HashMap<>();
    private final ArrayList<Bundle> pending = new ArrayList<>();
    private final Runnable flushTask=this::flush;
    private HandlerThread thread;
    private Handler worker;
    private Messenger endpoint;
    private long generation;
    private boolean flushScheduled;
    private volatile boolean closing;
    private final UnifiedConsoleLogBridge.Listener listener = new UnifiedConsoleLogBridge.Listener() {
        @Override public void onLine(UnifiedConsoleLogBridge.Line line) {
            Handler target=worker;
            if (target!=null && !closing) target.post(() -> {
                if (clients.isEmpty()) return;
                pending.add(encode(line));
                if (pending.size()>=128) flush();
                else if (!flushScheduled) { flushScheduled=true; worker.postDelayed(flushTask,48L); }
            });
        }
        @Override public void onClear(long ignored) {
            Handler target=worker;
            if (target!=null && !closing) target.post(() -> {
                pending.clear(); generation++;
                for (Subscriber client : new ArrayList<>(clients.values())) send(client,CLEARED,null);
            });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        thread=new HandlerThread("console-log-broker"); thread.start();
        worker=new Handler(thread.getLooper(), this::receive);
        endpoint=new Messenger(worker);
        UnifiedConsoleLogBridge.ensureMainTap();
        UnifiedConsoleLogBridge.addListener(listener);
    }

    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }

    private boolean receive(Message message) {
        if (closing || message.sendingUid!=android.os.Process.myUid() || message.replyTo==null) return true;
        IBinder key=message.replyTo.getBinder();
        if (message.what==UNSUBSCRIBE) { remove(key); return true; }
        if (message.what==CLEAR) { UnifiedConsoleLogBridge.clear(); return true; }
        if (message.what!=SUBSCRIBE) return true;
        flush();
        remove(key);
        Subscriber client=new Subscriber(message.replyTo,message.getData().getString("session",""));
        if (client.session.isEmpty()) return true;
        clients.put(key,client);
        try { key.linkToDeath(client.death,0); }
        catch (RemoteException dead) { remove(key); return true; }
        send(client,BEGIN,null);
        ArrayList<Bundle> snapshot=new ArrayList<>();
        for (UnifiedConsoleLogBridge.Line line : UnifiedConsoleLogBridge.snapshot(MAX_LINES)) snapshot.add(encode(line));
        sendRows(client,snapshot);
        send(client,END,null);
        return true;
    }

    private void flush() {
        worker.removeCallbacks(flushTask);
        flushScheduled=false;
        if (pending.isEmpty()) return;
        ArrayList<Bundle> batch=new ArrayList<>(pending); pending.clear();
        for (Subscriber client : new ArrayList<>(clients.values())) sendRows(client,batch);
    }

    private void sendRows(Subscriber client,List<Bundle> rows) {
        ArrayList<Bundle> batch=new ArrayList<>(); int size=0;
        for (Bundle row : rows) {
            int cost=row.getString("token","").length()+row.getString("name","").length()
                    +row.getString("text","").length()+256;
            if (!batch.isEmpty() && size+cost>PARCEL_CHARS) {
                send(client,ROWS,batch); batch=new ArrayList<>(); size=0;
            }
            batch.add(row); size+=cost;
        }
        if (!batch.isEmpty()) send(client,ROWS,batch);
    }

    private void send(Subscriber client,int kind,ArrayList<Bundle> rows) {
        Message reply=Message.obtain(null,kind); Bundle data=new Bundle();
        data.putString("session",client.session); data.putLong("generation",generation);
        if (rows!=null) data.putParcelableArrayList("rows",rows);
        reply.setData(data);
        try { client.target.send(reply); }
        catch (RemoteException dead) { remove(client.target.getBinder()); }
    }

    private void remove(IBinder key) {
        Subscriber old=clients.remove(key);
        if (old!=null) {
            try { key.unlinkToDeath(old.death,0); } catch (RuntimeException ignored) { }
        }
    }

    private final class Subscriber {
        final Messenger target; final String session; final IBinder.DeathRecipient death;
        Subscriber(Messenger target,String session) {
            this.target=target; this.session=session;
            death=() -> { Handler current=worker; if (current!=null) current.post(() -> remove(target.getBinder())); };
        }
    }

    @Override public void onDestroy() {
        closing=true;
        UnifiedConsoleLogBridge.removeListener(listener);
        worker.post(() -> {
            for (IBinder key : new ArrayList<>(clients.keySet())) remove(key);
            pending.clear(); thread.quitSafely();
        });
        super.onDestroy();
    }

    private static String clipped(String value,int size) { return value.length()<=size ? value : value.substring(0,size); }
    private static Bundle encode(UnifiedConsoleLogBridge.Line line) {
        Bundle row=new Bundle();
        row.putString("token",clipped(line.token,256)); row.putString("name",clipped(line.name,1024));
        row.putString("text",clipped(line.text,16000)); row.putInt("level",line.level);
        row.putLong("time",line.time); row.putLong("seq",line.seq);
        return row;
    }

    /** Process-wide client, with a bounded cache and decoding off the UI thread. */
    static final class Remote {
        private static final Remote INSTANCE=new Remote();
        private final Context context=GlobalAppContext.get().getApplicationContext();
        private final HandlerThread thread=new HandlerThread("console-log-client");
        private final Handler worker;
        private final Handler main=new Handler(Looper.getMainLooper());
        private final Messenger endpoint;
        private final ArrayDeque<UnifiedConsoleLogBridge.Line> cache=new ArrayDeque<>();
        private volatile Messenger host;
        private volatile String session="";
        private boolean bound, clearPending, snapshot;
        private int chars;
        private final ServiceConnection connection=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder binder) {
                worker.post(() -> {
                    synchronized (Remote.this) {
                        if (!bound) return;
                        host=new Messenger(binder); session=UUID.randomUUID().toString();
                    }
                    if (clearPending) { clearPending=false; send(CLEAR); }
                    send(SUBSCRIBE);
                });
            }
            @Override public void onServiceDisconnected(ComponentName name) { host=null; session=""; }
            @Override public void onBindingDied(ComponentName name) { stop(); start(); }
            @Override public void onNullBinding(ComponentName name) { stop(); }
        };

        private Remote() {
            thread.start(); worker=new Handler(thread.getLooper(),this::receive); endpoint=new Messenger(worker);
        }

        static Remote get() { return INSTANCE; }
        synchronized void start() {
            if (bound) return;
            try { bound=context.bindService(new Intent(context,ConsoleLogService.class),connection,Context.BIND_AUTO_CREATE); }
            catch (RuntimeException error) { Log.w("ConsoleLog", "Cannot bind log broker",error); }
        }
        synchronized void stop() {
            session=""; host=null;
            if (bound) { bound=false; try { context.unbindService(connection); } catch (RuntimeException ignored) { } }
        }
        void clear() { worker.post(() -> { if (host==null) { clearPending=true; start(); } else send(CLEAR); }); }

        List<UnifiedConsoleLogBridge.Line> snapshot(int limit) {
            start();
            synchronized (cache) {
                ArrayList<UnifiedConsoleLogBridge.Line> result=new ArrayList<>(cache);
                return limit>0 && result.size()>limit ? new ArrayList<>(result.subList(result.size()-limit,result.size())) : result;
            }
        }

        private void send(int kind) {
            Messenger current=host; if (current==null) return;
            Message request=Message.obtain(null,kind); request.replyTo=endpoint;
            Bundle data=new Bundle(); data.putString("session",session); request.setData(data);
            try { current.send(request); }
            catch (RemoteException dead) { host=null; }
        }

        private boolean receive(Message message) {
            Bundle data=message.getData();
            if (!session.equals(data.getString("session")) || session.isEmpty()) return true;
            long generation=data.getLong("generation");
            if (message.what==BEGIN || message.what==CLEARED) {
                synchronized (cache) { cache.clear(); chars=0; }
                snapshot=message.what==BEGIN;
                if (!snapshot) notifyClear(generation);
            } else if (message.what==END) {
                snapshot=false;
                notifyClear(generation);
            } else if (message.what==ROWS) {
                ArrayList<Bundle> rows=data.getParcelableArrayList("rows");
                ArrayList<UnifiedConsoleLogBridge.Line> added=new ArrayList<>();
                if (rows!=null) for (Bundle row : rows) {
                    try {
                        UnifiedConsoleLogBridge.Line line=new UnifiedConsoleLogBridge.Line(row.getString("token"),row.getString("name"),
                                row.getString("text"),row.getInt("level"),row.getLong("time"),row.getLong("seq"));
                        synchronized (cache) {
                            UnifiedConsoleLogBridge.Line last=cache.peekLast();
                            if (last!=null && line.seq<=last.seq) continue;
                            cache.addLast(line); chars+=line.text.length();
                            while (cache.size()>MAX_LINES || chars>MAX_CHARS) chars-=cache.removeFirst().text.length();
                        }
                        if (!snapshot) added.add(line);
                    } catch (Exception error) { Log.w("ConsoleLog", "Invalid log row",error); }
                }
                if (!added.isEmpty()) {
                    String expected=session;
                    main.post(() -> {
                        if (!expected.equals(session)) return;
                        for (UnifiedConsoleLogBridge.Line line : added) UnifiedConsoleLogBridge.remoteLine(line);
                    });
                }
            }
            return true;
        }

        private void notifyClear(long generation) {
            String expected=session;
            main.post(() -> { if (expected.equals(session)) UnifiedConsoleLogBridge.remoteClear(generation); });
        }
    }
}
