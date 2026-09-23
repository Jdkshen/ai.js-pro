package com.jdkshen.aijspro.model.explorer;

import com.stardust.autojs.project.ProjectConfig;
import com.stardust.pio.PFile;

import com.jdkshen.aijspro.model.script.ScriptRepository;

import java.io.File;

public class ExplorerProjectPage extends ExplorerDirPage {

    private ProjectConfig mProjectConfig;

    public ExplorerProjectPage(PFile file, ExplorerPage parent, ProjectConfig projectConfig) {
        super(file, parent);
        mProjectConfig = projectConfig;
    }

    public ExplorerProjectPage(String path, ExplorerPage parent, ProjectConfig projectConfig) {
        super(path, parent);
        mProjectConfig = projectConfig;
    }

    public ExplorerProjectPage(File file, ExplorerPage parent, ProjectConfig projectConfig) {
        super(file, parent);
        mProjectConfig = projectConfig;
    }

    public ProjectConfig getProjectConfig() {
        return mProjectConfig;
    }

    @Override
    public ExplorerFileItem rename(String newName) {
        File renamed = ScriptRepository.renameTo(getFile(), newName);
        return new ExplorerProjectPage(renamed == null ? getFile() : renamed, getParent(), mProjectConfig);
    }
}
