package com.jdkshen.aijspro.ui.user;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;

import com.stardust.app.OnActivityResultDelegate;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.widget.EWebView;

/**
 * Created by Stardust on 2017/10/26.
 */
public class WebActivity extends BaseActivity implements OnActivityResultDelegate.DelegateHost {

    public static final String EXTRA_URL = "url";

    private OnActivityResultDelegate.Mediator mMediator = new OnActivityResultDelegate.Mediator();

    EWebView mEWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web);
        mEWebView = findViewById(R.id.eweb_view);
        setupViews();
    }

    void setupViews() {
        setToolbarAsBack(getIntent().getStringExtra(Intent.EXTRA_TITLE));
        mEWebView.getWebView().loadUrl(getIntent().getStringExtra(EXTRA_URL));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        mMediator.onActivityResult(requestCode, resultCode, data);
    }

    @NonNull
    @Override
    public OnActivityResultDelegate.Mediator getOnActivityResultDelegateMediator() {
        return mMediator;
    }
}
