package com.jdkshen.buildtools.plugin.toolchain.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Declarative component package: source, hash, size, ABI/API and dependencies. */
public final class ComponentSpec {
    private final String id;
    private final String version;
    private final String sha256;
    private final String url;
    private final long sizeBytes;
    private final String abi;
    private final int minApi;
    private final String license;
    private final String installPath;
    private final List<String> dependencies;

    public ComponentSpec(String id, String version, String sha256, String url, long sizeBytes,
                         String abi, int minApi, String license, String installPath,
                         List<String> dependencies) {
        this.id = id == null ? "" : id;
        this.version = version == null ? "" : version;
        this.sha256 = sha256 == null ? "" : sha256.toLowerCase();
        this.url = url == null ? "" : url;
        this.sizeBytes = sizeBytes;
        this.abi = abi == null ? "" : abi;
        this.minApi = minApi;
        this.license = license == null ? "" : license;
        this.installPath = installPath == null ? "" : installPath;
        this.dependencies = dependencies == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(dependencies));
    }

    public static ComponentSpec parse(JSONObject o) throws JSONException {
        List<String> deps = new ArrayList<String>();
        JSONArray array = o.optJSONArray("dependencies");
        if (array != null) for (int i = 0; i < array.length(); i++) deps.add(array.optString(i));
        return new ComponentSpec(o.optString("id"), o.optString("version"), o.optString("sha256"),
                o.optString("url"), o.optLong("sizeBytes", 0), o.optString("abi", "arm64-v8a"),
                o.optInt("minApi", 26), o.optString("license"), o.optString("installPath"), deps);
    }

    public String getId() { return id; }
    public String getVersion() { return version; }
    public String getSha256() { return sha256; }
    public String getUrl() { return url; }
    public long getSizeBytes() { return sizeBytes; }
    public String getAbi() { return abi; }
    public int getMinApi() { return minApi; }
    public String getLicense() { return license; }
    public String getInstallPath() { return installPath; }
    public List<String> getDependencies() { return dependencies; }

    /** Basic shape check only; actual SHA is checked after download by ComponentStore. */
    public String validate() {
        if (id.isEmpty()) return "component id 不能为空";
        if (!sha256.matches("[0-9a-f]{64}")) return "component " + id + " 的 sha256 格式非法";
        if (sizeBytes <= 0) return "component " + id + " 的 sizeBytes 必须大于 0";
        if (url.isEmpty()) return "component " + id + " 缺少下载地址";
        if (!abi.isEmpty() && !"arm64-v8a".equals(abi)) return "component " + id + " ABI 不匹配: " + abi;
        return "";
    }

    public JSONObject toJson() throws JSONException {
        JSONArray deps = new JSONArray();
        for (String dep : dependencies) deps.put(dep);
        return new JSONObject().put("id", id).put("version", version).put("sha256", sha256)
                .put("url", url).put("sizeBytes", sizeBytes).put("abi", abi)
                .put("minApi", minApi).put("license", license)
                .put("installPath", installPath).put("dependencies", deps);
    }
}
