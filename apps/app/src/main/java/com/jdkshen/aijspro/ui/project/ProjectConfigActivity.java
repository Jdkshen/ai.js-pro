package com.jdkshen.aijspro.ui.project;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import androidx.annotation.Nullable;
import android.text.Editable;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.google.android.material.textfield.TextInputLayout;
import com.stardust.autojs.project.ProjectConfig;
import com.stardust.pio.PFiles;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.model.explorer.ExplorerDirPage;
import com.jdkshen.aijspro.model.explorer.ExplorerFileItem;
import com.jdkshen.aijspro.model.explorer.Explorers;
import com.jdkshen.aijspro.model.project.ProjectTemplate;
import com.jdkshen.aijspro.theme.dialog.ThemeColorMaterialDialogBuilder;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.shortcut.ShortcutIconSelectActivity;
import com.jdkshen.aijspro.ui.widget.SimpleTextWatcher;

import java.io.File;
import java.io.FileOutputStream;
import java.util.regex.Pattern;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class ProjectConfigActivity extends BaseActivity {

    public static final String EXTRA_PARENT_DIRECTORY = "parent_directory";

    public static final String EXTRA_NEW_PROJECT = "new_project";

    public static final String EXTRA_DIRECTORY = "directory";

    private static final int REQUEST_CODE = 12477;
    private static final Pattern REGEX_PACKAGE_NAME = Pattern.compile("^([A-Za-z][A-Za-z\\d_]*\\.)+([A-Za-z][A-Za-z\\d_]*)$");


    EditText mProjectLocation;

    EditText mAppName;

    EditText mPackageName;

    EditText mVersionName;

    EditText mVersionCode;

    EditText mMainFileName;

    ImageView mIcon;

    private File mDirectory;
    private File mParentDirectory;
    private ProjectConfig mProjectConfig;
    private boolean mNewProject;
    private Bitmap mIconBitmap;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_project_config);
        bindViews();
        mNewProject = getIntent().getBooleanExtra(EXTRA_NEW_PROJECT, false);
        String parentDirectory = getIntent().getStringExtra(EXTRA_PARENT_DIRECTORY);
        if (mNewProject) {
            if (parentDirectory == null) {
                finish();
                return;
            }
            mParentDirectory = new File(parentDirectory);
            mProjectConfig = new ProjectConfig();
            mProjectConfig.setEngine("quickjs");
        } else {
            String dir = getIntent().getStringExtra(EXTRA_DIRECTORY);
            if (dir == null) {
                finish();
                return;
            }
            mDirectory = new File(dir);
            mProjectConfig = ProjectConfig.fromProjectDir(dir);
            if (mProjectConfig == null) {
                new ThemeColorMaterialDialogBuilder(this)
                        .title(R.string.text_invalid_project)
                        .positiveText(R.string.ok)
                        .dismissListener(dialogInterface -> finish())
                        .show();
            }
        }
        setupViews();
    }

    private void bindViews() {
        mProjectLocation = findViewById(R.id.project_location);
        mAppName = findViewById(R.id.app_name);
        mPackageName = findViewById(R.id.package_name);
        mVersionName = findViewById(R.id.version_name);
        mVersionCode = findViewById(R.id.version_code);
        mMainFileName = findViewById(R.id.main_file_name);
        mIcon = findViewById(R.id.icon);
        findViewById(R.id.fab).setOnClickListener(v -> commit());
        findViewById(R.id.icon).setOnClickListener(v -> selectIcon());
    }

    void setupViews() {
        if (mProjectConfig == null) {
            return;
        }
        setToolbarAsBack(mNewProject ? getString(R.string.text_new_project) : mProjectConfig.getName());
        if (mNewProject) {
            mAppName.addTextChangedListener(new SimpleTextWatcher(s ->
                    mProjectLocation.setText(new File(mParentDirectory, s.toString()).getPath()))
            );
        } else {
            mAppName.setText(mProjectConfig.getName());
            mVersionCode.setText(String.valueOf(mProjectConfig.getVersionCode()));
            mPackageName.setText(mProjectConfig.getPackageName());
            mVersionName.setText(mProjectConfig.getVersionName());
            mMainFileName.setText(mProjectConfig.getMainScriptFile());
            mProjectLocation.setVisibility(View.GONE);
            String icon = mProjectConfig.getIcon();
            if (icon != null) {
                Glide.with(this)
                        .load(new File(mDirectory, icon))
                        .into(mIcon);
            }
        }
    }

    void commit() {
        if (!checkInputs()) {
            return;
        }
        syncProjectConfig();
        if (mIconBitmap != null) {
            saveIcon(mIconBitmap)
                    .subscribe(ignored -> saveProjectConfig(), e -> {
                        e.printStackTrace();
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
        } else {
            saveProjectConfig();
        }

    }

    @SuppressLint("CheckResult")
    private void saveProjectConfig() {
        if (mNewProject) {
            new ProjectTemplate(mProjectConfig, mDirectory)
                    .newProject()
                    .subscribe(ignored -> {
                        Explorers.workspace().notifyChildrenChanged(new ExplorerDirPage(mParentDirectory, null));
                        finish();
                    }, e -> {
                        e.printStackTrace();
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
        } else {
            Observable.fromCallable(() -> {
                PFiles.write(ProjectConfig.configFileOfDir(mDirectory.getPath()),
                        mProjectConfig.toJson());
                return Void.TYPE;
            })
                    .observeOn(Schedulers.io())
                    .subscribeOn(AndroidSchedulers.mainThread())
                    .subscribe(ignored -> {
                        ExplorerFileItem item = new ExplorerFileItem(mDirectory, null);
                        Explorers.workspace().notifyItemChanged(item, item);
                        finish();
                    }, e -> {
                        e.printStackTrace();
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
        }
    }

    void selectIcon() {
        startActivityForResult(new Intent(this, ShortcutIconSelectActivity.class), REQUEST_CODE);
    }

    private void syncProjectConfig() {
        mProjectConfig.setName(mAppName.getText().toString());
        mProjectConfig.setVersionCode(Integer.parseInt(mVersionCode.getText().toString()));
        mProjectConfig.setVersionName(mVersionName.getText().toString());
        mProjectConfig.setMainScriptFile(mMainFileName.getText().toString());
        mProjectConfig.setPackageName(mPackageName.getText().toString());
        if (mNewProject) {
            String location = mProjectLocation.getText().toString();
            mDirectory = new File(location);
        }
        //mProjectConfig.getLaunchConfig().setHideLogs(true);
    }

    private boolean checkInputs() {
        boolean inputValid = true;
        inputValid &= checkNotEmpty(mAppName);
        inputValid &= checkNotEmpty(mVersionCode);
        inputValid &= checkNotEmpty(mVersionName);
        inputValid &= checkPackageNameValid(mPackageName);
        return inputValid;
    }

    private boolean checkPackageNameValid(EditText editText) {
        Editable text = editText.getText();
        String hint = ((TextInputLayout) editText.getParent().getParent()).getHint().toString();
        if(TextUtils.isEmpty(text)){
            editText.setError(hint + getString(R.string.text_should_not_be_empty));
            return false;
        }
        if(!REGEX_PACKAGE_NAME.matcher(text).matches()){
            editText.setError(getString(R.string.text_invalid_package_name));
            return false;
        }
        return true;

    }

    private boolean checkNotEmpty(EditText editText) {
        if (!TextUtils.isEmpty(editText.getText()))
            return true;
        // TODO: 2017/12/8 more beautiful ways?
        String hint = ((TextInputLayout) editText.getParent().getParent()).getHint().toString();
        editText.setError(hint + getString(R.string.text_should_not_be_empty));
        return false;
    }


    @SuppressLint("CheckResult")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            return;
        }
        ShortcutIconSelectActivity.getBitmapFromIntent(getApplicationContext(), data)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(bitmap -> {
                            mIcon.setImageBitmap(bitmap);
                            mIconBitmap = bitmap;
                        },
                        Throwable::printStackTrace);
    }

    @SuppressLint("CheckResult")
    private Observable<String> saveIcon(Bitmap b) {
        return Observable.just(b)
                .map(bitmap -> {
                    String iconPath = mProjectConfig.getIcon();
                    if (iconPath == null) {
                        iconPath = "res/logo.png";
                    }
                    File iconFile = new File(mDirectory, iconPath);
                    PFiles.ensureDir(iconFile.getPath());
                    FileOutputStream fos = new FileOutputStream(iconFile);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    return iconPath;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(iconPath -> mProjectConfig.setIcon(iconPath));

    }

}
