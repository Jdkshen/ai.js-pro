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

/** Fully resolved command: no worker task should assemble its own shell string. */
public final class ResolvedInvocation {
    private final String executable;
    private final List<String> argv;
    private final Map<String, String> environment;
    private final String workingDirectory;
    private final List<String> components;

    public ResolvedInvocation(String executable, List<String> argv, Map<String, String> environment,
                              String workingDirectory, List<String> components) {
        this.executable = executable == null ? "" : executable;
        this.argv = argv == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(argv));
        this.environment = environment == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(environment));
        this.workingDirectory = workingDirectory == null ? "" : workingDirectory;
        this.components = components == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(components));
    }

    public String getExecutable() { return executable; }
    public List<String> getArgv() { return argv; }
    public Map<String, String> getEnvironment() { return environment; }
    public String getWorkingDirectory() { return workingDirectory; }
    public List<String> getComponents() { return components; }

    public String validate() {
        if (executable.isEmpty()) return "executable 不能为空";
        if (workingDirectory.isEmpty()) return "workingDirectory 不能为空";
        return "";
    }

    public JSONObject toJson() throws JSONException {
        JSONArray args = new JSONArray();
        for (String value : argv) args.put(value);
        JSONObject env = new JSONObject();
        for (Map.Entry<String, String> entry : environment.entrySet()) env.put(entry.getKey(), entry.getValue());
        JSONArray comps = new JSONArray();
        for (String value : components) comps.put(value);
        return new JSONObject().put("executable", executable).put("argv", args)
                .put("environment", env).put("workingDirectory", workingDirectory)
                .put("components", comps);
    }
}
