package com.jdkshen.buildtools.plugin.toolchain.core;

import org.json.JSONException;
import org.json.JSONObject;

/** Distinguishes "installed" from "actually runnable/verified". */
public final class CapabilityState {
    public enum State { NOT_INSTALLED, INSTALLED_UNUSABLE, RUNNABLE, VERIFIED }

    private final State state;
    private final String error;
    private final String detail;

    public CapabilityState(State state, String error, String detail) {
        this.state = state == null ? State.NOT_INSTALLED : state;
        this.error = error == null ? "" : error;
        this.detail = detail == null ? "" : detail;
    }

    public static CapabilityState parse(Object value) {
        if (value instanceof String) {
            return new CapabilityState(State.valueOf(((String) value).toUpperCase()), "", "");
        }
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            String state = o.optString("state", "NOT_INSTALLED").toUpperCase();
            State parsed;
            try { parsed = State.valueOf(state); } catch (RuntimeException error) { parsed = State.NOT_INSTALLED; }
            return new CapabilityState(parsed, o.optString("error", ""), o.optString("detail", ""));
        }
        return new CapabilityState(State.NOT_INSTALLED, "", "");
    }

    public State getState() { return state; }
    public String getError() { return error; }
    public String getDetail() { return detail; }
    public boolean isVerified() { return state == State.VERIFIED; }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("state", state.name())
                .put("error", error).put("detail", detail);
    }
}
