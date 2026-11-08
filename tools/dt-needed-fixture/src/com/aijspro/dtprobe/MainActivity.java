package com.aijspro.dtprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends Activity {
    static {
        Log.i("DTPROBE", "before System.loadLibrary(dtprobe)");
        System.loadLibrary("dtprobe");
        Log.i("DTPROBE", "after System.loadLibrary(dtprobe)");
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i("DTPROBE", "MainActivity.onCreate (gadget should already be loaded)");
    }
}