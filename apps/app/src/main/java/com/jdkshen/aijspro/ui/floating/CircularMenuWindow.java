package com.jdkshen.aijspro.ui.floating;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;

import com.stardust.enhancedfloaty.FloatyService;
import com.stardust.enhancedfloaty.FloatyWindow;
import com.stardust.enhancedfloaty.WindowBridge;
import com.stardust.util.ScreenMetrics;

import com.jdkshen.aijspro.ui.floating.gesture.BounceDragGesture;

public class CircularMenuWindow extends FloatyWindow {

    private static final String KEY_POSITION_X = CircularMenuWindow.class.getName() + ".position.x";
    private static final String KEY_POSITION_Y = CircularMenuWindow.class.getName() + ".position.y";

    protected CircularMenuFloaty mFloaty;
    protected CircularActionMenu mCircularActionMenu;
    protected View mCircularActionView;
    protected BounceDragGesture mDragGesture;
    protected OrientationAwareWindowBridge mActionViewWindowBridge;
    protected WindowBridge mMenuWindowBridge;
    protected WindowManager.LayoutParams mActionViewWindowLayoutParams;
    protected WindowManager.LayoutParams mMenuWindowLayoutParams;
    protected View.OnClickListener mActionViewOnClickListener;
    protected float mKeepToSideHiddenWidthRadio;
    protected float mActiveAlpha = 1.0F;
    protected float mInactiveAlpha = 0.4F;
    private Context mContext;
    private OrientationEventListener mOrientationEventListener;

    public CircularMenuWindow(Context context, CircularMenuFloaty floaty) {
        mFloaty = floaty;
        mContext = context;
    }

    @Override
    protected void onCreateWindow(FloatyService service, WindowManager manager) {
        mActionViewWindowLayoutParams = createWindowLayoutParams();
        mMenuWindowLayoutParams = createWindowLayoutParams();
        inflateWindowViews(service);
        initWindowBridge();
        initGestures();
        setListeners();
        setInitialState();
        mOrientationEventListener = new OrientationEventListener(mContext) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (mActionViewWindowBridge.isOrientationChanged(mContext.getResources().getConfiguration().orientation)) {
                    keepToSide();
                }
            }
        };
        if (mOrientationEventListener.canDetectOrientation()) {
            mOrientationEventListener.enable();
        }
    }

    @Override
    protected View onCreateView(FloatyService floatyService) {
        return null;
    }

    @Override
    protected WindowManager.LayoutParams onCreateWindowLayoutParams() {
        return null;
    }

    private void keepToSide() {
        mDragGesture.keepToEdge();
    }

    private void setInitialState() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        int y = preferences.getInt(KEY_POSITION_Y, ScreenMetrics.getDeviceScreenHeight() / 2);
        mActionViewWindowBridge.updatePosition(mActionViewWindowBridge.getX(), y);
        keepToSide();
    }

    private void initGestures() {
        mDragGesture = new BounceDragGesture(mActionViewWindowBridge, mCircularActionView);
        mDragGesture.setKeepToSideHiddenWidthRadio(mKeepToSideHiddenWidthRadio);
        mDragGesture.setPressedAlpha(mActiveAlpha);
        mDragGesture.setUnpressedAlpha(mInactiveAlpha);
    }

    private void initWindowBridge() {
        mActionViewWindowBridge = new OrientationAwareWindowBridge(mActionViewWindowLayoutParams, getWindowManager(), mCircularActionView, mContext);
        mMenuWindowBridge = new WindowBridge.DefaultImpl(mMenuWindowLayoutParams, getWindowManager(), mCircularActionMenu);
    }

    public void setKeepToSideHiddenWidthRadio(float keepToSideHiddenWidthRadio) {
        mKeepToSideHiddenWidthRadio = keepToSideHiddenWidthRadio;
        if (mDragGesture != null) {
            mDragGesture.setKeepToSideHiddenWidthRadio(mKeepToSideHiddenWidthRadio);
        }
    }

    private WindowManager.LayoutParams createWindowLayoutParams() {
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                FloatyWindowManger.getWindowType(), 520, -3);
        layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        return layoutParams;
    }

    private void setListeners() {
        setOnActionViewClickListener(v -> {
            if (isExpanded()) {
                collapse();
            } else {
                expand();
            }

        });
        if (mActionViewOnClickListener != null) {
            mDragGesture.setOnDraggedViewClickListener(mActionViewOnClickListener);
        }

        mCircularActionMenu.addOnStateChangeListener(new CircularActionMenu.OnStateChangeListenerAdapter() {
            @Override
            public void onExpanding(CircularActionMenu menu) {
                mDragGesture.setEnabled(false);
                mCircularActionView.setAlpha(mActiveAlpha);
                setMenuWindowTouchable(true);
            }

            public void onCollapsed(CircularActionMenu menu) {
                mDragGesture.setEnabled(true);
                mCircularActionView.setAlpha(mInactiveAlpha);
                setMenuWindowTouchable(false);
            }

            public void onExpanded(CircularActionMenu menu) {
                mCircularActionView.setAlpha(mActiveAlpha);
                setMenuWindowTouchable(true);
            }
        });
    }

    public void setOnActionViewClickListener(View.OnClickListener listener) {
        if (mDragGesture == null) {
            mActionViewOnClickListener = listener;
        } else {
            mDragGesture.setOnDraggedViewClickListener(listener);
        }
    }

    public void expand() {
        setMenuPositionAtActionView();
        if (mActionViewWindowBridge.getX() > mActionViewWindowBridge.getScreenWidth() / 2) {
            mCircularActionMenu.expand(3);
        } else {
            mCircularActionMenu.expand(5);
        }

    }

    public void setActiveAlpha(float activeAlpha) {
        mActiveAlpha = activeAlpha;
        if (mDragGesture != null) {
            mDragGesture.setPressedAlpha(activeAlpha);
        }

    }

    public void setInactiveAlpha(float inactiveAlpha) {
        mInactiveAlpha = inactiveAlpha;
        if (mDragGesture != null) {
            mDragGesture.setUnpressedAlpha(mInactiveAlpha);
        }

    }

    public void collapse() {
        setMenuPositionAtActionView();
        mCircularActionMenu.collapse();
    }

    public boolean isExpanded() {
        return mCircularActionMenu.isExpanded();
    }

    private void setMenuPositionAtActionView() {
        int y = mActionViewWindowBridge.getY() - mCircularActionMenu.getMeasuredHeight() / 2 + mCircularActionView.getMeasuredHeight() / 2;
        int x;
        if (mActionViewWindowBridge.getX() > mActionViewWindowBridge.getScreenWidth() / 2) {
            x = mActionViewWindowBridge.getX() - mCircularActionMenu.getExpandedWidth() + mCircularActionView.getMeasuredWidth() / 2;
        } else {
            x = mActionViewWindowBridge.getX() - mCircularActionMenu.getExpandedWidth() + mCircularActionView.getMeasuredWidth();
        }

        mMenuWindowBridge.updatePosition(x, y);
    }

    private void inflateWindowViews(FloatyService service) {
        mCircularActionMenu = mFloaty.inflateMenuItems(service, this);
        mCircularActionView = mFloaty.inflateActionView(service, this);
        mCircularActionMenu.setVisibility(View.GONE);
        // 参数别写反：菜单用菜单那份、球用球那份。两个桥对象各自持有自己的 LayoutParams，
        // 而 setMenuWindowTouchable() 要改的正是菜单那一份。
        setMenuWindowTouchable(false);   // 初始是收起态，窗口就不该吃触摸（参数存进去，addView 直接带上）
        getWindowManager().addView(mCircularActionMenu, mMenuWindowLayoutParams);
        getWindowManager().addView(mCircularActionView, mActionViewWindowLayoutParams);
    }

    /**
     * 菜单窗口是个 {@code 672×668} 的大矩形（环形菜单展开用）。收起后 View 虽然 GONE，
     * **窗口矩形仍然吃触摸**，真机上的表现就是「悬浮窗挡住下面应用，点不到」和
     * 「菜单关掉之后还要吞一会儿触摸」。收起时整窗 {@code FLAG_NOT_TOUCHABLE}、
     * 展开时立刻恢复，触摸就能直接落到下层应用。
     */
    private void setMenuWindowTouchable(boolean touchable) {
        if (mMenuWindowLayoutParams == null || mCircularActionMenu == null) {
            return;
        }
        int notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        int flags = mMenuWindowLayoutParams.flags;
        int newFlags = touchable ? (flags & ~notTouchable) : (flags | notTouchable);
        if (newFlags == flags) {
            return;
        }
        mMenuWindowLayoutParams.flags = newFlags;
        if (mCircularActionMenu.isAttachedToWindow()) {
            try {
                getWindowManager().updateViewLayout(mCircularActionMenu, mMenuWindowLayoutParams);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
    }

    public void onServiceDestroy(FloatyService floatyService) {
        close();
    }

    public void close() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        preferences.edit()
                .putInt(KEY_POSITION_X, mActionViewWindowBridge.getX())
                .putInt(KEY_POSITION_Y, mActionViewWindowBridge.getY())
                .apply();
        try {
            mDragGesture.cancel();
            mOrientationEventListener.disable();
            getWindowManager().removeView(mCircularActionMenu);
            getWindowManager().removeView(mCircularActionView);
            FloatyService.removeWindow(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
