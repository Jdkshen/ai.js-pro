package com.jdkshen.aijspro.model.explorer;

import android.content.Context;
import com.stardust.autojs.project.ProjectConfig;
import com.stardust.pio.PFile;
import java.io.File;
import java.io.IOException;

/** Reconstruct navigation pages without losing project/sample capabilities on view recreation. */
public final class ExplorerPageFactory {
    private ExplorerPageFactory() {}

    public static ExplorerPage fromPath(Context context, String path, ExplorerPage parent) {
        File file = new File(path);
        File samples = new File(context.getFilesDir(), "sample");
        // Search uses canonical paths, while WorkspaceFileProvider uses the Context filesDir
        // spelling. Preserve that spelling for Android's /data/data vs /data/user/0 aliases.
        try {
            String samplePath = samples.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (filePath.equals(samplePath)) {
                file = samples;
            } else if (filePath.startsWith(samplePath + File.separator)) {
                file = new File(samples, filePath.substring(samplePath.length() + 1));
            }
        } catch (IOException | SecurityException ignored) {
            // Existing saved paths can still be reconstructed if canonicalization is unavailable.
        }
        if (file.equals(samples)) return ExplorerSamplePage.createRoot(new PFile(file.getPath()));
        if (file.getPath().startsWith(samples.getPath() + File.separator)) {
            return new ExplorerSamplePage(file, parent);
        }
        ProjectConfig project = ProjectConfig.fromProjectDir(path);
        return project == null ? new ExplorerDirPage(file, parent)
                : new ExplorerProjectPage(file, parent, project);
    }
}
