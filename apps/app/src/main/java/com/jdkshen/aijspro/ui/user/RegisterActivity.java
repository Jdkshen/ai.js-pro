package com.jdkshen.aijspro.ui.user;

import android.content.Intent;
import android.util.Log;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.network.NodeBB;
import com.jdkshen.aijspro.network.UserService;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.stardust.theme.ThemeColorManager;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by Stardust on 2017/10/26.
 */
public class RegisterActivity extends BaseActivity {

    TextView mEmail;

    TextView mUserName;

    TextView mPassword;

    View mRegister;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.MIUIX_PILOT) {
            startActivity(new Intent().setClassName(this,
                    "com.jdkshen.aijspro.ui.user.MiuixRegisterActivity"));
            finish();
            return;
        }
        setContentView(R.layout.activity_register);
        mEmail = findViewById(R.id.email);
        mUserName = findViewById(R.id.username);
        mPassword = findViewById(R.id.password);
        mRegister = findViewById(R.id.register);
        findViewById(R.id.register).setOnClickListener(v -> login());
        setUpViews();
    }

    void setUpViews() {
        setToolbarAsBack(getString(R.string.text_register));
        ThemeColorManager.addViewBackground(mRegister);
    }

    void login() {
        String email = mEmail.getText().toString();
        String userName = mUserName.getText().toString();
        String password = mPassword.getText().toString();
        if (!validateInput(email, userName, password)) {
            return;
        }
        MaterialDialog dialog = new MaterialDialog.Builder(this)
                .progress(true, 0)
                .content(R.string.text_registering)
                .cancelable(false)
                .show();
        UserService.getInstance().register(email, userName, password)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(response -> {
                            dialog.dismiss();
                            onRegisterResponse(response.string());
                        }
                        , error -> {
                            dialog.dismiss();
                            mPassword.setError(NodeBB.getErrorMessage(error, RegisterActivity.this, R.string.text_register_fail));
                        });

    }

    private void onRegisterResponse(String res) {
        Toast.makeText(this, R.string.text_register_succeed, Toast.LENGTH_SHORT).show();
        finish();
    }

    private boolean validateInput(String email, String userName, String password) {
        if (email.isEmpty()) {
            mEmail.setError(getString(R.string.text_email_cannot_be_empty));
            return false;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            mEmail.setError(getString(R.string.text_email_format_error));
            return false;
        }
        if (userName.isEmpty()) {
            mUserName.setError(getString(R.string.text_username_cannot_be_empty));
            return false;
        }
        if (password.isEmpty()) {
            mUserName.setError(getString(R.string.text_password_cannot_be_empty));
            return false;
        }
        if (password.length() < 6) {
            mPassword.setError(getString(R.string.nodebb_error_change_password_error_length));
            return false;
        }
        return true;
    }
}
