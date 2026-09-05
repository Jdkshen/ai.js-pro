package com.jdkshen.aijspro.ui.doc;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.TextView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.ui.widget.EWebView;

/**
 * Created by Stardust on 2017/10/24.
 */

public class ManualDialog {

    TextView mTitle;

    EWebView mEWebView;

    View mPinToLeft;

    Dialog mDialog;
    private Context mContext;

    public ManualDialog(Context context) {
        mContext = context;
        View view = View.inflate(context, R.layout.floating_manual_dialog, null);
        mTitle = view.findViewById(R.id.title);
        mEWebView = view.findViewById(R.id.eweb_view);
        mPinToLeft = view.findViewById(R.id.pin_to_left);
        view.findViewById(R.id.close).setOnClickListener(v -> close());
        view.findViewById(R.id.fullscreen).setOnClickListener(v -> viewInNewActivity());
        mDialog = new MaterialDialog.Builder(context)
                .customView(view, false)
                .build();
        mDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }


    public ManualDialog title(String title) {
        mTitle.setText(title);
        return this;
    }

    public ManualDialog url(String url) {
        mEWebView.getWebView().loadUrl(url);
        return this;
    }

    public ManualDialog pinToLeft(View.OnClickListener listener) {
        mPinToLeft.setOnClickListener(v -> {
            mDialog.dismiss();
            listener.onClick(v);
        });
        return this;
    }

    public ManualDialog show() {
        mDialog.show();
        return this;
    }

    void close() {
        mDialog.dismiss();
    }

    void viewInNewActivity() {
        mDialog.dismiss();
        Intent intent = new Intent(mContext, DocumentationActivity.class);
        intent.putExtra(DocumentationActivity.EXTRA_URL, mEWebView.getWebView().getUrl());
        if (!(mContext instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        mContext.startActivity(intent);
    }

}
