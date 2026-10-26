"auto";
importClass(com.jdkshen.aijspro.build.ApkBuilderPluginHelper);
importClass(com.jdkshen.aijspro.autojs.build.ApkBuilder);
var project = new java.io.File("/storage/emulated/0/脚本/Materi-Original");
var buildDir = new java.io.File(project, "build");
if (buildDir.exists()) { buildDir.delete(); }
buildDir.mkdirs();
var template = ApkBuilderPluginHelper.openTemplateApk(context);
var out = new java.io.File(buildDir, "Materi-UI-original-1.0.0.apk");
var work = new java.io.File(buildDir, "work");
if (work.exists()) { work.delete(); }
log("template ready, building: " + out);
var config = new ApkBuilder.AppConfig()
    .setAppName("Materi-UI")
    .setPackageName("com.materi.ui")
    .setSourcePath(project.getAbsolutePath())
    .setVersionName("1.0.0")
    .setVersionCode(1)
    .setEngine("rhino")
    .setShowSplash(false)
    .setHideLogs(false)
    .setIncludeAccessibility(false)
    .setIncludeImageModule(false)
    .setEncryptLevel(0)
    .ignoreDir(buildDir);
var builder = new ApkBuilder(template, out, work.getAbsolutePath());
builder.withConfig(config);
builder.prepare();
builder.build();
builder.sign();
log("APK_DONE " + out.getAbsolutePath() + " bytes=" + out.length());
