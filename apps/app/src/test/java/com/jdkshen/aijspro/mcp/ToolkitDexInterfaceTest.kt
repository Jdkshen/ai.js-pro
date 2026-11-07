package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToolkitDexInterfaceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = ToolkitIo.json(*pairs)

    private fun assemble(name: String, combined: String): ByteArray {
        val parts = combined.split(Regex("(?m)(?=^\\.class )")).filter { it.isNotBlank() }.toTypedArray()
        return ToolkitDexFixtures.assemble(temp.root, name, *parts)
    }

    private fun table(dex: ByteArray) = ToolkitDexInterface(listOf("classes.dex" to ToolkitDex(dex)))
        .interfaceTable(args("descriptor" to "Lfixture/IFoo;"))

    private fun rows(table: JsonObject) = table.getAsJsonArray("methods").map { it.asJsonObject }
    private fun row(table: JsonObject, name: String) = rows(table).single { it.tString("method") == name }

    @Test
    fun binderInterfaceTableUsesStaticTransactFieldsAndComparesProxyAndStub() {
        val dex = assemble("binder", BINDER_FIXTURE)
        val table = table(dex)
        assertEquals("Lfixture/IFoo;", table.tString("interface"))
        assertEquals(1, table.get("firstCallTransaction").asInt)
        assertEquals("static_field", table.tString("transactSource"))
        assertEquals(4, table.get("methodCount").asInt)
        assertEquals(2, table.getAsJsonArray("chain").size())

        val request = row(table, "requestPermission")
        assertEquals(15, request.get("transactCode").asInt)
        assertEquals("static_field", request.tString("transactSource"))
        assertEquals(listOf("I"), request.getAsJsonArray("params").map { it.asString })
        assertEquals("V", request.tString("returnType"))
        assertEquals("Lfixture/IFoo;", request.tString("declaredIn"))
        assertEquals("TRANSACTION_requestPermission", request.tString("transactField"))
        assertEquals(15, request.get("proxyTransactCode").asInt)

        val base = row(table, "base")
        assertEquals(1, base.get("transactCode").asInt)
        assertEquals("Lfixture/IBase;", base.tString("declaredIn"))

        assertEquals(true, row(table, "removeUserService").get("oneway").asBoolean)
        assertEquals("proxy_transact_flags", row(table, "removeUserService").tString("onewaySource"))
        assertEquals(false, row(table, "requestPermission").get("oneway").asBoolean)

        val server = table.getAsJsonObject("server")
        assertTrue(server.get("present").asBoolean)
        assertEquals("Lfixture/IFoo${'$'}Stub;", server.tString("type"))
        assertEquals(4, server.get("methodCount").asInt)
        val client = table.getAsJsonObject("client")
        assertTrue(client.get("present").asBoolean)
        assertEquals("Lfixture/IFoo${'$'}Stub${'$'}Proxy;", client.tString("type"))
        assertEquals(4, client.get("methodCount").asInt)

        val serverRequest = server.getAsJsonArray("methods").map { it.asJsonObject }.single { it.tString("method") == "requestPermission" }
        val clientRequest = client.getAsJsonArray("methods").map { it.asJsonObject }.single { it.tString("method") == "requestPermission" }
        assertEquals(15, serverRequest.get("transactCode").asInt)
        assertEquals(15, clientRequest.get("transactCode").asInt)

        val comparison = table.getAsJsonObject("comparison")
        assertTrue(comparison.getAsJsonArray("missingOnServer").size() == 0)
        assertTrue(comparison.getAsJsonArray("missingOnClient").size() == 0)
        assertEquals(3, comparison.getAsJsonArray("orderMismatches").size())
        assertTrue(comparison.getAsJsonArray("proxyCodeMismatches").size() == 0)
        assertTrue(comparison.get("serverClientSameCode").asBoolean)
    }

    @Test
    fun interfaceWithoutTransactionFieldsFallsBackToDeclarationOrderAndReportsBoundary() {
        val dex = assemble("nofields", NO_FIELDS_FIXTURE)
        val table = ToolkitDexInterface(listOf("classes.dex" to ToolkitDex(dex)))
            .interfaceTable(args("descriptor" to "Lfixture/INoFields;"))
        assertEquals("declaration_order", table.tString("transactSource"))
        val rows = rows(table)
        assertEquals(listOf("alpha", "beta"), rows.map { it.tString("method") })
        assertEquals(1, rows[0].get("transactCode").asInt)
        assertEquals(2, rows[1].get("transactCode").asInt)
        assertEquals("declaration_order", rows[0].tString("transactSource"))
        assertTrue(table.getAsJsonArray("warnings").any { it.asString.contains("TRANSACTION_FIELDS_NOT_FOUND") })
        assertFalse(table.getAsJsonObject("server").get("present").asBoolean)
        assertFalse(table.getAsJsonObject("client").get("present").asBoolean)
    }

    @Test
    fun listClassesAndMethodsPaginateAndKeepFilters() {
        val dex = assemble("pages", PAGES_FIXTURE)
        val api = ToolkitDexInterface(listOf("classes.dex" to ToolkitDex(dex)))
        val first = api.listClasses(args("prefix" to "Lfixture/Page", "limit" to 1))
        assertEquals(3, first.get("total").asInt)
        assertEquals(1, first.getAsJsonArray("items").size())
        val cursor = first.tString("nextCursor")
        assertNotNull(cursor)
        val second = api.listClasses(args("prefix" to "Lfixture/Page", "limit" to 1, "cursor" to cursor))
        val firstType = first.getAsJsonArray("items")[0].asJsonObject.tString("type")
        val secondType = second.getAsJsonArray("items")[0].asJsonObject.tString("type")
        assertTrue(firstType != secondType)
        assertEquals(3, second.get("total").asInt)
        assertTrue(second.get("truncated").asBoolean)

        val methods = api.listMethods(args("owner" to "Lfixture/PageA;", "limit" to 10))
        assertEquals(1, methods.get("total").asInt)
        val method = methods.getAsJsonArray("items")[0].asJsonObject
        assertEquals("foo", method.tString("name"))
        assertEquals("Lfixture/PageA;->foo()V", method.tString("descriptor"))
        assertEquals("V", method.tString("returnType"))
        assertTrue(method.get("paramTypes").asJsonArray.size() == 0)

        val invalid = assertThrows(ToolkitException::class.java) {
            api.listClasses(args("prefix" to "Lfixture/Page", "cursor" to "dxi1:classes:1:deadbeefdead"))
        }
        assertEquals("INVALID_CURSOR", invalid.code)
    }

    @Test
    fun xrefImplementationsAndOverridesReuseHierarchyScanner() {
        val dex = assemble("xref", XREF_FIXTURE)
        val api = ToolkitDexInterface(listOf("classes.dex" to ToolkitDex(dex)))

        val impl = api.implementations(args("locator" to "dex_class:Lfixture/IX;"))
        assertEquals("class", impl.tString("kind"))
        val implementations = impl.getAsJsonArray("implementations").map { it.asJsonObject }
        assertTrue(implementations.any { it.tString("type") == "Lfixture/XImpl;" })
        assertTrue(impl.getAsJsonArray("directImplementations").any { it.asJsonObject.tString("type") == "Lfixture/XImpl;" })

        val methodImpl = api.implementations(args("locator" to "dex_method:Lfixture/IX;->run()V"))
        assertTrue(methodImpl.getAsJsonArray("implementations").any { it.asJsonObject.tString("type") == "Lfixture/XImpl;" })

        val overrides = api.overrides(args("locator" to "dex_method:Lfixture/IX;->run()V", "methodResolution" to "dispatch"))
        assertEquals("method", overrides.tString("kind"))
        val primary = overrides.getAsJsonObject("overrides").getAsJsonArray("primary").map { it.asJsonObject }
        assertTrue(primary.any { it.tString("locator") == "dex_method:Lfixture/XImpl;->run()V" })
    }

    @Test
    fun missingInterfaceUsesStructuredLocatorCodeAndNextActionText() {
        val dex = assemble("missing", NO_FIELDS_FIXTURE)
        val api = ToolkitDexInterface(listOf("classes.dex" to ToolkitDex(dex)))
        val error = assertThrows(ToolkitException::class.java) {
            api.interfaceTable(args("descriptor" to "Lfixture/Missing;", "mode" to "descriptor"))
        }
        assertEquals("LOCATOR_NOT_FOUND", error.code)
        assertEquals("descriptor", error.argument)
        assertTrue(error.message.orEmpty().contains("nextActions"))
    }

    @Test
    fun fromFileReadsStandaloneDex() {
        val dex = assemble("fromfile", NO_FIELDS_FIXTURE)
        val file = File(temp.root, "fixture.dex").apply { writeBytes(dex) }
        val table = ToolkitDexInterface.fromFile(file).interfaceTable(args("descriptor" to "Lfixture/INoFields;"))
        assertEquals(2, table.get("methodCount").asInt)
    }

    @Test
    fun canonicalPathStillWorksWithExplicitDescriptorMode() {
        val dex = assemble("canonical-explicit", BINDER_FIXTURE)
        val table = ToolkitDexInterface(listOf("classes.dex" to ToolkitDex(dex)))
            .interfaceTable(args("descriptor" to "Lfixture/IFoo;", "mode" to "descriptor"))
        assertEquals("static_field", table.tString("transactSource"))
        assertEquals(15, row(table, "requestPermission").get("transactCode").asInt)
    }

    @Test
    fun stringConstFallbackFindsTransactAndOnTransactSwitch() {
        val dex = assemble("shizuku-fallback", STRING_CONST_FIXTURE)
        val api = ToolkitDexInterface(listOf("classes.dex" to ToolkitDex(dex)))
        val table = api.interfaceTable(args("descriptor" to "moe.shizuku.server.IShizukuService"))
        assertEquals("string_const+transact_const", table.tString("transactSource"))
        assertTrue(table.get("stringPoolHits").asInt > 0)
        val methods = table.getAsJsonArray("methods").map { it.asJsonObject }
        val transact = methods.single { it.get("code")?.asInt == 15 && it.tString("side") == "client" }
        assertEquals("writeInterfaceToken+transact", transact.tString("evidence"))
        assertEquals("high", transact.tString("confidence"))
        assertEquals("Lfixture/ShizukuClient;->call()V", transact.tString("methodDescriptor"))
        assertTrue(transact.tString("callSiteLocator").startsWith("dex_method:Lfixture/ShizukuClient;->call"))
        assertEquals("transact", transact.tString("transactMethod"))

        val serverOne = methods.single { it.get("code")?.asInt == 1 && it.tString("side") == "server" }
        assertEquals("onTransact-packed-switch", serverOne.tString("evidence"))
        assertEquals("requestPermission", serverOne.tString("method"))
        val serverTwo = methods.single { it.get("code")?.asInt == 2 && it.tString("side") == "server" }
        assertEquals("checkSelfPermission", serverTwo.tString("method"))
        assertEquals(1, table.getAsJsonArray("codes").map { it.asJsonObject }.count { it.get("code").asInt == 1 && it.get("sources").asJsonArray.size() >= 1 })
    }

    @Test
    fun stringConstFallbackReturnsEmptyWarningsAndNextActionsWithoutInventingCodes() {
        val dex = assemble("empty-fallback", NO_FIELDS_FIXTURE)
        val api = ToolkitDexInterface(listOf("classes.dex" to ToolkitDex(dex)))
        val table = api.interfaceTable(args("descriptor" to "no.such.IService", "mode" to "string_const"))
        assertEquals("string_const+transact_const", table.tString("transactSource"))
        assertEquals(0, table.get("methodCount").asInt)
        assertTrue(table.getAsJsonArray("methods").size() == 0)
        assertTrue(table.getAsJsonArray("warnings").any { it.asString.contains("STRING_POOL_TOKEN_NOT_FOUND") })
        assertTrue(table.getAsJsonArray("nextActions").size() >= 1)
        assertFalse(table.getAsJsonArray("warnings").any { it.asString.contains("code=1") })
    }

    companion object {
        private val STRING_CONST_FIXTURE = """
            .class public Lfixture/ShizukuClient;
            .super Ljava/lang/Object;
            .method public call()V
                .registers 6
                const/4 v0, 0x0
                const-string v1, "moe.shizuku.server.IShizukuService"
                invoke-virtual {v0, v1}, Landroid/os/Parcel;->writeInterfaceToken(Ljava/lang/String;)V
                const/16 v1, 0xf
                const/4 v2, 0x0
                const/4 v3, 0x0
                const/4 v4, 0x0
                invoke-interface {v0, v1, v2, v3, v4}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
                return-void
            .end method
        """.trimIndent() + "\n" + """
            .class public Lfixture/ShizukuStub;
            .super Landroid/os/Binder;
            .method public requestPermission()V
                .registers 1
                return-void
            .end method
            .method public checkSelfPermission()V
                .registers 1
                return-void
            .end method
            .method public onTransact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
                .registers 6
                packed-switch p1, :switch_data
                const/4 v0, 0x0
                return v0
                :switch_data
                .packed-switch 0x1
                    :case_1
                    :case_2
                .end packed-switch
                :case_1
                const-string v0, "moe.shizuku.server.IShizukuService"
                invoke-virtual {p0}, Lfixture/ShizukuStub;->requestPermission()V
                const/4 v0, 0x1
                return v0
                :case_2
                invoke-virtual {p0}, Lfixture/ShizukuStub;->checkSelfPermission()V
                const/4 v0, 0x1
                return v0
            .end method
        """.trimIndent()

        private val BINDER_FIXTURE = """
            .class public interface abstract Lfixture/IBase;
            .super Ljava/lang/Object;
            .method public abstract base(I)V
            .end method
        """.trimIndent() + "\n" + """
            .class public interface abstract Lfixture/IFoo;
            .super Ljava/lang/Object;
            .implements Lfixture/IBase;
            .method public abstract requestPermission(I)V
            .end method
            .method public abstract checkSelfPermission(I)Z
            .end method
            .method public abstract removeUserService(I)V
            .end method
        """.trimIndent() + "\n" + """
            .class public abstract Lfixture/IFoo${'$'}Stub;
            .super Landroid/os/Binder;
            .implements Lfixture/IFoo;
            .field public static final TRANSACTION_base:I = 0x1
            .field public static final TRANSACTION_requestPermission:I = 0xf
            .field public static final TRANSACTION_checkSelfPermission:I = 0x10
            .field public static final TRANSACTION_removeUserService:I = 0xc
            .method public base(I)V
                .registers 2
                return-void
            .end method
            .method public requestPermission(I)V
                .registers 2
                return-void
            .end method
            .method public checkSelfPermission(I)Z
                .registers 2
                const/4 v0, 0x0
                return v0
            .end method
            .method public removeUserService(I)V
                .registers 2
                return-void
            .end method
        """.trimIndent() + "\n" + """
            .class public Lfixture/IFoo${'$'}Stub${'$'}Proxy;
            .super Ljava/lang/Object;
            .implements Lfixture/IFoo;
            .field private mRemote:Landroid/os/IBinder;
            .method public base(I)V
                .registers 7
                iget-object v0, p0, Lfixture/IFoo${'$'}Stub${'$'}Proxy;->mRemote:Landroid/os/IBinder;
                const/16 v1, 0x1
                const/4 v2, 0x0
                const/4 v3, 0x0
                const/4 v4, 0x0
                invoke-interface {v0, v1, v2, v3, v4}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
                return-void
            .end method
            .method public requestPermission(I)V
                .registers 7
                iget-object v0, p0, Lfixture/IFoo${'$'}Stub${'$'}Proxy;->mRemote:Landroid/os/IBinder;
                const/16 v1, 0xf
                const/4 v2, 0x0
                const/4 v3, 0x0
                const/4 v4, 0x0
                invoke-interface {v0, v1, v2, v3, v4}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
                return-void
            .end method
            .method public checkSelfPermission(I)Z
                .registers 7
                iget-object v0, p0, Lfixture/IFoo${'$'}Stub${'$'}Proxy;->mRemote:Landroid/os/IBinder;
                const/16 v1, 0x10
                const/4 v2, 0x0
                const/4 v3, 0x0
                const/4 v4, 0x0
                invoke-interface {v0, v1, v2, v3, v4}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
                const/4 v0, 0x0
                return v0
            .end method
            .method public removeUserService(I)V
                .registers 7
                iget-object v0, p0, Lfixture/IFoo${'$'}Stub${'$'}Proxy;->mRemote:Landroid/os/IBinder;
                const/16 v1, 0xc
                const/4 v2, 0x0
                const/4 v3, 0x0
                const/4 v4, 0x1
                invoke-interface {v0, v1, v2, v3, v4}, Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z
                return-void
            .end method
        """.trimIndent()

        private val NO_FIELDS_FIXTURE = """
            .class public interface abstract Lfixture/INoFields;
            .super Ljava/lang/Object;
            .method public abstract alpha()V
            .end method
            .method public abstract beta()V
            .end method
        """.trimIndent()

        private val PAGES_FIXTURE = """
            .class public Lfixture/PageA;
            .super Ljava/lang/Object;
            .method public foo()V
                .registers 1
                return-void
            .end method
        """.trimIndent() + "\n" + """
            .class public Lfixture/PageB;
            .super Ljava/lang/Object;
            .method public foo()V
                .registers 1
                return-void
            .end method
        """.trimIndent() + "\n" + """
            .class public Lfixture/PageC;
            .super Ljava/lang/Object;
            .method public foo()V
                .registers 1
                return-void
            .end method
        """.trimIndent()

        private val XREF_FIXTURE = """
            .class public interface abstract Lfixture/IX;
            .super Ljava/lang/Object;
            .method public abstract run()V
            .end method
        """.trimIndent() + "\n" + """
            .class public Lfixture/XImpl;
            .super Ljava/lang/Object;
            .implements Lfixture/IX;
            .method public run()V
                .registers 1
                return-void
            .end method
        """.trimIndent()
    }
}
