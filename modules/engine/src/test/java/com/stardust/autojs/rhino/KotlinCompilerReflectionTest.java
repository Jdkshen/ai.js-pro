package com.stardust.autojs.rhino;

import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import kotlin.jvm.internal.Reflection;
import kotlin.reflect.KClass;
import kotlin.reflect.KProperty1;
import kotlin.reflect.full.KClasses;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class KotlinCompilerReflectionTest {
    public static class Fixture {
        public final String value = "reflection-ok";
    }

    @Test
    public void stdlibClassReferencesSupportFullMemberPropertyReflection() {
        KClass<Fixture> type = Reflection.getOrCreateKotlinClass(Fixture.class);
        assertSame(Reflection.class.getClassLoader(), type.getClass().getClassLoader());
        KProperty1<Fixture, ?> value = null;
        for (KProperty1<Fixture, ?> property : KClasses.getMemberProperties(type)) {
            if (property.getName().equals("value")) {
                value = property;
            }
        }
        assertNotNull(value);
        assertEquals("reflection-ok", value.get(new Fixture()));
    }

    @Test
    public void kotlin1621CompilerCanReflectItsCommandLineArguments() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, "UTF-8")) {
            ExitCode result = new K2JVMCompiler().exec(stream,
                    "-no-jdk", "-no-stdlib", "-no-reflect", "-version");
            String text = output.toString("UTF-8");
            assertEquals(text, ExitCode.OK, result);
            assertTrue(text, text.contains("kotlinc-jvm 1.6.21"));
        }
    }
}
