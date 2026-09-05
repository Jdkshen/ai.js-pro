package com.jdkshen.aijspro.ui.user;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
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

import org.w3c.dom.Node;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by Stardust on 2017/9/20.
 */
public class LoginActivity extends BaseActivity {

    TextView mUserName;

    TextView mPassword;

    View mLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.MIUIX_PILOT) {
            startActivity(new Intent().setClassName(this,
                    "com.jdkshen.aijspro.ui.user.MiuixLoginActivity"));
            finish();
            return;
        }
        setContentView(R.layout.activity_login);
        mUserName = findViewById(R.id.username);
        mPassword = findViewById(R.id.password);
        mLogin = findViewById(R.id.login);
        findViewById(R.id.login).setOnClickListener(v -> login());
        findViewById(R.id.forgot_password).setOnClickListener(v -> forgotPassword());
        setUpViews();
    }

    void setUpViews() {
        setToolbarAsBack(getString(R.string.text_login));
        ThemeColorManager.addViewBackground(mLogin);
    }

    void login() {
        String userName = mUserName.getText().toString();
        String password = mPassword.getText().toString();
        if (!checkNotEmpty(userName, password)) {
            return;
        }
        MaterialDialog dialog = new MaterialDialog.Builder(this)
                .progress(true, 0)
                .content(R.string.text_logining)
                .cancelable(false)
                .show();
        UserService.getInstance().login(userName, password)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(response -> {
                            dialog.dismiss();
                            Toast.makeText(getApplicationContext(), R.string.text_login_succeed, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                        , error -> {
                            dialog.dismiss();
                            mPassword.setError(NodeBB.getErrorMessage(error, LoginActivity.this, R.string.text_login_fail));
                        });

    }

    void forgotPassword() {
        startActivity(new Intent(this, WebActivity.class)
                .putExtra(WebActivity.EXTRA_URL, NodeBB.BASE_URL + "reset")
                .putExtra(Intent.EXTRA_TITLE, getString(R.string.text_reset_password)));
    }

    private boolean checkNotEmpty(String userName, String password) {
        if (userName.isEmpty()) {
            mUserName.setError(getString(R.string.text_username_cannot_be_empty));
            return false;
        }
        if (password.isEmpty()) {
            mUserName.setError(getString(R.string.text_password_cannot_be_empty));
            return false;
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_login, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_register) {
            startActivity(new Intent(this, RegisterActivity.class));
            finish();
        }
        return super.onOptionsItemSelected(item);
    }
}
