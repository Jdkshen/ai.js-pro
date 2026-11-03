package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolkitDexNavigationTest {
    @get:Rule val temp = TemporaryFolder()

    private fun args(vararg pairs: Pair<String, Any?>) = ToolkitIo.json(*pairs)

    private fun JsonObject.items() = getAsJsonArray("items").map { it.asJsonObject }
    private fun JsonObject.nodes() = getAsJsonArray("nodes").map { it.asJsonObject }
    private fun JsonObject.edges() = getAsJsonArray("edges").map { it.asJsonObject }
    private fun JsonObject.array(name: String) = getAsJsonArray(name).map { it.asJsonObject }
    private fun JsonObject.summary() = getAsJsonObject("summary")

    @Test
    fun searchMembersAnchorsBothOverloadsAndInstructionHits() {
        val dex = ToolkitDexFixtures.assemble(temp.root, "overloads", OVERLOADS)
        val nav = ToolkitDexFixtures.nav("classes.dex" to dex)

        val names = nav.searchMembers(args("query" to "run", "scope" to "name"))
        val hits = names.items()
        assertEquals(2, hits.size)
        assertEquals(setOf("dex_method:Lfixture/Overloads;->run()V", "dex_method:Lfixture/Overloads;->run(I)V"),
            hits.map { it.tString("locator") }.toSet())
        hits.forEach { hit ->
            assertEquals(hit.tString("locator"), hit.tString("memberLocator"))
            assertTrue(hit.tString("uniqueLocator").startsWith("dex_entry:classes.dex!"))
            val range = hit.getAsJsonObject("lineRange")
            assertTrue(range.get("start").asInt in 1..range.get("end").asInt)
            assertEquals(hit.tString("locator"), hit.tString("memberLocator"))
            assertFalse("a name hit has no instruction offset", hit.has("codeUnitOffset"))
        }

        val strings = nav.searchMembers(args("query" to "second-needle", "scope" to "string",
            "includeMatchOffsets" to true))
        val stringHit = strings.items().single()
        assertEquals("string_literal", stringHit.tString("kind"))
        assertEquals("second-needle", stringHit.tString("value"))
        assertEquals("dex_method:Lfixture/Overloads;->run(I)V", stringHit.tString("memberLocator"))
        assertEquals(0, stringHit.get("codeUnitOffset").asInt)
        assertEquals("instruction", stringHit.tString("anchorKind"))
        val methodRange = stringHit.getAsJsonObject("lineRange")
        val instructionLine = stringHit.get("instructionLine").asInt
        assertTrue(instructionLine in methodRange.get("start").asInt..methodRange.get("end").asInt)
        assertTrue(stringHit.getAsJsonObject("matchOffsets").get("start").asInt >= 0)
    }

    @Test
    fun multiDexDuplicateClassesKeepDistinctIdentitiesAndRequireDisambiguation() {
        val first = ToolkitDexFixtures.assemble(temp.root, "dup1", duplicateClass(1))
        val second = ToolkitDexFixtures.assemble(temp.root, "dup2", duplicateClass(2))
        val nav = ToolkitDexFixtures.nav("classes.dex" to first, "classes2.dex" to second)
        val locator = "dex_method:Lfixture/Dup;->value()I"

        val hits = nav.searchMembers(args("query" to "value", "scope" to "name")).items()
        assertEquals(2, hits.size)
        assertEquals(setOf("classes.dex", "classes2.dex"), hits.map { it.tString("dexEntry") }.toSet())
        assertEquals(2, hits.map { it.tString("uniqueLocator") }.toSet().size)

        try {
            nav.callGraph(args("locator" to locator, "direction" to "callees"))
            fail("raw duplicate locator must be rejected")
        } catch (expected: ToolkitException) {
            assertEquals("DEX_AMBIGUOUS_LOCATOR", expected.code)
        }

        val explicit = nav.callGraph(args("locator" to locator, "dexEntry" to "classes2.dex",
            "direction" to "callees", "maxDepth" to 0))
        assertEquals("classes2.dex", explicit.getAsJsonObject("root").tString("dexEntry"))

        val qualified = nav.callGraph(args("locator" to "dex_entry:classes.dex!$locator",
            "direction" to "callees", "maxDepth" to 0))
        assertEquals("classes.dex", qualified.getAsJsonObject("root").tString("dexEntry"))
    }

    @Test
    fun callGraphCalleesFindCyclesWithoutRepeatingNodes() {
        val dex = ToolkitDexFixtures.assemble(temp.root, "cycle", CYCLE_A, CYCLE_B, CYCLE_C)
        val nav = ToolkitDexFixtures.nav("classes.dex" to dex)

        val graph = nav.callGraph(args("locator" to "dex_method:Lfixture/A;->a()V",
            "direction" to "callees", "maxDepth" to 3, "maxNodes" to 20, "maxEdges" to 20))
        val nodes = graph.nodes()
        val edges = graph.edges()
        assertEquals(3, nodes.size)
        assertEquals(setOf("Lfixture/A;", "Lfixture/B;", "Lfixture/C;"),
            nodes.map { it.tString("definingClass") }.toSet())
        assertEquals(3, edges.size)
        assertTrue(edges.any { it.tString("fromLocator").contains("Lfixture/A;->a()V") &&
            it.tString("toLocator").contains("Lfixture/B;->b()V") })
        assertTrue(edges.any { it.tString("fromLocator").contains("Lfixture/B;->b()V") &&
            it.tString("toLocator").contains("Lfixture/A;->a()V") })
        assertTrue(edges.any { it.tString("fromLocator").contains("Lfixture/B;->b()V") &&
            it.tString("toLocator").contains("Lfixture/C;->c()V") })
        assertEquals(2, graph.summary().get("maxDepthReached").asInt)
        assertFalse(graph.summary().get("depthLimited").asBoolean)
        assertTrue(graph.summary().get("complete").asBoolean)
    }

    @Test
    fun hierarchyAndCallerDispatchResolveInterfacesAndOverrides() {
        val dex = ToolkitDexFixtures.assemble(temp.root, "interfaces", INTERFACE, IMPL, CALLER)
        val nav = ToolkitDexFixtures.nav("classes.dex" to dex)

        val interfaceHierarchy = nav.hierarchy(args("locator" to "dex_method:Lfixture/I;->run()V",
            "direction" to "subtypes", "methodResolution" to "slot", "maxDepth" to 2))
        assertTrue(interfaceHierarchy.array("subtypes").any { it.tString("type") == "Lfixture/Impl;" })
        val interfaceOverrides = interfaceHierarchy.getAsJsonObject("overrides")
        assertTrue(interfaceOverrides.getAsJsonArray("exact").map { it.asJsonObject }
            .any { it.tString("locator") == "dex_method:Lfixture/I;->run()V" })
        assertTrue(interfaceOverrides.getAsJsonArray("slot").map { it.asJsonObject }
            .any { it.tString("locator") == "dex_method:Lfixture/Impl;->run()V" })

        val implHierarchy = nav.hierarchy(args("locator" to "dex_method:Lfixture/Impl;->run()V",
            "direction" to "supertypes", "methodResolution" to "dispatch", "maxDepth" to 2))
        assertTrue(implHierarchy.array("supertypes").any { it.tString("type") == "Lfixture/I;" })
        val implOverrides = implHierarchy.getAsJsonObject("overrides")
        assertTrue(implOverrides.getAsJsonArray("dispatch").map { it.asJsonObject }
            .any { it.tString("locator") == "dex_method:Lfixture/I;->run()V" })

        val callers = nav.callGraph(args("locator" to "dex_method:Lfixture/Impl;->run()V",
            "direction" to "callers", "methodResolution" to "dispatch", "maxDepth" to 2))
        assertTrue(callers.nodes().any { it.tString("locator") == "dex_method:Lfixture/Caller;->go(Lfixture/I;)V" })
        val dispatchEdge = callers.edges().single { it.tString("fromLocator") == "dex_method:Lfixture/Caller;->go(Lfixture/I;)V" }
        assertEquals("dispatch", dispatchEdge.tString("resolution"))
        assertTrue(dispatchEdge.get("potential").asBoolean)
        assertEquals("dex_method:Lfixture/I;->run()V", dispatchEdge.tString("reference"))
        assertEquals("dex_method:Lfixture/Impl;->run()V", dispatchEdge.tString("toLocator"))

        val outgoing = nav.callGraph(args("locator" to "dex_method:Lfixture/Caller;->go(Lfixture/I;)V",
            "direction" to "callees", "methodResolution" to "dispatch", "maxDepth" to 2))
        assertTrue(outgoing.edges().any {
            it.tString("fromLocator") == "dex_method:Lfixture/Caller;->go(Lfixture/I;)V" &&
                it.tString("toLocator") == "dex_method:Lfixture/Impl;->run()V" &&
                it.tString("resolution") == "dispatch" && it.get("potential").asBoolean
        })
    }

    @Test
    fun callGraphLimitsAndCancellationAreReportedWithoutThrowing() {
        val dex = ToolkitDexFixtures.assemble(temp.root, "chain", CHAIN_A, CHAIN_B, CHAIN_C, CHAIN_D)
        val nav = ToolkitDexFixtures.nav("classes.dex" to dex)
        val locator = "dex_method:Lfixture/A;->a()V"

        val depthBound = nav.callGraph(args("locator" to locator, "direction" to "callees", "maxDepth" to 1))
        assertEquals(2, depthBound.nodes().size)
        assertEquals(1, depthBound.edges().size)
        assertTrue(depthBound.summary().get("depthLimited").asBoolean)
        assertFalse(depthBound.summary().get("complete").asBoolean)

        val nodeBound = nav.callGraph(args("locator" to locator, "direction" to "callees", "maxDepth" to 4,
            "maxNodes" to 2, "maxEdges" to 20))
        assertTrue(nodeBound.summary().get("truncated").asBoolean)
        assertEquals("max_nodes", nodeBound.summary().get("stopReason").asString)
        assertTrue(nodeBound.nodes().size <= 2)

        val edgeBound = nav.callGraph(args("locator" to locator, "direction" to "callees", "maxDepth" to 4,
            "maxNodes" to 20, "maxEdges" to 1))
        assertTrue(edgeBound.summary().get("truncated").asBoolean)
        assertEquals("max_edges", edgeBound.summary().get("stopReason").asString)
        assertTrue(edgeBound.edges().size <= 1)

        Thread.currentThread().interrupt()
        val cancelled = try {
            nav.callGraph(args("locator" to locator, "direction" to "callees", "maxDepth" to 4))
        } finally {
            Thread.interrupted()
        }
        assertTrue(cancelled.summary().get("cancelled").asBoolean)
        assertFalse(cancelled.summary().get("complete").asBoolean)
        assertEquals("cancelled", cancelled.summary().get("stopReason").asString)
    }

    @Test
    fun modernInvokeCustomAndPolymorphicAreFactsNotFakeCallees() {
        val dex = ToolkitDexFixtures.assemble(temp.root, "modern", MODERN, MODERN_IMPL, BOOTSTRAP)
        val nav = ToolkitDexFixtures.nav("classes.dex" to dex)

        val graph = nav.callGraph(args("locator" to "dex_method:Lfixture/Modern;->modern()V",
            "direction" to "callees", "maxDepth" to 2, "includePotential" to true))
        val invokeCustom = graph.array("invokeCustom")
        assertEquals(1, invokeCustom.size)
        assertEquals("doSomething", invokeCustom.single().tString("methodName"))
        assertEquals("unknown", invokeCustom.single().tString("dynamicTarget"))
        assertTrue(invokeCustom.single().tString("bootstrap").contains("Lfixture/Bootstrap;->bsm"))
        assertTrue(graph.array("methodHandles").any { it.tString("member") == "Lfixture/Impl;->run()V" })
        assertTrue(graph.edges().any { it.tString("kind") == "invoke_custom_bootstrap" &&
            it.tString("toLocator").contains("Lfixture/Bootstrap;->bsm") })
        assertTrue(graph.edges().any { (it.tString("kind") == "method_handle_constant" || it.tString("kind") == "call_site_handle") &&
            it.tString("toLocator").contains("Lfixture/Impl;->run()V") })
        assertFalse(graph.nodes().any { it.toString().contains("doSomething") })
        assertTrue(graph.summary().get("dynamicUnknown").asBoolean)
        assertFalse(graph.summary().get("complete").asBoolean)

        val poly = nav.callGraph(args("locator" to "dex_method:Lfixture/Modern;->poly(Ljava/lang/invoke/MethodHandle;)V",
            "direction" to "callees", "maxDepth" to 2))
        val polymorphic = poly.array("invokePolymorphic")
        assertEquals(1, polymorphic.size)
        assertEquals("(Ljava/lang/String;)V", polymorphic.single().tString("prototype"))
        assertEquals("unknown", polymorphic.single().tString("dynamicTarget"))
        assertTrue(poly.array("dynamic").any { it.tString("kind") == "invoke_polymorphic" })
        assertTrue(poly.array("methodTypes").any { it.tString("proto") == "(Ljava/lang/String;)V" })
        assertTrue(poly.summary().get("dynamicUnknown").asBoolean)
        assertFalse(poly.summary().get("complete").asBoolean)
    }

    @Test
    fun reflectionCallsBecomeExplicitUnresolvedFacts() {
        val dex = ToolkitDexFixtures.assemble(temp.root, "reflection", REFLECT)
        val nav = ToolkitDexFixtures.nav("classes.dex" to dex)

        val graph = nav.callGraph(args("locator" to "dex_method:Lfixture/Reflect;->run()V",
            "direction" to "callees", "maxDepth" to 2))
        val reflections = graph.array("unresolved").filter { it.tString("kind") == "reflection" }
        assertTrue(graph.toString(), reflections.size >= 3)
        assertTrue(reflections.any { it.tString("api") == "Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;" })
        assertTrue(reflections.any { it.tString("api") == "Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;" })
        assertTrue(graph.summary().get("reflectionObserved").asBoolean)
        assertTrue(graph.summary().get("reflectionCallCount").asInt >= 3)
        assertFalse(graph.summary().get("complete").asBoolean)
        assertTrue(graph.nodes().all { !it.tString("definingClass").startsWith("Lfixture/") || it.tString("definingClass") == "Lfixture/Reflect;" })
    }

    private fun duplicateClass(value: Int) = """
        .class public Lfixture/Dup;
        .super Ljava/lang/Object;
        .method public static value()I
            .registers 1
            const/4 v0, 0x$value
            return v0
        .end method
    """.trimIndent()

    companion object {
        private val OVERLOADS = """
            .class public Lfixture/Overloads;
            .super Ljava/lang/Object;
            .method public static run()V
                .registers 1
                const-string v0, "first-needle"
                return-void
            .end method
            .method public static run(I)V
                .registers 1
                const-string v0, "second-needle"
                return-void
            .end method
        """.trimIndent()

        private val CYCLE_A = """
            .class public Lfixture/A;
            .super Ljava/lang/Object;
            .method public static a()V
                .registers 1
                invoke-static {}, Lfixture/B;->b()V
                return-void
            .end method
        """.trimIndent()

        private val CYCLE_B = """
            .class public Lfixture/B;
            .super Ljava/lang/Object;
            .method public static b()V
                .registers 1
                invoke-static {}, Lfixture/C;->c()V
                invoke-static {}, Lfixture/A;->a()V
                return-void
            .end method
        """.trimIndent()

        private val CYCLE_C = """
            .class public Lfixture/C;
            .super Ljava/lang/Object;
            .method public static c()V
                .registers 0
                return-void
            .end method
        """.trimIndent()

        private val INTERFACE = """
            .class public interface abstract Lfixture/I;
            .super Ljava/lang/Object;
            .method public abstract run()V
            .end method
        """.trimIndent()

        private val IMPL = """
            .class public Lfixture/Impl;
            .super Ljava/lang/Object;
            .implements Lfixture/I;
            .method public run()V
                .registers 1
                return-void
            .end method
        """.trimIndent()

        private val CALLER = """
            .class public Lfixture/Caller;
            .super Ljava/lang/Object;
            .method public static go(Lfixture/I;)V
                .registers 1
                invoke-interface {p0}, Lfixture/I;->run()V
                return-void
            .end method
        """.trimIndent()

        private val CHAIN_A = """
            .class public Lfixture/A;
            .super Ljava/lang/Object;
            .method public static a()V
                .registers 1
                invoke-static {}, Lfixture/B;->b()V
                return-void
            .end method
        """.trimIndent()

        private val CHAIN_B = """
            .class public Lfixture/B;
            .super Ljava/lang/Object;
            .method public static b()V
                .registers 1
                invoke-static {}, Lfixture/C;->c()V
                return-void
            .end method
        """.trimIndent()

        private val CHAIN_C = """
            .class public Lfixture/C;
            .super Ljava/lang/Object;
            .method public static c()V
                .registers 1
                invoke-static {}, Lfixture/D;->d()V
                return-void
            .end method
        """.trimIndent()

        private val CHAIN_D = """
            .class public Lfixture/D;
            .super Ljava/lang/Object;
            .method public static d()V
                .registers 0
                return-void
            .end method
        """.trimIndent()

        private val MODERN = """
            .class public Lfixture/Modern;
            .super Ljava/lang/Object;
            .method public static modern()V
                .registers 2
                const-method-handle v0, invoke-static@Lfixture/Impl;->run()V
                const-method-type v1, (Ljava/lang/String;)V
                invoke-custom {}, call_site_1("doSomething", (Lfixture/Custom;Ljava/lang/String;)Ljava/lang/String;, invoke-static@Lfixture/Impl;->run()V)@Lfixture/Bootstrap;->bsm(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;
                return-void
            .end method
            .method public static poly(Ljava/lang/invoke/MethodHandle;)V
                .registers 2
                invoke-polymorphic {p0}, Ljava/lang/invoke/MethodHandle;->invokeExact([Ljava/lang/Object;)Ljava/lang/Object;, (Ljava/lang/String;)V
                return-void
            .end method
        """.trimIndent()

        private val MODERN_IMPL = """
            .class public Lfixture/Impl;
            .super Ljava/lang/Object;
            .method public static run()V
                .registers 0
                return-void
            .end method
        """.trimIndent()

        private val BOOTSTRAP = """
            .class public Lfixture/Bootstrap;
            .super Ljava/lang/Object;
            .method public static bsm(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;
                .registers 5
                const/4 v0, 0x0
                return-object v0
            .end method
        """.trimIndent()

        private val REFLECT = """
            .class public Lfixture/Reflect;
            .super Ljava/lang/Object;
            .method public static run()V
                .registers 3
                const-string v0, "java.lang.String"
                invoke-static {v0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
                move-result-object v1
                const/4 v2, 0x0
                invoke-virtual {v1, v2}, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
                move-result-object v1
                invoke-virtual {v1, v2}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
                return-void
            .end method
        """.trimIndent()
    }
}
