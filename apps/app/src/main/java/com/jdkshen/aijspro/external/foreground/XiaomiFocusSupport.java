package com.jdkshen.aijspro.external.foreground;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONObject;

/** OEM queries are deliberately performed off the UI thread. No permission bypass. */
public final class XiaomiFocusSupport {
    public final boolean notificationsAllowed, islandSupported;
    public final int protocol;
    public final Boolean focusAllowed;
    public final String queryError;
    public final JSONObject platformDiagnostics;
    public final String family;
    private XiaomiFocusSupport(boolean notifications,boolean island,int protocol,Boolean focus,String error,JSONObject platform){
        notificationsAllowed=notifications;islandSupported=island;this.protocol=protocol;focusAllowed=focus;queryError=error;
        platformDiagnostics=platform;
        family=NotificationPresentation.family(Build.MANUFACTURER,Build.BRAND);
    }
    public boolean acceptsPayload(){return "xiaomi".equals(family)&&(protocol>=2||islandSupported);}
    public static XiaomiFocusSupport detect(Context context){
        boolean notifications=NotificationManagerCompat.from(context).areNotificationsEnabled(),island=false;
        int protocol=0;Boolean focus=null;String error="";
        if(NotificationPresentation.isXiaomi(Build.MANUFACTURER,Build.BRAND)){
            try{protocol=Settings.System.getInt(context.getContentResolver(),"notification_focus_protocol",0);}catch(Exception ignored){}
            try{island=(Boolean)Class.forName("android.os.SystemProperties").getMethod("getBoolean",String.class,boolean.class).invoke(null,"persist.sys.feature.island",false);}catch(Exception ignored){}
            try{Bundle request=new Bundle();request.putString("package",context.getPackageName());
                Bundle result=context.getContentResolver().call(Uri.parse("content://miui.statusbar.notification.public"),"canShowFocus",null,request);
                if(result!=null&&result.containsKey("canShowFocus"))focus=result.getBoolean("canShowFocus",false);else error="系统未返回焦点通知权限";
            }catch(Exception failure){error=failure.getClass().getSimpleName();}
        }
        return new XiaomiFocusSupport(notifications,island,protocol,focus,error,LiveUpdateSupport.capabilities(context));
    }
    public JSONObject toJson(){JSONObject j=new JSONObject();try{j.put("schemaVersion",3).put("manufacturer",Build.MANUFACTURER).put("brand",Build.BRAND).put("sdkInt",Build.VERSION.SDK_INT).put("notificationFamily",family).put("system",Build.VERSION.RELEASE).put("xiaomiInterfaceApplicable","xiaomi".equals(family)).put("islandSupportedScope","xiaomi_only").put("islandSupported",islandSupported).put("focusProtocol",protocol).put("notificationsAllowed",notificationsAllowed).put("focusAllowed",focusAllowed==null?JSONObject.NULL:focusAllowed).put("queryError",queryError).put("platform",platformDiagnostics);}catch(Exception ignored){}return j;}
}
