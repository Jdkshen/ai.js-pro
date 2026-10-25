package com.jdkshen.buildtools.plugin.toolchain.core;

import org.json.JSONException;
import org.json.JSONObject;

/** Normalized compiler diagnostic while the raw log remains readable in the terminal job. */
public final class Diagnostics {
    private final String stage;
    private final String file;
    private final int line;
    private final int column;
    private final String message;
    private final int exitCode;

    public Diagnostics(String stage, String file, int line, int column, String message, int exitCode) {
        this.stage = stage == null ? "" : stage;
        this.file = file == null ? "" : file;
        this.line = line;
        this.column = column;
        this.message = message == null ? "" : message;
        this.exitCode = exitCode;
    }

    public String getStage() { return stage; }
    public String getFile() { return file; }
    public int getLine() { return line; }
    public int getColumn() { return column; }
    public String getMessage() { return message; }
    public int getExitCode() { return exitCode; }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("stage", stage).put("file", file).put("line", line)
                .put("column", column).put("message", message).put("exitCode", exitCode);
    }
}
