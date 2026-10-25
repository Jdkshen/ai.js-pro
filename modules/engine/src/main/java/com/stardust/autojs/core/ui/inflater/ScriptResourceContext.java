package com.stardust.autojs.core.ui.inflater;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.view.LayoutInflater;
import androidx.annotation.Keep;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Advanced Node's androidContext contract, with resources owned by one execution. */
@Keep
public final class ScriptResourceContext extends ContextWrapper {
    private static final Map<String, WeakReference<ScriptResourceContext>> OWNERS = new ConcurrentHashMap<>();
    private final String owner;
    private final File projectDir;
    private final LinkedHashMap<String, File> archives = new LinkedHashMap<>();
    private final Map<String, String> archivePackages = new LinkedHashMap<>();
    private volatile CompiledProjectResources compiled;
    private Resources.Theme theme;
    private LayoutInflater inflater;
    private int themeId;
    private long revision;
    private boolean closed;

    public ScriptResourceContext(Context base, String directory, String owner) {
        super(base);
        projectDir = directory == null ? null : new File(directory);
        this.owner = owner;
        if (owner != null) OWNERS.put(owner, new WeakReference<>(this));
    }

    public static ScriptResourceContext find(String owner) {
        WeakReference<ScriptResourceContext> ref = owner == null ? null : OWNERS.get(owner);
        return ref == null ? null : ref.get();
    }

    /** Relative paths resolve against this script's project; a failed reload preserves the old table. */
    public synchronized void loadResources(String path) {
        if (closed) throw new IllegalStateException("脚本资源 Context 已结束");
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("资源包路径为空");
        try {
            File file = new File(path);
            if (!file.isAbsolute()) {
                if (projectDir == null) throw new IllegalArgumentException("相对资源路径需要项目目录：" + path);
                file = new File(projectDir, path);
            }
            file = file.getCanonicalFile();
            String resourcePackage = CompiledProjectResources.archivePackageName(file);
            LinkedHashMap<String, File> next = new LinkedHashMap<>(archives);
            // AssetManager does not replace duplicate package IDs reliably. Keep the latest table for that package.
            java.util.Iterator<String> paths = next.keySet().iterator();
            while (paths.hasNext()) if (resourcePackage.equals(archivePackages.get(paths.next()))) paths.remove();
            next.put(file.getPath(), file);
            CompiledProjectResources loaded = CompiledProjectResources.loadIsolated(getBaseContext(), new ArrayList<>(next.values()));
            archives.clear();
            archives.putAll(next);
            archivePackages.keySet().retainAll(next.keySet());
            archivePackages.put(file.getPath(), resourcePackage);
            compiled = loaded;
            theme = null;
            inflater = null;
            revision++;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("加载 Android 资源失败：" + path + "；" + failure.getMessage(), failure);
        }
    }

    public CompiledProjectResources getCompiledResources() { return compiled; }
    public String getResourceOwner() { return owner; }
    public synchronized long getResourceRevision() { return revision; }
    @Override public Resources getResources() {
        CompiledProjectResources current = compiled;
        return current == null ? super.getResources() : current.getResources();
    }
    @Override public AssetManager getAssets() { return getResources().getAssets(); }
    @Override public synchronized Resources.Theme getTheme() {
        if (compiled == null && themeId == 0) return super.getTheme();
        if (theme == null) {
            theme = getResources().newTheme();
            theme.setTo(super.getTheme());
            if (themeId != 0) theme.applyStyle(themeId, true);
        }
        return theme;
    }
    @Override public synchronized void setTheme(int id) { themeId = id; theme = null; }
    @Override public synchronized Object getSystemService(String name) {
        if (!Context.LAYOUT_INFLATER_SERVICE.equals(name)) return super.getSystemService(name);
        if (inflater == null) inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
        return inflater;
    }
    public synchronized void release() {
        closed = true;
        if (owner != null) OWNERS.remove(owner);
        archives.clear();
        archivePackages.clear();
        // Existing Activity/View references may still be finishing. Do not close their AssetManager.
        compiled = null;
        theme = null;
        inflater = null;
    }
}
