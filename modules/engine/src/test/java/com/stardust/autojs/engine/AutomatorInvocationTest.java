package com.stardust.autojs.engine;
import org.junit.Test;import java.io.IOException;import java.lang.reflect.Method;
import static org.junit.Assert.*;
public class AutomatorInvocationTest {
    public static class Target {
        public String success(){return "ok";}
        public void predicate(){throw new IllegalStateException("predicate-original-check");}
        public void checked() throws IOException {throw new IOException("checked-original");}
        public void fatal(){throw new AssertionError("fatal-original");}
    }
    private Object call(String name) throws Exception {return AutomatorInvocation.invoke(Target.class.getMethod(name),new Target(),new Object[0]);}
    @Test public void resultPassesThrough() throws Exception {assertEquals("ok",call("success"));}
    @Test public void predicateMessageAndTypeRemainVisible() throws Exception {
        try{call("predicate");fail();}catch(IllegalStateException e){assertEquals("predicate-original-check",e.getMessage());}
    }
    @Test public void checkedCauseRemainsChecked() throws Exception {
        try{call("checked");fail();}catch(IOException e){assertEquals("checked-original",e.getMessage());}
    }
    @Test public void fatalErrorsAreNotChangedToReflectionWrappers() throws Exception {
        try{call("fatal");fail();}catch(AssertionError e){assertEquals("fatal-original",e.getMessage());}
    }
}
