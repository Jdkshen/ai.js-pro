# Script/command entrypoints and the separately loaded Shizuku UserService.
-keep class com.stardust.autojs.runtime.api.ShizukuClient { public *; }
-keep class com.stardust.autojs.runtime.api.ShizukuShell { public *; }
-keep class com.stardust.autojs.runtime.api.shizuku.** { *; }
-keep class com.stardust.autojs.runtime.tool.ToolchainMain { public static void main(java.lang.String[]); }
-keep class rikka.shizuku.ShizukuProvider { public static void enableMultiProcessSupport(boolean); public static void requestBinderForNonProviderProcess(android.content.Context); }
