package com.stardust.autojs.core.image;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

/** Lifecycle/options facade. SIFT, FLANN, geometry and drawing run entirely in C++. */
public final class SiftMatcher implements AutoCloseable {
    private final JSONObject defaults;
    private long handle;
    private Runnable onClose;

    private static final class Loader {
        static { System.loadLibrary("quickjs"); System.loadLibrary("quickjs_jni"); }
        static void load() { }
    }

    /** A validated QuickJS frame is resolved and held by shared_ptr during the native call. */
    public static final class NativeFrameInput {
        public final long engineHandle, frameHandle;
        public NativeFrameInput(long engineHandle, long frameHandle) {
            this.engineHandle = engineHandle; this.frameHandle = frameHandle;
        }
    }

    public SiftMatcher(Object source, String json) {
        defaults = parse(json); validate(defaults);
        Input input = Input.of(source);
        Loader.load();
        handle = nativeCreate(source, input.kind, input.value, input.engine, input.path, config(defaults));
        if (handle == 0) throw new IllegalStateException("Cannot create native SIFT matcher");
    }

    public synchronized void setOnClose(Runnable callback) { onClose = callback; }
    public synchronized int getTemplateFeatureCount() { ensureOpen(); return nativeFeatureCount(handle); }
    public synchronized double getTemplatePrepareMs() { ensureOpen(); return nativePrepareMs(handle); }
    public synchronized boolean isClosed() { return handle == 0; }

    public synchronized String match(Object source, String json) {
        ensureOpen();
        JSONObject options = merge(defaults, parse(json)); validate(options);
        Input input = Input.of(source);
        return nativeMatch(handle, source, input.kind, input.value, input.engine, input.path,
                config(options), regionValues(options.optJSONArray("region")), options.optString("drawMatches", ""));
    }

    private static double[] config(JSONObject o) {
        // ABI shared with sift_matcher.cpp; zero scene scale selects the area-based default.
        return new double[]{o.has("scale") ? number(o,"scale",1,0.1,4) : 0,
                number(o,"templateScale",1,0.1,4), integer(o,"maxFeatures",0,0,10000),
                integer(o,"templateMaxFeatures",0,0,10000), number(o,"threshold",0.7,0.01,0.99),
                integer(o,"minMatches",6,4,10000), number(o,"ransacThreshold",3,0.1,100),
                number(o,"minAngle",30,0,90), number(o,"maxAngle",150,90,180),
                integer(o,"trackPadding",64,0,2048), o.optBoolean("track",false) ? 1 : 0,
                o.optBoolean("grayscale",false) ? 1 : 0,
                o.optBoolean("reuseScene",true) ? 1 : 0, o.optBoolean("fast",false) ? 1 : 0};
    }

    private static int[] regionValues(JSONArray array) {
        if (array == null || array.length() == 0) return null;
        if (array.length() != 4) throw new IllegalArgumentException("region must be [x,y,width,height]");
        int[] result = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                double value = array.getDouble(i);
                if (!Double.isFinite(value) || value != Math.rint(value) || value > Integer.MAX_VALUE || value < 0)
                    throw new IllegalArgumentException("region requires nonnegative integers");
                result[i] = (int) value;
            }
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid region", e); }
        return result;
    }

    private static final class Input {
        final int kind; final long value, engine; final String path;
        Input(int kind, long value, long engine, String path) {
            this.kind = kind; this.value = value; this.engine = engine; this.path = path;
        }
        static Input of(Object source) {
            if (source instanceof String) return new Input(0,0,0,(String)source);
            if (source instanceof Mat) return new Input(1,((Mat)source).getNativeObjAddr(),0,null);
            if (source instanceof NativeFrameInput) {
                NativeFrameInput frame = (NativeFrameInput) source;
                return new Input(2,frame.frameHandle,frame.engineHandle,null);
            }
            throw new IllegalArgumentException("SIFT input must be an image or local file path");
        }
    }

    /** Convex, finite, nondegenerate polygon with acceptable perspective angles. */
    static double[] validQuad(double[][] points, double minAngle, double maxAngle) {
        double[] angles = new double[4]; double direction = 0, area = 0;
        for (int i = 0; i < 4; i++) {
            double[] a = points[i], b = points[(i + 1) % 4], d = points[(i + 3) % 4];
            if (!Double.isFinite(a[0]) || !Double.isFinite(a[1])) return null;
            double ux = b[0] - a[0], uy = b[1] - a[1], vx = d[0] - a[0], vy = d[1] - a[1];
            double length = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
            double cross = ux * vy - uy * vx;
            if (length < 1 || Math.abs(cross) < 0.001 || (direction != 0 && cross * direction <= 0)) return null;
            direction = cross;
            double cosine = Math.max(-1, Math.min(1, (ux * vx + uy * vy) / length));
            double angle = Math.toDegrees(Math.acos(cosine));
            if (angle < minAngle || angle > maxAngle) return null;
            angles[i] = angle; area += a[0] * b[1] - a[1] * b[0];
        }
        return Math.abs(area) >= 32 ? angles : null;
    }

    static Rect checkedRegion(int x, int y, int width, int height, int imageWidth, int imageHeight) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || (long) x + width > imageWidth || (long) y + height > imageHeight) {
            throw new IllegalArgumentException("region is empty or outside the image");
        }
        return new Rect(x, y, width, height);
    }
    private static void validate(JSONObject options) {
        number(options, "scale", 1, 0.1, 4); number(options, "templateScale", 1, 0.1, 4);
        integer(options, "maxFeatures", 0, 0, 10000); integer(options, "templateMaxFeatures", 0, 0, 10000);
        number(options, "threshold", 0.7, 0.01, 0.99); integer(options, "minMatches", 6, 4, 10000);
        number(options, "ransacThreshold", 3, 0.1, 100);
        number(options, "minAngle", 30, 0, 90); number(options, "maxAngle", 150, 90, 180);
        integer(options, "trackPadding", 64, 0, 2048);
    }
    private static double number(JSONObject o, String key, double fallback, double min, double max) {
        if (!o.has(key)) return fallback;
        try {
            double value = o.getDouble(key);
            if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Invalid " + key);
            return value;
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid " + key, e); }
    }
    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        double value = number(o, key, fallback, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException(key + " requires an integer");
        return (int) value;
    }
    private static JSONObject parse(String json) {
        try { return new JSONObject(json == null || json.isEmpty() ? "{}" : json); }
        catch (JSONException e) { throw new IllegalArgumentException("Invalid SIFT options", e); }
    }
    private static JSONObject merge(JSONObject base, JSONObject override) {
        JSONObject result = parse(base.toString());
        java.util.Iterator<String> keys = override.keys();
        while (keys.hasNext()) { String key = keys.next(); put(result, key, override.opt(key)); }
        return result;
    }
    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    private void ensureOpen() { if (handle == 0) throw new IllegalStateException("SIFT matcher has been recycled"); }

    @Override public synchronized void close() {
        if (handle == 0) return;
        long previous = handle; handle = 0;
        try { nativeClose(previous); }
        finally { if (onClose != null) { onClose.run(); onClose = null; } }
    }

    // The jobject owner pins a caller's Mat against Java finalization for the whole JNI call.
    private static native long nativeCreate(Object owner,int kind,long value,long engine,String path,double[] options);
    private static native String nativeMatch(long handle,Object owner,int kind,long value,long engine,String path,
                                             double[] options,int[] region,String draw);
    private static native int nativeFeatureCount(long handle);
    private static native double nativePrepareMs(long handle);
    private static native void nativeClose(long handle);
}
