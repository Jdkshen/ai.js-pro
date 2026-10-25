package com.jdkshen.buildtools.plugin;

import android.content.Context;

import org.json.JSONObject;

import com.jdkshen.buildtools.plugin.toolchain.core.ComponentStore;
import com.jdkshen.buildtools.plugin.toolchain.core.ToolchainDescriptor;

/** Reflection entry class declared by org.autojs.plugin.sdk.registry. */
public final class BuildToolsPlugin {
    public static final String DESCRIPTOR_ASSET = "buildtools/descriptor.json";

    private BuildToolsPlugin() {}

    /** Returns the descriptor JSON; throws with an actionable message if the asset is invalid. */
    public static String describe(Context context) throws Exception {
        ToolchainDescriptor descriptor = ComponentStore.loadDescriptor(
                context.getAssets().open(DESCRIPTOR_ASSET));
        String error = descriptor.validate();
        if (!error.isEmpty()) throw new IllegalStateException("工具链描述校验失败：" + error);
        JSONObject result = descriptor.toJson();
        // Candidate metadata is advisory only; it is not part of the installed/verified state.
        try {
            JSONObject catalog = ComponentStore.readJson(
                    context.getAssets().open("buildtools/catalog.json"));
            if (catalog != null && catalog.has("candidates")) {
                result.put("candidates", catalog.getJSONArray("candidates"));
            }
        } catch (Exception ignored) {
            // Missing catalog must not hide an otherwise valid descriptor.
        }
        return result.toString();
    }
}
