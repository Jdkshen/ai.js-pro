package com.stardust.autojs.runtime.api.shizuku;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;
public class ShizukuCompatPolicyTest {
 @Test public void acceptsGeneratedTokens(){for(int i=0;i<30;i++)assertTrue(ShizukuCompatPolicy.validToken(UUID.randomUUID().toString()));}
 @Test public void rejectsMalformedTokens(){for(String token:new String[]{null,"","x","../a","11111111-1111-1111-1111-11111111111g","11111111-1111-1111-1111-111111111111extra"})assertFalse(ShizukuCompatPolicy.validToken(token));}
 @Test public void onlyExactPrivilegedIdentityCanAttach(){assertTrue(ShizukuCompatPolicy.acceptsUid(0,0));assertTrue(ShizukuCompatPolicy.acceptsUid(2000,2000));assertFalse(ShizukuCompatPolicy.acceptsUid(2000,0));assertFalse(ShizukuCompatPolicy.acceptsUid(0,2000));assertFalse(ShizukuCompatPolicy.acceptsUid(10001,10001));assertFalse(ShizukuCompatPolicy.acceptsUid(2000,10001));}
 @Test public void boundedLeaseExpiresAtDeadlineOrClockReset(){assertFalse(ShizukuCompatPolicy.expired(100,15099));assertTrue(ShizukuCompatPolicy.expired(100,15100));assertTrue(ShizukuCompatPolicy.expired(100,99));}
}
