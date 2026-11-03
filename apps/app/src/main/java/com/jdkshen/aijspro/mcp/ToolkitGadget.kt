package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Rootless APK instrumentation. Existing resource tables and DEX files are copied byte for byte. */
internal object ToolkitGadget {
    const val MARKER = "assets/aijs-gadget.json"
    const val LIBRARY = "aijsgadget"
    val ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    fun prepare(input: File, output: File, scratch: File, component: File, port: Int, defaultAbi: String): JsonObject {
        require(port in 1024..65535) { "Gadget port 必须为1024..65535" }
        require(!output.exists() && output.parentFile?.isDirectory == true) { "必须输出到父目录存在的新文件" }
        val token = ToolkitIo.hex(ByteArray(24).also { SecureRandom().nextBytes(it) }).lowercase()
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
            val patched = patchManifest(manifest, provider, suffix)
            val nativeAbis = entries.filter { it.name.matches(Regex("lib/[^/]+/[^/]+\\.so")) }.map { it.name.split('/')[1] }.toSet()
            require(nativeAbis.all { it in ABIS }) { "不支持的目标ABI：${nativeAbis - ABIS}" }
            val abis = nativeAbis.ifEmpty { setOf(defaultAbi) }
            require(abis.all { it in ABIS }) { "当前手机ABI不支持Gadget" }
            val libraries = abis.associateWith { abi -> File(component, "gadget/$abi/libfrida-gadget.so").also { require(it.isFile && it.length() in 1..ToolkitIo.MAX_ENTRY.toLong()) { "Gadget组件缺少${abi}库，请重装frida-gadget" } } }
            libraries.forEach { (abi, library) -> validateElf(library.inputStream().use { source -> ByteArray(65536).let { head -> val n=source.read(head); head.copyOf(n.coerceAtLeast(0)) } },abi) }
            require(expanded + libraries.values.sumOf { it.length() } < ToolkitIo.MAX_APK) { "加入Gadget后展开超过512MiB" }
            val dexNumbers = entries.mapNotNull { e -> Regex("classes([0-9]*)\\.dex").matchEntire(e.name)?.groupValues?.get(1)?.let { if (it.isEmpty()) 1 else it.toInt() } }
            val nextDex = (dexNumbers.maxOrNull() ?: 0) + 1
            require(nextDex <= 32) { "最多支持32个DEX" }
            val dexName = if (nextDex == 1) "classes.dex" else "classes$nextDex.dex"
            val dex = compileProvider(provider, scratch)
            info = ToolkitIo.json("schemaVersion" to 1, "mode" to "rootless-gadget", "requiresRoot" to false,
                "packageName" to patched.second, "providerClass" to provider, "providerProcess" to "application-default-process",
                "endpoint" to "127.0.0.1:$port", "token" to token, "onLoad" to "resume", "portConflict" to "fail",
                "fridaVersion" to "17.25.2", "abis" to abis.sorted(), "dexEntry" to dexName, "minimumRuntimeSdk" to 21,
                "originalSha256" to ToolkitIo.sha(input), "resourceTableRecompiled" to false)
            val config = ToolkitIo.json("interaction" to ToolkitIo.json("type" to "listen", "address" to "127.0.0.1", "port" to port,
                "on_port_conflict" to "fail", "on_load" to "resume", "token" to token)).toString().toByteArray(Charsets.UTF_8)
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
                    if (!entry.isDirectory) zip.getInputStream(entry).use { source ->
                        val buffer = ByteArray(65536)
                        while (true) { ToolkitIo.ensureActive(); val n = source.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
                    }
                    out.closeEntry()
                }
                add("AndroidManifest.xml", patched.first); add(dexName, dex); add(MARKER, info.toString().toByteArray(Charsets.UTF_8))
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
        app.newElement("provider").apply {
            getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(provider)
            getOrCreateAndroidAttribute("authorities", 0x01010018).setValueAsString("$packageName.aijs.gadget.$suffix")
            getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(false)
            getOrCreateAndroidAttribute("enabled", 0x0101000e).setValueAsBoolean(true)
            getOrCreateAndroidAttribute("initOrder", 0x0101001a).apply { setType(0x10.toByte()); data = Int.MAX_VALUE }
        }
        xml.refreshFull()
        return xml.bytes to packageName
    }

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
}
