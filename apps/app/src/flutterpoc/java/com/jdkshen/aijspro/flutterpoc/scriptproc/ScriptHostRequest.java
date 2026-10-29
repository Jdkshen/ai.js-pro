package com.jdkshen.aijspro.flutterpoc.scriptproc;

import android.content.Context;
import com.google.gson.Gson;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.script.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned request files keep source and arguments out of Binder transactions. */
final class ScriptHostRequest {
    private static final int MAX_BYTES=2*1024*1024, MAX_CODE=64*1024*1024;
    static File write(Context context,ScriptSource source,ExecutionConfig config) throws Exception {
        if (!(source instanceof JavaScriptSource)) throw new IllegalArgumentException("Only JavaScript engines use the script actor");
        JavaScriptSource script=(JavaScriptSource)source;
        if (script instanceof JavaScriptFileSource && ((JavaScriptFileSource)script).getFile().length()>MAX_CODE)
            throw new IllegalArgumentException("Script source is too large");
        File file=new File(context.getCacheDir(),"script-host-"+UUID.randomUUID()+".json");
        File payload=new File(file.getPath()+".payload");
        try {
        JSONObject request=new JSONObject().put("version",1).put("name",source.getName()).put("filename",source.toString())
                .put("engine",source.getEngineName()).put("mode",script.getExecutionMode());
        if (script instanceof QuickJsBytecodeSource) {
            byte[] bytecode=((QuickJsBytecodeSource)script).getBytecode();
            if (bytecode.length>MAX_CODE) throw new IllegalArgumentException("Script bytecode is too large");
            try (FileOutputStream output=new FileOutputStream(payload)) { output.write(bytecode); }
            request.put("bytecode",true);
        } else {
            Reader input=script.getScriptReader();
            if (input==null) input=new StringReader(script.getScript());
            try (Reader reader=input; Writer writer=new OutputStreamWriter(new LimitedOutput(new FileOutputStream(payload)),StandardCharsets.UTF_8)) {
                char[] buffer=new char[8192]; int count;
                while ((count=reader.read(buffer))!=-1) writer.write(buffer,0,count);
            }
        }
        Gson gson=new Gson();
        request.put("workingDirectory",config.getWorkingDirectory()).put("paths",new JSONArray(Arrays.asList(config.getPath())))
                .put("intentFlags",config.getIntentFlags()).put("delay",config.getDelay()).put("interval",config.getInterval())
                .put("loopTimes",config.getLoopTimes()).put("arguments",new JSONObject(gson.toJson(config.getArguments())))
                .put("scriptConfig",new JSONObject(gson.toJson(config.getScriptConfig())));
        byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length>MAX_BYTES) throw new IllegalArgumentException("Script actor request is too large");
        try (FileOutputStream output=new FileOutputStream(file)) { output.write(bytes); }
        return file;
        } catch (Exception error) { discard(file); throw error; }
    }

    static Loaded read(Context context,String path) throws Exception {
        File file=validatedFile(context,path);
        String cache=context.getCacheDir().getCanonicalPath()+File.separator;
        if (!file.getPath().startsWith(cache) || !file.isFile() || file.length()>MAX_BYTES)
            throw new IllegalArgumentException("Invalid script actor request file");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try (FileInputStream input=new FileInputStream(file)) {
            byte[] buffer=new byte[8192]; int count;
            while ((count=input.read(buffer))!=-1) {
                if (bytes.size()+count>MAX_BYTES) throw new IllegalArgumentException("Script actor request is too large");
                bytes.write(buffer,0,count);
            }
        }
        JSONObject request=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        File payload=new File(file.getPath()+".payload").getCanonicalFile();
        if (!payload.getPath().startsWith(cache) || !payload.isFile() || payload.length()>MAX_CODE)
            throw new IllegalArgumentException("Invalid script source payload");
        if (request.optInt("version")!=1) throw new IllegalArgumentException("Unsupported script actor request version");
        String engine=request.getString("engine"),filename=request.getString("filename");
        if (!JavaScriptSource.ENGINE_RHINO.equals(engine) && !JavaScriptSource.ENGINE_QUICKJS.equals(engine)
                && !JavaScriptSource.ENGINE_NODE24.equals(engine)) throw new IllegalArgumentException("Unknown script engine");
        JavaScriptSource source;
        if (request.has("bytecode")) {
            if (!JavaScriptSource.ENGINE_QUICKJS.equals(engine)) throw new IllegalArgumentException("Invalid bytecode engine");
            ByteArrayOutputStream code=new ByteArrayOutputStream();
            try (InputStream input=new FileInputStream(payload)) { byte[] buffer=new byte[8192]; int count;
                while ((count=input.read(buffer))!=-1) {
                    if (code.size()+count>MAX_CODE) throw new IllegalArgumentException("Script bytecode is too large");
                    code.write(buffer,0,count);
                }
            }
            source=new QuickJsBytecodeSource(filename,code.toByteArray(),request.optInt("mode"));
        } else source=new SnapshotSource(request.getString("name"),filename,engine,request.optInt("mode"),payload);
        ExecutionConfig config=ExecutionConfig.getDefault();
        config.setWorkingDirectory(request.optString("workingDirectory",context.getFilesDir().getPath()));
        JSONArray paths=request.optJSONArray("paths");
        if (paths!=null) { String[] values=new String[paths.length()]; for (int i=0;i<values.length;i++) values[i]=paths.getString(i); config.setPath(values); }
        config.setIntentFlags(request.optInt("intentFlags")); config.setDelay(request.optLong("delay"));
        config.setInterval(request.optLong("interval")); config.setLoopTimes(request.optInt("loopTimes",1));
        Gson gson=new Gson();
        JSONObject arguments=request.optJSONObject("arguments");
        if (arguments!=null) {
            Map<?,?> map=gson.fromJson(arguments.toString(),Map.class);
            for (Map.Entry<?,?> entry:map.entrySet()) config.setArgument(String.valueOf(entry.getKey()),entry.getValue());
        }
        JSONObject scriptConfig=request.optJSONObject("scriptConfig");
        if (scriptConfig!=null) config.setScriptConfig(gson.fromJson(scriptConfig.toString(),com.stardust.autojs.project.ScriptConfig.class));
        return new Loaded(source,config);
    }

    static final class Loaded {
        final JavaScriptSource source; final ExecutionConfig config;
        Loaded(JavaScriptSource source,ExecutionConfig config) { this.source=source; this.config=config; }
    }
    static File validatedFile(Context context,String path) throws IOException {
        File file=new File(path).getCanonicalFile();
        if (!file.getParentFile().equals(context.getCacheDir().getCanonicalFile())
                || !file.getName().matches("script-host-[a-f0-9-]{36}\\.json"))
            throw new IllegalArgumentException("Invalid script actor request path");
        return file;
    }
    static void discard(File request) { if (request!=null) { new File(request.getPath()+".payload").delete(); request.delete(); } }
    private static final class LimitedOutput extends FilterOutputStream {
        long written;
        LimitedOutput(OutputStream output) { super(output); }
        @Override public void write(int value) throws IOException {
            if (++written>MAX_CODE) throw new IOException("Script source is too large"); out.write(value);
        }
        @Override public void write(byte[] bytes,int offset,int length) throws IOException {
            if (written+length>MAX_CODE) throw new IOException("Script source is too large");
            written+=length; out.write(bytes,offset,length);
        }
    }
    private static final class SnapshotSource extends JavaScriptFileSource {
        volatile String code;
        final File payload;
        final String filename,engine; final int mode;
        SnapshotSource(String name,String filename,String engine,int mode,File payload) {
            super(name,new File(filename)); this.filename=filename; this.engine=engine; this.mode=mode; this.payload=payload;
            setPreferredEngine(engine);
        }
        @Override public String getScript() {
            if (code==null) code=com.stardust.pio.PFiles.read(payload);
            return code;
        }
        @Override public Reader getScriptReader() {
            try { return new InputStreamReader(new FileInputStream(payload),StandardCharsets.UTF_8); }
            catch (IOException error) { throw new IllegalStateException("Script snapshot is unavailable",error); }
        }
        @Override protected int parseExecutionMode() { return mode; }
        @Override public String toString() { return filename; }
    }
}
