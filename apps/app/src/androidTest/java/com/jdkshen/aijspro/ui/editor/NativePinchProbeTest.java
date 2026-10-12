package com.jdkshen.aijspro.ui.editor;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** Optional device probe. Coordinates are explicitly supplied by the operator;
 * it injects a pinch into the currently visible surface without opening files. */
public class NativePinchProbeTest {
    @Test public void injectPinch() {
        Bundle args=InstrumentationRegistry.getArguments();
        float x=Float.parseFloat(args.getString("centerX")), y=Float.parseFloat(args.getString("centerY"));
        float from=Float.parseFloat(args.getString("fromHalf")), to=Float.parseFloat(args.getString("toHalf"));
        long down=SystemClock.uptimeMillis();
        touch(down,MotionEvent.ACTION_DOWN,new float[]{x-from},y);
        touch(down,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{x-from,x+from},y);
        for(int i=1;i<=15;i++){
            float half=from+(to-from)*i/15;
            touch(down,MotionEvent.ACTION_MOVE,new float[]{x-half,x+half},y);SystemClock.sleep(20);
        }
        touch(down,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{x-to,x+to},y);
        touch(down,MotionEvent.ACTION_UP,new float[]{x-to},y);
    }
    private void touch(long down,int action,float[] x,float y){
        MotionEvent.PointerProperties[] p=new MotionEvent.PointerProperties[x.length];
        MotionEvent.PointerCoords[] c=new MotionEvent.PointerCoords[x.length];
        for(int i=0;i<x.length;i++){
            p[i]=new MotionEvent.PointerProperties();p[i].id=i;p[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            c[i]=new MotionEvent.PointerCoords();c[i].x=x[i];c[i].y=y;c[i].pressure=1;c[i].size=1;
        }
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x.length,p,c,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event,true));}
        finally{event.recycle();}
    }
}
