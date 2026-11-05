# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in E:\YiBin\eclipse\Android_SDK_windows/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# OkHttp / Okio：脚本（Rhino / QuickJS / Node24）会通过反射调用它们的公开成员。
# R8 看不到这些调用，会把只被 JS 触及的方法裁掉——真实现象：
#   示例「HTTP网络请求/文件上传.js」报 TypeError: Cannot find function addFormDataPart
#   in object okhttp3.MultipartBody$Builder（实测 R8 包里该类只剩 13 个方法，没有 addFormDataPart）。
# 所以这里按公开成员整体保留（okhttp/okio 体积很小，代价可忽略）。
-keep class okhttp3.** { public *; }
-keep class okio.** { public *; }
-dontwarn okhttp3.**
-dontwarn okio.**
