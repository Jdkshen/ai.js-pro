package com.jdkshen.aijspro.external.foreground;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.external.HostScripts;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** One aggregate native notification for the existing three-engine task registry. */
final class RunningScriptNotifications {
    static final String STOP_ONE="com.jdkshen.aijspro.running.STOP_ONE";
    static final String STOP_ALL="com.jdkshen.aijspro.running.STOP_ALL";
    private static final String TAG="ScriptRunningIsland";
    private static WeakReference<RunningScriptNotifications> active=new WeakReference<>(null);
    private static volatile String diagnostics="{}";
    private final ForegroundService owner;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"script-notification");t.setDaemon(true);return t;});
    private final RunningTaskState state=new RunningTaskState();
    private final String nonce=UUID.randomUUID().toString();
    private volatile boolean closed;
    private volatile long capabilitiesAt;
    private XiaomiFocusSupport support;
    private final NotificationDeliveryState delivery=new NotificationDeliveryState();
    private volatile long lastAttemptAt,lastPostedAt;
    private volatile String lastError="";
    private boolean hadTasks;

    RunningScriptNotifications(ForegroundService owner){this.owner=owner;}
    void start(){active=new WeakReference<>(this);worker.scheduleWithFixedDelay(this::poll,0,2,TimeUnit.SECONDS);}
    static String diagnostics(){return diagnostics;}
    static void refresh(){RunningScriptNotifications n=active.get();if(n!=null&&!n.closed){n.capabilitiesAt=0;n.delivery.invalidate();n.submit(n::poll);}}
    static JSONObject deliveryDiagnostics(){
        JSONObject result=new JSONObject();RunningScriptNotifications n=active.get();
        try{result.put("serviceActive",n!=null&&!n.closed);if(n!=null)result.put("lastAttemptAt",n.lastAttemptAt).put("lastPostedAt",n.lastPostedAt).put("lastError",n.lastError);}catch(Exception ignored){}
        return result;
    }
    private void submit(Runnable action){if(!closed)try{worker.execute(action);}catch(java.util.concurrent.RejectedExecutionException ignored){}}
    void close(){closed=true;delivery.invalidate();worker.shutdownNow();main.removeCallbacksAndMessages(null);if(active.get()==this)active.clear();}

    boolean handle(Intent intent){
        if(intent==null||(!STOP_ONE.equals(intent.getAction())&&!STOP_ALL.equals(intent.getAction())))return false;
        // Old notification buttons cannot stop executions from a later service lifetime.
        if(!nonce.equals(intent.getStringExtra("nonce")))return true;
        String action=intent.getAction(),token=intent.getStringExtra("token");
        submit(()->{
            if(closed)return;
            state.update(HostScripts.runningNotificationTasks(),System.currentTimeMillis());
            if(STOP_ALL.equals(action))HostScripts.stopAllScripts(owner);
            else if(state.contains(token))HostScripts.stopNotificationTask(token);
            delivery.invalidate();poll();
        });
        return true;
    }

    private void poll(){
        if(closed)return;
        try{
            long now=System.currentTimeMillis();
            if(support==null||now-capabilitiesAt>60000){support=XiaomiFocusSupport.detect(owner);capabilitiesAt=now;}
            List<RunningTaskState.Task> tasks=state.update(HostScripts.runningNotificationTasks(),now);
            long start=state.oldestStart(now);String elapsed=RunningTaskState.elapsed(now-start);
            JSONObject info=support.toJson().put("runningCount",tasks.size()).put("engines",state.engines()).put("currentName",tasks.isEmpty()?"":tasks.get(0).name).put("elapsed",elapsed);
            diagnostics=info.toString();
            String next=tasks.isEmpty()?"idle":state.details()+"|"+tasks.get(0).token+"|"+tasks.size()+"|"+((now-start)/10000)+"|"+support.toJson();
            long attempt=delivery.begin(next);
            if(attempt==0)return;
            lastAttemptAt=now;
            boolean empty=tasks.isEmpty();
            final Notification notification;
            try{notification=empty?owner.buildNotification():build(tasks,start,elapsed);}
            catch(Exception failure){delivery.failed(attempt);throw failure;}
            main.post(()->{
                if(closed||!delivery.isCurrent(attempt))return;
                try{
                if(empty&&hadTasks){
                    // Remove the old focus notification, rather than leaving stale island extras
                    // in SystemUI when "always keep foreground service" is enabled.
                    owner.stopForeground(true);
                    owner.startForeground(ForegroundService.NOTIFICATION_ID,notification);
                }else ((NotificationManager)owner.getSystemService(Context.NOTIFICATION_SERVICE)).notify(ForegroundService.NOTIFICATION_ID,notification);
                delivery.succeeded(attempt);lastPostedAt=System.currentTimeMillis();lastError="";
                if(hadTasks!=!empty)Log.i(TAG,"running="+tasks.size()+" protocol="+support.protocol+" focus="+support.focusAllowed);
                hadTasks=!empty;
                if(empty&&!com.jdkshen.aijspro.Pref.isForegroundServiceEnabled())ForegroundService.stop(owner);
                }catch(Exception failure){delivery.failed(attempt);lastError=failure.getClass().getSimpleName()+": "+failure.getMessage();Log.w(TAG,"运行通知发送失败",failure);}
            });
        }catch(Exception failure){lastError=failure.getClass().getSimpleName()+": "+failure.getMessage();Log.w(TAG,"运行通知刷新失败",failure);}
    }

    private Notification build(List<RunningTaskState.Task> tasks,long start,String elapsed)throws Exception{
        RunningTaskState.Task current=tasks.get(0);
        PendingIntent details=PendingIntent.getActivity(owner,0,new Intent(owner,ScriptStatusActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopOne=stopIntent(STOP_ONE,current.token),stopAll=stopIntent(STOP_ALL,"");
        NotificationCompat.Builder builder=new NotificationCompat.Builder(owner,ForegroundService.CHANEL_ID)
                .setSmallIcon(R.drawable.ic_ai_js_pro_notification).setContentTitle(tasks.size()+" 个脚本运行中")
                .setContentText(current.engine+" · "+current.name).setStyle(new NotificationCompat.BigTextStyle().bigText(state.details()))
                .setSubText("AI.js Pro").setContentIntent(details).setWhen(start).setUsesChronometer(true)
                .setOngoing(true).setOnlyAlertOnce(true).setSound(null).setVibrate(new long[0]).setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .addAction(R.drawable.ic_close_white_48dp,"停止当前",stopOne)
                .addAction(R.drawable.ic_close_white_48dp,"停止全部",stopAll);
        // Do not setSilent(true): it injects group/silent semantics on supported ROMs.
        // Sound remains controlled by the user's existing channel; updates alert only once.
        LiveUpdateSupport.request(builder,tasks.size());
        if(support.acceptsPayload()&&!LiveUpdateSupport.usePlatformRoute(support.platformDiagnostics)){
            String payload=XiaomiFocusPayload.build(tasks.size(),current.name,state.engines(),elapsed,state.details());
            Bundle extras=new Bundle(),actions=new Bundle(),pictures=new Bundle();
            actions.putParcelable(XiaomiFocusPayload.ACTION_STOP_CURRENT,action("停止当前",stopOne,R.drawable.ic_close_white_48dp));
            actions.putParcelable(XiaomiFocusPayload.ACTION_STOP_ALL,action("停止全部",stopAll,R.drawable.ic_close_white_48dp));
            actions.putParcelable(XiaomiFocusPayload.ACTION_DETAILS,action("查看任务",details,android.R.drawable.ic_menu_info_details));
            pictures.putParcelable(XiaomiFocusPayload.PICTURE_KEY,Icon.createWithResource(owner,R.drawable.ic_ai_js_pro_notification));
            extras.putBundle("miui.focus.actions",actions);extras.putBundle("miui.focus.pics",pictures);
            extras.putString("miui.focus.param",payload);builder.addExtras(extras);
        }
        return LiveUpdateSupport.applyStyle(owner,builder.build());
    }
    private Notification.Action action(String title,PendingIntent pending,int icon){return new Notification.Action.Builder(icon,title,pending).build();}
    private PendingIntent stopIntent(String action,String token){
        Intent intent=new Intent(owner,ForegroundService.class).setAction(action)
                .setData(new Uri.Builder().scheme("aijspro").authority("running").appendPath(nonce).appendQueryParameter("action",action).appendQueryParameter("token",token).build())
                .putExtra("nonce",nonce).putExtra("token",token);
        return PendingIntent.getService(owner,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
}
