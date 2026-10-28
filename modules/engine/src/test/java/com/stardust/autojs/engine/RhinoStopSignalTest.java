package com.stardust.autojs.engine;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
public class RhinoStopSignalTest {
    @Test public void stoppingUiScriptDoesNotPoisonHostThread() {
        assertFalse(Thread.currentThread().isInterrupted());
        RhinoStopSignal signal = new RhinoStopSignal();
        signal.request(Thread.currentThread(), Thread.currentThread());
        assertTrue(signal.isRequested()); assertFalse(Thread.currentThread().isInterrupted());
    }
    @Test public void backgroundScriptStillUnblocksOnStop() throws Exception {
        CountDownLatch ready=new CountDownLatch(1), stopped=new CountDownLatch(1);
        Thread worker=new Thread(() -> { ready.countDown(); try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { stopped.countDown(); } });
        worker.start();
        try { assertTrue(ready.await(2,TimeUnit.SECONDS)); new RhinoStopSignal().request(worker,Thread.currentThread());
            assertTrue(stopped.await(2,TimeUnit.SECONDS)); }
        finally { worker.interrupt(); worker.join(2000); }
        assertFalse(worker.isAlive()); assertFalse(Thread.currentThread().isInterrupted());
    }
    @Test public void cancellationIsPerEngineAndCanPrecedeThreadInitialization() {
        RhinoStopSignal first=new RhinoStopSignal(), second=new RhinoStopSignal();
        first.request(null,Thread.currentThread()); assertTrue(first.isRequested()); assertFalse(second.isRequested());
    }
}
