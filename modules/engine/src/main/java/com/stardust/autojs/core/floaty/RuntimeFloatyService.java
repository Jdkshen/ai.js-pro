package com.stardust.autojs.core.floaty;

import android.content.Context;
import com.stardust.autojs.execution.ScriptProcessIdentity;
import com.stardust.enhancedfloaty.FloatyService;

/** Window Java references stay in the process that constructed them. */
public class RuntimeFloatyService extends FloatyService {
    public static void installRouting() { FloatyService.setComponentResolver(RuntimeFloatyService::component); }

    private static Class<? extends FloatyService> component(Context context) {
        switch (ScriptProcessIdentity.suffix(context)) {
            case "": return Host.class;
            case ":script": return FloatyService.class;
            case ":native": return Native.class;
            case ":inspector": return Inspector.class;
            case ":node24": return Node.class;
            case ":node24_1": return Node1.class;
            case ":node24_2": return Node2.class;
            case ":node24_3": return Node3.class;
            case ":nodeui": return NodeUi.class;
            default: throw new IllegalStateException("No floating host for " + ScriptProcessIdentity.name());
        }
    }

    public static final class Host extends RuntimeFloatyService { }
    public static final class Native extends RuntimeFloatyService { }
    public static final class Inspector extends RuntimeFloatyService { }
    public static final class Node extends RuntimeFloatyService { }
    public static final class Node1 extends RuntimeFloatyService { }
    public static final class Node2 extends RuntimeFloatyService { }
    public static final class Node3 extends RuntimeFloatyService { }
    public static final class NodeUi extends RuntimeFloatyService { }
}
