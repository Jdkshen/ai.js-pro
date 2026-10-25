package com.jdkshen.buildtools.plugin.toolchain.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared, versioned description consumed by the host terminal and MCP. */
public final class ToolchainDescriptor {
    private final int schemaVersion;
    private final String id;
    private final String version;
    private final String provider;
    private final String packageName;
    private final String abi;
    private final int minApi;
    private final String license;
    private final List<ComponentSpec> components;
    private final Map<String, CapabilityState> capabilities;

    public ToolchainDescriptor(int schemaVersion, String id, String version, String provider,
                               String packageName, String abi, int minApi, String license,
                               List<ComponentSpec> components,
                               Map<String, CapabilityState> capabilities) {
        this.schemaVersion = schemaVersion;
        this.id = id == null ? "" : id;
        this.version = version == null ? "" : version;
        this.provider = provider == null ? "" : provider;
        this.packageName = packageName == null ? "" : packageName;
        this.abi = abi == null ? "" : abi;
        this.minApi = minApi;
        this.license = license == null ? "" : license;
        this.components = components == null ? Collections.<ComponentSpec>emptyList()
                : Collections.unmodifiableList(new ArrayList<ComponentSpec>(components));
        this.capabilities = capabilities == null ? Collections.<String, CapabilityState>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, CapabilityState>(capabilities));
    }

    public static ToolchainDescriptor parse(JSONObject o) throws JSONException {
        List<ComponentSpec> components = new ArrayList<ComponentSpec>();
        JSONArray array = o.optJSONArray("components");
        if (array != null) for (int i = 0; i < array.length(); i++) {
            components.add(ComponentSpec.parse(array.getJSONObject(i)));
        }
        Map<String, CapabilityState> capabilities = new LinkedHashMap<String, CapabilityState>();
        JSONObject caps = o.optJSONObject("capabilities");
        if (caps != null) {
            Iterator<String> keys = caps.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                capabilities.put(key, CapabilityState.parse(caps.opt(key)));
            }
        }
        return new ToolchainDescriptor(o.optInt("schemaVersion", 0), o.optString("id"),
                o.optString("version"), o.optString("provider"), o.optString("packageName"),
                o.optString("abi"), o.optInt("minApi", 0), o.optString("license"),
                components, capabilities);
    }

    public int getSchemaVersion() { return schemaVersion; }
    public String getId() { return id; }
    public String getVersion() { return version; }
    public String getProvider() { return provider; }
    public String getPackageName() { return packageName; }
    public String getAbi() { return abi; }
    public int getMinApi() { return minApi; }
    public String getLicense() { return license; }
    public List<ComponentSpec> getComponents() { return components; }
    public Map<String, CapabilityState> getCapabilities() { return capabilities; }

    public String validate() {
        if (schemaVersion != 1) return "不支持的 schemaVersion: " + schemaVersion;
        if (id.isEmpty()) return "descriptor id 不能为空";
        if (packageName.isEmpty()) return "descriptor packageName 不能为空";
        if (!"arm64-v8a".equals(abi)) return "descriptor ABI 不匹配: " + abi;
        for (ComponentSpec component : components) {
            String error = component.validate();
            if (!error.isEmpty()) return error;
        }
        return "";
    }

    public JSONObject toJson() throws JSONException {
        JSONArray array = new JSONArray();
        for (ComponentSpec component : components) array.put(component.toJson());
        JSONObject caps = new JSONObject();
        for (Map.Entry<String, CapabilityState> entry : capabilities.entrySet()) {
            caps.put(entry.getKey(), entry.getValue().toJson());
        }
        return new JSONObject().put("schemaVersion", schemaVersion).put("id", id)
                .put("version", version).put("provider", provider)
                .put("packageName", packageName).put("abi", abi)
                .put("minApi", minApi).put("license", license)
                .put("components", array).put("capabilities", caps);
    }
}
