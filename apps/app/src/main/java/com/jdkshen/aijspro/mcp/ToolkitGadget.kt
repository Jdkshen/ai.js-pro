package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Rootless APK instrumentation. Existing resource tables and DEX files are copied byte for byte. */
internal object ToolkitGadget {
    internal data class ManifestPatch(
        val bytes: ByteArray,
        val packageName: String,
        val providerProcess: String,
        val providerProcessSource: String,
        /** clinit 模式：改写前的 application@name（null=Manifest 未声明，运行时即 android.app.Application）。 */
        val originalApplication: String? = null,
    )
    const val MARKER = "assets/aijs-gadget.json"
    const val LIBRARY = "aijsgadget"
    val ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    /** Read package name without writing/compiling; used to choose a Gadget script target path. */
    internal fun packageName(input: File): String = ZipFile(input).use { zip ->
        val entry = zip.getEntry("AndroidManifest.xml") ?: error("缺少AndroidManifest.xml")
        val bytes = zip.getInputStream(entry).use { ToolkitIo.read(it, 4 * 1024 * 1024) }
        require(ToolkitResources.isBinaryXml(bytes)) { "输入APK的Manifest必须为二进制AXML" }
        val xml = ResXmlDocument().apply { readBytes(ByteArrayInputStream(bytes)) }
        val manifest = xml.documentElement ?: error("Manifest根节点不存在")
        manifest.searchAttributeByName("package")?.valueAsString ?: error("Manifest缺少包名")
    }

    fun prepare(input: File, output: File, scratch: File, component: File, port: Int, defaultAbi: String,
                interactionType: String = "listen", scriptPath: String? = null,
                providerProcess: String? = null, patchedEntries: Map<String, ByteArray> = emptyMap(),
                clinit: ClinitHook? = null): JsonObject {
        require(interactionType == "listen" || interactionType == "script") { "interactionType 只能是 listen 或 script" }
        if (interactionType == "script") {
            // 跨 UID 的 loopback 可能被 SELinux 静默 DROP（实测停在 SYN_SENT），script 模式不碰网络。
            require(!scriptPath.isNullOrBlank()) { "script 模式必须提供 scriptPath（目标应用可读的脚本绝对路径）" }
            require(scriptPath.length <= 1024 && !scriptPath.contains('\u0000')) { "scriptPath 无效" }
        } else {
            require(port in 1024..65535) { "Gadget port 必须为1024..65535" }
        }
        require(!output.exists() && output.parentFile?.isDirectory == true) { "必须输出到父目录存在的新文件" }
        val token = if (interactionType == "listen") ToolkitIo.hex(ByteArray(24).also { SecureRandom().nextBytes(it) }).lowercase() else null
        val suffix = ToolkitIo.fresh().replace("-", "")
        val provider = "com.aijs.instrumentation.GadgetProvider$suffix"
        val info: JsonObject
        ZipFile(input).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= 30000 && entries.map { it.name }.toSet().size == entries.size) { "APK条目重复或超过30000" }
            var expanded = 0L
            entries.forEach { e ->
                ToolkitIo.ensureActive(); ToolkitIo.entry(e.name)
                require(e.size in 0..ToolkitIo.MAX_APK) { "APK条目大小无效" }
                expanded += e.size; require(expanded <= ToolkitIo.MAX_APK) { "APK展开超过512MiB" }
            }
            require(zip.getEntry(MARKER) == null && entries.none { it.name.endsWith("/lib$LIBRARY.so") || it.name.endsWith("/lib$LIBRARY.config.so") }) { "此APK已包含AI.js Gadget，请从原APK重新生成" }
            val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: error("缺少AndroidManifest.xml")
            val manifest = zip.getInputStream(manifestEntry).use { ToolkitIo.read(it, 4 * 1024 * 1024) }
            // clinit 模式额外把 application@name 指向生成类；Manifest 其它字节不变，失败按专属码抛出（不产出 APK）。
            val patched = if (clinit == null) {
                patchManifest(manifest, provider, suffix, providerProcess)
            } else {
                try {
                    patchManifest(manifest, provider, suffix, providerProcess, clinit.className)
                } catch (failure: Throwable) {
                    if (failure is VirtualMachineError) throw failure
                    throw ToolkitException("PRELOAD_CLINIT_MANIFEST_PATCH_FAILED",
                        "clinit 模式改写 AndroidManifest.xml 失败：" + (failure.message ?: failure.javaClass.simpleName), "preloadMode")
                }
            }
            val nativeAbis = entries.filter { it.name.matches(Regex("lib/[^/]+/[^/]+\\.so")) }.map { it.name.split('/')[1] }.toSet()
            require(nativeAbis.all { it in ABIS }) { "不支持的目标ABI：${nativeAbis - ABIS}" }
            val abis = nativeAbis.ifEmpty { setOf(defaultAbi) }
            require(abis.all { it in ABIS }) { "当前手机ABI不支持Gadget" }
            val libraries = abis.associateWith { abi -> File(component, "gadget/$abi/libfrida-gadget.so").also { require(it.isFile && it.length() in 1..ToolkitIo.MAX_ENTRY.toLong()) { "Gadget组件缺少${abi}库，请重装frida-gadget" } } }
            libraries.forEach { (abi, library) -> validateElf(library.inputStream().use { source -> ByteArray(65536).let { head -> val n=source.read(head); head.copyOf(n.coerceAtLeast(0)) } },abi) }
            require(expanded + libraries.values.sumOf { it.length() } < ToolkitIo.MAX_APK) { "加入Gadget后展开超过512MiB" }
            val dexNumbers = entries.mapNotNull { e -> Regex("classes([0-9]*)\\.dex").matchEntire(e.name)?.groupValues?.get(1)?.let { if (it.isEmpty()) 1 else it.toInt() } }
            val usedDex = dexNumbers.toSet()
            val freeDex = generateSequence(1) { it + 1 }.filter { it !in usedDex }.take(if (clinit == null) 1 else 2).toList()
            require(freeDex.last() <= 32) { "最多支持32个DEX" }
            fun dexEntryName(number: Int) = if (number == 1) "classes.dex" else "classes$number.dex"
            val dexName = dexEntryName(freeDex[0])
            val clinitDexName = if (clinit == null) null else dexEntryName(freeDex[1])
            val dex = compileProvider(provider, scratch)
            info = ToolkitIo.json("schemaVersion" to 1, "mode" to "rootless-gadget", "requiresRoot" to false,
                "packageName" to patched.packageName, "providerClass" to provider,
                "providerProcess" to patched.providerProcess, "providerProcessSource" to patched.providerProcessSource,
                "interactionType" to interactionType, "interaction" to interactionType,
                "onLoad" to "resume", "portConflict" to "fail",
                "fridaVersion" to "17.25.2", "abis" to abis.sorted(), "dexEntry" to dexName, "minimumRuntimeSdk" to 21,
                "originalSha256" to ToolkitIo.sha(input), "resourceTableRecompiled" to false)
            // script 模式不构建 endpoint/token；只暴露 interaction=script + scriptPath。
            if (interactionType == "script") info.addProperty("scriptPath", scriptPath)
            else {
                info.addProperty("endpoint", "127.0.0.1:$port")
                info.addProperty("token", token ?: error("listen 模式缺少令牌"))
            }
            // clinit 模式：诚实记录生成类/父类/DEX 条目（provider 仍在，两个钩子并存；二次 loadLibrary 是同 classloader no-op）。
            if (clinit != null) {
                info.addProperty("clinitClass", clinit.className)
                info.addProperty("clinitOriginalApplication", clinit.originalApplication)
                info.addProperty("clinitDexEntry", clinitDexName)
            }
            val config = ToolkitIo.json("interaction" to if (interactionType == "script") {
                ToolkitIo.json("type" to "script", "path" to scriptPath, "on_load" to "resume")
            } else {
                ToolkitIo.json("type" to "listen", "address" to "127.0.0.1", "port" to port,
                    "on_port_conflict" to "fail", "on_load" to "resume", "token" to (token ?: error("listen 模式缺少令牌")))
            }).toString().toByteArray(Charsets.UTF_8)
            ZipOutputStream(output.outputStream()).use { out ->
                fun add(name: String, data: ByteArray) { out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry() }
                for (entry in entries) {
                    ToolkitIo.ensureActive()
                    if (entry.name == "AndroidManifest.xml" || signatureEntry(entry.name)) continue
                    val copy = ZipEntry(entry.name).apply {
                        if (entry.time >= 0) time = entry.time
                        method = entry.method
                        if (method == ZipEntry.STORED) { size = entry.size; compressedSize = entry.size; crc = entry.crc }
                    }
                    out.putNextEntry(copy)
                    val patched = patchedEntries[entry.name]
                    if (patched != null) {
                        require(patched.size.toLong() == entry.size) { "就地 ELF 补丁改变了大小：" + entry.name }
                        if (entry.method == ZipEntry.STORED) {
                            copy.size = patched.size.toLong(); copy.compressedSize = patched.size.toLong()
                            copy.crc = CRC32().apply { update(patched) }.value
                        }
                        out.write(patched)
                    } else if (!entry.isDirectory) zip.getInputStream(entry).use { source ->
                        val buffer = ByteArray(65536)
                        while (true) { ToolkitIo.ensureActive(); val n = source.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
                    }
                    out.closeEntry()
                }
                add("AndroidManifest.xml", patched.bytes); add(dexName, dex)
                if (clinit != null && clinitDexName != null) add(clinitDexName, clinit.dex)
                add(MARKER, info.toString().toByteArray(Charsets.UTF_8))
                libraries.forEach { (abi, library) ->
                    out.putNextEntry(ZipEntry("lib/$abi/lib$LIBRARY.so"))
                    library.inputStream().use { source -> val buffer=ByteArray(65536);while(true){ToolkitIo.ensureActive();val n=source.read(buffer);if(n<0)break;out.write(buffer,0,n)} };out.closeEntry()
                    // Android only extracts native-library-shaped names. Gadget recognizes .config.so.
                    add("lib/$abi/lib$LIBRARY.config.so", config)
                }
            }
        }
        return info
    }

    /** P1-2：目标 App 外部 files 目录下的 Gadget 脚本路径（绝对路径）。 */
    internal fun scriptTargetPath(packageName: String, sourceName: String): File {
        val safe = File(sourceName).name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "gadget-script.js" }
        return File("/sdcard/Android/data/$packageName/files/aijs-gadget", safe)
    }

    internal fun signatureEntry(name: String) = name.uppercase().let {
        it == "META-INF/MANIFEST.MF" || it.matches(Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)"))
    }

    /** Official Android Gadget configuration deliberately has a .so suffix so PackageManager extracts it. */
    internal fun isConfiguration(path:String,bytes:ByteArray):Boolean {
        if(!path.matches(Regex("lib/[^/]+/lib(?:aijsgadget|frida-gadget)\\.config\\.so"))||bytes.size>16384)return false
        return runCatching {
            val config=com.google.gson.JsonParser().parse(ToolkitIo.text(bytes)).asJsonObject.getAsJsonObject("interaction")
            config.tString("type") in setOf("listen","connect","script","script-directory")
        }.getOrDefault(false)
    }

    internal fun validateElf(head:ByteArray,abi:String) {
        require(head.size>=64&&head[0]==0x7f.toByte()&&head[1]==69.toByte()&&head[2]==76.toByte()&&head[3]==70.toByte()&&head[5]==1.toByte()){"Gadget不是受支持的ELF"}
        val b=ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);val wide=head[4]==2.toByte()
        val machine=b.getShort(18).toInt() and 65535
        require(machine==mapOf("arm64-v8a" to 183,"armeabi-v7a" to 40,"x86" to 3,"x86_64" to 62)[abi]&&wide==(abi in setOf("arm64-v8a","x86_64"))){"Gadget ELF与目标ABI不匹配"}
        val offset=if(wide)b.getLong(32)else b.getInt(28).toLong() and 0xffffffffL
        val stride=b.getShort(if(wide)54 else 42).toInt() and 65535;val count=b.getShort(if(wide)56 else 44).toInt() and 65535
        require(offset>=0&&count in 1..1024&&stride>=(if(wide)56 else 32)&&offset+count.toLong()*stride<=head.size){"Gadget ELF程序头无效"}
        var loads=0
        repeat(count){i->val p=(offset+i.toLong()*stride).toInt();if(b.getInt(p)==1){
            val fileOffset=if(wide)b.getLong(p+8)else b.getInt(p+4).toLong() and 0xffffffffL
            val address=if(wide)b.getLong(p+16)else b.getInt(p+8).toLong() and 0xffffffffL
            val alignment=if(wide)b.getLong(p+48)else b.getInt(p+28).toLong() and 0xffffffffL
            val page=if(wide)16384L else 4096L
            require(alignment>=page&&(fileOffset-address)%page==0L){"Gadget ELF未满足${page}字节LOAD对齐"};loads++
        }}
        require(loads>0){"Gadget ELF缺少LOAD段"}
    }

    internal fun patchManifest(bytes: ByteArray, provider: String, suffix: String): Pair<ByteArray, String> {
        val patched = patchManifest(bytes, provider, suffix, null)
        return patched.bytes to patched.packageName
    }

    internal fun patchManifest(bytes: ByteArray, provider: String, suffix: String,
                               requestedProviderProcess: String?, clinitClass: String? = null): ManifestPatch {
        require(ToolkitResources.isBinaryXml(bytes)) { "输入APK的Manifest必须为二进制AXML" }
        val xml = ResXmlDocument().apply { readBytes(ByteArrayInputStream(bytes)) }
        val manifest = xml.documentElement ?: error("Manifest根节点不存在")
        require(manifest.name == "manifest") { "Manifest根节点无效" }
        val packageName = manifest.searchAttributeByName("package")?.valueAsString ?: error("Manifest缺少包名")
        require(packageName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))) { "包名无效" }
        require(manifest.searchAttributeByName("split")?.valueAsString.isNullOrEmpty() && manifest.listElements("uses-split").isEmpty()) { "暂不支持Split APK；需要完整单APK" }
        require(manifest.searchAttributeByName("sharedUserId") == null) { "sharedUserId应用不能用新身份直接重签" }
        val app = manifest.getElement("application") ?: error("Manifest缺少application")
        require(app.searchAttributeByName("isSplitRequired")?.valueAsBoolean != true) { "应用依赖分包，需要完整单APK" }
        require(app.searchAttributeByName("requiredSplitTypes")?.valueAsString.isNullOrEmpty()&&manifest.searchAttributeByName("requiredSplitTypes")?.valueAsString.isNullOrEmpty()){ "应用声明必需分包，需要完整单APK" }
        require(app.searchAttributeByName("enabled")?.valueAsBoolean != false) { "application已禁用" }
        val processChoice = resolveProviderProcess(app, requestedProviderProcess)
        app.getOrCreateAndroidAttribute("extractNativeLibs", 0x010104ea).setValueAsBoolean(true)
        app.getOrCreateAndroidAttribute("hasCode", 0x0101000c).setValueAsBoolean(true)
        // Secondary DEX loading and the official Gadget require Android 5+. Make the output honest.
        val sdk=manifest.getOrCreateElement("uses-sdk")
        val minimum=sdk.getOrCreateAndroidAttribute("minSdkVersion",0x0101020c)
        require(minimum.type in listOf(0.toByte(),0x10.toByte(),0x11.toByte())){"minSdkVersion必须为整数"}
        if(minimum.data<21){minimum.setType(0x10.toByte());minimum.data=21}
        if (manifest.listElements("uses-permission").none { it.searchAttributeByResourceId(0x01010003)?.valueAsString == "android.permission.INTERNET" }) {
            manifest.newElementAt(0, "uses-permission").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("android.permission.INTERNET")
        }
        // clinit 模式：**只**改 application@name 一个属性；原值回传，用于生成 .super 与诚实报告。
        val originalApplication = if (clinitClass == null) null else {
            val existing = app.searchAttributeByName("name")?.valueAsString?.trim().orEmpty()
            app.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(clinitClass)
            existing.ifEmpty { null }
        }
        app.newElement("provider").apply {
            getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(provider)
            if (processChoice.second != "default") getOrCreateAndroidAttribute("process", 0x01010011).setValueAsString(processChoice.first)
            getOrCreateAndroidAttribute("authorities", 0x01010018).setValueAsString("$packageName.aijs.gadget.$suffix")
            getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(false)
            getOrCreateAndroidAttribute("enabled", 0x0101000e).setValueAsBoolean(true)
            getOrCreateAndroidAttribute("initOrder", 0x0101001a).apply { setType(0x10.toByte()); data = Int.MAX_VALUE }
        }
        xml.refreshFull()
        return ManifestPatch(xml.bytes, packageName, processChoice.first, processChoice.second, originalApplication)
    }

    private fun resolveProviderProcess(app: ResXmlElement, requested: String?): Pair<String, String> {
        explicitProcess(requested)?.let { return it to "explicit" }
        val launcher = (app.listElements("activity") + app.listElements("activity-alias")).firstOrNull { isLauncherComponent(it) }
        val inherited = processOf(launcher) ?: processOf(app)
        if (inherited != null) return inherited to "manifest"
        return DEFAULT_PROVIDER_PROCESS to "default"
    }

    private fun isLauncherComponent(component: ResXmlElement): Boolean =
        component.listElements("intent-filter").any { filter ->
            filter.listElements("action").any { it.searchAttributeByName("name")?.valueAsString == "android.intent.action.MAIN" } &&
                filter.listElements("category").any { it.searchAttributeByName("name")?.valueAsString == "android.intent.category.LAUNCHER" }
        }

    private fun processOf(element: ResXmlElement?): String? {
        if (element == null) return null
        val raw = element.searchAttributeByName("process")?.valueAsString?.trim().orEmpty()
        return raw.takeIf { it.isNotEmpty() && isValidProcessName(it) }
    }

    private fun explicitProcess(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val process = value.trim()
        require(process.length <= 256 && !process.contains('\u0000') && isValidProcessName(process)) {
            "providerProcess 无效：需要应用进程名（包名、:私有进程或 包名:后缀），长度≤256"
        }
        return process
    }

    private fun isValidProcessName(value: String): Boolean =
        value.matches(Regex(":?[A-Za-z_][A-Za-z0-9_.]*(:[A-Za-z_][A-Za-z0-9_.]*)?"))

    private const val DEFAULT_PROVIDER_PROCESS = "application-default-process"

    private fun compileProvider(name: String, scratch: File): ByteArray {
        val type = "L${name.replace('.', '/')};"
        val source = File(scratch, "gadget-provider.smali")
        val output = File(scratch, "gadget-provider.dex")
        val text = """
.class public final $type
.super Landroid/content/ContentProvider;
.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroid/content/ContentProvider;-><init>()V
    return-void
.end method
.method public onCreate()Z
    .locals 3
    :start
    const-string v0, "$LIBRARY"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    :end
    const/4 v0, 0x1
    return v0
    .catch Ljava/lang/Throwable; {:start .. :end} :failed
    :failed
    move-exception v2
    const-string v0, "AIjsGadget"
    const-string v1, "Gadget failed to load; check ABI, configuration and port"
    invoke-static {v0, v1, v2}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I
    const/4 v0, 0x0
    return v0
.end method
.method public query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;
    .locals 1
    const/4 v0, 0x0
    return-object v0
.end method
.method public getType(Landroid/net/Uri;)Ljava/lang/String;
    .locals 1
    const/4 v0, 0x0
    return-object v0
.end method
.method public insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;
    .locals 1
    const/4 v0, 0x0
    return-object v0
.end method
.method public delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I
    .locals 1
    const/4 v0, 0x0
    return v0
.end method
.method public update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I
    .locals 1
    const/4 v0, 0x0
    return v0
.end method
""".trimIndent()
        source.writeText(text, Charsets.UTF_8)
        require(Smali.assemble(SmaliOptions().apply { outputDexFile = output.path; apiLevel = 21; jobs = 1 }, listOf(source.path))) { "Gadget加载器DEX编译失败" }
        return output.readBytes()
    }

    // ------------------------------------------------------------------ clinit 提前加载（P1-4 的另一半）

    /** clinit 模式生成的 Application 子类名（固定，便于真机/单测核对）。 */
    const val CLINIT_CLASS = "com.aijs.instrumentation.AijsApplication"

    /** clinit 模式要追加的类：dex 字节 + 父类（原 application@name，已展开为全限定名）。 */
    internal data class ClinitHook(val className: String, val originalApplication: String, val dex: ByteArray)

    /** 只读解析出的 application 关键字段。 */
    internal data class ManifestApplication(val packageName: String, val applicationName: String?, val appComponentFactory: String?)

    /** 只读 Manifest 关键字段；不改任何字节。 */
    internal fun readApplicationDetails(bytes: ByteArray): ManifestApplication {
        require(ToolkitResources.isBinaryXml(bytes)) { "输入APK的Manifest必须为二进制AXML" }
        val xml = ResXmlDocument().apply { readBytes(ByteArrayInputStream(bytes)) }
        val manifest = xml.documentElement ?: error("Manifest根节点不存在")
        require(manifest.name == "manifest") { "Manifest根节点无效" }
        val packageName = manifest.searchAttributeByName("package")?.valueAsString ?: error("Manifest缺少包名")
        val app = manifest.getElement("application") ?: error("Manifest缺少application")
        return ManifestApplication(packageName,
            app.searchAttributeByName("name")?.valueAsString?.trim()?.takeIf { it.isNotEmpty() },
            app.searchAttributeByName("appComponentFactory")?.valueAsString?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** Manifest 里的相对类名（.Foo）按包名展开。 */
    internal fun expandClassName(packageName: String, raw: String): String =
        if (raw.startsWith(".")) packageName + raw else raw

    /**
     * clinit 提前加载的**预检 + 生成**：只读输入 APK，校验父类可继承后用进程内 smali 汇编生成新 DEX。
     * 失败抛 ToolkitException(PRELOAD_CLINIT_*)，调用方据此**不产出任何 APK**。
     */
    internal fun prepareClinitHook(input: File, scratch: File, explicitTarget: String? = null): ClinitHook {
        ZipFile(input).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
                ?: throw ToolkitException("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", "clinit 需要完整单APK：缺少 AndroidManifest.xml", "clinitTarget")
            val manifest = zip.getInputStream(manifestEntry).use { ToolkitIo.read(it, 4 * 1024 * 1024) }
            val details = try {
                readApplicationDetails(manifest)
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                throw ToolkitException("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION",
                    "无法读取 application 信息：" + (failure.message ?: failure.javaClass.simpleName), "clinitTarget")
            }
            val raw = explicitTarget?.trim()?.takeIf { it.isNotEmpty() } ?: details.applicationName
            val original = raw?.let { expandClassName(details.packageName, it) } ?: "android.app.Application"
            if (details.appComponentFactory != null) {
                throw ToolkitException("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION",
                    "目标声明了 android:appComponentFactory=" + details.appComponentFactory + "：它可能自己实例化 Application，application@name 不可靠", "clinitTarget")
            }
            if (original == CLINIT_CLASS) {
                throw ToolkitException("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", "父类不能是本工具生成的类 " + CLINIT_CLASS, "clinitTarget")
            }
            if (!original.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))) {
                throw ToolkitException("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", "application@name 不是合法类名：" + original, "clinitTarget")
            }
            val originalType = "L" + original.replace('.', '/') + ";"
            val clinitType = "L" + CLINIT_CLASS.replace('.', '/') + ";"
            var superFound = original == "android.app.Application"
            var clinitPresent = false
            for (entry in zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }) {
                val dex = try {
                    ToolkitDex(zip.getInputStream(entry).use { ToolkitIo.read(it, ToolkitIo.MAX_ENTRY) })
                } catch (failure: Throwable) {
                    if (failure is VirtualMachineError) throw failure
                    throw ToolkitException("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION",
                        "无法解析 " + entry.name + "（可能被壳加密/损坏），不能确认父类可继承：" + (failure.message ?: failure.javaClass.simpleName), "clinitTarget")
                }
                for (definition in dex.classes) {
                    if (definition.type == clinitType) clinitPresent = true
                    if (definition.type == originalType) {
                        superFound = true
                        if (definition.accessFlags and 0x10 != 0) {
                            throw ToolkitException("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", "父类 " + original + " 是 final，无法继承", "clinitTarget")
                        }
                        if (definition.accessFlags and 0x200 != 0) {
                            throw ToolkitException("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", "父类 " + original + " 是接口，无法继承", "clinitTarget")
                        }
                    }
                }
            }
            if (!superFound) {
                throw ToolkitException("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION",
                    "在 APK 的 DEX 集里找不到 " + original + "（壳可能动态加载/加密），无法确认能否继承；可用 clinitTarget 显式指定", "clinitTarget")
            }
            if (clinitPresent) {
                throw ToolkitException("PRELOAD_CLINIT_UNSUPPORTED_APPLICATION", "APK 里已存在 " + CLINIT_CLASS + "，请从原 APK 重新生成", "clinitTarget")
            }
            return ClinitHook(CLINIT_CLASS, original, compileClinitApplication(original, scratch))
        }
    }

    /**
     * 生成 Application 子类：<init> 只调父构造；<clinit> 里 loadLibrary，整段 .catch Throwable 只 Log.e
     * （与 provider 模板同构：加载失败绝不崩启动）。进程内 smali 汇编，不改动任何既有 DEX。
     */
    private fun compileClinitApplication(superName: String, scratch: File): ByteArray {
        val type = "L" + CLINIT_CLASS.replace('.', '/') + ";"
        val superType = "L" + superName.replace('.', '/') + ";"
        val source = File(scratch, "aijs-clinit-application.smali")
        val output = File(scratch, "aijs-clinit-application.dex")
        val text = """
.class public $type
.super $superType
.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, $superType-><init>()V
    return-void
.end method
.method static constructor <clinit>()V
    .locals 3
    :start
    const-string v0, "$LIBRARY"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    :end
    return-void
    .catch Ljava/lang/Throwable; {:start .. :end} :failed
    :failed
    move-exception v1
    const-string v0, "AIjsGadget"
    const-string v2, "clinit preload failed; check ABI, configuration and port"
    invoke-static {v0, v2, v1}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I
    return-void
.end method
""".trimIndent()
        return try {
            source.writeText(text, Charsets.UTF_8)
            if (!Smali.assemble(SmaliOptions().apply { outputDexFile = output.path; apiLevel = 21; jobs = 1 }, listOf(source.path))) {
                throw ToolkitException("PRELOAD_CLINIT_DEX_ASSEMBLY_FAILED", "clinit dex 汇编失败（smali assemble 返回 false）", "clinitTarget")
            }
            output.readBytes()
        } finally {
            source.delete(); output.delete()
        }
    }
}
