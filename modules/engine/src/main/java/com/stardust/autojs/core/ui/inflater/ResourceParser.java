package com.stardust.autojs.core.ui.inflater;

import com.stardust.autojs.core.ui.inflater.util.Drawables;

/**
 * Created by Stardust on 2018/1/24.
 */

public class ResourceParser {


    private final Drawables mDrawables;
    private ProjectResources mProjectResources;

    public ResourceParser(Drawables drawables) {
        mDrawables = drawables;
    }

    public Drawables getDrawables() {
        return mDrawables;
    }

    /**
     * 工程模式（{@code project.json} 的 {@code androidResources}）的资源表。
     *
     * <p>普通脚本（不在工程里、工程没声明资源）时为 {@code null}，所有工程资源相关的分支都是零开销的空操作。
     */
    public ProjectResources getProjectResources() {
        return mProjectResources;
    }

    public void setProjectResources(ProjectResources projectResources) {
        mProjectResources = projectResources;
        mDrawables.setProjectResources(projectResources);
    }
}
