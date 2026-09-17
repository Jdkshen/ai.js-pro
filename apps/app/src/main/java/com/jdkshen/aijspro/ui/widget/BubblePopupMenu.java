package com.jdkshen.aijspro.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import androidx.recyclerview.widget.RecyclerView;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Created by Stardust on 2017/5/23.
 */

public class BubblePopupMenu extends PopupWindow {


    public interface OnItemClickListener {
        void onClick(View view, int position);
    }

    private RecyclerView mRecyclerView;
    private OnItemClickListener mOnItemClickListener;
    private View mLittleTriangle;

    /**
     * 当前用的是 Miuix(Compose) 内容还是旧的深色气泡。
     * Miuix 版没有那个指向节点的小三角（卡片自带圆角与阴影），定位时改为直接夹住 x。
     */
    private boolean mMiuixContent;

    public BubblePopupMenu(Context context, List<String> options) {
        super(context);
        View view = View.inflate(context, R.layout.bubble_popup_menu, null);
        mLittleTriangle = view.findViewById(R.id.little_triangle);
        mRecyclerView = (RecyclerView) view.findViewById(R.id.list);
        View miuixView = createMiuixContentView(context, options);
        if (miuixView != null) {
            // Miuix 路径：旧的 RecyclerView 留着（布局不动）但不再用
            mMiuixContent = true;
            mRecyclerView.setVisibility(View.GONE);
            mLittleTriangle.setVisibility(View.GONE);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    miuixViewWidth, miuixViewHeight);
            ((LinearLayout) view).addView(miuixView, params);
        } else {
            mRecyclerView.setAdapter(new SimpleRecyclerViewAdapter<>(R.layout.bubble_popup_menu_item, options, MenuItemViewHolder::new));
        }
        setContentView(view);
        setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        setOutsideTouchable(true);
        setFocusable(true);
        // 这个菜单是从布局分析的悬浮窗（FloatyService 的 context）弹的，**没有窗口 token**，
        // 不指定悬浮窗类型会在 showAtLocation 抛 BadTokenException（菜单直接弹不出来）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setWindowLayoutType(FloatyWindowManger.getWindowType());
        }
    }

    private int miuixViewWidth;
    private int miuixViewHeight;

    /**
     * 反射取 miuix 源集里的菜单宿主（`main` 不编译依赖 miuix，与 `MiuixMainFabHost` / `MiuixDrawerHost` 同一做法）。
     * 取不到（或尺寸算不出来）就返回 null，调用方回落旧实现。
     */
    private View createMiuixContentView(Context context, List<String> options) {
        try {
            Class<?> host = Class.forName("com.jdkshen.aijspro.ui.widget.MiuixBubbleMenuHost");
            int[] size = (int[]) host.getMethod("preferredSize", Context.class, List.class)
                    .invoke(null, context, options);
            if (size == null || size[0] <= 0 || size[1] <= 0) {
                return null;
            }
            miuixViewWidth = size[0];
            miuixViewHeight = size[1];
            // 点击回调仍走 OnItemClickListener（第 3 个参数用内容视图充数，两个调用点都只用 position）
            return (View) host.getMethod("createView", Context.class, List.class, IntConsumer.class)
                    .invoke(null, context, options, (IntConsumer) index -> {
                        if (mOnItemClickListener != null) {
                            mOnItemClickListener.onClick(getContentView(), index);
                        }
                    });
        } catch (Throwable e) {
            // 回落旧实现是兜底，但**不能静默**：真机上曾因为这里吞掉异常，菜单看起来「没改成 Miuix」。
            android.util.Log.w("BubblePopupMenu", "Miuix 菜单内容创建失败，回落到旧气泡", e);
            return null;
        }
    }

    public void setOnItemClickListener(OnItemClickListener onItemClickListener) {
        mOnItemClickListener = onItemClickListener;
    }

    public void showAsDropDownAtLocation(View parent, int contentHeight, int x, int y) {
        int screenWidth = getContentView().getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getContentView().getResources().getDisplayMetrics().heightPixels;
        int width = getContentView().getMeasuredWidth();
        int height = getContentView().getMeasuredHeight();
        if (mMiuixContent) {
            // 没有小三角可以用来吸收偏移 → 直接把位置夹在屏幕内
            if (x + width > screenWidth) {
                x = screenWidth - width;
            } else if (x < 0) {
                x = 0;
            }
            if (y > screenHeight / 2) {
                y -= contentHeight + height;
            }
            super.showAtLocation(parent, Gravity.NO_GRAVITY, x, y);
            return;
        }
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) mLittleTriangle.getLayoutParams();
        if (x + width > screenWidth) {
            params.leftMargin = x + width - screenWidth;
        } else if (x < 0) {
            params.leftMargin = x;
        } else {
            params.leftMargin = 0;
        }
        if (y > screenHeight / 2) {
            getContentView().setRotation(180);
            mRecyclerView.setRotation(180);
            params.leftMargin = -params.leftMargin;
            y -= contentHeight + height;
        } else {
            getContentView().setRotation(0);
            mRecyclerView.setRotation(0);
        }
        mLittleTriangle.setLayoutParams(params);
        super.showAtLocation(parent, Gravity.NO_GRAVITY, x, y);
    }


    public void preMeasure() {
        getContentView().measure(getWidth(), getHeight());
    }


    private class MenuItemViewHolder extends BindableViewHolder<String> {

        private TextView mOption;

        public MenuItemViewHolder(View itemView) {
            super(itemView);
            mOption = (TextView) itemView.findViewById(R.id.option);
            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mOnItemClickListener != null) {
                        int i = mRecyclerView.getChildAdapterPosition(v);
                        mOnItemClickListener.onClick(v, i);
                    }
                }
            });
        }

        @Override
        public void bind(String s, int position) {
            mOption.setText(s);
        }
    }

}
