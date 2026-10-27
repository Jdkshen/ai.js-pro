# AI.js Pro release keep rules.
#
# The host exposes a large Java bridge to Rhino/QuickJS/Node scripts, generated UIs,
# WebView pages and JNI. A script can call Class.forName / getMethod / getField with
# names that never appear as direct references in the app dex, so a blanket "optimize
# everything" R8 pass would break working features. The rules below are intentionally
# conservative for the app and engine packages, and only rely on R8 for unused
# dependency/library removal. Do not remove a keep without a device regression test.

# Runtime annotations are needed by @JavascriptInterface, EventBus, @Keep and script
# bridge metadata; signature/exception metadata is used by reflection.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Retrofit 2.3 predates R8 full mode. Signature attributes alone do not preserve
# generic arguments when the wrapper type is not explicitly kept: an API method
# returning Observable<ResponseBody> otherwise becomes raw Observable at runtime.
# Keep wrapper declarations/type parameters, not every method in these libraries.
-keep class io.reactivex.Observable
-keep interface kotlinx.coroutines.Deferred
-keep interface retrofit2.Call
-keep class retrofit2.Response

# Optional / legacy references that are guarded at runtime.
-dontwarn org.mozilla.javascript.**
-dontwarn com.jecelyin.editor.**
-dontwarn com.makeramen.**
-dontwarn org.junit.**
-dontwarn junit.**
-dontwarn com.iwebpp.nodeandroid.**
-dontwarn org.msgpack.core.**
-dontwarn com.pushtorefresh.storio.**
-dontwarn java.lang.invoke.*
-dontwarn **$$Lambda$*
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.flurry.**
-dontwarn com.tencent.bugly.**
# WindowManager extensions/sidecar are optional OEM-provided classes on some devices.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

# ---- Host, engines and reflective bridges -----------------------------------
# The whole host package is reachable by class/method name from scripts, MCP,
# generated Node24 classes and native callbacks. Keep it first-stage safe.
-keep class com.jdkshen.aijspro.** { *; }
# Rhino/QuickJS/Node engine, accessibility automator, enhanced floaty, Node UI
# inflater and host-side generated-class support.
-keep class com.stardust.** { *; }
-keep class org.autojs.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.greenrobot.eventbus.** { *; }
-keep class com.jecelyin.editor.** { *; }
-keep class org.msgpack.** { *; }
# The script-facing Java surface and legacy libraries use embedded class names.
# Stage one removes unused code but preserves names globally. Obfuscation needs
# its own compatibility audit and is deliberately not enabled by releaseR8.
-dontobfuscate
# log4j 1.x bootstraps Category by string, invisible to ordinary class references.
# Preserve reachable log4j contracts without retaining its desktop Swing viewers.
-keep class org.apache.log4j.Category { *; }
-keep,allowshrinking class org.apache.log4j.** { *; }
-keep,allowshrinking class de.mindpipe.android.logging.log4j.** { *; }


# These SDK classes/methods are part of the existing script Java/UI surface. JS
# lookups and generated subclasses are invisible to R8's static call graph.
-keep class androidx.appcompat.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.recyclerview.** { *; }
-keep class androidx.drawerlayout.** { *; }
-keep class androidx.viewpager.** { *; }
-keep class com.google.android.material.** { *; }
-keep class org.opencv.** { *; }
-keep class org.jf.** { *; }
# Smali's terminal CLI instantiates annotation-selected validators/converters by reflection.
-keep class com.beust.jcommander.** { *; }
# D8 selects a threading provider by class name; these constructors are invisible
# to R8. Guarded by the Java 17 jar-to-dex release device probe.
-keep class com.android.tools.r8.threading.** { *; }

-keep class org.antlr.** { *; }

# Embedded Flutter embedding. Flutter uses JNI plus reflective/generated access to
# its engine and activity classes; keep the complete embedding package for release.
-keep class io.flutter.** { *; }
# FlutterView creates a WindowInfoRepository adapter unconditionally.
-keep class androidx.window.** { *; }
# Relinker locates and loads native libraries through reflection.
-keep class com.getkeepsafe.relinker.** { *; }

# Third-party SDKs with reflection or JNI registration.
-keep class com.flurry.** { *; }
-keep class com.tencent.bugly.** { *; }
-keep class com.wang.avi.** { *; }
-keep class com.wang.avi.indicators.** { *; }

# ---- JNI --------------------------------------------------------------------
# Native methods are looked up by name by libquickjs_jni/libaijscrypto/libppocr
# and Flutter; descriptor classes must stay intact too.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---- WebView / JavascriptInterface -----------------------------------------
# The Ace editor and legacy UI pages call Java through addJavascriptInterface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
    public void *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public void *(android.webkit.WebView, java.lang.String);
}

# ---- Android components / XML inflation ------------------------------------
# XML layouts and script-created views are inflated by class name. Keep the
# constructors (and therefore the class names) that LayoutInflater reflects on.
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keepclassmembers public class * extends android.view.View {
    void set*(***);
    *** get*();
}
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# AndroidX fragments are referenced from XML/adapters. (The old support-v4
# Fragment keep was stale: the app is AndroidX-only and that class is absent.)
-keep class * extends androidx.fragment.app.Fragment { <methods>; }

# EventBus and script bridge annotation members.
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keepclassmembers class ** {
    @com.stardust.autojs.runtime.ScriptInterface <methods>;
}
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
-keep @androidx.annotation.Keep class * { *; }

# ---- Serialization / framework contracts -----------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepnames class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep class * extends org.json.JSONObject { <fields>; }

# Google Play services Parcelable contracts used by ML Kit/play-services-basement.
-keep public class com.google.android.gms.common.internal.safeparcel.SafeParcelable {
    public static final *** NULL;
}
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}

# Gson uses field names/annotations unless @SerializedName is present.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# R fields are referenced by generated resource lookups.
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Keep resource bundles used by the framework.
-keep class * extends java.util.ListResourceBundle {
    protected Object[][] getContents();
}

# Optional paths shipped by upstream jars, absent from Android by design.
# Reviewed against the concrete R8 missing-class call sites (2026-10-24):
# - Flutter Play Store deferred components are not configured by this monolithic APK.
# - ANTLR/StringTemplate desktop GUI uses AWT/Swing; the Android Smali parser does not.
# - log4j JavaBeans PropertySetter is a desktop configuration path; the host uses
#   Android appenders directly. This does not add desktop logging configuration support.
# - R8's JVM XML reader is not part of the Android D8 jar-to-dex path.
# - Joda Convert annotations and SLF4J's optional binding are not installed here.
# Exact types only: new missing runtime types must still fail the build.
-dontwarn com.google.android.play.core.splitcompat.SplitCompatApplication
-dontwarn com.google.android.play.core.splitinstall.SplitInstallException
-dontwarn com.google.android.play.core.splitinstall.SplitInstallManager
-dontwarn com.google.android.play.core.splitinstall.SplitInstallManagerFactory
-dontwarn com.google.android.play.core.splitinstall.SplitInstallRequest$Builder
-dontwarn com.google.android.play.core.splitinstall.SplitInstallRequest
-dontwarn com.google.android.play.core.splitinstall.SplitInstallSessionState
-dontwarn com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
-dontwarn com.google.android.play.core.tasks.OnFailureListener
-dontwarn com.google.android.play.core.tasks.OnSuccessListener
-dontwarn com.google.android.play.core.tasks.Task
-dontwarn java.awt.BorderLayout
-dontwarn java.awt.Component
-dontwarn java.awt.Container
-dontwarn java.awt.Frame
-dontwarn java.awt.GridBagConstraints
-dontwarn java.awt.GridBagLayout
-dontwarn java.awt.Insets
-dontwarn java.awt.LayoutManager
-dontwarn java.awt.Window
-dontwarn java.awt.event.WindowAdapter
-dontwarn java.awt.event.WindowEvent
-dontwarn java.awt.event.WindowListener
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.swing.BorderFactory
-dontwarn javax.swing.BoxLayout
-dontwarn javax.swing.DefaultListModel
-dontwarn javax.swing.JEditorPane
-dontwarn javax.swing.JFrame
-dontwarn javax.swing.JList
-dontwarn javax.swing.JPanel
-dontwarn javax.swing.JScrollPane
-dontwarn javax.swing.JSplitPane
-dontwarn javax.swing.JTabbedPane
-dontwarn javax.swing.JTextPane
-dontwarn javax.swing.JToolBar
-dontwarn javax.swing.JTree
-dontwarn javax.swing.JViewport
-dontwarn javax.swing.ListModel
-dontwarn javax.swing.border.Border
-dontwarn javax.swing.event.CaretListener
-dontwarn javax.swing.event.ListSelectionListener
-dontwarn javax.swing.event.TreeModelListener
-dontwarn javax.swing.event.TreeSelectionListener
-dontwarn javax.swing.text.Document
-dontwarn javax.swing.tree.TreeModel
-dontwarn javax.swing.tree.TreePath
-dontwarn javax.xml.stream.XMLInputFactory
-dontwarn javax.xml.stream.XMLStreamReader
-dontwarn org.joda.convert.FromString
-dontwarn org.joda.convert.ToString
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Rhino/Auto.js Pro compatibility: original projects import androidx.documentfile.provider.DocumentFile directly.
-keep class androidx.documentfile.** { *; }

# Main-source running notifications use the optional Flutter runtime by reflection.
-keep class com.jdkshen.aijspro.flutterpoc.PocRuntime { *; }
