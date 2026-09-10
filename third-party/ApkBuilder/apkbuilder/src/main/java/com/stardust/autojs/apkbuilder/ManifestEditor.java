package com.stardust.autojs.apkbuilder;

import com.stardust.autojs.apkbuilder.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.Iterator;
import java.util.List;

import pxb.android.StringItem;
import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.DumpAdapter;
import pxb.android.axml.NodeVisitor;
import pxb.android.axml.Util;
import pxb.android.axml.ValueWrapper;

import static pxb.android.axml.NodeVisitor.TYPE_STRING;

/**
 * Created by Stardust on 2017/10/23.
 */

public class ManifestEditor {


    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String TAG_MANIFEST = "manifest";
    private static final String TAG_APPLICATION = "application";
    private static final String TAG_USES_PERMISSION = "uses-permission";
    private static final String ATTR_NAME = "name";
    /** android:name is a framework attribute with a fixed resource id. */
    private static final int ANDROID_ATTR_NAME = 0x01010003;

    private InputStream mManifestInputStream;
    private int mVersionCode = -1;
    private String mVersionName;
    private String mAppName;
    private String mPackageName;
    private String mOriginalPackageName;
    private byte[] mManifestData;
    private List<String> mPermissionsToAdd;
    private List<String> mPermissionsToRemove;
    private List<String> mComponentsToRemove;


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

    /**
     * Adds {@code <uses-permission>} entries the template does not declare yet.
     * Duplicates are ignored.
     */
    public ManifestEditor setPermissionsToAdd(List<String> permissions) {
        mPermissionsToAdd = permissions;
        return this;
    }

    /**
     * Removes {@code <uses-permission>} entries declared by the template. Permissions
     * that are not present are silently ignored.
     */
    public ManifestEditor setPermissionsToRemove(List<String> permissions) {
        mPermissionsToRemove = permissions;
        return this;
    }

    /**
     * Removes whole components (service / activity / receiver / provider) declared with the
     * given {@code android:name}, used by the packaging page's "features" switches
     * (e.g. dropping the accessibility service from the packaged app).
     */
    public ManifestEditor setComponentsToRemove(List<String> componentNames) {
        mComponentsToRemove = componentNames;
        return this;
    }

    public ManifestEditor commit() throws IOException {
        AxmlReader reader = new AxmlReader(StreamUtils.readAsBytes(mManifestInputStream));
        mManifestInputStream.close();
        MutableAxmlWriter writer = new MutableAxmlWriter();
        reader.accept(new DumpAdapter(writer));
        writer.applyPermissions(mPermissionsToAdd, mPermissionsToRemove);
        writer.applyComponents(mComponentsToRemove);
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

        private MutableNodeImpl mManifestNode;

        /**
         * Applies the requested permission set to the parsed manifest tree. Runs between
         * parsing and serialization, the only window where the node tree can be edited.
         */
        void applyPermissions(List<String> toAdd, List<String> toRemove) {
            if (mManifestNode == null) {
                return;
            }
            if (toRemove != null && !toRemove.isEmpty()) {
                mManifestNode.removeChildPermissions(toRemove);
            }
            if (toAdd == null) {
                return;
            }
            for (String permission : toAdd) {
                if (permission != null && permission.trim().length() > 0) {
                    mManifestNode.addPermission(permission.trim());
                }
            }
        }

        /** Removes declared components by {@code android:name} from the application node. */
        void applyComponents(List<String> namesToRemove) {
            if (mManifestNode == null || namesToRemove == null || namesToRemove.isEmpty()) {
                return;
            }
            mManifestNode.removeDescendantComponents(namesToRemove);
        }

        private class MutableNodeImpl extends AxmlWriter.NodeImpl {

            /**
             * Element name captured by us: {@link AxmlWriter.NodeImpl} keeps its own name
             * private, and permission nodes have to be identified while iterating children.
             */
            private final String mName;
            private String mAndroidName;

            MutableNodeImpl(String ns, String name) {
                super(ns, name);
                mName = name;
            }

            @Override
            public void attr(String ns, String name, int resourceId, int type, Object value) {
                if (NS_ANDROID.equals(ns) && ATTR_NAME.equals(name)) {
                    mAndroidName = asString(value);
                }
                super.attr(ns, name, resourceId, type, value);
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

            void removeChildPermissions(List<String> permissions) {
                for (Iterator<NodeImpl> it = children.iterator(); it.hasNext(); ) {
                    NodeImpl child = it.next();
                    if (!(child instanceof MutableNodeImpl)) {
                        continue;
                    }
                    MutableNodeImpl node = (MutableNodeImpl) child;
                    if (TAG_USES_PERMISSION.equals(node.mName) && node.mAndroidName != null
                            && permissions.contains(node.mAndroidName)) {
                        it.remove();
                    }
                }
            }

            /**
             * Drops every descendant declaring one of {@code componentNames} through
             * {@code android:name}, including its meta-data / intent-filter children.
             * Components live under {@code <application>}, so the whole tree is walked.
             */
            void removeDescendantComponents(List<String> componentNames) {
                for (Iterator<NodeImpl> it = children.iterator(); it.hasNext(); ) {
                    NodeImpl child = it.next();
                    if (!(child instanceof MutableNodeImpl)) {
                        continue;
                    }
                    MutableNodeImpl node = (MutableNodeImpl) child;
                    if (node.mAndroidName != null && componentNames.contains(node.mAndroidName)) {
                        it.remove();
                        continue;
                    }
                    node.removeDescendantComponents(componentNames);
                }
            }

            void addPermission(String permission) {
                for (NodeImpl child : children) {
                    if (!(child instanceof MutableNodeImpl)) {
                        continue;
                    }
                    MutableNodeImpl node = (MutableNodeImpl) child;
                    if (TAG_USES_PERMISSION.equals(node.mName)
                            && permission.equals(node.mAndroidName)) {
                        return;
                    }
                }
                MutableNodeImpl node = new MutableNodeImpl(null, TAG_USES_PERMISSION);
                node.attr(NS_ANDROID, ATTR_NAME, ANDROID_ATTR_NAME, TYPE_STRING, permission);
                // Keep the manifest schema order: uses-permission before application.
                int index = indexOfApplication();
                if (index < 0) {
                    children.add(node);
                } else {
                    children.add(index, node);
                }
            }

            private int indexOfApplication() {
                for (int i = 0; i < children.size(); i++) {
                    NodeImpl child = children.get(i);
                    if (child instanceof MutableNodeImpl
                            && TAG_APPLICATION.equals(((MutableNodeImpl) child).mName)) {
                        return i;
                    }
                }
                return -1;
            }
        }

        @Override
        public NodeVisitor child(String ns, String name) {
            MutableNodeImpl first = new MutableNodeImpl(ns, name);
            this.firsts.add(first);
            if (TAG_MANIFEST.equals(name)) {
                mManifestNode = first;
            }
            return first;
        }

    }

    /**
     * Attribute values reach {@link AxmlWriter.NodeImpl#attr} as plain strings, string
     * pool items or wrapped references depending on how aapt2 encoded them.
     */
    private static String asString(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof StringItem) {
            return ((StringItem) value).data;
        }
        if (value instanceof ValueWrapper) {
            Object ref = ((ValueWrapper) value).ref;
            if (ref instanceof StringItem) {
                return ((StringItem) ref).data;
            }
            if (ref instanceof String) {
                return (String) ref;
            }
        }
        return null;
    }


}
