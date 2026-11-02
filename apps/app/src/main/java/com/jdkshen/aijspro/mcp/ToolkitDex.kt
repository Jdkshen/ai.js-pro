package com.jdkshen.aijspro.mcp

import com.google.gson.*
import org.jf.baksmali.BaksmaliOptions
import org.jf.baksmali.Adaptors.ClassDefinition
import org.jf.baksmali.formatter.BaksmaliWriter
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.Field
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.reference.*
import org.jf.dexlib2.iface.value.*
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.*

internal class ToolkitDex(bytes: ByteArray) {
    val dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), ByteArrayInputStream(bytes))
    val classes: List<ClassDef> by lazy { dex.classes.sortedBy { it.type } }
    fun type(locator: String) = locator.substringAfter(':').substringBefore("->")
    fun find(locator: String): ClassDef = classes.firstOrNull { it.type == type(locator) }
        ?: throw IllegalArgumentException("DEX 类不存在：$locator")
    fun methodId(method: MethodReference) = method.definingClass + "->" + method.name + "(" + method.parameterTypes.joinToString("") + ")" + method.returnType
    fun fieldId(field: FieldReference) = field.definingClass + "->" + field.name + ":" + field.type
    fun renderClass(definition: ClassDef): String {
        val output = StringWriter(); val writer = BaksmaliWriter(output)
        ClassDefinition(BaksmaliOptions(), definition).writeTo(writer); writer.flush()
        require(output.buffer.length <= ToolkitIo.MAX_TEXT) { "Smali 类超过 10 MiB" }; return output.toString()
    }
    fun render(locator: String): String {
        val definition = find(locator); val text = renderClass(definition)
        return when {
            locator.startsWith("dex_class:") -> text
            locator.startsWith("dex_method:") -> memberText(text, locator.substringAfter("->"), true)
            locator.startsWith("dex_field:") -> memberText(text, locator.substringAfter("->"), false)
            else -> error("DEX 定位符无效")
        }
    }
    private fun memberText(source: String, descriptor: String, method: Boolean): String {
        val lines = source.split('\n'); val begin = lines.indexOfFirst { it.startsWith(if (method) ".method " else ".field ") && it.substringBefore(" = ").trim().endsWith(descriptor) }
        require(begin >= 0) { "DEX 成员不存在" }
        if (method) {
            val end = (begin + 1 until lines.size).firstOrNull { lines[it].trim() == ".end method" } ?: error("Smali 方法不完整")
            return lines.subList(begin, end + 1).joinToString("\n")
        }
        val next = (begin + 1 until lines.size).firstOrNull { lines[it].startsWith(".field ") || lines[it].startsWith(".method ") || lines[it].startsWith("# ") } ?: lines.size
        return lines.subList(begin, next).joinToString("\n").trimEnd()
    }
    fun outline(locator: String): List<JsonObject> {
        val c = find(locator)
        return listOf(ToolkitIo.json("kind" to "class", "locator" to "dex_class:${c.type}", "type" to c.type,
            "superclass" to c.superclass, "interfaces" to c.interfaces, "accessFlags" to c.accessFlags)) +
            c.fields.map { ToolkitIo.json("kind" to "field", "locator" to "dex_field:${fieldId(it)}", "name" to it.name, "type" to it.type, "accessFlags" to it.accessFlags) } +
            c.methods.map { ToolkitIo.json("kind" to "method", "locator" to "dex_method:${methodId(it)}", "name" to it.name, "parameters" to it.parameterTypes, "returns" to it.returnType, "accessFlags" to it.accessFlags) }
    }
    fun references(locator: String, resolution: String, max: Int, universe: List<ClassDef> = classes): List<JsonObject> {
        require(resolution in listOf("exact", "slot", "dispatch", "not_applicable")) { "方法解析模式无效" }
        val target = locator.substringAfter(':'); val targetClass = target.substringBefore("->")
        val isMethod = locator.startsWith("dex_method:"); val isField = locator.startsWith("dex_field:")
        require((isMethod && resolution != "not_applicable") || (!isMethod && resolution == "not_applicable")) { "定位符与方法解析模式不匹配" }
        val result = ArrayList<JsonObject>(); val hierarchy = universe.associateBy { it.type }
        val targetMethod = if (isMethod) hierarchy[targetClass]?.methods?.firstOrNull { methodId(it) == target } else null
        val virtual = targetMethod == null || targetMethod.accessFlags and (0x2 or 0x8) == 0 && targetMethod.name !in listOf("<init>", "<clinit>")
        fun subtype(child: String, parent: String, seen: MutableSet<String> = HashSet()): Boolean {
            if (child == parent) return true
            if (!seen.add(child)) return false
            val c = hierarchy[child] ?: return false
            return (c.superclass?.let { subtype(it, parent, seen) } == true) || c.interfaces.any { subtype(it, parent, seen) }
        }
        loop@ for (c in classes) for (method in c.methods) {
            var offset = 0
            for (instruction in method.implementation?.instructions ?: emptyList()) {
                val reference = (instruction as? ReferenceInstruction)?.reference
                val name = when(reference) {
                    is MethodReference -> methodId(reference)
                    is FieldReference -> fieldId(reference)
                    is TypeReference -> reference.type
                    else -> null
                }
                val opcode = instruction.opcode.name.uppercase()
                val virtualCall = opcode.contains("VIRTUAL") || opcode.contains("INTERFACE") || opcode.contains("SUPER")
                val match = when {
                    name == null -> false
                    isMethod && reference is MethodReference -> name == target || (virtual && virtualCall && resolution in listOf("slot", "dispatch") &&
                        name.substringAfter("->") == target.substringAfter("->") && (subtype(reference.definingClass, targetClass) ||
                        (resolution == "dispatch" && subtype(targetClass, reference.definingClass))))
                    isField && reference is FieldReference -> name == target
                    !isMethod && !isField -> name == targetClass || name.startsWith("$targetClass->")
                    else -> false
                }
                if (match) result.add(ToolkitIo.json("locator" to "dex_method:${methodId(method)}", "codeUnitOffset" to offset,
                    "opcode" to instruction.opcode.name, "reference" to name, "resolution" to resolution))
                offset += instruction.codeUnits
                if (result.size >= max) break@loop
            }
        }; return result
    }
    fun strings(max: Int): List<JsonObject> {
        val result = ArrayList<JsonObject>()
        fun add(locator: String, value: EncodedValue?) {
            if (result.size >= max) return
            when (value) {
                is StringEncodedValue -> result.add(ToolkitIo.json("locator" to locator, "value" to value.value))
                is ArrayEncodedValue -> value.value.forEach { add(locator, it) }
                is AnnotationEncodedValue -> value.elements.forEach { add(locator, it.value) }
            }
        }
        loop@ for (c in classes) {
            c.fields.forEach { field -> add("dex_field:${fieldId(field)}", field.initialValue) }
            c.annotations.forEach { annotation -> annotation.elements.forEach { add("dex_class:${c.type}", it.value) } }
            for (method in c.methods) {
                method.annotations.forEach { annotation -> annotation.elements.forEach { add("dex_method:${methodId(method)}", it.value) } }
                for (instruction in method.implementation?.instructions ?: emptyList()) {
                    val ref = (instruction as? ReferenceInstruction)?.reference as? StringReference ?: continue
                    result.add(ToolkitIo.json("locator" to "dex_method:${methodId(method)}", "value" to ref.string))
                    if (result.size >= max) break@loop
                }
            }
            if (result.size >= max) break
        }
        return result.take(max)
    }
    fun resourceXref(id: Int, max: Int): List<JsonObject> {
        val result = ArrayList<JsonObject>()
        loop@ for (c in classes) {
            for (field in c.fields) {
                if ((field.initialValue as? IntEncodedValue)?.value == id)
                    result.add(ToolkitIo.json("locator" to "dex_field:${fieldId(field)}", "kind" to "field_constant"))
                if (result.size >= max) break@loop
            }
            for (method in c.methods) for (instruction in method.implementation?.instructions ?: emptyList()) {
                if ((instruction as? WideLiteralInstruction)?.wideLiteral?.toInt() == id)
                    result.add(ToolkitIo.json("locator" to "dex_method:${methodId(method)}", "opcode" to instruction.opcode.name))
                if (result.size >= max) break@loop
            }
        }; return result
    }
    fun update(locator: String, replacement: String?, scratch: File, allowCreate: Boolean = false): ByteArray {
        require(locator.startsWith("dex_class:") || locator.startsWith("dex_method:")) { "DEX 字段只读；编辑请使用 dex_class 或 dex_method" }
        val owner = type(locator)
        require(owner.matches(Regex("L[^;\\[.]+;"))) { "类描述符无效" }
        val old = classes.firstOrNull { it.type == owner }
        require(old != null || allowCreate && locator.startsWith("dex_class:") && replacement != null) { "DEX 类不存在" }
        val rendered = old?.let { renderClass(it) }.orEmpty()
        val newText = if (locator.startsWith("dex_class:")) replacement else {
            val before = render(locator); require(rendered.contains(before))
            rendered.replaceRange(rendered.indexOf(before), rendered.indexOf(before) + before.length, replacement ?: "")
        }
        val changed: ClassDef? = newText?.let {
            val source = File(scratch, "${ToolkitIo.fresh()}.smali"); val output = File(scratch, "${ToolkitIo.fresh()}.dex")
            try {
                source.writeText(it, Charsets.UTF_8)
                val options = SmaliOptions().apply { outputDexFile = output.path; apiLevel = dex.opcodes.api; jobs = 1 }
                require(Smali.assemble(options, listOf(source.path))) { "Smali 编译失败，原会话未修改" }
                val parsed = ToolkitDex(output.readBytes()).classes.single()
                require(parsed.type == owner) { "编辑不允许改变类描述符" }
                if (locator.startsWith("dex_method:")) {
                    val target = locator.substringAfter(':')
                    require(parsed.methods.count { methodId(it) == target } == if (replacement == null) 0 else 1) { "方法名、参数与返回类型必须与定位符完全一致" }
                    require(old!!.methods.filter { methodId(it) != target }.map { methodId(it) }.toSet() ==
                        parsed.methods.filter { methodId(it) != target }.map { methodId(it) }.toSet()) { "局部方法编辑不能增删其他方法" }
                    require(old.fields.map { fieldId(it) }.toSet() == parsed.fields.map { fieldId(it) }.toSet()) { "局部方法编辑不能增删字段" }
                }
                ImmutableClassDef.of(parsed)
            } finally { source.delete(); output.delete() }
        }
        val pool = DexPool(dex.opcodes)
        classes.forEach { if (it.type != owner) pool.internClass(it) }
        if (changed != null) pool.internClass(changed)
        val output = MemoryDataStore()
        return try { pool.writeTo(output); output.data } finally { output.close() }
    }
    companion object {
        fun empty(): ToolkitDex {
            val output = MemoryDataStore()
            return try { DexPool(Opcodes.getDefault()).writeTo(output); ToolkitDex(output.data) } finally { output.close() }
        }
        fun ownerPrefix(value: String): String {
            val prefix = value.substringAfter(':', value)
            if (prefix.isEmpty()) return ""
            return if (prefix.startsWith('L')) prefix.replace('.', '/') else "L" + prefix.replace('.', '/')
        }
    }
}
