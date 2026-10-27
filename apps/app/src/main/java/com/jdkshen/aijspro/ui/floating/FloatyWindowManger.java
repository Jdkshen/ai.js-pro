package com.jdkshen.aijspro.ui.floating;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.WindowManager;
import android.widget.Toast;

import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.util.FloatingPermission;
import com.stardust.enhancedfloaty.FloatyService;
import com.stardust.enhancedfloaty.FloatyWindow;
import com.stardust.enhancedfloaty.util.FloatingWindowPermissionUtil;

import com.jdkshen.aijspro.App;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.ui.floating.CircularMenu;

import com.stardust.util.IntentUtil;

import java.lang.ref.WeakReference;

import ezy.assist.compat.SettingsCompat;

import static com.stardust.autojs.util.FloatingPermission.manageDrawOverlays;

/**
 * Created by Stardust on 2017/9/30.
 */

public class FloatyWindowManger {

    private static WeakReference<CircularMenu> sCircularMenu;
    private static final FloatingMenuVisibility sMenuVisibility = new FloatingMenuVisibility();

    public static boolean addWindow(Context context, FloatyWindow window) {
        if (window instanceof com.jdkshen.aijspro.ui.floating.layoutinspector.InspectorFloatyWindow) {
            if (com.jdkshen.aijspro.ui.floating.layoutinspector.InspectorProcessBridge.isRendererProcess(context)) {
                FloatyService.addWindow(window);
                return true;
            }
            // Prefer the isolated renderer, but keep the old in-process path as a fallback
            // when the snapshot cannot be handed off (e.g. no root / non-main caller), and
            // let the bridge fall back asynchronously after write/bind/renderer failures.
            if (((com.jdkshen.aijspro.ui.floating.layoutinspector.InspectorFloatyWindow) window).openIsolated(context)) {
                return true;
            }
        }
        return addWindowInProcess(context, window);
    }

    /** Legacy in-process path; also used when the isolated inspector cannot start. */
    public static boolean addWindowInProcess(Context context, FloatyWindow window) {
        context.startService(new Intent(context, FloatyService.class));
        boolean hasPermission = FloatingPermission.ensurePermissionGranted(context);
        try {
            FloatyService.addWindow(window);
            return true;
            // SecurityException: https://github.com/hyb1996-guest/AutoJsIssueReport/issues/4781
        } catch (Exception e) {
            e.printStackTrace();
            if(hasPermission){
                manageDrawOverlays(context);
                GlobalAppContext.toast(R.string.text_no_floating_window_permission);
            }
        }
        return false;
    }

    public static boolean isCircularMenuShowing() {
        return sCircularMenu != null && sCircularMenu.get() != null;
    }

    public static void showCircularMenuIfNeeded() {
        if (isCircularMenuShowing()) {
            return;
        }
        showCircularMenu();
    }

    public static boolean showCircularMenu() {
        if (isCircularMenuShowing()) return true;
        if (!FloatingPermission.canDrawOverlays(GlobalAppContext.get())) {
            Toast.makeText(GlobalAppContext.get(), R.string.text_no_floating_window_permission, Toast.LENGTH_SHORT).show();
            manageDrawOverlays(GlobalAppContext.get());
            return false;
        } else {
            GlobalAppContext.get().startService(new Intent(GlobalAppContext.get(), FloatyService.class));
            CircularMenu menu = new CircularMenu(GlobalAppContext.get(), sMenuVisibility.isSuppressed());
            sCircularMenu = new WeakReference<>(menu);
            sMenuVisibility.attach(menu);
            return true;
        }
    }

    public static void hideCircularMenu() {
        if (sCircularMenu == null)
            return;
        CircularMenu menu = sCircularMenu.get();
        if (menu != null)
            menu.close();
        sCircularMenu = null;
    }

    /** Main thread: keep the menu mounted, without changing the user's enabled state. */
    public static int acquireCircularMenuSuppression() {
        return sMenuVisibility.acquire();
    }

    /** Main thread: only the last owner can restore the currently enabled menu. */
    public static void releaseCircularMenuSuppression(int token) {
        sMenuVisibility.release(token);
    }

    public static boolean isCircularMenuSuppressed() {
        return sMenuVisibility.isSuppressed();
    }

    static void onCircularMenuClosed(CircularMenu menu) {
        sMenuVisibility.detach(menu);
        if (sCircularMenu != null && sCircularMenu.get() == menu) sCircularMenu = null;
    }

    public static int getWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }
    }
}
