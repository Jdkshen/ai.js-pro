package com.stardust.autojs.apkbuilder;

import com.stardust.autojs.apkbuilder.util.StreamUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Created by Stardust on 2017/10/23.
 */

public class ApkPackager {

    private InputStream mApkInputStream;
    private String mWorkspacePath;
    private Signer mSigner;

    public ApkPackager(InputStream apkInputStream, String workspacePath) {
        mApkInputStream = apkInputStream;
        mWorkspacePath = workspacePath;
    }

    public ApkPackager(String apkPath, String workspacePath) throws FileNotFoundException {
        mApkInputStream = new FileInputStream(apkPath);
        mWorkspacePath = workspacePath;
    }

    /**
     * 设置产物要用哪份签名。
     *
     * <p>调用方必须给一个（用户选的密钥，或者本机为该应用自动生成的身份）：
     * 没给就抛异常，而不是静默退回 tiny-sign 那份全世界共用的测试证书 ——
     * 那个默认值会让所有用同款打包器的人共用一个身份，安全软件据此就能把它们
     * 归成同一家族（检测名里的 crt 就是这么来的）。
     */
    public ApkPackager setSigner(Signer signer) {
        mSigner = signer;
        return this;
    }

    public Signer getSigner() {
        return mSigner;
    }

    public void unzip() throws IOException {
        ZipInputStream zis = new ZipInputStream(mApkInputStream);
        for (ZipEntry e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
            String name = e.getName();
            if (!e.isDirectory()) {
                File file = new File(mWorkspacePath, name);
                System.out.println(file);
                file.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(file);
                StreamUtils.write(zis, fos);
                fos.close();
            }
        }
        zis.close();
    }

    public void repackage(String newApkPath) throws Exception {
        if (mSigner == null) {
            throw new IllegalStateException(
                    "没有可用的签名身份：打包器拒绝使用内置的公共测试证书，" +
                            "请先选择密钥库或让调用方调用 setSigner()");
        }
        mSigner.sign(new File(mWorkspacePath), new File(newApkPath));
    }

    public void cleanWorkspace() {

    }

}
