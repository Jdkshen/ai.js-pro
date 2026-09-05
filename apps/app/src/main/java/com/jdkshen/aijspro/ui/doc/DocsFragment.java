package com.jdkshen.aijspro.ui.doc;

import android.app.Activity;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.ui.main.QueryEvent;
import com.jdkshen.aijspro.ui.main.ViewPagerFragment;

import com.stardust.util.BackPressedHandler;

import com.jdkshen.aijspro.ui.widget.EWebView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

/**
 * Created by Stardust on 2017/8/22.
 */
public class DocsFragment extends ViewPagerFragment implements BackPressedHandler {

    public static final String ARGUMENT_URL = "url";

    EWebView mEWebView;
    WebView mWebView;

    private String mIndexUrl;
    private String mPreviousQuery;


    public DocsFragment() {
        super(ROTATION_GONE);
        setArguments(new Bundle());
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EventBus.getDefault().register(this);
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_online_docs, container, false);
        mEWebView = view.findViewById(R.id.eweb_view);
        setUpViews();
        return view;
    }

    void setUpViews() {
        mWebView = mEWebView.getWebView();
        mEWebView.getSwipeRefreshLayout().setOnRefreshListener(() -> {
            if (TextUtils.equals(mWebView.getUrl(), mIndexUrl)) {
                loadUrl();
            } else {
                mEWebView.onRefresh();
            }
        });
        Bundle savedWebViewState = getArguments().getBundle("savedWebViewState");
        if (savedWebViewState != null) {
            mWebView.restoreState(savedWebViewState);
        } else {
            loadUrl();
        }
    }

    private void loadUrl() {
        mIndexUrl = getArguments().getString(ARGUMENT_URL, Pref.getDocumentationUrl() + "index.html");
        mWebView.loadUrl(mIndexUrl);
    }


    @Override
    public void onPause() {
        super.onPause();
        Bundle savedWebViewState = new Bundle();
        mWebView.saveState(savedWebViewState);
        getArguments().putBundle("savedWebViewState", savedWebViewState);
    }

    @Override
    public boolean onBackPressed(Activity activity) {
        if (mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }
        return false;
    }

    @Override
    protected void onFabClick(FloatingActionButton fab) {

    }

    @Subscribe
    public void onQuerySummit(QueryEvent event) {
        if (!isShown()) {
            return;
        }
        if (event == QueryEvent.CLEAR) {
            mWebView.clearMatches();
            mPreviousQuery = null;
            return;
        }
        if (event.isFindForward()) {
            mWebView.findNext(false);
            return;
        }
        if (event.getQuery().equals(mPreviousQuery)) {
            mWebView.findNext(true);
            return;
        }
        mWebView.findAllAsync(event.getQuery());
        mPreviousQuery = event.getQuery();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }
}
