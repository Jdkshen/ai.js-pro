package com.stardust.autojs.apkbuilder;

import com.stardust.autojs.apkbuilder.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import pxb.android.StringItem;
import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.DumpAdapter;
import pxb.android.axml.NodeVisitor;
import pxb.android.axml.Util;

import static pxb.android.axml.NodeVisitor.TYPE_STRING;

/**
 * Created by Stardust on 2017/10/23.
 */

public class ManifestEditor {


    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";
    private InputStream mManifestInputStream;
    private int mVersionCode = -1;
    private String mVersionName;
    private String mAppName;
    private String mPackageName;
    private String mOriginalPackageName;
    private byte[] mManifestData;


    public ManifestEditor(InputStream manifestInputStream) {
        mManifestInputStream = manifestInputStream;
    }

    public ManifestEditor setVersionCode(int versionCode) {
        mVersionCode = versionCode;
        return this;
    }

    public ManifestEditor setVersionName(String versionName) {
        mVersionName = versionName;
        return this;
    }

    public ManifestEditor setAppName(String appName) {
        mAppName = appName;
        return this;
    }

    public ManifestEditor setPackageName(String packageName) {
        mPackageName = packageName;
        return this;
    }

    public ManifestEditor commit() throws IOException {
        AxmlReader reader = new AxmlReader(StreamUtils.readAsBytes(mManifestInputStream));
        mManifestInputStream.close();
        AxmlWriter writer = new MutableAxmlWriter();
        reader.accept(new DumpAdapter(writer));
        mManifestData = writer.toByteArray();
        return this;
    }


    public void writeTo(OutputStream manifestOutputStream) throws IOException {
        manifestOutputStream.write(mManifestData);
        manifestOutputStream.close();
    }

    public void onAttr(AxmlWriter.Attr attr) {
        // Handle the "package" attribute on <manifest> element (no namespace)
        if (attr.ns == null) {
            if ("package".equals(attr.name.data)) {
                if (attr.value instanceof StringItem) {
                    // Keep the template identity: provider authorities and the AGP
                    // runtime permission are derived from it and must be renamed too.
                    mOriginalPackageName = ((StringItem) attr.value).data;
                }
                if (mPackageName != null) {
                    attr.value = new StringItem(mPackageName);
                    attr.type = TYPE_STRING;
                    attr.raw = new StringItem(mPackageName);
                }
            }
            return;
        }
        if (!NS_ANDROID.equals(attr.ns.data)) {
            return;
        }

        if ("versionCode".equals(attr.name.data) && mVersionCode != -1) {
            attr.value = mVersionCode;
            return;
        }
        if ("versionName".equals(attr.name.data) && mVersionName != null) {
            attr.value = new StringItem(mVersionName);
            attr.type = TYPE_STRING;
            attr.raw = new StringItem(mVersionName);
            return;
        }
        if ("label".equals(attr.name.data) && mAppName != null) {
            attr.value = new StringItem(mAppName);
            attr.type = TYPE_STRING;
            attr.raw = new StringItem(mAppName);
            return;
        }
        // Provider authorities (FileProvider / androidx-startup) have to be unique per
        // app, otherwise installing two packaged apps fails with
        // INSTALL_FAILED_CONFLICTING_PROVIDER.
        if ("authorities".equals(attr.name.data)) {
            renamePackagePrefix(attr);
            return;
        }
        // The AGP-generated DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION is per
        // applicationId; keeping the template's name would leave the renamed app
        // without the permission it needs at runtime.
        if ("name".equals(attr.name.data) && isPackageDerivedPermission(attr)) {
            renamePackagePrefix(attr);
        }
    }

    private boolean isPackageDerivedPermission(AxmlWriter.Attr attr) {
        if (!(attr.value instanceof StringItem)) {
            return false;
        }
        String value = ((StringItem) attr.value).data;
        return value != null && value.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
    }

    private void renamePackagePrefix(AxmlWriter.Attr attr) {
        if (mPackageName == null || mOriginalPackageName == null
                || !(attr.value instanceof StringItem)) {
            return;
        }
        String value = ((StringItem) attr.value).data;
        if (value == null || !value.startsWith(mOriginalPackageName)) {
            return;
        }
        if (value.length() > mOriginalPackageName.length()
                && value.charAt(mOriginalPackageName.length()) != '.') {
            return;
        }
        String renamed = mPackageName + value.substring(mOriginalPackageName.length());
        attr.value = new StringItem(renamed);
        attr.type = TYPE_STRING;
        attr.raw = new StringItem(renamed);
    }


    private class MutableAxmlWriter extends AxmlWriter {
        private class MutableNodeImpl extends AxmlWriter.NodeImpl {

            MutableNodeImpl(String ns, String name) {
                super(ns, name);
            }

            @Override
            protected void onAttr(AxmlWriter.Attr a) {
                ManifestEditor.this.onAttr(a);
                super.onAttr(a);
            }


            @Override
            public NodeVisitor child(String ns, String name) {
                NodeImpl child = new MutableNodeImpl(ns, name);
                this.children.add(child);
                return child;
            }

        }

        @Override
        public NodeVisitor child(String ns, String name) {
            NodeImpl first = new MutableNodeImpl(ns, name);
            this.firsts.add(first);
            return first;
        }
    }


}
