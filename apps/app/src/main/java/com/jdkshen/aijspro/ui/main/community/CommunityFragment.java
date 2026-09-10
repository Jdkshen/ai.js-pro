package com.jdkshen.aijspro.ui.main.community;

import android.app.Activity;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.network.NodeBB;
import com.jdkshen.aijspro.ui.main.QueryEvent;
import com.jdkshen.aijspro.ui.main.ViewPagerFragment;
import com.stardust.util.BackPressedHandler;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.net.URLEncoder;

/**
 * Created by Stardust on 2017/8/22.
 */
public class CommunityFragment extends ViewPagerFragment implements BackPressedHandler {

    public static class LoadUrl {
        public final String url;

        public LoadUrl(String url) {
            this.url = url;
        }

    }

    public static class VisibilityChange {
        public final boolean visible;

        public VisibilityChange(boolean visible) {
            this.visible = visible;
        }
    }

    CommunityWebView mEWebView;
    WebView mWebView;

    public CommunityFragment() {
        super(0);
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
        View view = inflater.inflate(R.layout.fragment_community, container, false);
        mEWebView = view.findViewById(R.id.eweb_view);
        setUpViews();
        return view;
    }

    void setUpViews() {
        mWebView = mEWebView.getWebView();
        String url = "https://github.com/Jdkshen/ai.js-pro";
        Bundle savedWebViewState = getArguments().getBundle("savedWebViewState");
        if (savedWebViewState != null) {
            mWebView.restoreState(savedWebViewState);
        } else {
            mWebView.loadUrl(url);
        }
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
        mWebView.loadUrl("https://github.com/Jdkshen/ai.js-pro/issues/new/choose");
    }

    @Subscribe
    public void loadUrl(LoadUrl loadUrl) {
        mWebView.loadUrl(NodeBB.url(loadUrl.url));
    }

    @Subscribe
    public void submitQuery(QueryEvent event) {
        if (!isShown() || event == QueryEvent.CLEAR) {
            return;
        }
        String query = URLEncoder.encode(event.getQuery());
        String url = "https://github.com/Jdkshen/ai.js-pro/issues?q=" + query;
        mWebView.loadUrl(url);
        event.collapseSearchView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onPageShow() {
        super.onPageShow();
        EventBus.getDefault().post(new VisibilityChange(true));
    }

    @Override
    public void onPageHide() {
        super.onPageHide();
        EventBus.getDefault().post(new VisibilityChange(false));
    }
}
