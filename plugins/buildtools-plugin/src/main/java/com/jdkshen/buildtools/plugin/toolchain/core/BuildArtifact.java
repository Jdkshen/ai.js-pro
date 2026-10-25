package com.jdkshen.buildtools.plugin.toolchain.core;

import org.json.JSONException;
import org.json.JSONObject;

/** A real output tied to one job; old files must never be reported as this build's success. */
public final class BuildArtifact {
    private final String jobId;
    private final String path;
    private final String type;
    private final long sizeBytes;
    private final String sha256;
    private final long generatedAtMillis;
    private final boolean verified;

    public BuildArtifact(String jobId, String path, String type, long sizeBytes,
                         String sha256, long generatedAtMillis, boolean verified) {
        this.jobId = jobId == null ? "" : jobId;
        this.path = path == null ? "" : path;
        this.type = type == null ? "" : type;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256 == null ? "" : sha256.toLowerCase();
        this.generatedAtMillis = generatedAtMillis;
        this.verified = verified;
    }

    public String getJobId() { return jobId; }
    public String getPath() { return path; }
    public String getType() { return type; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public long getGeneratedAtMillis() { return generatedAtMillis; }
    public boolean isVerified() { return verified; }

    public String validate() {
        if (jobId.isEmpty() || path.isEmpty()) return "artifact 缺少 jobId/path";
        if (sizeBytes <= 0) return "artifact 文件为空";
        if (!sha256.matches("[0-9a-f]{64}")) return "artifact sha256 格式非法";
        return "";
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("jobId", jobId).put("path", path).put("type", type)
                .put("sizeBytes", sizeBytes).put("sha256", sha256)
                .put("generatedAtMillis", generatedAtMillis).put("verified", verified);
    }
}
