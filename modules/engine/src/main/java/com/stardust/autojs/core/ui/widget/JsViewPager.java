package com.stardust.autojs.core.ui.widget;

import android.content.Context;

import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.stardust.autojs.core.ui.inflater.ShouldCallOnFinishInflate;

import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

public class JsViewPager extends ViewPager implements ShouldCallOnFinishInflate {

    private String[] mTitles;

    public JsViewPager(Context context) {
        super(context);
    }


    public JsViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void onFinishDynamicInflate() {
        setAdapter();
    }

    /**
     * Auto.js Pro 的 {@code ui.viewPager.initAdapterFromChildren()}：把子 View 当作各页。
     *
     * <p>动态 inflate 完已经调过 {@link #onFinishDynamicInflate()}（内部就是 {@link #setAdapter}），
     * 这里必须**幂等**：{@code ViewPager.setAdapter()} 里有 {@code removeNonDecorViews()}，
     * 会把「本来就在 ViewPager 里、由适配器直接返回」的子页全删掉 —— 再调一次页面就白了
     * （示例「使用Android原生界面」第 19 行正是这行调用）。
     */
    public void initAdapterFromChildren() {
        if (getAdapter() == null) {
            setAdapter();
        }
    }

    public void setTitles(String[] titles) {
        mTitles = titles;
    }

    private void setAdapter() {
        setOffscreenPageLimit(getChildCount());
        setAdapter(new PagerAdapter() {

            @Override
            public Object instantiateItem(ViewGroup container, int position) {
                return getChildAt(position);
            }

            @Override
            public CharSequence getPageTitle(int position) {
                return mTitles != null && position < mTitles.length ?
                        mTitles[position] :
                        super.getPageTitle(position);
            }

            @Override
            public int getCount() {
                return getChildCount();
            }

            @Override
            public boolean isViewFromObject(View view, Object object) {
                return view == object;
            }

            @Override
            public void destroyItem(ViewGroup container, int position, Object object) {

            }
        });
    }
}
