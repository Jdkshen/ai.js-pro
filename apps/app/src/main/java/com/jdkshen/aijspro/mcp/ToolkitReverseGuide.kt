package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.security.MessageDigest

/**
 * Pure JVM reader for the reverse-MCP guide and machine workflows.
 *
 * The two assets are the source of truth: this class only parses, pages, validates
 * tool references and expands a plan. It never calls a tool, starts a job downloads
 * a component or executes target code.
 */
class ToolkitReverseGuide(
    private val guideText: String,
    workflows: JsonObject,
    private val availableTools: Set<String>,
) {
    private data class Section(val id: String, val title: String, val content: String)
    private data class Variable(val name: String, val required: Boolean, val example: String, val description: String)
    private data class Workflow(
        val goal: String, val id: String, val title: String, val summary: String,
        val read: List<String>, val variables: JsonArray, val components: JsonArray,
        val steps: JsonArray, val limitations: JsonArray,
    )

    private val workflowsJson: JsonObject = workflows.deepCopy()
    private val guideVersion: String = readGuideVersion()
    private val workflowSchemaVersion: Int = versionNumber()
    private val guideBytes: ByteArray = guideText.toByteArray(Charsets.UTF_8)
    private val guideHashHex: String = sha256(guideBytes)
    private val placeholderRegex = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
    private val sections: List<Section>
    private val sectionById: Map<String, Section>
    private val workflowList: List<Workflow>
    private val workflowByGoal: Map<String, Workflow>
    private val candidateTools: List<String>
    private val allToolRefs: Map<String, Set<String>>

    init {
        require(workflowSchemaVersion == 1) { "reverse-workflows.json schemaVersion 只支持 1" }
        val parsedSections = parseSections(guideText)
        sections = parsedSections
        sectionById = parsedSections.associateBy { it.id }
        require(sectionById.size == sections.size) { "指南章节 id 重复：${sections.groupBy { it.id }.filterValues { it.size > 1 }.keys}" }
        workflowList = parseWorkflows(workflowsJson)
        workflowByGoal = workflowList.associateBy { it.goal }
        require(workflowByGoal.size == workflowList.size) { "工作流 goal 重复" }
        workflowList.forEach { parseVariables(it.variables) }
        candidateTools = stringList(workflowsJson.get("candidateTools"), "candidateTools")
        require(candidateTools.toSet().size == candidateTools.size) { "candidateTools 有重复项" }
        allToolRefs = collectToolRefs()
    }

    /** Dispatches topic=index|all|<section-id>, with UTF-16-safe paging. */
    fun read(args: JsonObject): JsonObject {
        allowed(args, setOf("topic", "offset", "maxChars"))
        val topic = string(args, "topic").trim().ifEmpty { "index" }
        val offset = integer(args, "offset", 0, 0, Int.MAX_VALUE)
        val maxChars = integer(args, "maxChars", 6000, 1, 24000)
        val text = when (topic) {
            "index" -> indexText()
            "all" -> guideText
            else -> sectionById[topic]?.content
                ?: throw IllegalArgumentException("topic 不存在；可用：index、all、${sections.joinToString("、") { it.id }}")
        }
        require(offset <= text.length) { "offset 超出内容范围（contentLength=${text.length}）" }
        require(offset == 0 || offset == text.length || !Character.isLowSurrogate(text[offset]) ||
            !Character.isHighSurrogate(text[offset - 1])) { "offset 不能切开 UTF-16 代理对；请使用 nextOffset" }
        val end = endAtBoundary(text, offset, minOf(text.length.toLong(), offset.toLong() + maxChars).toInt())
        return base(topic).apply {
            addProperty("selectedSection", topic)
            addProperty("contentFormat", "markdown")
            addProperty("content", text.substring(offset, end))
            addProperty("contentLength", text.length)
            addProperty("offset", offset)
            addProperty("nextOffset", end)
            addProperty("hasMore", end < text.length)
            addProperty("truncated", offset > 0 || end < text.length)
            addProperty("hint", "分页用返回的 nextOffset；机读步骤按 goal 规划，缺失工具会标 unsupported。")
        }
    }

    /** Expands one goal into a non-executing plan and validates every tool reference. */
    fun plan(args: JsonObject): JsonObject {
        allowed(args, setOf("goal"))
        val goal = string(args, "goal").trim()
        require(goal.isNotEmpty()) { "goal 必填；可用：${workflowList.joinToString("、") { it.goal }}" }
        val workflow = workflowByGoal[goal]
            ?: throw IllegalArgumentException("goal 不存在；可用：${workflowList.joinToString("、") { it.goal }}")
        val variables = parseVariables(workflow.variables)
        val declared = variables.map { it.name }.toSet()
        val unknownVariables = linkedSetOf<String>()
        val references = linkedMapOf<String, LinkedHashSet<String>>()
        fun reference(tool: String, source: String) {
            if (tool.isNotBlank()) references.getOrPut(tool) { linkedSetOf() }.add(source)
        }
        var requiredBlocked = false
        var componentCheckRequired = false
        val unsupportedSteps = JsonArray()
        val optionalUnsupportedSteps = JsonArray()
        val unsupportedComponents = JsonArray()
        val stepResults = JsonArray()
        for ((index, element) in workflow.steps.withIndex()) {
            val step = element.asJsonObject
            val id = string(step, "id").ifEmpty { "step-${index + 1}" }
            val tool = toolName(step, "tool", "step:$id")
            val required = bool(step, "required", true)
            val usable = tool.isBlank() || tool in availableTools
            val placeholders = placeholdersIn(step.get("args"))
            unknownVariables += placeholders.filterNot { it in declared }
            reference(tool, "step:$id")
            val value = copy(step)
            value.addProperty("index", index + 1)
            value.addProperty("required", required)
            value.addProperty("available", usable)
            value.addProperty("supported", usable)
            value.add("unresolvedVariables", strings(placeholders))
            value.addProperty("status", when {
                tool.isBlank() -> "manual"
                usable -> "ready"
                required -> "unsupported"
                else -> "unsupported_optional"
            })
            if (!usable) {
                value.addProperty("reason", "工具未注册：$tool；仅作候选，不能执行，也不要用相似工具顶替")
                if (required) {
                    requiredBlocked = true
                    unsupportedSteps.add(id)
                } else optionalUnsupportedSteps.add(id)
            }
            stepResults.add(value)
        }

        val componentResults = JsonArray()
        for (element in workflow.components) {
            val component = element.asJsonObject
            val id = string(component, "id")
            require(id.isNotBlank()) { "requiredComponents[].id 不能为空" }
            val required = bool(component, "required", true)
            val checkTool = toolName(component, "checkTool", "component:$id")
            val installTool = toolName(component, "installTool", "component:$id")
            val installArgumentSupported = bool(component, "installArgumentSupported", false)
            reference(checkTool, "component:$id.check")
            reference(installTool, "component:$id.install")
            val checkUsable = checkTool.isBlank() || checkTool in availableTools
            val installUsable = installTool.isBlank() || (installTool in availableTools && installArgumentSupported)
            val hasPath = checkTool.isNotBlank() || installTool.isNotBlank()
            val status = when {
                !required -> if (hasPath && checkUsable && installUsable) "optional_check" else "optional_unsupported"
                !hasPath || !checkUsable || !installUsable -> "unsupported"
                else -> "check_required"
            }
            if (status == "check_required") componentCheckRequired = true
            if (required && status == "unsupported") {
                requiredBlocked = true
                unsupportedComponents.add(id)
            }
            val value = copy(component)
            value.addProperty("required", required)
            value.addProperty("status", status)
            value.addProperty("checkedByPlan", false)
            value.addProperty("checkSupported", checkTool.isNotBlank() && checkTool in availableTools)
            value.addProperty("installToolAvailable", installTool.isNotBlank() && installTool in availableTools)
            value.addProperty("installSupported", installTool.isNotBlank() && installTool in availableTools && installArgumentSupported)
            if (status == "unsupported") value.addProperty("reason", "组件检查/安装路径未注册或 component enum 不支持；不要伪称已安装")
            componentResults.add(value)
        }

        val readResults = JsonArray()
        val missingReadSections = JsonArray()
        for (id in workflow.read) {
            val found = sectionById[id]
            if (found == null) missingReadSections.add(id)
            readResults.add(JsonObject().apply {
                addProperty("id", id)
                addProperty("found", found != null)
                addProperty("title", found?.title ?: "")
            })
        }

        val readiness = when {
            requiredBlocked -> "blocked"
            componentCheckRequired -> "ready_with_component_check"
            else -> "ready"
        }
        return base("plan").apply {
            addProperty("goal", goal)
            addProperty("workflowId", workflow.id)
            addProperty("title", workflow.title)
            addProperty("summary", workflow.summary)
            add("readSections", readResults)
            add("steps", stepResults)
            add("requiredComponents", componentResults)
            add("variables", variablesJson(variables, workflow.steps))
            add("limitations", workflow.limitations.deepCopy())
            addProperty("planOnly", true)
            addProperty("executed", false)
            addProperty("readiness", readiness)
            addProperty("templateRequiresReplacement", true)
            addProperty("templatePolicy", "所有 \${...} 占位符必须替换为真实值；未替换的模板不是合法参数")
            add("unknownVariables", strings(unknownVariables))
            add("unsupportedTools", strings(references.keys.filter { it !in availableTools }))
            add("unsupportedSteps", unsupportedSteps)
            add("optionalUnsupportedSteps", optionalUnsupportedSteps)
            add("unsupportedComponents", unsupportedComponents)
            add("missingReadSections", missingReadSections)
            add("toolReferences", toolReferences(references))
            addProperty("nextAction", "把 variables[].example 换成真实值后按 steps 顺序调用；unsupported 项不要伪造，先集成或换已验证路径")
        }
    }

    private fun base(topic: String) = JsonObject().apply {
        addProperty("guideSha256", "sha256:$guideHashHex")
        addProperty("guideSha256Hex", guideHashHex)
        addProperty("guideVersion", guideVersion)
        addProperty("guideSchemaVersion", 1)
        addProperty("workflowSchemaVersion", workflowSchemaVersion)
        addProperty("guideChars", guideText.length)
        addProperty("guideBytes", guideBytes.size)
        addProperty("topic", topic)
        addProperty("availableToolCount", availableTools.size)
        add("sections", sectionsJson())
        add("sectionIds", strings(sections.map { it.id }))
        add("workflows", workflowSummaries())
        add("candidateTools", candidateToolStatuses())
        addProperty("candidateToolCount", candidateTools.size)
        addProperty("pendingCandidateTools", candidateTools.count { it !in availableTools })
    }

    private fun indexText(): String = buildString {
        append("# 逆向 MCP 指南索引\n\n")
        append("- guideVersion: `").append(guideVersion).append("`\n")
        append("- sections: ").append(sections.size).append("\n")
        append("- workflows: ").append(workflowList.size).append("\n")
        append("- availableTools: ").append(availableTools.size).append("\n\n")
        append("## 章节\n\n")
        sections.forEach { append("- `").append(it.id).append("` — ").append(it.title)
            .append(" (").append(it.content.length).append(" chars)\n") }
        append("\n## 工作流（reverse_plan goal）\n\n")
        workflowList.forEach { append("- `").append(it.goal).append("` — ").append(it.title).append("\n") }
        val missing = candidateTools.filter { it !in availableTools }
        if (missing.isNotEmpty()) {
            append("\n## 候选但未注册（计划会标 unsupported）\n\n")
            missing.forEach { append("- `").append(it).append("`\n") }
        }
        append("\n用法：`topic=index|all|<section-id>`；分页用 `nextOffset`；计划只读，不执行。\n")
    }

    private fun sectionsJson(): JsonArray = JsonArray().apply {
        sections.forEach { section ->
            add(JsonObject().apply {
                addProperty("id", section.id)
                addProperty("title", section.title)
                addProperty("contentLength", section.content.length)
            })
        }
    }

    private fun workflowSummaries(): JsonArray = JsonArray().apply {
        workflowList.forEach { workflow ->
            add(JsonObject().apply {
                addProperty("goal", workflow.goal)
                addProperty("id", workflow.id)
                addProperty("title", workflow.title)
                addProperty("summary", workflow.summary)
                add("readSections", strings(workflow.read))
                add("missingRequiredTools", strings(missingRequiredTools(workflow)))
            })
        }
    }

    private fun missingRequiredTools(workflow: Workflow): List<String> = workflow.steps.mapNotNull { element ->
        val step = element.asJsonObject
        val tool = toolName(step, "tool", "step")
        if (bool(step, "required", true) && tool.isNotBlank() && tool !in availableTools) tool else null
    }.distinct()

    private fun candidateToolStatuses(): JsonArray = JsonArray().apply {
        candidateTools.forEach { tool ->
            add(JsonObject().apply {
                addProperty("tool", tool)
                addProperty("available", tool in availableTools)
                addProperty("supported", tool in availableTools)
                add("usedBy", strings(allToolRefs[tool] ?: emptySet()))
            })
        }
    }

    private fun toolReferences(references: Map<String, Set<String>>): JsonArray = JsonArray().apply {
        references.toSortedMap().forEach { (tool, usedBy) ->
            add(JsonObject().apply {
                addProperty("tool", tool)
                addProperty("available", tool in availableTools)
                addProperty("supported", tool in availableTools)
                add("usedBy", strings(usedBy))
            })
        }
    }

    private fun variablesJson(variables: List<Variable>, steps: JsonArray): JsonArray = JsonArray().apply {
        variables.forEach { variable ->
            add(JsonObject().apply {
                addProperty("name", variable.name)
                addProperty("required", variable.required)
                addProperty("example", variable.example)
                addProperty("description", variable.description)
                addProperty("placeholder", "\${${variable.name}}")
                addProperty("replaceBeforeUse", true)
                addProperty("usedInStepCount", steps.count { placeholdersIn(it.asJsonObject.get("args")).contains(variable.name) })
            })
        }
    }

    private fun collectToolRefs(): Map<String, Set<String>> {
        val references = linkedMapOf<String, LinkedHashSet<String>>()
        fun add(tool: String, source: String) {
            if (tool.isNotBlank()) references.getOrPut(tool) { linkedSetOf() }.add(source)
        }
        candidateTools.forEach { add(it, "candidate") }
        workflowList.forEach { workflow ->
            for (element in workflow.steps) {
                val step = element.asJsonObject
                val id = string(step, "id").ifEmpty { "step" }
                add(toolName(step, "tool", "step:$id"), "workflow:${workflow.goal}:step:$id")
            }
            for (element in workflow.components) {
                val component = element.asJsonObject
                val id = string(component, "id").ifEmpty { "component" }
                add(toolName(component, "checkTool", "component:$id"), "workflow:${workflow.goal}:component:$id.check")
                add(toolName(component, "installTool", "component:$id"), "workflow:${workflow.goal}:component:$id.install")
            }
        }
        return references.mapValues { it.value.toSet() }
    }

    private fun parseSections(text: String): List<Section> {
        val marker = Regex("""^<!--\s*section:id=([a-z][a-z0-9-]{0,39})\s*-->$""")
        val result = ArrayList<Section>()
        var position = 0
        var start = 0
        var id: String? = null
        fun addSection(sectionId: String, content: String) {
            val title = Regex("""(?m)^#{1,6}\s+(.+?)\s*$""").find(content)?.groupValues?.get(1)?.trim()?.take(160)
            result.add(Section(sectionId, title?.ifEmpty { null } ?: sectionId, content))
        }
        for (line in text.split('\n')) {
            val lineStart = position
            val lineEnd = position + line.length
            val clean = line.trimEnd('\r')
            val match = marker.matchEntire(clean)
            if (match != null) {
                if (id != null) addSection(id, text.substring(start, lineStart))
                else if (text.substring(0, lineStart).isNotBlank()) addSection("overview", text.substring(0, lineStart))
                id = match.groupValues[1]
                start = minOf(lineEnd + 1, text.length)
            }
            position = lineEnd + 1
        }
        if (id != null) addSection(id, text.substring(start))
        else result.add(Section("guide", firstHeading(text) ?: "guide", text))
        return result
    }

    private fun firstHeading(text: String): String? =
        Regex("""(?m)^#{1,6}\s+(.+?)\s*$""").find(text)?.groupValues?.get(1)?.trim()?.take(160)

    private fun parseWorkflows(root: JsonObject): List<Workflow> {
        val array = root.get("workflows")
        require(array != null && array.isJsonArray) { "reverse-workflows.json 缺少 workflows 数组" }
        val workflows = array.asJsonArray
        require(workflows.size() in 1..32) { "workflows 数量必须为 1..32" }
        return workflows.mapIndexed { index, element ->
            require(element.isJsonObject) { "workflows[$index] 必须是对象" }
            val value = element.asJsonObject
            val goal = requiredString(value, "goal", "workflows[$index]")
            val id = string(value, "id").ifEmpty { goal }
            val title = requiredString(value, "title", "workflow:$goal")
            val summary = string(value, "summary").ifEmpty { title }
            Workflow(goal, id, title, summary, stringList(value.get("read"), "workflow:$goal.read"),
                array(value, "variables", "workflow:$goal.variables"),
                array(value, "requiredComponents", "workflow:$goal.requiredComponents"),
                array(value, "steps", "workflow:$goal.steps"),
                array(value, "limitations", "workflow:$goal.limitations"))
        }
    }

    private fun parseVariables(array: JsonArray): List<Variable> {
        val names = linkedSetOf<String>()
        return array.mapIndexed { index, element ->
            require(element.isJsonObject) { "variables[$index] 必须是对象" }
            val value = element.asJsonObject
            val name = requiredString(value, "name", "variables[$index]")
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "变量名不合法：$name" }
            require(names.add(name)) { "变量名重复：$name" }
            Variable(name, bool(value, "required", true), string(value, "example"), string(value, "description"))
        }
    }

    private fun placeholdersIn(element: JsonElement?): Set<String> {
        val result = linkedSetOf<String>()
        fun walk(value: JsonElement) {
            when {
                value.isJsonObject -> value.asJsonObject.entrySet().forEach { walk(it.value) }
                value.isJsonArray -> value.asJsonArray.forEach { walk(it) }
                value.isJsonPrimitive && value.asJsonPrimitive.isString ->
                    placeholderRegex.findAll(value.asString).forEach { result.add(it.groupValues[1]) }
            }
        }
        element?.let { walk(it) }
        return result
    }

    private fun allowed(args: JsonObject, names: Set<String>) {
        require(args.keySet().all { it in names }) { "未知参数：${args.keySet() - names}" }
    }

    private fun endAtBoundary(text: String, start: Int, initial: Int): Int {
        var end = initial
        if (end in (start + 1) until text.length && Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])) end--
        if (end <= start && start < text.length) end = minOf(text.length, start + 2)
        return end
    }

    private fun readGuideVersion(): String {
        val value = workflowsJson.get("guideVersion")
        if (value != null && !value.isJsonNull) {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "guideVersion 必须是字符串" }
            return value.asString.ifBlank { "1" }
        }
        return Regex("""<!--\s*guide-version:\s*([^\s]+)\s*-->""").find(guideText)?.groupValues?.get(1) ?: "1"
    }

    private fun versionNumber(): Int {
        val value = workflowsJson.get("schemaVersion") ?: return 1
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "schemaVersion 必须是整数" }
        return value.asInt
    }

    private fun copy(value: JsonObject): JsonObject = JsonObject().apply {
        value.entrySet().forEach { (name, element) -> add(name, element.deepCopy()) }
    }

    private fun array(value: JsonObject, name: String, where: String): JsonArray {
        val element = value.get(name) ?: return JsonArray()
        require(element.isJsonArray) { "$where 必须是数组" }
        return element.asJsonArray
    }

    private fun strings(values: Collection<String>): JsonArray = JsonArray().apply { values.forEach { add(it) } }

    private fun stringList(element: JsonElement?, where: String): List<String> {
        if (element == null || element.isJsonNull) return emptyList()
        require(element.isJsonArray) { "$where 必须是字符串数组" }
        return element.asJsonArray.mapIndexed { index, value ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$where[$index] 必须是字符串" }
            value.asString
        }
    }

    private fun requiredString(value: JsonObject, name: String, where: String): String =
        string(value, name).trim().also { require(it.isNotEmpty()) { "$where.$name 不能为空" } }

    private fun string(value: JsonObject, name: String): String {
        val element = value.get(name) ?: return ""
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "$name 必须是字符串" }
        return element.asString
    }

    private fun bool(value: JsonObject, name: String, fallback: Boolean): Boolean {
        val element = value.get(name) ?: return fallback
        require(element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) { "$name 必须是 JSON 布尔值" }
        return element.asBoolean
    }

    private fun toolName(value: JsonObject, name: String, where: String): String {
        val element = value.get(name) ?: return ""
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "$where.$name 必须是字符串" }
        return element.asString.trim()
    }

    private fun integer(args: JsonObject, name: String, fallback: Int, min: Int, max: Int): Int {
        val element = args.get(name) ?: return fallback
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$name 必须是 JSON 整数" }
        val number = try {
            element.asBigDecimal.intValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("$name 必须是范围内的整数")
        }
        require(number in min..max) { "$name 范围为 $min..$max" }
        return number
    }

    companion object {
        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
