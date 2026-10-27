package com.jdkshen.aijspro.external.foreground;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.jdkshen.aijspro.external.HostScripts;
import com.jdkshen.aijspro.theme.AppThemePalette;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.jdkshen.aijspro.theme.HostUiAppearance;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Notification destination: live tokens, individual stop, and OEM permission diagnostics. */
public final class ScriptStatusActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final RunningTaskState state=new RunningTaskState();
    private ScheduledExecutorService worker;
    private TextView status,count;
    private LinearLayout rows;
    private Button stopAll;
    private AppThemePalette palette;
    private volatile boolean visible;
    private volatile String copied="{}";
    private String rowsKey="";
    private int generation;
    private XiaomiFocusSupport cachedSupport;

    @Override public void onCreate(Bundle saved){super.onCreate(saved);}
    @Override protected void onResume(){
        super.onResume();visible=true;cachedSupport=null;int session=++generation;
        palette=AppThemeRepository.get(this).getPalette();
        HostUiAppearance.apply(getWindow(),palette.isDark);build();
        worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"script-status");t.setDaemon(true);return t;});
        worker.scheduleWithFixedDelay(()->refresh(session),0,2,TimeUnit.SECONDS);
        RunningScriptNotifications.refresh();
    }
    @Override protected void onPause(){visible=false;++generation;if(worker!=null)worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onPause();}

    private void build(){
        rowsKey="";LinearLayout content=new LinearLayout(this);content.setOrientation(1);content.setPadding(dp(20),dp(12),dp(20),dp(24));content.setBackgroundColor(palette.surfacePrimary);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(content);setContentView(scroll);
        button(content,"返回",this::finish);
        text(content,"脚本运行状态",24,palette.textPrimary);
        status=text(content,"正在检测超级岛与通知权限…",14,palette.textSecondary);
        button(content,"打开系统通知设置",()->{
            Intent intent=Build.VERSION.SDK_INT>=26?new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()):new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()));
            try{startActivity(intent);}catch(Exception e){Toast.makeText(this,"无法打开通知设置",Toast.LENGTH_SHORT).show();}
        });
        button(content,"复制诊断信息",()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("脚本运行与超级岛诊断",copied));Toast.makeText(this,"诊断已复制",Toast.LENGTH_SHORT).show();});
        stopAll=button(content,"停止全部脚本",()->submit(()->{HostScripts.stopAllScripts(getApplicationContext());RunningScriptNotifications.refresh();}));
        count=text(content,"正在读取任务…",18,palette.textPrimary);
        rows=new LinearLayout(this);rows.setOrientation(1);content.addView(rows);
        text(content,"停止当前：停止通知中最近启动的一个脚本；这里也可逐个停止。超级岛显示由系统能力、通知权限和小米应用权限共同决定。",13,palette.textSecondary);
    }

    private void submit(Runnable action){if(worker!=null&&!worker.isShutdown())worker.execute(()->{try{action.run();refresh(generation);}catch(Exception error){main.post(()->{if(visible)Toast.makeText(this,"操作失败："+error.getMessage(),Toast.LENGTH_SHORT).show();});}});}
    private void refresh(int session){
        if(!visible)return;
        try{
            if(cachedSupport==null)cachedSupport=XiaomiFocusSupport.detect(getApplicationContext());
            XiaomiFocusSupport support=cachedSupport;
            JSONArray raw=HostScripts.runningNotificationTasks();List<RunningTaskState.Task> tasks=state.update(raw,System.currentTimeMillis());
            JSONObject diagnostic=support.toJson().put("runningCount",tasks.size()).put("tasks",raw);
            boolean channelAllowed=true;
            if(Build.VERSION.SDK_INT>=26){NotificationChannel c=((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).getNotificationChannel(ForegroundService.CHANEL_ID);channelAllowed=c==null||c.getImportance()!=NotificationManager.IMPORTANCE_NONE;}
            diagnostic.put("runningChannelAllowed",channelAllowed);copied=diagnostic.toString(2);
            String message="系统超级岛能力："+(support.islandSupported?"已声明支持":"未声明支持")
                    +"\n焦点通知协议："+support.protocol+"（3 为超级岛）"
                    +"\n应用通知："+(support.notificationsAllowed?"已开启":"未开启")
                    +"\n运行通知通道："+(channelAllowed?"已开启":"已关闭")
                    +"\n焦点通知权限："+(support.focusAllowed==null?"未能查询":support.focusAllowed?"已允许":"未允许")
                    +(support.queryError.isEmpty()?"":"\n查询说明："+support.queryError);
            String key=raw.toString();
            main.post(()->{
                if(!visible||session!=generation)return;
                if(!message.contentEquals(status.getText()))status.setText(message);
                count.setText(tasks.isEmpty()?"没有正在运行的脚本":tasks.size()+" 个脚本运行中");stopAll.setEnabled(!tasks.isEmpty());
                if(!key.equals(rowsKey)){rowsKey=key;rows.removeAllViews();for(RunningTaskState.Task task:tasks){text(rows,task.engine+" · "+task.name,16,palette.textPrimary);button(rows,"停止 "+task.name,()->submit(()->{HostScripts.stopNotificationTask(task.token);RunningScriptNotifications.refresh();}));}}
            });
        }catch(Exception failure){main.post(()->{if(visible&&session==generation)status.setText("读取失败："+failure.getClass().getSimpleName());});}
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout parent,String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setPadding(0,dp(10),0,dp(10));view.setTextIsSelectable(true);parent.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private Button button(LinearLayout parent,String label,Runnable action){Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setMinHeight(dp(48));button.setTextColor(palette.textPrimary);button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.surfaceSecondary));button.setOnClickListener(v->action.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(6);parent.addView(button,lp);return button;}
}
