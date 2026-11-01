package com.stardust.autojs.runtime.api.shizuku;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class ShizukuCommandsTest {
    @Test public void installDoesNotGrantPermissionsOrChangeToUserZeroByDefault() {
        List<String> options = ShizukuCommands.installOptions(new String[0], 10);
        assertEquals(Arrays.asList("-r", "--user", "10"), options);
        assertFalse(options.contains("-g"));
    }
    @Test public void explicitInstallerOptionsRemainSeparateArguments() {
        assertEquals(Arrays.asList("--user","current","-g","-t","-r"),
                ShizukuCommands.installOptions(new String[]{"--user","current","-g","-t"},0));
    }
    @Test public void userAndUnknownOptionsAreNotInterpretedAsShellCode() {
        for (String[] options : new String[][]{{"--user"},{"--user","0;id"},{"--user","0","--user","1"},{"-r;id"},{"--user","-1"}}) {
            try { ShizukuCommands.installOptions(options,0); fail(Arrays.toString(options)); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void quotedPathsPreserveEmptyValuesWhitespaceAndMetacharacters() {
        assertEquals("'pm' '带 空格' 'a'\\''b.apk' '$HOME;id' ''",
                ShizukuCommands.argv(new String[]{"pm","带 空格","a'b.apk","$HOME;id",""}));
    }
    @Test public void nulAndMissingCommandAreRejected() {
        for(String text : new String[]{null,""," \n","a\0b"}) {
            try { ShizukuCommands.validateCommand(text); fail(); } catch (IllegalArgumentException expected) { }
        }
        try { ShizukuCommands.quote("a\0b"); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void outputLimitZeroAndTimeoutBoundariesAreMeaningful() throws Exception {
        assertEquals(0, ShizukuFacade.integer(new JSONObject().put("maxOutput",0),"maxOutput",1,0,16777216));
        assertEquals(3600000, ShizukuFacade.integer(new JSONObject().put("timeout",3600000),"timeout",10000,1,3600000));
        for (Object bad : new Object[]{"100",-1,1.5,JSONObject.NULL,3600001}) {
            try { ShizukuFacade.integer(new JSONObject().put("timeout",bad),"timeout",10000,1,3600000); fail(String.valueOf(bad)); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
