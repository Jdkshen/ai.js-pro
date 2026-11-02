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
    fun references(locator: String, resolution: String, max: Int): List<JsonObject> {
        require(resolution in listOf("exact", "slot", "dispatch", "not_applicable")) { "方法解析模式无效" }
        val target = locator.substringAfter(':'); val targetClass = target.substringBefore("->")
        val isMethod = locator.startsWith("dex_method:"); val isField = locator.startsWith("dex_field:")
        val result = ArrayList<JsonObject>()
        fun subtype(child: String, parent: String, seen: MutableSet<String> = HashSet()): Boolean {
            if (child == parent) return true
            if (!seen.add(child)) return false
            val c = classes.firstOrNull { it.type == child } ?: return false
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
                val match = when {
                    name == null -> false
                    isMethod && reference is MethodReference -> name == target || (resolution in listOf("slot", "dispatch") &&
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
        loop@ for (c in classes) for (method in c.methods) for (instruction in method.implementation?.instructions ?: emptyList()) {
            val ref = (instruction as? ReferenceInstruction)?.reference as? StringReference ?: continue
            result.add(ToolkitIo.json("locator" to "dex_method:${methodId(method)}", "value" to ref.string))
            if (result.size >= max) break@loop
        }; return result
    }
    fun resourceXref(id: Int, max: Int): List<JsonObject> {
        val result = ArrayList<JsonObject>()
        loop@ for (c in classes) for (method in c.methods) for (instruction in method.implementation?.instructions ?: emptyList()) {
            if ((instruction as? WideLiteralInstruction)?.wideLiteral?.toInt() == id)
                result.add(ToolkitIo.json("locator" to "dex_method:${methodId(method)}", "opcode" to instruction.opcode.name))
            if (result.size >= max) break@loop
        }; return result
    }
    fun update(locator: String, replacement: String?, scratch: File): ByteArray {
        val old = find(locator)
        val rendered = renderClass(old)
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
                require(parsed.type == old.type) { "编辑不允许改变类描述符" }; ImmutableClassDef.of(parsed)
            } finally { source.delete(); output.delete() }
        }
        val pool = DexPool(dex.opcodes)
        classes.forEach { if (it.type != old.type) pool.internClass(it) else if (changed != null) pool.internClass(changed) }
        val output = MemoryDataStore()
        return try { pool.writeTo(output); output.data } finally { output.close() }
    }
}
